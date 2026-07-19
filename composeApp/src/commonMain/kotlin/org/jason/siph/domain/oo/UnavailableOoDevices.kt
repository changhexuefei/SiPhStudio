package org.jason.siph.domain.oo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition

private fun unavailableDescriptor(
    id: String,
    model: String
): DeviceDescriptor = DeviceDescriptor(
    id = id,
    vendor = "Unconfigured",
    model = model,
    backendMode = DeviceBackendMode.Real,
    verificationState = DeviceVerificationState.ProtocolImplemented
)

private abstract class UnavailableOoDevice(
    protected val capabilityName: String
) {
    protected val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    val unavailableStatus: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    protected fun unavailable(): Nothing = error(
        "$capabilityName real adapter is not configured or hardware-verified"
    )
}

class UnavailableOoPowerMeter : UnavailableOoDevice("O-O optical power meter"), OoOpticalPowerMeterPort {
    override val status = unavailableStatus
    override val descriptor = unavailableDescriptor("unconfigured-power-meter", "O-O Optical Power Meter")
    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()
    override suspend fun capabilities(): OpticalPowerMeterCapabilities = unavailable()
    override suspend fun setWavelengthNm(wavelengthNm: Double, channel: Int) = unavailable()
    override suspend fun readPowerDbm(channel: Int): Double = unavailable()
    override suspend fun setRange(range: OpticalPowerRange, channel: Int) = unavailable()
    override suspend fun setAveraging(count: Int, channel: Int) = unavailable()
    override suspend fun configureTrigger(config: OpticalTriggerConfig) = unavailable()
    override suspend fun zero(channel: Int) = unavailable()
    override suspend fun acquireLog(request: OpticalLogAcquisitionRequest): OpticalLogAcquisitionResult = unavailable()
}

class UnavailableTunableLaser : UnavailableOoDevice("Tunable laser"), TunableLaserPort {
    override val status = unavailableStatus
    override val descriptor = unavailableDescriptor("unconfigured-laser", "Tunable Laser")
    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()
    override suspend fun capabilities(): TunableLaserCapabilities = unavailable()
    override suspend fun setWavelengthNm(value: Double) = unavailable()
    override suspend fun setPowerDbm(value: Double) = unavailable()
    override suspend fun setOutputEnabled(enabled: Boolean) = unavailable()
    override suspend fun configureSweep(config: LaserSweepConfig) = unavailable()
    override suspend fun startSweep() = unavailable()
    override suspend fun stopSweep() = Unit
    override suspend fun snapshot(): TunableLaserSnapshot = unavailable()
}

class UnavailableWaferProber : UnavailableOoDevice("Wafer prober"), WaferProberPort {
    override val status = unavailableStatus
    override val descriptor = unavailableDescriptor("unconfigured-prober", "Wafer Prober")
    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()
    override suspend fun capabilities(): WaferProberCapabilities = unavailable()
    override suspend fun machineStatus(): ProberMachineStatus = unavailable()
    override suspend fun loadMap(wafer: SiPhWaferDefinition) = unavailable()
    override suspend fun snapshot(): ProberSiteSnapshot = unavailable()
    override suspend fun moveToFirstDie(): ProberSiteSnapshot = unavailable()
    override suspend fun moveToSite(site: MeasurementSiteKey): ProberSiteSnapshot = unavailable()
    override suspend fun contact() = unavailable()
    override suspend fun separate() = Unit
    override suspend fun stop() = Unit
}

class UnavailableTemperatureController : UnavailableOoDevice("Temperature controller"), TemperatureControllerPort {
    override val status = unavailableStatus
    override val descriptor = unavailableDescriptor("unconfigured-temperature", "Temperature Controller")
    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()
    override suspend fun capabilities(): TemperatureControllerCapabilities = unavailable()
    override suspend fun readSnapshot(): TemperatureSnapshot = unavailable()
    override suspend fun setSetpointC(value: Double) = unavailable()
    override suspend fun startControl() = unavailable()
    override suspend fun stopControl() = Unit
    override suspend fun waitUntilStable(policy: TemperatureStabilityPolicy): TemperatureStabilityResult = unavailable()
}

class UnavailableOoAlignmentPort : OoAlignmentPort {
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    override suspend fun align(site: MeasurementSiteKey): OoAlignmentResult = error(
        "O-O alignment adapter is not configured"
    )
    override suspend fun moveToSafeState() = Unit
    override suspend fun stop() = Unit
}
