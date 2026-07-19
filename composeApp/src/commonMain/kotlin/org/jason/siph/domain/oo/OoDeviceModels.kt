package org.jason.siph.domain.oo

import kotlinx.serialization.Serializable
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.MeasurementSiteKey

@Serializable
enum class DeviceBackendMode {
    Simulation,
    Replay,
    Real
}

@Serializable
enum class DeviceVerificationState {
    SimulationOnly,
    ProtocolImplemented,
    HardwareVerified
}

@Serializable
data class DeviceDescriptor(
    val id: String,
    val vendor: String,
    val model: String,
    val backendMode: DeviceBackendMode,
    val verificationState: DeviceVerificationState,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null
) {
    init {
        require(id.isNotBlank()) { "device id must not be blank" }
        require(vendor.isNotBlank()) { "device vendor must not be blank" }
        require(model.isNotBlank()) { "device model must not be blank" }
    }
}

@Serializable
data class OoDeviceStatus(
    val capability: AutonomyCapabilityStatus,
    val descriptor: DeviceDescriptor
)

@Serializable
data class OpticalPowerMeterCapabilities(
    val channelCount: Int,
    val minimumWavelengthNm: Double,
    val maximumWavelengthNm: Double,
    val supportsAutoRange: Boolean,
    val supportsHardwareTrigger: Boolean,
    val supportsLogAcquisition: Boolean,
    val maximumLogPoints: Int
) {
    init {
        require(channelCount > 0)
        require(minimumWavelengthNm.isFinite() && maximumWavelengthNm.isFinite())
        require(minimumWavelengthNm < maximumWavelengthNm)
        require(maximumLogPoints > 0)
    }
}

@Serializable
enum class OpticalPowerRangeMode {
    Auto,
    Manual
}

@Serializable
data class OpticalPowerRange(
    val mode: OpticalPowerRangeMode = OpticalPowerRangeMode.Auto,
    val upperDbm: Double? = null
) {
    init {
        require(upperDbm == null || upperDbm.isFinite())
        require(mode == OpticalPowerRangeMode.Auto || upperDbm != null) {
            "manual optical power range requires upperDbm"
        }
    }
}

@Serializable
enum class OpticalTriggerMode {
    Immediate,
    ExternalRising,
    ExternalFalling
}

@Serializable
data class OpticalTriggerConfig(
    val mode: OpticalTriggerMode = OpticalTriggerMode.Immediate,
    val expectedPoints: Int = 1,
    val timeoutMs: Long = 30_000L
) {
    init {
        require(expectedPoints > 0)
        require(timeoutMs > 0L)
    }
}

@Serializable
data class OpticalLogAcquisitionRequest(
    val wavelengthsNm: List<Double>,
    val channel: Int = 1,
    val timeoutMs: Long = 60_000L
) {
    init {
        require(wavelengthsNm.isNotEmpty())
        require(wavelengthsNm.all { it.isFinite() && it > 0.0 })
        require(channel > 0)
        require(timeoutMs > 0L)
    }
}

@Serializable
data class OpticalLogAcquisitionResult(
    val wavelengthsNm: List<Double>,
    val powersDbm: List<Double>,
    val completed: Boolean,
    val message: String? = null
) {
    init {
        require(wavelengthsNm.size == powersDbm.size)
        require(wavelengthsNm.all { it.isFinite() && it > 0.0 })
        require(powersDbm.all(Double::isFinite))
    }
}

@Serializable
data class TunableLaserCapabilities(
    val minimumWavelengthNm: Double,
    val maximumWavelengthNm: Double,
    val minimumPowerDbm: Double,
    val maximumPowerDbm: Double,
    val supportsContinuousSweep: Boolean,
    val supportsHardwareTriggerOutput: Boolean,
    val maximumSweepPoints: Int
) {
    init {
        require(minimumWavelengthNm.isFinite() && maximumWavelengthNm.isFinite())
        require(minimumWavelengthNm < maximumWavelengthNm)
        require(minimumPowerDbm.isFinite() && maximumPowerDbm.isFinite())
        require(minimumPowerDbm <= maximumPowerDbm)
        require(maximumSweepPoints > 0)
    }
}

@Serializable
enum class LaserTriggerOutputMode {
    Disabled,
    StepComplete,
    SweepStart,
    SweepEnd
}

