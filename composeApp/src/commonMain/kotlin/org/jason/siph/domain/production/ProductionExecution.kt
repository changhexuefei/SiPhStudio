package org.jason.siph.domain.production

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ProductionLotPlanner {
    fun buildTasks(
        lot: ProductionLot,
        sitesByWafer: Map<String, List<MeasurementSiteKey>>,
        maximumAttempts: Int = 2
    ): List<ProductionTask> {
        require(maximumAttempts > 0)
        return lot.wafers.filter { it.enabled }.flatMap { wafer ->
            val sites = sitesByWafer[wafer.waferId].orEmpty()
            require(sites.isNotEmpty()) { "No production sites were supplied for wafer ${wafer.waferId}" }
            sites.distinctBy { it.stableId }.mapIndexed { index, site ->
                require(site.waferId == wafer.waferId) {
                    "Site wafer ${site.waferId} does not match production wafer ${wafer.waferId}"
                }
                val key = "${lot.id}:${wafer.waferId}:${site.stableId}:${lot.recipeId}:v${lot.recipeVersion}"
                ProductionTask(
                    id = "task-${lot.id}-${wafer.waferNumber}-${index + 1}",
                    lotId = lot.id,
                    waferId = wafer.waferId,
                    site = site,
                    recipeId = lot.recipeId,
                    recipeVersion = lot.recipeVersion,
                    priority = lot.priority,
                    maximumAttempts = maximumAttempts,
                    idempotencyKey = key
                )
            }
        }
    }
}

enum class ProductionWorkerStage {
    Idle,
    ReserveTask,
    ValidateCalibration,
    ExecuteMeasurement,
    PersistQuality,
    ClassifyAnomaly,
    CompleteTask,
    Failed,
    Stopped
}

data class ProductionWorkerState(
    val workerId: String,
    val stage: ProductionWorkerStage = ProductionWorkerStage.Idle,
    val running: Boolean = false,
    val currentTaskId: String? = null,
    val completedTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val message: String = "Production worker is idle",
    val errorMessage: String? = null
)

