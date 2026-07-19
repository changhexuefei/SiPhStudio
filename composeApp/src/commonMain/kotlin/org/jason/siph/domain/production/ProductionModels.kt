package org.jason.siph.domain.production

import kotlinx.serialization.Serializable
import org.jason.siph.domain.autonomy.MeasurementSiteKey

@Serializable
enum class FiberArrayOrientation {
    Horizontal,
    Vertical,
    Custom
}

@Serializable
data class FiberArrayChannel(
    val index: Int,
    val name: String,
    val offsetXUm: Double,
    val offsetYUm: Double,
    val enabled: Boolean = true,
    val opticalPathId: String? = null
) {
    init {
        require(index >= 0)
        require(name.isNotBlank())
        require(offsetXUm.isFinite() && offsetYUm.isFinite())
    }
}

@Serializable
data class FiberArrayDefinition(
    val id: String,
    val name: String,
    val channelCount: Int,
    val nominalPitchUm: Double,
    val referenceChannel: Int,
    val channels: List<FiberArrayChannel>,
    val orientation: FiberArrayOrientation = FiberArrayOrientation.Horizontal,
    val nominalAngleDeg: Double = 0.0,
    val verified: Boolean = false,
    val revision: Int = 1
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(channelCount > 0)
        require(nominalPitchUm.isFinite() && nominalPitchUm > 0.0)
        require(nominalAngleDeg.isFinite())
        require(revision > 0)
        require(channels.size == channelCount)
        require(channels.map { it.index }.distinct().size == channels.size)
        require(referenceChannel in channels.map { it.index })
    }
}

@Serializable
data class FiberChannelObservation(
    val channelIndex: Int,
    val measuredXUm: Double,
    val measuredYUm: Double,
    val powerDbm: Double,
    val detected: Boolean,
    val confidence: Double
) {
    init {
        require(channelIndex >= 0)
        require(measuredXUm.isFinite() && measuredYUm.isFinite())
        require(powerDbm.isFinite())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

@Serializable
data class FiberArrayAlignmentResult(
    val arrayId: String,
    val translationXUm: Double,
    val translationYUm: Double,
    val rotationErrorDeg: Double,
    val correctedPitchUm: Double,
    val minimumPowerDbm: Double,
    val maximumChannelImbalanceDb: Double,
    val channelObservations: List<FiberChannelObservation>,
    val passed: Boolean,
    val message: String
) {
    init {
        require(arrayId.isNotBlank())
        require(
            listOf(
                translationXUm,
                translationYUm,
                rotationErrorDeg,
                correctedPitchUm,
                minimumPowerDbm,
                maximumChannelImbalanceDb
            ).all(Double::isFinite)
        )
        require(correctedPitchUm > 0.0)
        require(maximumChannelImbalanceDb >= 0.0)
        require(channelObservations.isNotEmpty())
        require(message.isNotBlank())
    }
}

@Serializable
enum class ProductionMeasurementType {
    OpticalOptical,
    OpticalElectrical,
    OpticalElectricalOptical
}

@Serializable
enum class MeasurementStepType {
    ValidateCalibration,
    LoadSite,
    AlignOpticalPath,
    ConfigureLaser,
    ConfigureElectricalBias,
    WaitForStability,
    SweepWavelength,
    SweepVoltage,
    CaptureOpticalPower,
    CaptureElectricalSignal,
    CaptureWaveform,
    EvaluateQuality,
    SaveCheckpoint,
    ReturnSafeState
}

@Serializable
data class MeasurementStepDefinition(
    val id: String,
    val type: MeasurementStepType,
    val requiredCapability: String? = null,
    val numericParameters: Map<String, Double> = emptyMap(),
    val textParameters: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank())
        require(requiredCapability == null || requiredCapability.isNotBlank())
        require(numericParameters.values.all(Double::isFinite))
        require(textParameters.keys.all(String::isNotBlank))
    }
}

@Serializable
enum class RecipeApprovalState {
    Draft,
    AwaitingApproval,
    Approved,
    Retired
}

