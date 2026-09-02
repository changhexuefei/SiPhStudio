package org.jason.siph.domain.autonomy

import kotlinx.serialization.Serializable
import org.jason.siph.domain.coupling.CouplingConfig
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import kotlin.math.sqrt

/** 晶圆中的 Die 索引。 */
@Serializable
data class DieIndex(
    val column: Int,
    val row: Int
)

/** 一个可追溯的光学测量位置。 */
@Serializable
data class MeasurementSiteKey(
    val waferId: String,
    val die: DieIndex,
    val subDieId: String,
    val couplerId: String
) {
    init {
        require(waferId.isNotBlank()) { "waferId must not be blank" }
        require(subDieId.isNotBlank()) { "subDieId must not be blank" }
        require(couplerId.isNotBlank()) { "couplerId must not be blank" }
    }

    val stableId: String
        get() = "$waferId:${die.column}:${die.row}:$subDieId:$couplerId"
}

@Serializable
data class OpticalCouplerDefinition(
    val id: String,
    val name: String,
    val geometry: PhotonicCouplingGeometry,
    val offsetXUm: Double,
    val offsetYUm: Double,
    val expectedPowerRangeDbm: ClosedPowerRange? = null,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "coupler id must not be blank" }
        require(name.isNotBlank()) { "coupler name must not be blank" }
        require(offsetXUm.isFinite()) { "coupler offsetXUm must be finite" }
        require(offsetYUm.isFinite()) { "coupler offsetYUm must be finite" }
    }
}

@Serializable
data class ClosedPowerRange(
    val minimumDbm: Double,
    val maximumDbm: Double
) {
    init {
        require(minimumDbm.isFinite() && maximumDbm.isFinite()) {
            "power range must be finite"
        }
        require(minimumDbm <= maximumDbm) {
            "power range minimum must not exceed maximum"
        }
    }
}

@Serializable
data class SiPhSubDieDefinition(
    val id: String,
    val name: String,
    val originOffsetXUm: Double,
    val originOffsetYUm: Double,
    val couplers: List<OpticalCouplerDefinition>,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "sub-die id must not be blank" }
        require(name.isNotBlank()) { "sub-die name must not be blank" }
        require(originOffsetXUm.isFinite()) { "sub-die originOffsetXUm must be finite" }
        require(originOffsetYUm.isFinite()) { "sub-die originOffsetYUm must be finite" }
        require(couplers.map { it.id }.distinct().size == couplers.size) {
            "coupler ids must be unique inside a sub-die"
        }
    }
}

@Serializable
data class SiPhDieDefinition(
    val index: DieIndex,
    val label: String? = null,
    val subDies: List<SiPhSubDieDefinition>,
    val enabled: Boolean = true
) {
    init {
        require(subDies.map { it.id }.distinct().size == subDies.size) {
            "sub-die ids must be unique inside a die"
        }
    }
}

@Serializable
data class WaferCoordinateTransform(
    val originStageXUm: Double,
    val originStageYUm: Double,
    val diePitchXUm: Double,
    val diePitchYUm: Double,
    val rotationDeg: Double = 0.0
) {
    init {
        require(originStageXUm.isFinite()) { "originStageXUm must be finite" }
        require(originStageYUm.isFinite()) { "originStageYUm must be finite" }
        require(diePitchXUm.isFinite() && diePitchXUm != 0.0) { "diePitchXUm must be finite and non-zero" }
        require(diePitchYUm.isFinite() && diePitchYUm != 0.0) { "diePitchYUm must be finite and non-zero" }
        require(rotationDeg.isFinite()) { "rotationDeg must be finite" }
    }
}

