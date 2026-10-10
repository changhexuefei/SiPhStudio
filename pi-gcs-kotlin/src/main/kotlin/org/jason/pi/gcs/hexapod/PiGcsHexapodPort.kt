package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.pitools.PiTools

/**
 * PI GCS based hexapod port.
 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
) : PiHexapodPort {

    private val axes: List<PiAxis> = axes.distinct().also {
        require(it.isNotEmpty()) { "PI hexapod axes must not be empty" }
    }

    @Volatile private var recoveryRequired = false
    private val safetyEpoch = AtomicLong(0L)
    private val stateLock = Any()
    private val _motionState = MutableStateFlow(PiMotionState())
    val motionState: StateFlow<PiMotionState> = _motionState.asStateFlow()

    override suspend fun connect() {
        check(!recoveryRequired) { "PI motion recovery required; reconnect alone cannot unlock movement" }
        device.connect()
        _motionState.value = PiMotionState(PiMotionPhase.Ready)
    }

    override suspend fun disconnect() {
        close()
    }

    /** Operator-controlled recovery: transport reopens for read-only checks only.
     * Caller must confirm physical clearance / emergency-stop reset separately.
     */
    suspend fun recover(operatorConfirmedSafe: Boolean) {
        check(recoveryRequired) { "No fault to recover" }
        require(operatorConfirmedSafe) { "Explicit operator safety confirmation required" }
        val expectedEpoch = safetyEpoch.get()
        _motionState.value = PiMotionState(PiMotionPhase.RecoveryRequired)
        try {
            if (!device.isOpen) device.connect()
            val positions = device.qPOS(axes)
            check(axes.all { positions[it]?.isFinite() == true }) { "Invalid recovered axis positions" }
            val onTarget = device.qONT(axes)
            check(axes.all { onTarget[it] == true }) { "Motion not confirmed on target" }
            check(device.qERR() == 0) { "Controller reports an active error" }
            val minima = device.qTMN(axes)
            val maxima = device.qTMX(axes)
            check(axes.all { axis ->
                val pos = positions[axis] ?: return@all false
                val min = minima[axis] ?: return@all false
                val max = maxima[axis] ?: return@all false
                min.isFinite() && max.isFinite() && min <= max && pos in min..max
            }) { "Recovered position is outside validated controller travel range" }
            synchronized(stateLock) {
                check(recoveryRequired && safetyEpoch.get() == expectedEpoch) {
                    "Recovery invalidated by a concurrent Stop or motion fault"
                }
                safetyEpoch.incrementAndGet()
                recoveryRequired = false
                _motionState.value = PiMotionState(PiMotionPhase.Ready)
            }
        } catch (failure: Throwable) {
            device.close()
            _motionState.value = PiMotionState(PiMotionPhase.RecoveryRequired, failure.message)
            throw failure
        }
    }

    override suspend fun identify(): String {
        ensureConnected()
        return device.qIDN()
    }

    override suspend fun startup(reference: Boolean) {
        ensureConnected()
        PiTools.startup(
            device = device,
            axes = axes,
            enableServo = true,
            reference = reference
        )
    }

    override suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean
    ) {
        ensureConnected()

        val commandValues = unitConfig
            .toCommandValues(pose)
            .filterKeys { it in axes }

        require(commandValues.isNotEmpty()) {
            "No configured PI axes are available for moveTo"
        }

        guardedMotion {
            device.moveAbsolute(commandValues)
            if (wait) waitForTarget()
        }
    }

    override suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean
    ) {
        ensureConnected()

        val commandDeltas = unitConfig
            .toCommandDeltas(delta)
            .filterKeys { it in axes }
            .filterValues { it != 0.0 }

        if (commandDeltas.isEmpty()) return

        guardedMotion {
            device.moveRelative(commandDeltas)
            if (wait) waitForTarget()
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()
        return unitConfig.fromCommandValues(device.qPOS(axes))
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        ensureConnected()
        guardedMotion { waitForTarget(timeoutMs) }
    }

    override suspend fun stop() {
        synchronized(stateLock) {
            safetyEpoch.incrementAndGet()
            recoveryRequired = true
            _motionState.value = PiMotionState(PiMotionPhase.Stopping)
        }
        try {
            withContext(NonCancellable) { withTimeout(2_000L) { if (device.isOpen) device.stopAll() } }
        } finally {
            device.close()
            _motionState.value = PiMotionState(PiMotionPhase.RecoveryRequired, "Stop requested; physical stop unverified")
        }
    }

    override suspend fun moveToSafePose() {
        moveTo(
            pose = safePose,
            wait = true
        )
    }

    /**
     * Returns the travel range in controller command units.
     */
    suspend fun queryCommandTravelRange(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        ensureConnected()
        return PiTools.queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    override fun close() {
        device.close()
        if (!recoveryRequired) _motionState.value = PiMotionState(PiMotionPhase.Disconnected)
    }

    private suspend fun waitForTarget(timeoutMs: Long = 10_000L) {
        PiTools.waitOnTarget(device, axes, timeoutMs, pollDelayMs = 100L)
    }

    private suspend fun guardedMotion(block: suspend () -> Unit) {
        ensureConnected()
        _motionState.value = PiMotionState(PiMotionPhase.Moving)
        try {
            block()
            if (!recoveryRequired) {
                _motionState.value = PiMotionState(PiMotionPhase.Ready)
            } else {
                error("Motion was interrupted; recovery is required")
            }
        } catch (failure: Throwable) {
            safetyEpoch.incrementAndGet()
            recoveryRequired = true
            if (_motionState.value.phase != PiMotionPhase.RecoveryRequired) {
                _motionState.value = PiMotionState(PiMotionPhase.Fault, failure.message)
            }
            withContext(NonCancellable) {
                try { withTimeout(2_000L) { if (device.isOpen) device.stopAll() } }
                catch (_: Exception) { /* Lost communication cannot confirm physical stop. */ }
                finally { device.close() }
            }
            _motionState.value = PiMotionState(PiMotionPhase.RecoveryRequired, failure.message)
            throw failure
        }
    }

    private fun ensureConnected() {
        check(!recoveryRequired) { "PI motion recovery required; motion locked" }
        check(device.isOpen) {
            "PI GCS Hexapod 尚未连接"
        }
    }
}