@Serializable
data class LaserSweepConfig(
    val startWavelengthNm: Double,
    val stopWavelengthNm: Double,
    val stepWavelengthNm: Double,
    val powerDbm: Double,
    val dwellMs: Long = 5L,
    val triggerOutputMode: LaserTriggerOutputMode = LaserTriggerOutputMode.StepComplete
) {
    init {
        require(startWavelengthNm.isFinite() && stopWavelengthNm.isFinite())
        require(stopWavelengthNm >= startWavelengthNm)
        require(stepWavelengthNm.isFinite() && stepWavelengthNm > 0.0)
        require(powerDbm.isFinite())
        require(dwellMs >= 0L)
        require(pointCount in 1..1_000_000)
    }

    val pointCount: Int
        get() = kotlin.math.floor((stopWavelengthNm - startWavelengthNm) / stepWavelengthNm + 1e-9)
            .toInt() + 1

    fun wavelengths(): List<Double> = List(pointCount) { index ->
        (startWavelengthNm + index * stepWavelengthNm).coerceAtMost(stopWavelengthNm)
    }
}

@Serializable
data class TunableLaserSnapshot(
    val wavelengthNm: Double,
    val powerDbm: Double,
    val outputEnabled: Boolean,
    val sweeping: Boolean
)

@Serializable
data class WaferProberCapabilities(
    val supportsContactControl: Boolean,
    val supportsSubDieNavigation: Boolean,
    val supportsSerpentineTraversal: Boolean,
    val supportsMapUpload: Boolean
)

@Serializable
enum class ProberContactState {
    Unknown,
    Separated,
    Contact
}

@Serializable
enum class ProberMachineState {
    Disconnected,
    Ready,
    Busy,
    Error
}

@Serializable
data class ProberMachineStatus(
    val state: ProberMachineState,
    val loaderBusy: Boolean = false,
    val measuring: Boolean = false,
    val message: String? = null
)

@Serializable
data class ProberSiteSnapshot(
    val loadedWaferId: String? = null,
    val currentSite: MeasurementSiteKey? = null,
    val stageXUm: Double? = null,
    val stageYUm: Double? = null,
    val contactState: ProberContactState = ProberContactState.Unknown
)

@Serializable
data class TemperatureControllerCapabilities(
    val minimumTemperatureC: Double,
    val maximumTemperatureC: Double,
    val supportsRunStop: Boolean,
    val supportsAlarmReadback: Boolean
) {
    init {
        require(minimumTemperatureC.isFinite() && maximumTemperatureC.isFinite())
        require(minimumTemperatureC < maximumTemperatureC)
    }
}

@Serializable
data class TemperatureSnapshot(
    val processValueC: Double,
    val setpointC: Double,
    val running: Boolean,
    val alarmActive: Boolean,
    val outputPercent: Double? = null
) {
    init {
        require(processValueC.isFinite())
        require(setpointC.isFinite())
        require(outputPercent == null || outputPercent.isFinite())
    }
}

@Serializable
data class TemperatureStabilityPolicy(
    val targetToleranceC: Double = 0.3,
    val maximumSlopeCPerMinute: Double = 0.2,
    val stableWindowMs: Long = 60_000L,
    val timeoutMs: Long = 30L * 60_000L,
    val pollIntervalMs: Long = 1_000L
) {
    init {
        require(targetToleranceC.isFinite() && targetToleranceC >= 0.0)
        require(maximumSlopeCPerMinute.isFinite() && maximumSlopeCPerMinute >= 0.0)
        require(stableWindowMs >= 0L)
        require(timeoutMs > 0L)
        require(pollIntervalMs > 0L)
    }
}

@Serializable
data class TemperatureStabilityResult(
    val stable: Boolean,
    val finalSnapshot: TemperatureSnapshot,
    val observedDurationMs: Long,
    val maximumObservedSlopeCPerMinute: Double,
    val message: String
)

@Serializable
enum class WaferTraversalStrategy {
    Serpentine,
    RowMajor,
    ColumnMajor,
    Explicit
}

@Serializable
data class DeviceFaultPlan(
    val failOperationCounts: Map<String, Int> = emptyMap(),
    val timeoutOperationCounts: Map<String, Int> = emptyMap(),
    val temperatureNeverStable: Boolean = false,
    val laserSweepStopsEarly: Boolean = false,
    val proberPositionErrorUm: Double = 0.0
) {
    init {
        require(failOperationCounts.values.all { it >= 0 })
        require(timeoutOperationCounts.values.all { it >= 0 })
        require(proberPositionErrorUm.isFinite() && proberPositionErrorUm >= 0.0)
    }
}
