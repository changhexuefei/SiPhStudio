package org.jason.siph.domain.oo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.siph.domain.autonomy.AutonomyCapabilityState
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal data class SimulatedOoSnapshot(
    val laserWavelengthNm: Double = 1550.0,
    val laserPowerDbm: Double = 0.0,
    val laserOutputEnabled: Boolean = false,
    val laserSweeping: Boolean = false,
    val temperatureC: Double = 25.0,
    val site: MeasurementSiteKey? = null,
    val alignedSite: MeasurementSiteKey? = null
)

class SimulatedOoEnvironment(
    private val faultPlan: DeviceFaultPlan = DeviceFaultPlan()
) {
    private val mutex = Mutex()
    private var value = SimulatedOoSnapshot()
    private val failures = faultPlan.failOperationCounts.toMutableMap()
    private val timeouts = faultPlan.timeoutOperationCounts.toMutableMap()

    internal suspend fun snapshot(): SimulatedOoSnapshot = mutex.withLock { value }

    internal suspend fun update(transform: (SimulatedOoSnapshot) -> SimulatedOoSnapshot) {
        mutex.withLock { value = transform(value) }
    }

    internal suspend fun beforeOperation(operation: String) {
        val action = mutex.withLock {
            when {
                (failures[operation] ?: 0) > 0 -> {
                    failures[operation] = requireNotNull(failures[operation]) - 1
                    "failure"
                }
                (timeouts[operation] ?: 0) > 0 -> {
                    timeouts[operation] = requireNotNull(timeouts[operation]) - 1
                    "timeout"
                }
                else -> null
            }
        }
        when (action) {
            "failure" -> error("Injected simulation failure at $operation")
            "timeout" -> {
                delay(5L)
                error("Injected simulation timeout at $operation")
            }
        }
    }

    internal val temperatureNeverStable get() = faultPlan.temperatureNeverStable
    internal val laserSweepStopsEarly get() = faultPlan.laserSweepStopsEarly
    internal val proberPositionErrorUm get() = faultPlan.proberPositionErrorUm
}

class SimulatedPhotonicDeviceModel(
    private val nominalInsertionLossDb: Double = 4.0,
    private val resonanceDepthDb: Double = 12.0,
    private val resonanceWidthNm: Double = 0.55,
    private val temperatureShiftNmPerC: Double = 0.08
) {
    internal fun outputPowerDbm(
        snapshot: SimulatedOoSnapshot,
        wavelengthNm: Double,
        sampleIndex: Int
    ): Double {
        if (!snapshot.laserOutputEnabled) return -90.0
        val site = snapshot.site ?: return -90.0
        val siteOffset = ((site.stableId.hashCode() and 0x7fffffff) % 1000) / 1000.0
        val center = 1549.4 + siteOffset * 1.2 +
            (snapshot.temperatureC - 25.0) * temperatureShiftNmPerC
        val normalized = (wavelengthNm - center) / resonanceWidthNm
        val resonanceLoss = resonanceDepthDb * exp(-0.5 * normalized * normalized)
        val alignmentPenalty = if (snapshot.alignedSite == site) 0.0 else 8.0
        val noise = 0.015 * sin(sampleIndex * 1.731 + wavelengthNm * 0.031)
        return snapshot.laserPowerDbm - nominalInsertionLossDb - resonanceLoss - alignmentPenalty + noise
    }
}

private fun descriptor(id: String, model: String) = DeviceDescriptor(
    id = id,
    vendor = "SiPhStudio",
    model = model,
    backendMode = DeviceBackendMode.Simulation,
    verificationState = DeviceVerificationState.SimulationOnly
)

private fun ready(identity: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Ready,
    identity = identity,
    detail = "Simulation backend is ready"
)

private fun disconnected(model: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Disconnected,
    identity = model,
    detail = "Simulation backend disconnected"
)

