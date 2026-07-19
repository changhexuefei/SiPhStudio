package org.jason.siph.domain.oo

import kotlinx.serialization.Serializable
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition

@Serializable
enum class SweepAcquisitionMode {
    SoftwareStep,
    HardwareTriggered
}

@Serializable
data class OoRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 100L,
    val backoffMultiplier: Double = 2.0,
    val maximumDelayMs: Long = 2_000L
) {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMs >= 0L)
        require(backoffMultiplier.isFinite() && backoffMultiplier >= 1.0)
        require(maximumDelayMs >= initialDelayMs)
    }
}

@Serializable
data class OoMeasurementRecipe(
    val id: String,
    val waferId: String,
    val traversalStrategy: WaferTraversalStrategy = WaferTraversalStrategy.Serpentine,
    val explicitSiteOrder: List<MeasurementSiteKey> = emptyList(),
    val selectedSites: Set<MeasurementSiteKey> = emptySet(),
    val temperaturesC: List<Double> = listOf(25.0),
    val sweep: LaserSweepConfig,
    val acquisitionMode: SweepAcquisitionMode = SweepAcquisitionMode.SoftwareStep,
    val powerMeterChannel: Int = 1,
    val powerMeterAveraging: Int = 1,
    val powerMeterRange: OpticalPowerRange = OpticalPowerRange(),
    val temperatureStability: TemperatureStabilityPolicy = TemperatureStabilityPolicy(),
    val enableOpticalAlignment: Boolean = true,
    val contactBeforeMeasurement: Boolean = false,
    val separateAfterEachSite: Boolean = true,
    val manageDeviceConnections: Boolean = true,
    val retryPolicy: OoRetryPolicy = OoRetryPolicy(),
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank())
        require(waferId.isNotBlank())
        require(temperaturesC.isNotEmpty())
        require(temperaturesC.all(Double::isFinite))
        require(powerMeterChannel > 0)
        require(powerMeterAveraging in 1..10_000)
        require(schemaVersion > 0)
        require(
            traversalStrategy != WaferTraversalStrategy.Explicit || explicitSiteOrder.isNotEmpty()
        ) { "explicit traversal requires explicitSiteOrder" }
    }
}

@Serializable
enum class OoMeasurementStage {
    Idle,
    ValidateRecipe,
    ConnectDevices,
    InspectDevices,
    LoadWaferMap,
    StabilizeTemperature,
    ConfigureLaser,
    ConfigurePowerMeter,
    MoveToSite,
    Contact,
    OpticalAlignment,
    ExecuteWavelengthSweep,
    ValidateMeasurement,
    PersistResult,
    Separate,
    ReturnSafePosition,
    Paused,
    Completed,
    Stopped,
    Failed
}

@Serializable
data class OoMeasurementPoint(
    val wavelengthNm: Double,
    val inputPowerDbm: Double?,
    val outputPowerDbm: Double,
    val insertionLossDb: Double?,
    val temperatureC: Double,
    val site: MeasurementSiteKey,
    val timestampEpochMs: Long
) {
    init {
        require(wavelengthNm.isFinite() && wavelengthNm > 0.0)
        require(inputPowerDbm == null || inputPowerDbm.isFinite())
        require(outputPowerDbm.isFinite())
        require(insertionLossDb == null || insertionLossDb.isFinite())
        require(temperatureC.isFinite())
    }
}

@Serializable
data class OoSiteMeasurementResult(
    val site: MeasurementSiteKey,
    val temperatureC: Double,
    val alignmentPeakPowerDbm: Double?,
    val points: List<OoMeasurementPoint>,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val valid: Boolean,
    val validationMessages: List<String> = emptyList()
) {
    init {
        require(temperatureC.isFinite())
        require(alignmentPeakPowerDbm == null || alignmentPeakPowerDbm.isFinite())
        require(points.all { it.site == site })
    }
}

@Serializable
data class OoDeviceSnapshot(
    val role: String,
    val identity: String,
    val descriptor: DeviceDescriptor
)

@Serializable
data class OoMeasurementFailure(
    val stage: OoMeasurementStage,
    val site: MeasurementSiteKey? = null,
    val temperatureC: Double? = null,
    val attempt: Int,
    val message: String,
    val recoverable: Boolean,
    val occurredAtEpochMs: Long
) {
    init {
        require(attempt >= 1)
        require(temperatureC == null || temperatureC.isFinite())
        require(message.isNotBlank())
    }
}

@Serializable
data class OoMeasurementResult(
    val runId: String,
    val recipe: OoMeasurementRecipe,
    val waferSnapshot: SiPhWaferDefinition,
    val deviceSnapshots: List<OoDeviceSnapshot> = emptyList(),
    val siteResults: List<OoSiteMeasurementResult> = emptyList(),
    val failures: List<OoMeasurementFailure> = emptyList(),
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val completed: Boolean,
    val stopped: Boolean = false,
    val schemaVersion: Int = 1
) {
    init {
        require(runId.isNotBlank())
        require(schemaVersion > 0)
    }
}

@Serializable
data class OoMeasurementCheckpoint(
    val runId: String,
    val recipe: OoMeasurementRecipe,
    val waferSnapshot: SiPhWaferDefinition,
    val completedMeasurementKeys: Set<String> = emptySet(),
    val currentTemperatureIndex: Int = 0,
    val currentSiteIndex: Int = 0,
    val resultRunId: String = runId,
    val failures: List<OoMeasurementFailure> = emptyList(),
    val updatedAtEpochMs: Long,
    val schemaVersion: Int = 1
) {
    init {
        require(runId.isNotBlank())
        require(currentTemperatureIndex >= 0)
        require(currentSiteIndex >= 0)
        require(schemaVersion > 0)
    }
}

@Serializable
data class OoMeasurementState(
    val runId: String? = null,
    val stage: OoMeasurementStage = OoMeasurementStage.Idle,
    val message: String = "O-O workflow is idle",
    val running: Boolean = false,
    val paused: Boolean = false,
    val stopRequested: Boolean = false,
    val temperatureC: Double? = null,
    val site: MeasurementSiteKey? = null,
    val completedMeasurements: Int = 0,
    val totalMeasurements: Int = 0,
    val currentAttempt: Int = 0,
    val latestFailure: OoMeasurementFailure? = null,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null
)

internal fun measurementKey(
    temperatureC: Double,
    site: MeasurementSiteKey
): String = "${temperatureC}:${site.stableId}"