@Serializable
data class SiPhWaferDefinition(
    val id: String,
    val lotId: String? = null,
    val diameterMm: Double,
    val transform: WaferCoordinateTransform,
    val dies: List<SiPhDieDefinition>,
    val createdAtEpochMs: Long,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank()) { "wafer id must not be blank" }
        require(diameterMm.isFinite() && diameterMm > 0.0) { "diameterMm must be positive" }
        require(dies.map { it.index }.distinct().size == dies.size) { "die indices must be unique" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    fun findSite(key: MeasurementSiteKey): OpticalCouplerDefinition? {
        if (key.waferId != id) return null
        return dies.firstOrNull { it.index == key.die }
            ?.subDies
            ?.firstOrNull { it.id == key.subDieId }
            ?.couplers
            ?.firstOrNull { it.id == key.couplerId }
    }
}

/** 训练得到的定位器绝对位置。 */
@Serializable
data class TrainedMeasurementPosition(
    val id: String,
    val name: String,
    val site: MeasurementSiteKey,
    val pose: OpticalPose,
    val referencePowerDbm: Double?,
    val calibrationProfileId: String,
    val safetyProfileId: String? = null,
    val trainedAtEpochMs: Long,
    val verifiedAtEpochMs: Long? = null,
    val verified: Boolean = false,
    val notes: String? = null,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank()) { "trained position id must not be blank" }
        require(name.isNotBlank()) { "trained position name must not be blank" }
        require(calibrationProfileId.isNotBlank()) { "calibrationProfileId must not be blank" }
        require(referencePowerDbm == null || referencePowerDbm.isFinite()) {
            "referencePowerDbm must be finite when provided"
        }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }
}

@Serializable
data class CalibrationVerificationRecord(
    val profileId: String,
    val verifiedAtEpochMs: Long,
    val controllerIdentity: String,
    val fixtureId: String,
    val poseErrorUm: Double,
    val powerErrorDb: Double?,
    val passed: Boolean,
    val message: String
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(controllerIdentity.isNotBlank()) { "controllerIdentity must not be blank" }
        require(fixtureId.isNotBlank()) { "fixtureId must not be blank" }
        require(poseErrorUm.isFinite() && poseErrorUm >= 0.0) { "poseErrorUm must be non-negative" }
        require(powerErrorDb == null || powerErrorDb.isFinite()) { "powerErrorDb must be finite" }
    }
}

@Serializable
data class OpticalVerificationConfig(
    val repeatCount: Int = 5,
    val readsPerRepeat: Int = 3,
    val settleDelayMs: Long = 40L,
    val excursion: OpticalDelta = OpticalDelta(dxUm = 2.0),
    val maxPeakToPeakDb: Double = 0.8,
    val maxStandardDeviationDb: Double = 0.25,
    val maxReturnPositionErrorUm: Double = 0.5
) {
    init {
        require(repeatCount in 1..1000) { "repeatCount must be in 1..1000" }
        require(readsPerRepeat in 1..100) { "readsPerRepeat must be in 1..100" }
        require(settleDelayMs >= 0L) { "settleDelayMs must be non-negative" }
        require(maxPeakToPeakDb.isFinite() && maxPeakToPeakDb >= 0.0)
        require(maxStandardDeviationDb.isFinite() && maxStandardDeviationDb >= 0.0)
        require(maxReturnPositionErrorUm.isFinite() && maxReturnPositionErrorUm >= 0.0)
    }
}

@Serializable
data class OpticalVerificationSample(
    val index: Int,
    val pose: OpticalPose,
    val powerDbm: Double,
    val positionErrorUm: Double,
    val timestampEpochMs: Long
)

@Serializable
data class OpticalAlignmentVerificationResult(
    val bestPose: OpticalPose,
    val referencePowerDbm: Double,
    val meanPowerDbm: Double,
    val standardDeviationDb: Double,
    val peakToPeakDb: Double,
    val maximumPositionErrorUm: Double,
    val samples: List<OpticalVerificationSample>,
    val passed: Boolean,
    val failures: List<String>,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long
)

@Serializable
enum class DriftAction {
    Continue,
    LocalRealign,
    FullRecalibration,
    StopWorkflow
}