class SimulatedTunableLaser(
    private val environment: SimulatedOoEnvironment
) : TunableLaserPort {
    override val descriptor = descriptor("sim-laser", "Simulated Tunable Laser")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var sweepConfig: LaserSweepConfig? = null

    override suspend fun connect() {
        environment.beforeOperation("laser.connect")
        connected = true
        mutableStatus.value = ready(identify())
    }

    override suspend fun disconnect() {
        connected = false
        environment.update { it.copy(laserOutputEnabled = false, laserSweeping = false) }
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Simulated Tunable Laser,0001,1.0"
    }

    override suspend fun capabilities() = TunableLaserCapabilities(
        minimumWavelengthNm = 1260.0,
        maximumWavelengthNm = 1630.0,
        minimumPowerDbm = -20.0,
        maximumPowerDbm = 13.0,
        supportsContinuousSweep = true,
        supportsHardwareTriggerOutput = true,
        maximumSweepPoints = 100_001
    )

    override suspend fun setWavelengthNm(value: Double) {
        ensureConnected()
        environment.beforeOperation("laser.setWavelength")
        require(value in capabilities().minimumWavelengthNm..capabilities().maximumWavelengthNm)
        environment.update { it.copy(laserWavelengthNm = value) }
    }

    override suspend fun setPowerDbm(value: Double) {
        ensureConnected()
        environment.beforeOperation("laser.setPower")
        require(value in capabilities().minimumPowerDbm..capabilities().maximumPowerDbm)
        environment.update { it.copy(laserPowerDbm = value) }
    }

    override suspend fun setOutputEnabled(enabled: Boolean) {
        ensureConnected()
        environment.beforeOperation("laser.output")
        environment.update { it.copy(laserOutputEnabled = enabled) }
    }

    override suspend fun configureSweep(config: LaserSweepConfig) {
        ensureConnected()
        environment.beforeOperation("laser.configureSweep")
        require(config.pointCount <= capabilities().maximumSweepPoints)
        sweepConfig = config
        setPowerDbm(config.powerDbm)
        setWavelengthNm(config.startWavelengthNm)
    }

    override suspend fun startSweep() {
        ensureConnected()
        environment.beforeOperation("laser.startSweep")
        checkNotNull(sweepConfig) { "Laser sweep is not configured" }
        environment.update { it.copy(laserSweeping = true, laserOutputEnabled = true) }
    }

    override suspend fun stopSweep() {
        if (connected) environment.update { it.copy(laserSweeping = false) }
    }

    override suspend fun snapshot(): TunableLaserSnapshot {
        ensureConnected()
        return environment.snapshot().let {
            TunableLaserSnapshot(
                wavelengthNm = it.laserWavelengthNm,
                powerDbm = it.laserPowerDbm,
                outputEnabled = it.laserOutputEnabled,
                sweeping = it.laserSweeping
            )
        }
    }

    private fun ensureConnected() = check(connected) { "Simulation laser is not connected" }
}

class SimulatedOoPowerMeter(
    private val environment: SimulatedOoEnvironment,
    private val model: SimulatedPhotonicDeviceModel = SimulatedPhotonicDeviceModel()
) : OoOpticalPowerMeterPort {
    override val descriptor = descriptor("sim-power-meter", "Simulated Optical Power Meter")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var wavelengthNm = 1550.0
    private var averaging = 1
    private var readIndex = 0

    override suspend fun connect() {
        environment.beforeOperation("powerMeter.connect")
        connected = true
        mutableStatus.value = ready(identify())
    }

    override suspend fun disconnect() {
        connected = false
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Simulated Optical Power Meter,0001,1.0"
    }

    override suspend fun capabilities() = OpticalPowerMeterCapabilities(
        channelCount = 4,
        minimumWavelengthNm = 800.0,
        maximumWavelengthNm = 1700.0,
        supportsAutoRange = true,
        supportsHardwareTrigger = true,
        supportsLogAcquisition = true,
        maximumLogPoints = 100_001
    )

    override suspend fun setWavelengthNm(wavelengthNm: Double, channel: Int) {
        ensureConnected()
        require(channel in 1..capabilities().channelCount)
        require(wavelengthNm in capabilities().minimumWavelengthNm..capabilities().maximumWavelengthNm)
        this.wavelengthNm = wavelengthNm
    }

    override suspend fun readPowerDbm(channel: Int): Double {
        ensureConnected()
        environment.beforeOperation("powerMeter.read")
        require(channel in 1..capabilities().channelCount)
        var total = 0.0
        repeat(averaging) {
            total += model.outputPowerDbm(environment.snapshot(), wavelengthNm, readIndex++)
        }
        return total / averaging
    }

    override suspend fun setRange(range: OpticalPowerRange, channel: Int) {
        ensureConnected()
        require(channel in 1..capabilities().channelCount)
    }

    override suspend fun setAveraging(count: Int, channel: Int) {
        ensureConnected()
        require(channel in 1..capabilities().channelCount)
        require(count in 1..10_000)
        averaging = count
    }

    override suspend fun configureTrigger(config: OpticalTriggerConfig) {
        ensureConnected()
        require(config.expectedPoints <= capabilities().maximumLogPoints)
    }

    override suspend fun zero(channel: Int) {
        ensureConnected()
        require(channel in 1..capabilities().channelCount)
    }

    override suspend fun acquireLog(request: OpticalLogAcquisitionRequest): OpticalLogAcquisitionResult {
        ensureConnected()
        environment.beforeOperation("powerMeter.acquireLog")
        val wavelengths = if (environment.laserSweepStopsEarly && request.wavelengthsNm.size > 1) {
            request.wavelengthsNm.dropLast(1)
        } else request.wavelengthsNm
        val powers = wavelengths.map { model.outputPowerDbm(environment.snapshot(), it, readIndex++) }
        return OpticalLogAcquisitionResult(
            wavelengthsNm = wavelengths,
            powersDbm = powers,
            completed = wavelengths.size == request.wavelengthsNm.size,
            message = if (wavelengths.size == request.wavelengthsNm.size) null else "Injected early sweep stop"
        )
    }

    private fun ensureConnected() = check(connected) { "Simulation power meter is not connected" }
}

