package org.jason.siph.domain.oo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityState
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import kotlin.math.abs

interface RegisterTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun readHoldingRegister(address: Int): Int
    suspend fun writeSingleRegister(address: Int, value: Int)
}

data class TemperatureRegisterProfile(
    val processValueAddress: Int,
    val setpointAddress: Int,
    val runStateAddress: Int,
    val alarmAddress: Int? = null,
    val outputAddress: Int? = null,
    val scale: Double = 10.0,
    val runningRawValue: Int = 1,
    val stoppedRawValue: Int = 0,
    val alarmActiveRawValues: Set<Int> = setOf(1)
) {
    init {
        require(processValueAddress >= 0)
        require(setpointAddress >= 0)
        require(runStateAddress >= 0)
        require(alarmAddress == null || alarmAddress >= 0)
        require(outputAddress == null || outputAddress >= 0)
        require(scale.isFinite() && scale > 0.0)
    }
}

class RegisterTemperatureControllerAdapter(
    override val descriptor: DeviceDescriptor,
    private val transport: RegisterTransport,
    private val registers: TemperatureRegisterProfile,
    private val declaredCapabilities: TemperatureControllerCapabilities,
    private val identityProvider: suspend () -> String
) : TemperatureControllerPort {

    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = AutonomyCapabilityStatus(
            state = AutonomyCapabilityState.Connecting,
            identity = descriptor.model,
            detail = "Connecting register temperature adapter"
        )
        runCatching {
            transport.connect()
            val identity = identityProvider().ifBlank { descriptor.model }
            mutableStatus.value = AutonomyCapabilityStatus(
                state = AutonomyCapabilityState.Ready,
                identity = identity,
                detail = "Register protocol connected; hardware verification is still required"
            )
        }.onFailure { error ->
            mutableStatus.value = AutonomyCapabilityStatus(
                state = AutonomyCapabilityState.Error,
                identity = descriptor.model,
                detail = "Temperature protocol adapter error",
                errorMessage = error.message ?: error::class.simpleName
            )
            runCatching { transport.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { transport.disconnect() }
        mutableStatus.value = AutonomyCapabilityStatus(
            state = AutonomyCapabilityState.Disconnected,
            identity = descriptor.model,
            detail = "Register protocol disconnected"
        )
    }

    override suspend fun identify(): String = identityProvider().ifBlank { descriptor.model }
    override suspend fun capabilities() = declaredCapabilities

    override suspend fun readSnapshot(): TemperatureSnapshot {
        val pv = transport.readHoldingRegister(registers.processValueAddress) / registers.scale
        val sv = transport.readHoldingRegister(registers.setpointAddress) / registers.scale
        val runRaw = transport.readHoldingRegister(registers.runStateAddress)
        val alarmRaw = registers.alarmAddress?.let { transport.readHoldingRegister(it) }
        val output = registers.outputAddress
            ?.let { transport.readHoldingRegister(it) / registers.scale }
        return TemperatureSnapshot(
            processValueC = pv,
            setpointC = sv,
            running = runRaw == registers.runningRawValue,
            alarmActive = alarmRaw != null && alarmRaw in registers.alarmActiveRawValues,
            outputPercent = output
        )
    }

    override suspend fun setSetpointC(value: Double) {
        require(value in declaredCapabilities.minimumTemperatureC..declaredCapabilities.maximumTemperatureC)
        transport.writeSingleRegister(
            registers.setpointAddress,
            kotlin.math.round(value * registers.scale).toInt()
        )
    }

    override suspend fun startControl() {
        transport.writeSingleRegister(registers.runStateAddress, registers.runningRawValue)
    }

    override suspend fun stopControl() {
        transport.writeSingleRegister(registers.runStateAddress, registers.stoppedRawValue)
    }

    override suspend fun waitUntilStable(
        policy: TemperatureStabilityPolicy
    ): TemperatureStabilityResult {
        val samples = ArrayDeque<Pair<Long, Double>>()
        var elapsed = 0L
        var maxSlope = 0.0
        var finalSnapshot = readSnapshot()

        while (elapsed <= policy.timeoutMs) {
            finalSnapshot = readSnapshot()
            if (finalSnapshot.alarmActive) {
                return TemperatureStabilityResult(
                    stable = false,
                    finalSnapshot = finalSnapshot,
                    observedDurationMs = elapsed,
                    maximumObservedSlopeCPerMinute = maxSlope,
                    message = "Temperature controller alarm is active"
                )
            }
            samples.addLast(elapsed to finalSnapshot.processValueC)
            while (samples.isNotEmpty() && elapsed - samples.first().first > policy.stableWindowMs) {
                samples.removeFirst()
            }
            val first = samples.firstOrNull()
            val duration = first?.let { elapsed - it.first } ?: 0L
            val slope = first?.takeIf { duration > 0L }?.let {
                abs(finalSnapshot.processValueC - it.second) * 60_000.0 / duration
            } ?: Double.POSITIVE_INFINITY
            if (slope.isFinite()) maxSlope = maxOf(maxSlope, slope)
            val inTolerance = abs(finalSnapshot.processValueC - finalSnapshot.setpointC) <=
                policy.targetToleranceC
            val windowReady = policy.stableWindowMs == 0L || duration >= policy.stableWindowMs
            val slopeReady = slope <= policy.maximumSlopeCPerMinute || policy.stableWindowMs == 0L
            if (inTolerance && windowReady && slopeReady && finalSnapshot.running) {
                return TemperatureStabilityResult(
                    stable = true,
                    finalSnapshot = finalSnapshot,
                    observedDurationMs = elapsed,
                    maximumObservedSlopeCPerMinute = maxSlope,
                    message = "Temperature is stable"
                )
            }
            delay(policy.pollIntervalMs)
            elapsed += policy.pollIntervalMs
        }

        return TemperatureStabilityResult(
            stable = false,
            finalSnapshot = finalSnapshot,
            observedDurationMs = elapsed,
            maximumObservedSlopeCPerMinute = maxSlope,
            message = "Temperature stability timeout"
        )
    }
}