@Serializable
data class DriftPolicy(
    val warningPowerDropDb: Double = 0.4,
    val localRealignPowerDropDb: Double = 0.8,
    val fullRecalibrationPowerDropDb: Double = 1.5,
    val stopPowerDropDb: Double = 3.0,
    val warningPositionShiftUm: Double = 0.5,
    val fullRecalibrationPositionShiftUm: Double = 2.0,
    val temperatureRecalibrationDeltaC: Double = 5.0
) {
    init {
        val powerThresholds = listOf(
            warningPowerDropDb,
            localRealignPowerDropDb,
            fullRecalibrationPowerDropDb,
            stopPowerDropDb
        )
        require(powerThresholds.all { it.isFinite() && it >= 0.0 })
        require(powerThresholds.zipWithNext().all { (left, right) -> left <= right }) {
            "drift power thresholds must be monotonic"
        }
        require(warningPositionShiftUm.isFinite() && warningPositionShiftUm >= 0.0)
        require(fullRecalibrationPositionShiftUm.isFinite() && fullRecalibrationPositionShiftUm >= warningPositionShiftUm)
        require(temperatureRecalibrationDeltaC.isFinite() && temperatureRecalibrationDeltaC >= 0.0)
    }
}

@Serializable
data class DriftBaseline(
    val id: String,
    val site: MeasurementSiteKey,
    val referencePose: OpticalPose,
    val referencePowerDbm: Double,
    val referenceTemperatureC: Double? = null,
    val calibrationProfileId: String,
    val createdAtEpochMs: Long
) {
    init {
        require(id.isNotBlank()) { "baseline id must not be blank" }
        require(referencePowerDbm.isFinite()) { "referencePowerDbm must be finite" }
        require(referenceTemperatureC == null || referenceTemperatureC.isFinite())
        require(calibrationProfileId.isNotBlank())
    }
}

@Serializable
data class DriftAssessment(
    val baselineId: String,
    val currentPowerDbm: Double,
    val powerDropDb: Double,
    val positionShiftUm: Double,
    val temperatureDeltaC: Double?,
    val action: DriftAction,
    val reasons: List<String>,
    val assessedAtEpochMs: Long
)

@Serializable
data class AlignmentTracePoint(
    val index: Int,
    val stage: String,
    val pose: OpticalPose,
    val powerDbm: Double,
    val timestampEpochMs: Long
)

@Serializable
data class MeasurementDeviceSnapshot(
    val positionerIdentity: String,
    val powerMeterIdentity: String,
    val runtimeMode: String,
    val controllerConnection: String? = null
)

@Serializable
data class MeasurementProvenance(
    val runId: String,
    val recipeId: String,
    val site: MeasurementSiteKey,
    val calibrationProfileId: String,
    val trainedPositionId: String,
    val safetyProfileId: String? = null,
    val devices: MeasurementDeviceSnapshot,
    val startPose: OpticalPose,
    val bestPose: OpticalPose,
    val finalPose: OpticalPose,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val warnings: List<String> = emptyList()
)

@Serializable
data class SiPhMeasurementRecord(
    val id: String,
    val provenance: MeasurementProvenance,
    val bestPowerDbm: Double,
    val finalPowerDbm: Double,
    val couplingStatus: String,
    val trace: List<AlignmentTracePoint>,
    val verification: OpticalAlignmentVerificationResult?,
    val driftAssessment: DriftAssessment?,
    val completed: Boolean,
    val failureMessage: String? = null,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank()) { "measurement record id must not be blank" }
        require(bestPowerDbm.isFinite() || bestPowerDbm == Double.NEGATIVE_INFINITY)
        require(finalPowerDbm.isFinite() || finalPowerDbm == Double.NEGATIVE_INFINITY)
        require(schemaVersion > 0)
    }
}