class SimulatedWaferProber(
    private val environment: SimulatedOoEnvironment
) : WaferProberPort {
    override val descriptor = descriptor("sim-prober", "Simulated Wafer Prober")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var wafer: SiPhWaferDefinition? = null
    private var current = ProberSiteSnapshot(contactState = ProberContactState.Separated)

    override suspend fun connect() {
        environment.beforeOperation("prober.connect")
        connected = true
        mutableStatus.value = ready(identify())
    }

    override suspend fun disconnect() {
        connected = false
        current = current.copy(contactState = ProberContactState.Separated)
        environment.update { it.copy(site = null, alignedSite = null) }
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Simulated Wafer Prober,0001,1.0"
    }

    override suspend fun capabilities() = WaferProberCapabilities(
        supportsContactControl = true,
        supportsSubDieNavigation = true,
        supportsSerpentineTraversal = true,
        supportsMapUpload = true
    )

    override suspend fun machineStatus(): ProberMachineStatus {
        ensureConnected()
        return ProberMachineStatus(ProberMachineState.Ready)
    }

    override suspend fun loadMap(wafer: SiPhWaferDefinition) {
        ensureConnected()
        environment.beforeOperation("prober.loadMap")
        this.wafer = wafer
        current = ProberSiteSnapshot(
            loadedWaferId = wafer.id,
            contactState = ProberContactState.Separated
        )
    }

    override suspend fun snapshot(): ProberSiteSnapshot {
        ensureConnected()
        return current
    }

    override suspend fun moveToFirstDie(): ProberSiteSnapshot {
        val loaded = requireNotNull(wafer) { "Wafer map is not loaded" }
        val first = WaferTraversalPlanner().buildRoute(loaded, WaferTraversalStrategy.RowMajor)
            .firstOrNull() ?: error("Wafer map has no enabled measurement sites")
        return moveToSite(first)
    }

    override suspend fun moveToSite(site: MeasurementSiteKey): ProberSiteSnapshot {
        ensureConnected()
        environment.beforeOperation("prober.moveToSite")
        val loaded = requireNotNull(wafer) { "Wafer map is not loaded" }
        require(loaded.findSite(site) != null) { "Measurement site does not exist: ${site.stableId}" }
        val (x, y) = stagePosition(loaded, site)
        current = ProberSiteSnapshot(
            loadedWaferId = loaded.id,
            currentSite = site,
            stageXUm = x + environment.proberPositionErrorUm,
            stageYUm = y,
            contactState = ProberContactState.Separated
        )
        environment.update { it.copy(site = site, alignedSite = null) }
        return current
    }

    override suspend fun contact() {
        ensureConnected()
        environment.beforeOperation("prober.contact")
        check(current.currentSite != null) { "Cannot contact before moving to a site" }
        current = current.copy(contactState = ProberContactState.Contact)
    }

    override suspend fun separate() {
        if (connected) current = current.copy(contactState = ProberContactState.Separated)
    }

    override suspend fun stop() = Unit

    private fun stagePosition(wafer: SiPhWaferDefinition, site: MeasurementSiteKey): Pair<Double, Double> {
        val die = wafer.dies.first { it.index == site.die }
        val subDie = die.subDies.first { it.id == site.subDieId }
        val coupler = subDie.couplers.first { it.id == site.couplerId }
        val localX = site.die.column * wafer.transform.diePitchXUm + subDie.originOffsetXUm + coupler.offsetXUm
        val localY = site.die.row * wafer.transform.diePitchYUm + subDie.originOffsetYUm + coupler.offsetYUm
        val angle = wafer.transform.rotationDeg * PI / 180.0
        val rotatedX = localX * cos(angle) - localY * sin(angle)
        val rotatedY = localX * sin(angle) + localY * cos(angle)
        return wafer.transform.originStageXUm + rotatedX to wafer.transform.originStageYUm + rotatedY
    }

    private fun ensureConnected() = check(connected) { "Simulation prober is not connected" }
}