@Serializable
data class ProductionMeasurementRecipe(
    val id: String,
    val version: Int,
    val name: String,
    val measurementType: ProductionMeasurementType,
    val fiberArrayId: String? = null,
    val steps: List<MeasurementStepDefinition>,
    val requiredDeviceCapabilities: Set<String>,
    val qualityRuleSetId: String,
    val calibrationPolicyId: String,
    val approvalState: RecipeApprovalState = RecipeApprovalState.Draft,
    val createdBy: String,
    val approvedBy: String? = null,
    val createdAtEpochMs: Long,
    val approvedAtEpochMs: Long? = null
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(version > 0)
        require(steps.isNotEmpty())
        require(steps.map { it.id }.distinct().size == steps.size)
        require(requiredDeviceCapabilities.all(String::isNotBlank))
        require(qualityRuleSetId.isNotBlank() && calibrationPolicyId.isNotBlank())
        require(createdBy.isNotBlank())
        if (approvalState == RecipeApprovalState.Approved) {
            require(!approvedBy.isNullOrBlank())
            require(approvedAtEpochMs != null)
            require(approvedBy != createdBy) {
                "Recipe creator cannot approve the same recipe version"
            }
        }
    }

    val stableVersionId: String get() = "$id-v$version"
}

@Serializable
data class MeasurementMetric(
    val name: String,
    val value: Double,
    val unit: String,
    val lowerSpecificationLimit: Double? = null,
    val upperSpecificationLimit: Double? = null
) {
    init {
        require(name.isNotBlank() && unit.isNotBlank())
        require(value.isFinite())
        require(lowerSpecificationLimit == null || lowerSpecificationLimit.isFinite())
        require(upperSpecificationLimit == null || upperSpecificationLimit.isFinite())
        if (lowerSpecificationLimit != null && upperSpecificationLimit != null) {
            require(lowerSpecificationLimit < upperSpecificationLimit)
        }
    }
}

@Serializable
data class ArtifactReference(
    val id: String,
    val type: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long
) {
    init {
        require(id.isNotBlank() && type.isNotBlank() && relativePath.isNotBlank())
        require(sha256.isNotBlank())
        require(sizeBytes >= 0L)
    }
}