@Serializable
enum class SiPhWorkflowStage {
    Idle,
    InspectHardware,
    VerifyCalibration,
    ResolveMeasurementPosition,
    MoveToMeasurementPosition,
    AutoCoupling,
    VerifyReturnRepeatability,
    AssessDrift,
    PersistMeasurement,
    Completed,
    Paused,
    Stopped,
    Failed
}

@Serializable
enum class WorkflowStageStatus {
    Pending,
    Running,
    Succeeded,
    Skipped,
    Failed
}

@Serializable
data class WorkflowStageProgress(
    val stage: SiPhWorkflowStage,
    val status: WorkflowStageStatus,
    val attempt: Int = 0,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    val message: String? = null
)

@Serializable
data class WorkflowRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 100L,
    val backoffMultiplier: Double = 2.0,
    val maximumDelayMs: Long = 2_000L,
    val retryableStages: Set<SiPhWorkflowStage> = setOf(
        SiPhWorkflowStage.InspectHardware,
        SiPhWorkflowStage.MoveToMeasurementPosition,
        SiPhWorkflowStage.AutoCoupling,
        SiPhWorkflowStage.VerifyReturnRepeatability,
        SiPhWorkflowStage.PersistMeasurement
    )
) {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMs >= 0L)
        require(backoffMultiplier.isFinite() && backoffMultiplier >= 1.0)
        require(maximumDelayMs >= initialDelayMs)
    }
}

@Serializable
data class SiPhWorkflowRecipe(
    val id: String,
    val site: MeasurementSiteKey,
    val calibrationProfileId: String? = null,
    val trainedPositionId: String? = null,
    val couplingConfig: CouplingConfig = CouplingConfig(),
    val verificationConfig: OpticalVerificationConfig = OpticalVerificationConfig(),
    val driftPolicy: DriftPolicy = DriftPolicy(),
    val retryPolicy: WorkflowRetryPolicy = WorkflowRetryPolicy(),
    val requireVerifiedCalibration: Boolean = true,
    val requireVerifiedMeasurementPosition: Boolean = true,
    val enableVerification: Boolean = true,
    val enableDriftAssessment: Boolean = true,
    val manageDeviceConnections: Boolean = false,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank()) { "workflow recipe id must not be blank" }
        require(schemaVersion > 0)
    }
}

@Serializable
data class WorkflowFailure(
    val stage: SiPhWorkflowStage,
    val attempt: Int,
    val message: String,
    val recoverable: Boolean,
    val occurredAtEpochMs: Long
)

@Serializable
data class SiPhWorkflowCheckpoint(
    val runId: String,
    val recipe: SiPhWorkflowRecipe,
    val completedStages: Set<SiPhWorkflowStage>,
    val currentStage: SiPhWorkflowStage,
    val trainedPosition: TrainedMeasurementPosition? = null,
    val bestPose: OpticalPose? = null,
    val bestPowerDbm: Double? = null,
    val measurementRecordId: String? = null,
    val failures: List<WorkflowFailure> = emptyList(),
    val updatedAtEpochMs: Long,
    val schemaVersion: Int = 1
)

@Serializable
data class SiPhWorkflowState(
    val runId: String? = null,
    val recipeId: String? = null,
    val stage: SiPhWorkflowStage = SiPhWorkflowStage.Idle,
    val stageProgress: List<WorkflowStageProgress> = emptyList(),
    val message: String = "Workflow is idle",
    val running: Boolean = false,
    val stopRequested: Boolean = false,
    val attempt: Int = 0,
    val completedStageCount: Int = 0,
    val totalStageCount: Int = 0,
    val lastFailure: WorkflowFailure? = null,
    val measurementRecordId: String? = null,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null
)

internal fun OpticalPose.linearDistanceTo(other: OpticalPose): Double {
    val dx = xUm - other.xUm
    val dy = yUm - other.yUm
    val dz = zUm - other.zUm
    return sqrt(dx * dx + dy * dy + dz * dz)
}