class SimulatedTemperatureController(
    private val environment: SimulatedOoEnvironment
) : TemperatureControllerPort {
    override val descriptor = descriptor("sim-temperature", "Simulated Temperature Controller")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var running = false
    private var setpointC = 25.0

    override suspend fun connect() {
        environment.beforeOperation("temperature.connect")
        connected = true
        mutableStatus.value = ready(identify())
    }

    override suspend fun disconnect() {
        connected = false
        running = false
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Simulated Temperature Controller,0001,1.0"
    }

    override suspend fun capabilities() = TemperatureControllerCapabilities(
        minimumTemperatureC = -40.0,
        maximumTemperatureC = 200.0,
        supportsRunStop = true,
        supportsAlarmReadback = true
    )

    override suspend fun readSnapshot(): TemperatureSnapshot {
        ensureConnected()
        return TemperatureSnapshot(
            processValueC = environment.snapshot().temperatureC,
            setpointC = setpointC,
            running = running,
            alarmActive = false,
            outputPercent = if (running) 35.0 else 0.0
        )
    }

    override suspend fun setSetpointC(value: Double) {
        ensureConnected()
        environment.beforeOperation("temperature.setpoint")
        require(value in capabilities().minimumTemperatureC..capabilities().maximumTemperatureC)
        setpointC = value
    }

    override suspend fun startControl() {
        ensureConnected()
        environment.beforeOperation("temperature.start")
        running = true
    }

    override suspend fun stopControl() {
        if (connected) running = false
    }

    override suspend fun waitUntilStable(policy: TemperatureStabilityPolicy): TemperatureStabilityResult {
        ensureConnected()
        environment.beforeOperation("temperature.waitStable")
        if (environment.temperatureNeverStable) {
            return TemperatureStabilityResult(
                stable = false,
                finalSnapshot = readSnapshot(),
                observedDurationMs = policy.timeoutMs,
                maximumObservedSlopeCPerMinute = 1.0,
                message = "Injected temperature stability timeout"
            )
        }
        delay(policy.pollIntervalMs.coerceAtMost(10L))
        environment.update { it.copy(temperatureC = setpointC) }
        return TemperatureStabilityResult(
            stable = true,
            finalSnapshot = readSnapshot(),
            observedDurationMs = policy.stableWindowMs,
            maximumObservedSlopeCPerMinute = 0.0,
            message = "Simulation temperature is stable"
        )
    }

    private fun ensureConnected() = check(connected) { "Simulation temperature controller is not connected" }
}

class SimulatedOoAlignmentPort(
    private val environment: SimulatedOoEnvironment
) : OoAlignmentPort {
    private val mutableStatus = MutableStateFlow(ready("SiPhStudio Simulated O-O Alignment"))
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    override suspend fun align(site: MeasurementSiteKey): OoAlignmentResult {
        environment.beforeOperation("alignment.align")
        check(environment.snapshot().site == site) { "Prober is not positioned at requested alignment site" }
        environment.update { it.copy(alignedSite = site) }
        return OoAlignmentResult(site, true, -5.0, "Simulation alignment completed")
    }

    override suspend fun moveToSafeState() {
        environment.update { it.copy(alignedSite = null) }
    }

    override suspend fun stop() = Unit
}