@Serializable
data class ProductionMeasurementResult(
    val resultId: String,
    val taskId: String,
    val attemptId: String,
    val idempotencyKey: String,
    val recipeSnapshot: ProductionMeasurementRecipe,
    val site: MeasurementSiteKey,
    val metrics: List<MeasurementMetric>,
    val artifacts: List<ArtifactReference> = emptyList(),
    val equipmentIdentities: Map<String, String> = emptyMap(),
    val temperatureC: Double? = null,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val passed: Boolean,
    val failureMessage: String? = null
) {
    init {
        require(resultId.isNotBlank() && taskId.isNotBlank() && attemptId.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(metrics.isNotEmpty())
        require(temperatureC == null || temperatureC.isFinite())
        require(finishedAtEpochMs >= startedAtEpochMs)
        require(equipmentIdentities.keys.all(String::isNotBlank))
    }
}

@Serializable
data class CalibrationExpectedMetric(
    val metricName: String,
    val nominalValue: Double,
    val warningTolerance: Double,
    val failureTolerance: Double,
    val unit: String
) {
    init {
        require(metricName.isNotBlank() && unit.isNotBlank())
        require(nominalValue.isFinite())
        require(warningTolerance.isFinite() && warningTolerance >= 0.0)
        require(failureTolerance.isFinite() && failureTolerance >= warningTolerance)
    }
}

@Serializable
data class CalibrationReferenceSite(
    val site: MeasurementSiteKey,
    val expectedMetrics: List<CalibrationExpectedMetric>
) {
    init {
        require(expectedMetrics.isNotEmpty())
        require(expectedMetrics.map { it.metricName }.distinct().size == expectedMetrics.size)
    }
}

@Serializable
data class CalibrationWaferDefinition(
    val id: String,
    val serialNumber: String,
    val revision: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long? = null,
    val referenceSites: List<CalibrationReferenceSite>,
    val certificateId: String? = null,
    val approved: Boolean = false
) {
    init {
        require(id.isNotBlank() && serialNumber.isNotBlank() && revision.isNotBlank())
        require(validUntilEpochMs == null || validUntilEpochMs > validFromEpochMs)
        require(referenceSites.isNotEmpty())
    }
}

@Serializable
enum class CalibrationQualificationState {
    Running,
    Passed,
    Warning,
    Failed,
    Expired,
    Revoked
}

@Serializable
data class CalibrationMetricResult(
    val site: MeasurementSiteKey,
    val metricName: String,
    val expected: CalibrationExpectedMetric,
    val measuredValue: Double,
    val deviation: Double,
    val passed: Boolean,
    val warning: Boolean
) {
    init {
        require(metricName.isNotBlank())
        require(measuredValue.isFinite() && deviation.isFinite())
    }
}

@Serializable
data class CalibrationQualification(
    val id: String,
    val calibrationWaferSnapshot: CalibrationWaferDefinition,
    val recipeId: String,
    val recipeVersion: Int,
    val equipmentIdentities: Map<String, String>,
    val cameraCalibrationId: String?,
    val probeHeightProfileId: String?,
    val pivotProfileId: String?,
    val temperatureC: Double,
    val metricResults: List<CalibrationMetricResult>,
    val state: CalibrationQualificationState,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val executedBy: String,
    val approvedBy: String? = null
) {
    init {
        require(id.isNotBlank() && recipeId.isNotBlank())
        require(recipeVersion > 0)
        require(temperatureC.isFinite())
        require(metricResults.isNotEmpty())
        require(finishedAtEpochMs >= startedAtEpochMs)
        require(executedBy.isNotBlank())
        require(equipmentIdentities.keys.all(String::isNotBlank))
    }
}

@Serializable
enum class LotState {
    Draft,
    AwaitingApproval,
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Aborted,
    Archived
}

@Serializable
data class ProductionWafer(
    val waferId: String,
    val waferNumber: Int,
    val enabled: Boolean = true
) {
    init {
        require(waferId.isNotBlank())
        require(waferNumber > 0)
    }
}

@Serializable
data class ProductionLot(
    val id: String,
    val lotNumber: String,
    val productCode: String,
    val recipeId: String,
    val recipeVersion: Int,
    val wafers: List<ProductionWafer>,
    val priority: Int = 0,
    val state: LotState = LotState.Draft,
    val createdAtEpochMs: Long,
    val createdBy: String,
    val approvedBy: String? = null
) {
    init {
        require(id.isNotBlank() && lotNumber.isNotBlank() && productCode.isNotBlank())
        require(recipeId.isNotBlank() && recipeVersion > 0)
        require(wafers.isNotEmpty())
        require(wafers.map { it.waferId }.distinct().size == wafers.size)
        require(createdBy.isNotBlank())
    }
}

@Serializable
enum class ProductionTaskState {
    Pending,
    Reserved,
    Running,
    Passed,
    Failed,
    Skipped,
    RetryPending,
    Aborted
}

@Serializable
enum class ProductionCheckpointStage {
    Reserved,
    Positioned,
    Aligned,
    StimulusConfigured,
    AcquisitionCompleted,
    ResultPersisted,
    QualityEvaluated,
    Completed
}

@Serializable
data class ProductionTask(
    val id: String,
    val lotId: String,
    val waferId: String,
    val site: MeasurementSiteKey,
    val recipeId: String,
    val recipeVersion: Int,
    val priority: Int,
    val state: ProductionTaskState = ProductionTaskState.Pending,
    val attemptCount: Int = 0,
    val maximumAttempts: Int = 2,
    val idempotencyKey: String,
    val leaseOwner: String? = null,
    val leaseExpiresAtEpochMs: Long? = null,
    val lastError: String? = null
) {
    init {
        require(id.isNotBlank() && lotId.isNotBlank() && waferId.isNotBlank())
        require(recipeId.isNotBlank() && recipeVersion > 0)
        require(attemptCount >= 0 && maximumAttempts > 0)
        require(idempotencyKey.isNotBlank())
        require(leaseOwner == null || leaseOwner.isNotBlank())
    }
}

@Serializable
data class ProductionCheckpoint(
    val taskId: String,
    val attemptId: String,
    val stage: ProductionCheckpointStage,
    val updatedAtEpochMs: Long,
    val resultId: String? = null,
    val message: String
) {
    init {
        require(taskId.isNotBlank() && attemptId.isNotBlank())
        require(message.isNotBlank())
    }
}

@Serializable
data class ReservedProductionTask(
    val task: ProductionTask,
    val attemptId: String,
    val workerId: String,
    val reservedAtEpochMs: Long,
    val leaseExpiresAtEpochMs: Long
) {
    init {
        require(attemptId.isNotBlank() && workerId.isNotBlank())
        require(leaseExpiresAtEpochMs > reservedAtEpochMs)
    }
}

@Serializable
data class QualityObservation(
    val id: String,
    val lotId: String,
    val waferId: String,
    val site: MeasurementSiteKey,
    val metricName: String,
    val value: Double,
    val unit: String,
    val timestampEpochMs: Long,
    val recipeId: String,
    val recipeVersion: Int,
    val equipmentGroupId: String,
    val temperatureC: Double? = null
) {
    init {
        require(id.isNotBlank() && lotId.isNotBlank() && waferId.isNotBlank())
        require(metricName.isNotBlank() && unit.isNotBlank())
        require(value.isFinite())
        require(recipeId.isNotBlank() && recipeVersion > 0)
        require(equipmentGroupId.isNotBlank())
        require(temperatureC == null || temperatureC.isFinite())
    }
}

@Serializable
enum class SpcRuleType {
    OutsideThreeSigma,
    TwoOfThreeBeyondTwoSigma,
    FourOfFiveBeyondOneSigma,
    EightOnSameSide,
    SixIncreasingOrDecreasing
}

@Serializable
enum class SpcSeverity {
    Information,
    Warning,
    Critical
}

@Serializable
data class SpcViolation(
    val metricName: String,
    val rule: SpcRuleType,
    val observationIds: List<String>,
    val severity: SpcSeverity,
    val message: String
) {
    init {
        require(metricName.isNotBlank())
        require(observationIds.isNotEmpty())
        require(message.isNotBlank())
    }
}

@Serializable
data class ProcessCapability(
    val sampleCount: Int,
    val mean: Double,
    val standardDeviation: Double,
    val cp: Double?,
    val cpk: Double?,
    val pp: Double?,
    val ppk: Double?
) {
    init {
        require(sampleCount > 0)
        require(mean.isFinite() && standardDeviation.isFinite() && standardDeviation >= 0.0)
        require(listOf(cp, cpk, pp, ppk).all { it == null || it.isFinite() })
    }
}

@Serializable
enum class ProductionAnomalyType {
    FiberNotDetected,
    GratingNotDetected,
    FacetNotDetected,
    OpticalPowerTooLow,
    OpticalPowerUnstable,
    ChannelImbalance,
    PositionerTimeout,
    PositionLimitViolation,
    ProbeHeightInvalid,
    ZSensorSaturated,
    TemperatureUnstable,
    CalibrationExpired,
    InstrumentCommunicationError,
    ElectricalOpenCircuit,
    ElectricalShortCircuit,
    ExcessiveDarkCurrent,
    MeasurementNoise,
    ProcessShift,
    Unknown
}

@Serializable
data class AnomalyEvidence(
    val key: String,
    val value: String,
    val source: String
) {
    init {
        require(key.isNotBlank() && value.isNotBlank() && source.isNotBlank())
    }
}

@Serializable
enum class RecommendedAction {
    RetryMeasurement,
    LocalRealign,
    Recalibrate,
    InspectHardware,
    HoldWafer,
    HoldLot,
    EngineeringReview,
    Continue
}

@Serializable
data class AnomalyClassification(
    val primaryType: ProductionAnomalyType,
    val secondaryTypes: List<ProductionAnomalyType> = emptyList(),
    val confidence: Double,
    val evidence: List<AnomalyEvidence>,
    val recommendedAction: RecommendedAction,
    val classifierVersion: String
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(classifierVersion.isNotBlank())
    }
}