class SimulatedProductionMeasurementExecutor(
    private val repository: ProductionRepository,
    private val nowEpochMs: () -> Long,
    private val equipmentIdentities: Map<String, String> = mapOf(
        "laser" to "SiPhStudio Simulated Tunable Laser",
        "powerMeter" to "SiPhStudio Simulated Optical Power Meter",
        "electrical" to "SiPhStudio Simulated Electrical Analyzer",
        "prober" to "SiPhStudio Simulated Wafer Prober"
    ),
    private val faultTaskIds: Set<String> = emptySet()
) : ProductionMeasurementExecutor {
    private val stopRequested = MutableStateFlow(false)

    override suspend fun execute(
        reservation: ReservedProductionTask,
        recipe: ProductionMeasurementRecipe,
        checkpoint: ProductionCheckpoint?
    ): ProductionMeasurementResult {
        stopRequested.value = false
        require(recipe.approvalState == RecipeApprovalState.Approved) { "Recipe is not approved" }
        require(recipe.id == reservation.task.recipeId && recipe.version == reservation.task.recipeVersion)
        repository.findMeasurementResultByIdempotencyKey(reservation.task.idempotencyKey)?.let { return it }
        val started = nowEpochMs()

        suspend fun stage(stage: ProductionCheckpointStage, message: String) {
            ensureRunning()
            repository.saveCheckpoint(
                ProductionCheckpoint(
                    taskId = reservation.task.id,
                    attemptId = reservation.attemptId,
                    stage = stage,
                    updatedAtEpochMs = nowEpochMs(),
                    message = message
                )
            )
            delay(2L)
        }

        val resumeStage = checkpoint?.takeIf { it.attemptId == reservation.attemptId }?.stage
        if (resumeStage == null || resumeStage.ordinal < ProductionCheckpointStage.Positioned.ordinal) {
            stage(ProductionCheckpointStage.Positioned, "Simulated prober positioned at ${reservation.task.site.stableId}")
        }
        if (resumeStage == null || resumeStage.ordinal < ProductionCheckpointStage.Aligned.ordinal) {
            stage(ProductionCheckpointStage.Aligned, "Simulated optical alignment completed")
        }
        if (resumeStage == null || resumeStage.ordinal < ProductionCheckpointStage.StimulusConfigured.ordinal) {
            stage(ProductionCheckpointStage.StimulusConfigured, "Simulated optical and electrical stimulus configured")
        }
        if (reservation.task.id in faultTaskIds) {
            error("Injected simulated instrument communication timeout for ${reservation.task.id}")
        }
        stage(ProductionCheckpointStage.AcquisitionCompleted, "Simulated acquisition completed")
        val metrics = simulatedMetrics(reservation.task, recipe)
        val passed = metrics.all { metric ->
            val lowerPass = metric.lowerSpecificationLimit?.let { metric.value >= it } ?: true
            val upperPass = metric.upperSpecificationLimit?.let { metric.value <= it } ?: true
            lowerPass && upperPass
        }
        val result = ProductionMeasurementResult(
            resultId = "result-${reservation.attemptId}",
            taskId = reservation.task.id,
            attemptId = reservation.attemptId,
            idempotencyKey = reservation.task.idempotencyKey,
            recipeSnapshot = recipe,
            site = reservation.task.site,
            metrics = metrics,
            equipmentIdentities = equipmentIdentities,
            temperatureC = 25.0,
            startedAtEpochMs = started,
            finishedAtEpochMs = nowEpochMs(),
            passed = passed,
            failureMessage = if (passed) null else "One or more simulated production metrics failed specification"
        )
        repository.saveMeasurementResult(result)
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = reservation.task.id,
                attemptId = reservation.attemptId,
                stage = ProductionCheckpointStage.ResultPersisted,
                updatedAtEpochMs = nowEpochMs(),
                resultId = result.resultId,
                message = "Simulated production result persisted"
            )
        )
        return result
    }

    override suspend fun requestStop() {
        stopRequested.value = true
    }

    private fun simulatedMetrics(
        task: ProductionTask,
        recipe: ProductionMeasurementRecipe
    ): List<MeasurementMetric> {
        val variation = deterministicVariation(task.site.stableId)
        val base = when (recipe.measurementType) {
            ProductionMeasurementType.OpticalOptical -> listOf(
                MeasurementMetric("outputPowerDbm", -4.0 + variation, "dBm", -7.0, 0.0),
                MeasurementMetric("insertionLossDb", 1.3 + abs(variation) * 0.3, "dB", 0.0, 3.0)
            )
            ProductionMeasurementType.OpticalElectrical -> listOf(
                MeasurementMetric("inputOpticalPowerDbm", -3.0, "dBm", -5.0, 0.0),
                MeasurementMetric("photoCurrentA", 0.00078 + variation * 0.00002, "A", 0.00065, 0.0012),
                MeasurementMetric("darkCurrentA", 1.2e-9 + abs(variation) * 2e-10, "A", 0.0, 5e-9),
                MeasurementMetric("responsivityAperW", 0.83 + variation * 0.015, "A/W", 0.72, 0.95)
            )
            ProductionMeasurementType.OpticalElectricalOptical -> listOf(
                MeasurementMetric("inputOpticalPowerDbm", -3.0, "dBm", -5.0, 0.0),
                MeasurementMetric("outputOpticalPowerDbm", -7.0 + variation, "dBm", -10.0, -3.0),
                MeasurementMetric("insertionLossDb", 4.0 - variation, "dB", 0.0, 6.0),
                MeasurementMetric("extinctionRatioDb", 8.5 + variation * 0.4, "dB", 6.0, 15.0),
                MeasurementMetric("eyeHeight", 0.72 + variation * 0.03, "UI", 0.55, 1.0),
                MeasurementMetric("bitErrorRate", max(1e-12, 5e-10 + abs(variation) * 1e-10), "ratio", 0.0, 1e-8)
            )
        }
        if (recipe.fiberArrayId == null) return base
        return base + listOf(
            MeasurementMetric("fiberArrayMinPowerDbm", -6.0 + variation, "dBm", -9.0, 0.0),
            MeasurementMetric("fiberArrayImbalanceDb", 0.7 + abs(variation) * 0.4, "dB", 0.0, 2.0),
            MeasurementMetric("fiberArrayDetectedChannels", 8.0, "count", 8.0, 8.0)
        )
    }

    private fun deterministicVariation(value: String): Double {
        val accumulator = value.fold(17L) { current, character -> current * 31L + character.code }
        return ((abs(accumulator) % 2001L).toDouble() / 1000.0 - 1.0) * 0.35
    }

    private fun ensureRunning() {
        if (stopRequested.value) throw CancellationException("Production measurement stop requested")
    }
}

