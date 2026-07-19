package org.jason.siph.domain.oo

import kotlinx.coroutines.flow.StateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.optical.OpticalPowerMeterPort

/** 扫描、触发和日志采集使用的增强型功率计端口。 */
interface OoOpticalPowerMeterPort : OpticalPowerMeterPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun capabilities(): OpticalPowerMeterCapabilities

    suspend fun setRange(
        range: OpticalPowerRange,
        channel: Int = 1
    )

    suspend fun setAveraging(
        count: Int,
        channel: Int = 1
    )

    suspend fun configureTrigger(config: OpticalTriggerConfig)

    suspend fun zero(channel: Int = 1)

    suspend fun acquireLog(
        request: OpticalLogAcquisitionRequest
    ): OpticalLogAcquisitionResult
}

interface TunableLaserPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): TunableLaserCapabilities
    suspend fun setWavelengthNm(value: Double)
    suspend fun setPowerDbm(value: Double)
    suspend fun setOutputEnabled(enabled: Boolean)
    suspend fun configureSweep(config: LaserSweepConfig)
    suspend fun startSweep()
    suspend fun stopSweep()
    suspend fun snapshot(): TunableLaserSnapshot
}

interface WaferProberPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): WaferProberCapabilities
    suspend fun machineStatus(): ProberMachineStatus
    suspend fun loadMap(wafer: SiPhWaferDefinition)
    suspend fun snapshot(): ProberSiteSnapshot
    suspend fun moveToFirstDie(): ProberSiteSnapshot
    suspend fun moveToSite(site: MeasurementSiteKey): ProberSiteSnapshot
    suspend fun contact()
    suspend fun separate()
    suspend fun stop()
}

interface TemperatureControllerPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): TemperatureControllerCapabilities
    suspend fun readSnapshot(): TemperatureSnapshot
    suspend fun setSetpointC(value: Double)
    suspend fun startControl()
    suspend fun stopControl()
    suspend fun waitUntilStable(
        policy: TemperatureStabilityPolicy
    ): TemperatureStabilityResult
}

interface OoAlignmentPort {
    val status: StateFlow<AutonomyCapabilityStatus>

    suspend fun align(site: MeasurementSiteKey): OoAlignmentResult
    suspend fun moveToSafeState()
    suspend fun stop()
}

data class OoAlignmentResult(
    val site: MeasurementSiteKey,
    val aligned: Boolean,
    val peakPowerDbm: Double?,
    val message: String
) {
    init {
        require(peakPowerDbm == null || peakPowerDbm.isFinite())
    }
}
