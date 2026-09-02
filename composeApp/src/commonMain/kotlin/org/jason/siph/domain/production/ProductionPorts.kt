package org.jason.siph.domain.production

interface ProductionRepository {
    suspend fun saveFiberArray(definition: FiberArrayDefinition)
    suspend fun findFiberArray(id: String): FiberArrayDefinition?
    suspend fun listFiberArrays(): List<FiberArrayDefinition>

    suspend fun saveRecipe(recipe: ProductionMeasurementRecipe)
    suspend fun findRecipe(id: String, version: Int): ProductionMeasurementRecipe?
    suspend fun listRecipes(): List<ProductionMeasurementRecipe>

    suspend fun saveCalibrationWafer(definition: CalibrationWaferDefinition)
    suspend fun findCalibrationWafer(id: String): CalibrationWaferDefinition?
    suspend fun listCalibrationWafers(): List<CalibrationWaferDefinition>

    suspend fun saveCalibrationQualification(qualification: CalibrationQualification)
    suspend fun findCalibrationQualification(id: String): CalibrationQualification?
    suspend fun listCalibrationQualifications(): List<CalibrationQualification>

    suspend fun saveLot(lot: ProductionLot)
    suspend fun findLot(id: String): ProductionLot?
    suspend fun listLots(): List<ProductionLot>

    suspend fun saveTask(task: ProductionTask)
    suspend fun findTask(id: String): ProductionTask?
    suspend fun listTasks(lotId: String? = null): List<ProductionTask>

    suspend fun saveCheckpoint(checkpoint: ProductionCheckpoint)
    suspend fun findCheckpoint(taskId: String): ProductionCheckpoint?
    suspend fun deleteCheckpoint(taskId: String)

    suspend fun saveMeasurementResult(result: ProductionMeasurementResult)
    suspend fun findMeasurementResultByIdempotencyKey(key: String): ProductionMeasurementResult?
    suspend fun listMeasurementResults(lotId: String? = null): List<ProductionMeasurementResult>

    suspend fun saveQualityObservation(observation: QualityObservation)
    suspend fun listQualityObservations(metricName: String? = null): List<QualityObservation>

    suspend fun saveAnomalyCase(case: AnomalyCase)
    suspend fun findAnomalyCase(id: String): AnomalyCase?
    suspend fun listAnomalyCases(lotId: String? = null): List<AnomalyCase>

    suspend fun appendAuditEvent(event: AuditEvent)
    suspend fun latestAuditEvent(): AuditEvent?
    suspend fun listAuditEvents(limit: Int = 500): List<AuditEvent>
}

interface ProductionScheduler {
    suspend fun enqueueLot(lot: ProductionLot, tasks: List<ProductionTask>)
    suspend fun reserveNext(workerId: String, leaseDurationMs: Long): ReservedProductionTask?
    suspend fun renewLease(reservation: ReservedProductionTask, leaseDurationMs: Long): ReservedProductionTask
    suspend fun complete(reservation: ReservedProductionTask, result: ProductionMeasurementResult)
    suspend fun fail(reservation: ReservedProductionTask, error: Throwable, retryable: Boolean)
    suspend fun releaseExpiredLeases(nowEpochMs: Long): Int
}

interface ProductionMeasurementExecutor {
    suspend fun execute(
        reservation: ReservedProductionTask,
        recipe: ProductionMeasurementRecipe,
        checkpoint: ProductionCheckpoint?
    ): ProductionMeasurementResult

    suspend fun requestStop()
}

data class ProductionCalibrationContext(
    val recipe: ProductionMeasurementRecipe,
    val equipmentIdentities: Map<String, String>,
    val cameraCalibrationId: String?,
    val probeHeightProfileId: String?,
    val pivotProfileId: String?,
    val nowEpochMs: Long
)

enum class ProductionCalibrationDecisionType {
    Allowed,
    Warning,
    CalibrationMissing,
    CalibrationExpired,
    CalibrationFailed,
    EquipmentChanged,
    RecipeVersionChanged,
    ReferenceDriftExceeded
}

data class ProductionCalibrationDecision(
    val type: ProductionCalibrationDecisionType,
    val qualificationId: String? = null,
    val message: String
) {
    val allowed: Boolean
        get() = type == ProductionCalibrationDecisionType.Allowed ||
            type == ProductionCalibrationDecisionType.Warning
}

interface ProductionCalibrationGate {
    suspend fun evaluate(context: ProductionCalibrationContext): ProductionCalibrationDecision
}

interface QualitySpcEngine {
    fun analyze(
        metricName: String,
        observations: List<QualityObservation>,
        lowerSpecificationLimit: Double? = null,
        upperSpecificationLimit: Double? = null
    ): SpcAnalysisResult
}

data class SpcAnalysisResult(
    val metricName: String,
    val centerLine: Double,
    val sigma: Double,
    val upperControlLimit: Double,
    val lowerControlLimit: Double,
    val movingRanges: List<Double>,
    val violations: List<SpcViolation>,
    val capability: ProcessCapability?
)

data class ProductionAnomalyContext(
    val lotId: String,
    val taskId: String,
    val errorMessage: String?,
    val metrics: List<MeasurementMetric>,
    val fiberDetected: Boolean? = null,
    val targetDetected: Boolean? = null,
    val targetKind: String? = null,
    val zSensorValid: Boolean? = null,
    val zSensorSaturated: Boolean? = null,
    val temperatureStable: Boolean? = null,
    val calibrationDecision: ProductionCalibrationDecision? = null,
    val spcViolations: List<SpcViolation> = emptyList()
)

interface ProductionAnomalyClassifier {
    fun classify(context: ProductionAnomalyContext): AnomalyClassification
}

interface ProductionAuthorizationService {
    fun permissions(actor: ProductionActor): Set<ProductionPermission>
    fun requirePermission(actor: ProductionActor, permission: ProductionPermission)
}

interface AuditHasher {
    fun hash(canonicalValue: String): String
}

interface ProductionAuditService {
    suspend fun record(
        actor: ProductionActor,
        action: String,
        targetType: String,
        targetId: String,
        correlationId: String,
        reason: String? = null,
        beforeJson: String? = null,
        afterJson: String? = null,
        success: Boolean,
        errorMessage: String? = null
    ): AuditEvent
}