class DefaultProductionWorker(
    private val workerId: String,
    private val repository: ProductionRepository,
    private val scheduler: ProductionScheduler,
    private val calibrationGate: ProductionCalibrationGate,
    private val executor: ProductionMeasurementExecutor,
    private val anomalyClassifier: ProductionAnomalyClassifier,
    private val audit: ProductionAuditService,
    private val authorization: ProductionAuthorizationService,
    private val equipmentIdentities: () -> Map<String, String>,
    private val cameraCalibrationId: () -> String?,
    private val probeHeightProfileId: () -> String?,
    private val pivotProfileId: () -> String?,
    private val nowEpochMs: () -> Long,
    private val leaseDurationMs: Long = 60_000L
) {
    private val mutableState = MutableStateFlow(ProductionWorkerState(workerId = workerId))
    val state: StateFlow<ProductionWorkerState> = mutableState.asStateFlow()
    private val stopRequested = MutableStateFlow(false)

    suspend fun runNext(actor: ProductionActor): Boolean {
        authorization.requirePermission(actor, ProductionPermission.LotStart)
        stopRequested.value = false
        setStage(ProductionWorkerStage.ReserveTask, "Reserving next production task")
        val reservation = scheduler.reserveNext(workerId, leaseDurationMs) ?: run {
            mutableState.update { it.copy(stage = ProductionWorkerStage.Idle, running = false, message = "No production task is queued") }
            return false
        }
        mutableState.update { it.copy(running = true, currentTaskId = reservation.task.id) }
        val correlationId = "production-${reservation.attemptId}"
        audit.record(
            actor = actor,
            action = "PRODUCTION_TASK_START",
            targetType = "ProductionTask",
            targetId = reservation.task.id,
            correlationId = correlationId,
            success = true
        )

        try {
            ensureRunning()
            val recipe = repository.findRecipe(reservation.task.recipeId, reservation.task.recipeVersion)
                ?: error("Production recipe was not found")
            setStage(ProductionWorkerStage.ValidateCalibration, "Evaluating production calibration gate")
            val calibration = calibrationGate.evaluate(
                ProductionCalibrationContext(
                    recipe = recipe,
                    equipmentIdentities = equipmentIdentities(),
                    cameraCalibrationId = cameraCalibrationId(),
                    probeHeightProfileId = probeHeightProfileId(),
                    pivotProfileId = pivotProfileId(),
                    nowEpochMs = nowEpochMs()
                )
            )
            check(calibration.allowed) { calibration.message }
            ensureRunning()

            val existing = repository.findMeasurementResultByIdempotencyKey(reservation.task.idempotencyKey)
            val result = if (existing != null) {
                existing
            } else {
                setStage(ProductionWorkerStage.ExecuteMeasurement, "Executing ${recipe.measurementType} recipe")
                executor.execute(
                    reservation = reservation,
                    recipe = recipe,
                    checkpoint = repository.findCheckpoint(reservation.task.id)
                )
            }
            setStage(ProductionWorkerStage.PersistQuality, "Persisting production quality observations")
            persistQuality(reservation.task, result)

            if (!result.passed) {
                setStage(ProductionWorkerStage.ClassifyAnomaly, "Classifying failed production result")
                val classification = anomalyClassifier.classify(
                    ProductionAnomalyContext(
                        lotId = reservation.task.lotId,
                        taskId = reservation.task.id,
                        errorMessage = result.failureMessage,
                        metrics = result.metrics,
                        calibrationDecision = calibration
                    )
                )
                repository.saveAnomalyCase(
                    AnomalyCase(
                        id = "anomaly-${reservation.attemptId}",
                        lotId = reservation.task.lotId,
                        taskId = reservation.task.id,
                        automaticClassification = classification,
                        openedAtEpochMs = nowEpochMs()
                    )
                )
            }
            setStage(ProductionWorkerStage.CompleteTask, "Completing production task")
            scheduler.complete(reservation, result)
            audit.record(
                actor = actor,
                action = "PRODUCTION_TASK_COMPLETE",
                targetType = "ProductionTask",
                targetId = reservation.task.id,
                correlationId = correlationId,
                afterJson = "resultId=${result.resultId};passed=${result.passed}",
                success = true
            )
            mutableState.update {
                it.copy(
                    stage = ProductionWorkerStage.Idle,
                    running = false,
                    currentTaskId = null,
                    completedTaskCount = it.completedTaskCount + 1,
                    message = "Production task ${reservation.task.id} completed",
                    errorMessage = null
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { executor.requestStop() }
                runCatching { scheduler.fail(reservation, cancelled, retryable = true) }
                runCatching {
                    audit.record(
                        actor = actor,
                        action = "PRODUCTION_TASK_STOP",
                        targetType = "ProductionTask",
                        targetId = reservation.task.id,
                        correlationId = correlationId,
                        success = false,
                        errorMessage = cancelled.message
                    )
                }
            }
            mutableState.update {
                it.copy(
                    stage = ProductionWorkerStage.Stopped,
                    running = false,
                    currentTaskId = null,
                    message = cancelled.message ?: "Production worker stopped"
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            val classification = anomalyClassifier.classify(
                ProductionAnomalyContext(
                    lotId = reservation.task.lotId,
                    taskId = reservation.task.id,
                    errorMessage = error.message,
                    metrics = emptyList()
                )
            )
            withContext(NonCancellable) {
                repository.saveAnomalyCase(
                    AnomalyCase(
                        id = "anomaly-${reservation.attemptId}",
                        lotId = reservation.task.lotId,
                        taskId = reservation.task.id,
                        automaticClassification = classification,
                        openedAtEpochMs = nowEpochMs()
                    )
                )
                scheduler.fail(
                    reservation = reservation,
                    error = error,
                    retryable = classification.recommendedAction == RecommendedAction.RetryMeasurement ||
                        classification.primaryType == ProductionAnomalyType.InstrumentCommunicationError
                )
                audit.record(
                    actor = actor,
                    action = "PRODUCTION_TASK_FAIL",
                    targetType = "ProductionTask",
                    targetId = reservation.task.id,
                    correlationId = correlationId,
                    success = false,
                    errorMessage = error.message
                )
            }
            mutableState.update {
                it.copy(
                    stage = ProductionWorkerStage.Failed,
                    running = false,
                    currentTaskId = null,
                    failedTaskCount = it.failedTaskCount + 1,
                    message = "Production task failed",
                    errorMessage = error.message ?: error::class.simpleName
                )
            }
            return true
        }
    }

    suspend fun runUntilEmpty(actor: ProductionActor, maximumTasks: Int = Int.MAX_VALUE): Int {
        require(maximumTasks > 0)
        var count = 0
        while (count < maximumTasks && !stopRequested.value) {
            if (!runNext(actor)) break
            count++
        }
        return count
    }

    suspend fun requestStop() {
        stopRequested.value = true
        executor.requestStop()
        mutableState.update { it.copy(message = "Production stop requested") }
    }

    private suspend fun persistQuality(task: ProductionTask, result: ProductionMeasurementResult) {
        result.metrics.forEachIndexed { index, metric ->
            repository.saveQualityObservation(
                QualityObservation(
                    id = "quality-${result.resultId}-$index",
                    lotId = task.lotId,
                    waferId = task.waferId,
                    site = task.site,
                    metricName = metric.name,
                    value = metric.value,
                    unit = metric.unit,
                    timestampEpochMs = result.finishedAtEpochMs,
                    recipeId = result.recipeSnapshot.id,
                    recipeVersion = result.recipeSnapshot.version,
                    equipmentGroupId = result.equipmentIdentities.values.sorted().joinToString("|"),
                    temperatureC = result.temperatureC
                )
            )
        }
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = task.id,
                attemptId = result.attemptId,
                stage = ProductionCheckpointStage.QualityEvaluated,
                updatedAtEpochMs = nowEpochMs(),
                resultId = result.resultId,
                message = "Production quality observations persisted"
            )
        )
    }

    private fun ensureRunning() {
        if (stopRequested.value) throw CancellationException("Production stop requested")
    }

    private fun setStage(stage: ProductionWorkerStage, message: String) {
        mutableState.update { it.copy(stage = stage, running = true, message = message, errorMessage = null) }
    }
}