@Serializable
data class AnomalyCase(
    val id: String,
    val lotId: String,
    val taskId: String,
    val automaticClassification: AnomalyClassification,
    val reviewedClassification: AnomalyClassification? = null,
    val reviewReason: String? = null,
    val reviewedBy: String? = null,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null
) {
    init {
        require(id.isNotBlank() && lotId.isNotBlank() && taskId.isNotBlank())
        require(reviewReason == null || reviewReason.isNotBlank())
        require(reviewedBy == null || reviewedBy.isNotBlank())
    }
}

@Serializable
enum class ProductionRole {
    Operator,
    Engineer,
    QualityEngineer,
    Supervisor,
    Administrator,
    Auditor,
    ServiceEngineer
}

@Serializable
enum class ProductionPermission {
    LotCreate,
    LotApprove,
    LotStart,
    LotPause,
    LotAbort,
    TaskRetry,
    RecipeCreate,
    RecipeApprove,
    CalibrationExecute,
    CalibrationApprove,
    QualityRuleEdit,
    AnomalyOverride,
    UserManage,
    AuditRead,
    ManualMotion,
    LaserOutputControl,
    DataExport
}

@Serializable
data class ProductionActor(
    val id: String,
    val displayName: String,
    val roles: Set<ProductionRole>,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(roles.isNotEmpty())
    }
}

@Serializable
data class AuditEvent(
    val id: String,
    val timestampEpochMs: Long,
    val actorId: String,
    val actorRoles: Set<ProductionRole>,
    val action: String,
    val targetType: String,
    val targetId: String,
    val correlationId: String,
    val reason: String? = null,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val applicationVersion: String,
    val workstationId: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val previousHash: String? = null,
    val eventHash: String
) {
    init {
        require(id.isNotBlank() && actorId.isNotBlank())
        require(action.isNotBlank() && targetType.isNotBlank() && targetId.isNotBlank())
        require(correlationId.isNotBlank())
        require(applicationVersion.isNotBlank() && workstationId.isNotBlank())
        require(eventHash.isNotBlank())
    }
}
