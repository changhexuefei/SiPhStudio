package org.jason.siph.domain.production

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class InMemoryProductionRepository : ProductionRepository {
    private val mutex = Mutex()
    private val fiberArrays = linkedMapOf<String, FiberArrayDefinition>()
    private val recipes = linkedMapOf<String, ProductionMeasurementRecipe>()
    private val calibrationWafers = linkedMapOf<String, CalibrationWaferDefinition>()
    private val qualifications = linkedMapOf<String, CalibrationQualification>()
    private val lots = linkedMapOf<String, ProductionLot>()
    private val tasks = linkedMapOf<String, ProductionTask>()
    private val checkpoints = linkedMapOf<String, ProductionCheckpoint>()
    private val results = linkedMapOf<String, ProductionMeasurementResult>()
    private val quality = linkedMapOf<String, QualityObservation>()
    private val anomalies = linkedMapOf<String, AnomalyCase>()
    private val audits = mutableListOf<AuditEvent>()

    override suspend fun saveFiberArray(definition: FiberArrayDefinition) {
        mutex.withLock { fiberArrays[definition.id] = definition }
    }

    override suspend fun findFiberArray(id: String): FiberArrayDefinition? = mutex.withLock { fiberArrays[id] }

    override suspend fun listFiberArrays(): List<FiberArrayDefinition> = mutex.withLock {
        fiberArrays.values.sortedBy { it.name }
    }

    override suspend fun saveRecipe(recipe: ProductionMeasurementRecipe) {
        mutex.withLock { recipes[recipe.stableVersionId] = recipe }
    }

    override suspend fun findRecipe(id: String, version: Int): ProductionMeasurementRecipe? = mutex.withLock {
        recipes["$id-v$version"]
    }

    override suspend fun listRecipes(): List<ProductionMeasurementRecipe> = mutex.withLock {
        recipes.values.sortedWith(compareBy<ProductionMeasurementRecipe> { it.id }.thenByDescending { it.version })
    }

    override suspend fun saveCalibrationWafer(definition: CalibrationWaferDefinition) {
        mutex.withLock { calibrationWafers[definition.id] = definition }
    }

    override suspend fun findCalibrationWafer(id: String): CalibrationWaferDefinition? = mutex.withLock {
        calibrationWafers[id]
    }

    override suspend fun listCalibrationWafers(): List<CalibrationWaferDefinition> = mutex.withLock {
        calibrationWafers.values.sortedBy { it.serialNumber }
    }

    override suspend fun saveCalibrationQualification(qualification: CalibrationQualification) {
        mutex.withLock { qualifications[qualification.id] = qualification }
    }

    override suspend fun findCalibrationQualification(id: String): CalibrationQualification? = mutex.withLock {
        qualifications[id]
    }

    override suspend fun listCalibrationQualifications(): List<CalibrationQualification> = mutex.withLock {
        qualifications.values.sortedByDescending { it.finishedAtEpochMs }
    }

    override suspend fun saveLot(lot: ProductionLot) {
        mutex.withLock { lots[lot.id] = lot }
    }

    override suspend fun findLot(id: String): ProductionLot? = mutex.withLock { lots[id] }

    override suspend fun listLots(): List<ProductionLot> = mutex.withLock {
        lots.values.sortedWith(compareByDescending<ProductionLot> { it.priority }.thenBy { it.createdAtEpochMs })
    }

    override suspend fun saveTask(task: ProductionTask) {
        mutex.withLock { tasks[task.id] = task }
    }

    override suspend fun findTask(id: String): ProductionTask? = mutex.withLock { tasks[id] }

    override suspend fun listTasks(lotId: String?): List<ProductionTask> = mutex.withLock {
        tasks.values.filter { lotId == null || it.lotId == lotId }
            .sortedWith(compareByDescending<ProductionTask> { it.priority }.thenBy { it.id })
    }

    override suspend fun saveCheckpoint(checkpoint: ProductionCheckpoint) {
        mutex.withLock { checkpoints[checkpoint.taskId] = checkpoint }
    }

    override suspend fun findCheckpoint(taskId: String): ProductionCheckpoint? = mutex.withLock {
        checkpoints[taskId]
    }

    override suspend fun deleteCheckpoint(taskId: String) {
        mutex.withLock { checkpoints.remove(taskId) }
    }

    override suspend fun saveMeasurementResult(result: ProductionMeasurementResult) {
        mutex.withLock {
            val duplicate = results.values.firstOrNull { it.idempotencyKey == result.idempotencyKey }
            require(duplicate == null || duplicate.resultId == result.resultId) {
                "A production result already exists for idempotencyKey=${result.idempotencyKey}"
            }
            results[result.resultId] = result
        }
    }

    override suspend fun findMeasurementResultByIdempotencyKey(key: String): ProductionMeasurementResult? =
        mutex.withLock { results.values.firstOrNull { it.idempotencyKey == key } }

    override suspend fun listMeasurementResults(lotId: String?): List<ProductionMeasurementResult> = mutex.withLock {
        val taskIds = if (lotId == null) null else tasks.values.filter { it.lotId == lotId }.map { it.id }.toSet()
        results.values.filter { taskIds == null || it.taskId in taskIds }
            .sortedByDescending { it.finishedAtEpochMs }
    }

    override suspend fun saveQualityObservation(observation: QualityObservation) {
        mutex.withLock { quality[observation.id] = observation }
    }

    override suspend fun listQualityObservations(metricName: String?): List<QualityObservation> = mutex.withLock {
        quality.values.filter { metricName == null || it.metricName == metricName }
            .sortedBy { it.timestampEpochMs }
    }

    override suspend fun saveAnomalyCase(case: AnomalyCase) {
        mutex.withLock { anomalies[case.id] = case }
    }

    override suspend fun findAnomalyCase(id: String): AnomalyCase? = mutex.withLock { anomalies[id] }

    override suspend fun listAnomalyCases(lotId: String?): List<AnomalyCase> = mutex.withLock {
        anomalies.values.filter { lotId == null || it.lotId == lotId }.sortedByDescending { it.openedAtEpochMs }
    }

    override suspend fun appendAuditEvent(event: AuditEvent) {
        mutex.withLock {
            val expectedPrevious = audits.lastOrNull()?.eventHash
            require(event.previousHash == expectedPrevious) {
                "Audit hash chain mismatch: expected previousHash=$expectedPrevious, actual=${event.previousHash}"
            }
            audits += event
        }
    }

    override suspend fun latestAuditEvent(): AuditEvent? = mutex.withLock { audits.lastOrNull() }

    override suspend fun listAuditEvents(limit: Int): List<AuditEvent> {
        require(limit > 0)
        return mutex.withLock { audits.takeLast(limit).reversed() }
    }
}

class FiberArrayAlignmentService {
    fun evaluate(
        definition: FiberArrayDefinition,
        observations: List<FiberChannelObservation>,
        minimumPowerDbm: Double,
        maximumImbalanceDb: Double
    ): FiberArrayAlignmentResult {
        require(definition.verified) { "Fiber array definition is not verified: ${definition.id}" }
        require(minimumPowerDbm.isFinite())
        require(maximumImbalanceDb.isFinite() && maximumImbalanceDb >= 0.0)

        val enabled = definition.channels.filter { it.enabled }
        val observedByIndex = observations.associateBy { it.channelIndex }
        val validPairs = enabled.mapNotNull { channel ->
            observedByIndex[channel.index]?.takeIf { it.detected }?.let { channel to it }
        }
        require(validPairs.size >= 2) { "At least two detected array channels are required" }

        val first = validPairs.minBy { it.first.index }
        val last = validPairs.maxBy { it.first.index }
        val expectedDx = last.first.offsetXUm - first.first.offsetXUm
        val expectedDy = last.first.offsetYUm - first.first.offsetYUm
        val observedDx = last.second.measuredXUm - first.second.measuredXUm
        val observedDy = last.second.measuredYUm - first.second.measuredYUm
        val expectedLength = sqrt(expectedDx * expectedDx + expectedDy * expectedDy)
        val observedLength = sqrt(observedDx * observedDx + observedDy * observedDy)
        require(expectedLength > 1e-9 && observedLength > 1e-9) { "Fiber array span is degenerate" }

        val expectedAngle = atan2(expectedDy, expectedDx)
        val observedAngle = atan2(observedDy, observedDx)
        val rotation = normalizeDegrees((observedAngle - expectedAngle) * 180.0 / PI)
        val scale = observedLength / expectedLength
        val correctedPitch = definition.nominalPitchUm * scale
        val radians = rotation * PI / 180.0
        val cosine = cos(radians)
        val sine = sin(radians)
        val referenceDefinition = definition.channels.first { it.index == definition.referenceChannel }
        val referenceObservation = observedByIndex[definition.referenceChannel]
            ?: error("Reference channel ${definition.referenceChannel} was not observed")
        require(referenceObservation.detected) { "Reference channel was not detected" }
        val rotatedReferenceX = scale * (
            referenceDefinition.offsetXUm * cosine - referenceDefinition.offsetYUm * sine
            )
        val rotatedReferenceY = scale * (
            referenceDefinition.offsetXUm * sine + referenceDefinition.offsetYUm * cosine
            )
        val translationX = referenceObservation.measuredXUm - rotatedReferenceX
        val translationY = referenceObservation.measuredYUm - rotatedReferenceY
        val powers = validPairs.map { it.second.powerDbm }
        val minimumPower = powers.minOrNull() ?: error("No channel power was measured")
        val imbalance = (powers.maxOrNull() ?: minimumPower) - minimumPower
        val allEnabledDetected = validPairs.size == enabled.size
        val passed = allEnabledDetected && minimumPower >= minimumPowerDbm && imbalance <= maximumImbalanceDb

        return FiberArrayAlignmentResult(
            arrayId = definition.id,
            translationXUm = translationX,
            translationYUm = translationY,
            rotationErrorDeg = rotation,
            correctedPitchUm = correctedPitch,
            minimumPowerDbm = minimumPower,
            maximumChannelImbalanceDb = imbalance,
            channelObservations = observations,
            passed = passed,
            message = when {
                !allEnabledDetected -> "One or more enabled fiber-array channels were not detected"
                minimumPower < minimumPowerDbm -> "Minimum channel power is below the production threshold"
                imbalance > maximumImbalanceDb -> "Fiber-array channel imbalance exceeds the threshold"
                else -> "Fiber-array geometry and channel balance verified"
            }
        )
    }

    private fun normalizeDegrees(value: Double): Double {
        var result = value
        while (result > 180.0) result -= 360.0
        while (result < -180.0) result += 360.0
        return result
    }
}

class CalibrationQualificationService {
    fun qualify(
        id: String,
        wafer: CalibrationWaferDefinition,
        recipe: ProductionMeasurementRecipe,
        measured: Map<String, Double>,
        equipmentIdentities: Map<String, String>,
        temperatureC: Double,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        executedBy: String,
        cameraCalibrationId: String? = null,
        probeHeightProfileId: String? = null,
        pivotProfileId: String? = null
    ): CalibrationQualification {
        require(wafer.approved) { "Calibration wafer is not approved" }
        require(recipe.approvalState == RecipeApprovalState.Approved) { "Production recipe is not approved" }
        val metricResults = wafer.referenceSites.flatMap { reference ->
            reference.expectedMetrics.map { expected ->
                val key = "${reference.site.stableId}:${expected.metricName}"
                val value = measured[key] ?: error("Missing calibration measurement: $key")
                val deviation = abs(value - expected.nominalValue)
                CalibrationMetricResult(
                    site = reference.site,
                    metricName = expected.metricName,
                    expected = expected,
                    measuredValue = value,
                    deviation = deviation,
                    passed = deviation <= expected.failureTolerance,
                    warning = deviation > expected.warningTolerance && deviation <= expected.failureTolerance
                )
            }
        }
        val state = when {
            metricResults.any { !it.passed } -> CalibrationQualificationState.Failed
            metricResults.any { it.warning } -> CalibrationQualificationState.Warning
            else -> CalibrationQualificationState.Passed
        }
        return CalibrationQualification(
            id = id,
            calibrationWaferSnapshot = wafer,
            recipeId = recipe.id,
            recipeVersion = recipe.version,
            equipmentIdentities = equipmentIdentities,
            cameraCalibrationId = cameraCalibrationId,
            probeHeightProfileId = probeHeightProfileId,
            pivotProfileId = pivotProfileId,
            temperatureC = temperatureC,
            metricResults = metricResults,
            state = state,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            executedBy = executedBy
        )
    }
}

class DefaultProductionCalibrationGate(
    private val repository: ProductionRepository
) : ProductionCalibrationGate {
    override suspend fun evaluate(context: ProductionCalibrationContext): ProductionCalibrationDecision {
        val candidates = repository.listCalibrationQualifications()
            .filter { it.recipeId == context.recipe.id }
            .sortedByDescending { it.finishedAtEpochMs }
        val latest = candidates.firstOrNull()
            ?: return decision(ProductionCalibrationDecisionType.CalibrationMissing, "No calibration qualification exists")
        val wafer = latest.calibrationWaferSnapshot
        if (wafer.validUntilEpochMs != null && context.nowEpochMs > wafer.validUntilEpochMs) {
            return decision(
                ProductionCalibrationDecisionType.CalibrationExpired,
                "Calibration wafer qualification is outside its validity window",
                latest.id
            )
        }
        if (latest.recipeVersion != context.recipe.version) {
            return decision(
                ProductionCalibrationDecisionType.RecipeVersionChanged,
                "Calibration was qualified for recipe v${latest.recipeVersion}, current is v${context.recipe.version}",
                latest.id
            )
        }
        if (latest.equipmentIdentities != context.equipmentIdentities) {
            return decision(
                ProductionCalibrationDecisionType.EquipmentChanged,
                "Production equipment identity differs from the calibration qualification",
                latest.id
            )
        }
        if (
            latest.cameraCalibrationId != context.cameraCalibrationId ||
            latest.probeHeightProfileId != context.probeHeightProfileId ||
            latest.pivotProfileId != context.pivotProfileId
        ) {
            return decision(
                ProductionCalibrationDecisionType.ReferenceDriftExceeded,
                "Camera, probe-height or pivot calibration identity has changed",
                latest.id
            )
        }
        return when (latest.state) {
            CalibrationQualificationState.Passed -> decision(
                ProductionCalibrationDecisionType.Allowed,
                "Production calibration gate passed",
                latest.id
            )
            CalibrationQualificationState.Warning -> decision(
                ProductionCalibrationDecisionType.Warning,
                "Production calibration passed with warning tolerances",
                latest.id
            )
            CalibrationQualificationState.Expired -> decision(
                ProductionCalibrationDecisionType.CalibrationExpired,
                "Calibration qualification is expired",
                latest.id
            )
            else -> decision(
                ProductionCalibrationDecisionType.CalibrationFailed,
                "Calibration qualification state is ${latest.state}",
                latest.id
            )
        }
    }

    private fun decision(
        type: ProductionCalibrationDecisionType,
        message: String,
        id: String? = null
    ) = ProductionCalibrationDecision(type = type, qualificationId = id, message = message)
}

class DefaultProductionScheduler(
    private val repository: ProductionRepository,
    private val nowEpochMs: () -> Long
) : ProductionScheduler {
    private val mutex = Mutex()

    override suspend fun enqueueLot(lot: ProductionLot, tasks: List<ProductionTask>) = mutex.withLock {
        require(lot.state == LotState.Queued || lot.state == LotState.Draft)
        require(tasks.isNotEmpty())
        require(tasks.all { it.lotId == lot.id })
        require(tasks.map { it.idempotencyKey }.distinct().size == tasks.size)
        repository.saveLot(lot.copy(state = LotState.Queued))
        tasks.forEach { repository.saveTask(it.copy(state = ProductionTaskState.Pending)) }
    }

    override suspend fun reserveNext(workerId: String, leaseDurationMs: Long): ReservedProductionTask? = mutex.withLock {
        require(workerId.isNotBlank())
        require(leaseDurationMs > 0L)
        releaseExpiredLeasesLocked(nowEpochMs())
        val task = repository.listTasks()
            .filter { it.state == ProductionTaskState.Pending || it.state == ProductionTaskState.RetryPending }
            .sortedWith(compareByDescending<ProductionTask> { it.priority }.thenBy { it.id })
            .firstOrNull() ?: return@withLock null
        val now = nowEpochMs()
        val attempt = task.attemptCount + 1
        val attemptId = "${task.id}-attempt-$attempt"
        val reservedTask = task.copy(
            state = ProductionTaskState.Reserved,
            attemptCount = attempt,
            leaseOwner = workerId,
            leaseExpiresAtEpochMs = now + leaseDurationMs,
            lastError = null
        )
        repository.saveTask(reservedTask)
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = task.id,
                attemptId = attemptId,
                stage = ProductionCheckpointStage.Reserved,
                updatedAtEpochMs = now,
                message = "Task reserved by $workerId"
            )
        )
        updateLotRunning(task.lotId)
        ReservedProductionTask(
            task = reservedTask,
            attemptId = attemptId,
            workerId = workerId,
            reservedAtEpochMs = now,
            leaseExpiresAtEpochMs = now + leaseDurationMs
        )
    }

    override suspend fun renewLease(
        reservation: ReservedProductionTask,
        leaseDurationMs: Long
    ): ReservedProductionTask = mutex.withLock {
        require(leaseDurationMs > 0L)
        val current = repository.findTask(reservation.task.id) ?: error("Task not found")
        require(current.leaseOwner == reservation.workerId) { "Task lease is owned by another worker" }
        require(current.state == ProductionTaskState.Reserved || current.state == ProductionTaskState.Running)
        val now = nowEpochMs()
        val updated = current.copy(leaseExpiresAtEpochMs = now + leaseDurationMs)
        repository.saveTask(updated)
        reservation.copy(task = updated, leaseExpiresAtEpochMs = now + leaseDurationMs)
    }

    override suspend fun complete(
        reservation: ReservedProductionTask,
        result: ProductionMeasurementResult
    ) = mutex.withLock {
        val current = requireOwned(reservation)
        val existing = repository.findMeasurementResultByIdempotencyKey(current.idempotencyKey)
        if (existing == null) repository.saveMeasurementResult(result)
        repository.saveTask(
            current.copy(
                state = if (result.passed) ProductionTaskState.Passed else ProductionTaskState.Failed,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = result.failureMessage
            )
        )
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = current.id,
                attemptId = reservation.attemptId,
                stage = ProductionCheckpointStage.Completed,
                updatedAtEpochMs = nowEpochMs(),
                resultId = existing?.resultId ?: result.resultId,
                message = "Production result persisted and task completed"
            )
        )
        updateLotTerminalState(current.lotId)
    }

    override suspend fun fail(
        reservation: ReservedProductionTask,
        error: Throwable,
        retryable: Boolean
    ) = mutex.withLock {
        val current = requireOwned(reservation)
        val retry = retryable && current.attemptCount < current.maximumAttempts
        repository.saveTask(
            current.copy(
                state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = error.message ?: error::class.simpleName
            )
        )
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = current.id,
                attemptId = reservation.attemptId,
                stage = repository.findCheckpoint(current.id)?.stage ?: ProductionCheckpointStage.Reserved,
                updatedAtEpochMs = nowEpochMs(),
                message = if (retry) "Task failed and is queued for retry" else "Task failed permanently"
            )
        )
        updateLotTerminalState(current.lotId)
    }

    override suspend fun releaseExpiredLeases(nowEpochMs: Long): Int = mutex.withLock {
        releaseExpiredLeasesLocked(nowEpochMs)
    }

    private suspend fun releaseExpiredLeasesLocked(now: Long): Int {
        val expired = repository.listTasks().filter {
            it.state in setOf(ProductionTaskState.Reserved, ProductionTaskState.Running) &&
                it.leaseExpiresAtEpochMs != null && it.leaseExpiresAtEpochMs <= now
        }
        expired.forEach { task ->
            repository.saveTask(
                task.copy(
                    state = if (task.attemptCount < task.maximumAttempts) {
                        ProductionTaskState.RetryPending
                    } else {
                        ProductionTaskState.Failed
                    },
                    leaseOwner = null,
                    leaseExpiresAtEpochMs = null,
                    lastError = "Worker lease expired"
                )
            )
        }
        return expired.size
    }

    private suspend fun requireOwned(reservation: ReservedProductionTask): ProductionTask {
        val current = repository.findTask(reservation.task.id) ?: error("Task not found")
        require(current.leaseOwner == reservation.workerId) { "Task lease is not owned by ${reservation.workerId}" }
        return current
    }

    private suspend fun updateLotRunning(lotId: String) {
        val lot = repository.findLot(lotId) ?: return
        if (lot.state == LotState.Queued) repository.saveLot(lot.copy(state = LotState.Running))
    }

    private suspend fun updateLotTerminalState(lotId: String) {
        val lot = repository.findLot(lotId) ?: return
        val tasks = repository.listTasks(lotId)
        if (tasks.isEmpty()) return
        val terminal = tasks.all {
            it.state in setOf(
                ProductionTaskState.Passed,
                ProductionTaskState.Failed,
                ProductionTaskState.Skipped,
                ProductionTaskState.Aborted
            )
        }
        if (!terminal) return
        val finalState = if (tasks.any { it.state == ProductionTaskState.Failed }) {
            LotState.Failed
        } else {
            LotState.Completed
        }
        repository.saveLot(lot.copy(state = finalState))
    }
}

class DefaultQualitySpcEngine : QualitySpcEngine {
    override fun analyze(
        metricName: String,
        observations: List<QualityObservation>,
        lowerSpecificationLimit: Double?,
        upperSpecificationLimit: Double?
    ): SpcAnalysisResult {
        require(metricName.isNotBlank())
        val samples = observations.filter { it.metricName == metricName }.sortedBy { it.timestampEpochMs }
        require(samples.size >= 2) { "SPC analysis requires at least two observations" }
        val values = samples.map { it.value }
        val mean = values.average()
        val sigma = sampleStandardDeviation(values)
        val movingRanges = values.zipWithNext { left, right -> abs(right - left) }
        val upper = mean + 3.0 * sigma
        val lower = mean - 3.0 * sigma
        val violations = buildList {
            samples.forEachIndexed { index, observation ->
                if (observation.value > upper || observation.value < lower) {
                    add(
                        violation(
                            metricName,
                            SpcRuleType.OutsideThreeSigma,
                            listOf(observation.id),
                            SpcSeverity.Critical,
                            "Observation is outside the three-sigma control limits"
                        )
                    )
                }
                if (index >= 2) detectTwoOfThree(samples.subList(index - 2, index + 1), mean, sigma)?.let(::add)
                if (index >= 4) detectFourOfFive(samples.subList(index - 4, index + 1), mean, sigma)?.let(::add)
                if (index >= 7) detectEightSameSide(samples.subList(index - 7, index + 1), mean)?.let(::add)
                if (index >= 5) detectSixTrend(samples.subList(index - 5, index + 1))?.let(::add)
            }
        }.distinctBy { it.rule to it.observationIds }
        val capability = if (lowerSpecificationLimit != null || upperSpecificationLimit != null) {
            processCapability(values, lowerSpecificationLimit, upperSpecificationLimit)
        } else {
            null
        }
        return SpcAnalysisResult(
            metricName = metricName,
            centerLine = mean,
            sigma = sigma,
            upperControlLimit = upper,
            lowerControlLimit = lower,
            movingRanges = movingRanges,
            violations = violations,
            capability = capability
        )
    }

    private fun detectTwoOfThree(
        window: List<QualityObservation>,
        mean: Double,
        sigma: Double
    ): SpcViolation? {
        if (sigma <= 0.0) return null
        val positive = window.count { it.value > mean + 2.0 * sigma }
        val negative = window.count { it.value < mean - 2.0 * sigma }
        return if (positive >= 2 || negative >= 2) {
            violation(
                window.first().metricName,
                SpcRuleType.TwoOfThreeBeyondTwoSigma,
                window.map { it.id },
                SpcSeverity.Warning,
                "Two of three consecutive observations exceed two sigma on the same side"
            )
        } else null
    }

    private fun detectFourOfFive(
        window: List<QualityObservation>,
        mean: Double,
        sigma: Double
    ): SpcViolation? {
        if (sigma <= 0.0) return null
        val positive = window.count { it.value > mean + sigma }
        val negative = window.count { it.value < mean - sigma }
        return if (positive >= 4 || negative >= 4) {
            violation(
                window.first().metricName,
                SpcRuleType.FourOfFiveBeyondOneSigma,
                window.map { it.id },
                SpcSeverity.Warning,
                "Four of five consecutive observations exceed one sigma on the same side"
            )
        } else null
    }

    private fun detectEightSameSide(
        window: List<QualityObservation>,
        mean: Double
    ): SpcViolation? {
        val sameSide = window.all { it.value > mean } || window.all { it.value < mean }
        return if (sameSide) {
            violation(
                window.first().metricName,
                SpcRuleType.EightOnSameSide,
                window.map { it.id },
                SpcSeverity.Warning,
                "Eight consecutive observations are on the same side of the center line"
            )
        } else null
    }

    private fun detectSixTrend(window: List<QualityObservation>): SpcViolation? {
        val values = window.map { it.value }
        val increasing = values.zipWithNext().all { (left, right) -> right > left }
        val decreasing = values.zipWithNext().all { (left, right) -> right < left }
        return if (increasing || decreasing) {
            violation(
                window.first().metricName,
                SpcRuleType.SixIncreasingOrDecreasing,
                window.map { it.id },
                SpcSeverity.Warning,
                "Six consecutive observations form a monotonic trend"
            )
        } else null
    }

    private fun processCapability(
        values: List<Double>,
        lower: Double?,
        upper: Double?
    ): ProcessCapability {
        val mean = values.average()
        val sigma = sampleStandardDeviation(values)
        if (sigma <= 0.0) {
            return ProcessCapability(values.size, mean, sigma, null, null, null, null)
        }
        val cp = if (lower != null && upper != null) (upper - lower) / (6.0 * sigma) else null
        val upperCpk = upper?.let { (it - mean) / (3.0 * sigma) }
        val lowerCpk = lower?.let { (mean - it) / (3.0 * sigma) }
        val cpk = listOfNotNull(upperCpk, lowerCpk).minOrNull()
        return ProcessCapability(
            sampleCount = values.size,
            mean = mean,
            standardDeviation = sigma,
            cp = cp,
            cpk = cpk,
            pp = cp,
            ppk = cpk
        )
    }

    private fun sampleStandardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun violation(
        metric: String,
        rule: SpcRuleType,
        ids: List<String>,
        severity: SpcSeverity,
        message: String
    ) = SpcViolation(metric, rule, ids, severity, message)
}

class RuleBasedProductionAnomalyClassifier : ProductionAnomalyClassifier {
    override fun classify(context: ProductionAnomalyContext): AnomalyClassification {
        context.calibrationDecision?.takeIf { !it.allowed }?.let { decision ->
            return classification(
                type = ProductionAnomalyType.CalibrationExpired,
                confidence = 0.99,
                action = RecommendedAction.Recalibrate,
                evidence = evidence("calibration", decision.type.name, decision.message)
            )
        }
        val error = context.errorMessage.orEmpty().lowercase()
        if (listOf("timeout", "disconnect", "communication", "socket", "visa", "modbus").any(error::contains)) {
            return classification(
                ProductionAnomalyType.InstrumentCommunicationError,
                0.96,
                RecommendedAction.InspectHardware,
                evidence("error", context.errorMessage ?: "communication error", "runtime")
            )
        }
        if ("limit" in error || "motion rejected" in error) {
            return classification(
                ProductionAnomalyType.PositionLimitViolation,
                0.97,
                RecommendedAction.EngineeringReview,
                evidence("error", context.errorMessage ?: "position limit", "motion-safety")
            )
        }
        if (context.zSensorSaturated == true) {
            return classification(
                ProductionAnomalyType.ZSensorSaturated,
                0.99,
                RecommendedAction.InspectHardware,
                evidence("zSensor", "saturated", "inspection")
            )
        }
        if (context.zSensorValid == false) {
            return classification(
                ProductionAnomalyType.ProbeHeightInvalid,
                0.94,
                RecommendedAction.Recalibrate,
                evidence("zSensor", "invalid", "inspection")
            )
        }
        if (context.fiberDetected == false) {
            return classification(
                ProductionAnomalyType.FiberNotDetected,
                0.95,
                RecommendedAction.LocalRealign,
                evidence("fiber", "not detected", "vision")
            )
        }
        if (context.targetDetected == false) {
            val target = when (context.targetKind?.lowercase()) {
                "facet" -> ProductionAnomalyType.FacetNotDetected
                else -> ProductionAnomalyType.GratingNotDetected
            }
            return classification(
                target,
                0.93,
                RecommendedAction.LocalRealign,
                evidence("target", context.targetKind ?: "unknown", "vision")
            )
        }
        if (context.temperatureStable == false) {
            return classification(
                ProductionAnomalyType.TemperatureUnstable,
                0.96,
                RecommendedAction.HoldWafer,
                evidence("temperature", "not stable", "temperature-controller")
            )
        }
        val darkCurrent = context.metrics.firstOrNull { it.name.equals("darkCurrentA", ignoreCase = true) }
        if (darkCurrent != null && darkCurrent.upperSpecificationLimit != null &&
            darkCurrent.value > darkCurrent.upperSpecificationLimit
        ) {
            return classification(
                ProductionAnomalyType.ExcessiveDarkCurrent,
                0.91,
                RecommendedAction.EngineeringReview,
                evidence("darkCurrentA", darkCurrent.value.toString(), "measurement")
            )
        }
        val optical = context.metrics.firstOrNull {
            it.name.contains("power", ignoreCase = true) || it.name.contains("insertionLoss", ignoreCase = true)
        }
        if (optical != null && optical.lowerSpecificationLimit != null && optical.value < optical.lowerSpecificationLimit) {
            return classification(
                ProductionAnomalyType.OpticalPowerTooLow,
                0.88,
                RecommendedAction.LocalRealign,
                evidence(optical.name, optical.value.toString(), "measurement")
            )
        }
        if (context.spcViolations.isNotEmpty()) {
            return classification(
                ProductionAnomalyType.ProcessShift,
                0.86,
                RecommendedAction.HoldLot,
                context.spcViolations.map {
                    AnomalyEvidence("spcRule", it.rule.name, it.message)
                }
            )
        }
        return classification(
            ProductionAnomalyType.Unknown,
            0.35,
            RecommendedAction.EngineeringReview,
            evidence("context", "No deterministic rule matched", "classifier")
        )
    }

    private fun classification(
        type: ProductionAnomalyType,
        confidence: Double,
        action: RecommendedAction,
        evidence: List<AnomalyEvidence>
    ) = AnomalyClassification(
        primaryType = type,
        confidence = confidence,
        evidence = evidence,
        recommendedAction = action,
        classifierVersion = "rules-v1"
    )

    private fun evidence(key: String, value: String, source: String) =
        listOf(AnomalyEvidence(key, value, source))
}

class RoleBasedProductionAuthorizationService : ProductionAuthorizationService {
    override fun permissions(actor: ProductionActor): Set<ProductionPermission> {
        if (!actor.enabled) return emptySet()
        return actor.roles.flatMapTo(mutableSetOf()) { rolePermissions[it].orEmpty() }
    }

    override fun requirePermission(actor: ProductionActor, permission: ProductionPermission) {
        require(actor.enabled) { "Actor is disabled: ${actor.id}" }
        require(permission in permissions(actor)) {
            "Actor ${actor.id} does not have permission $permission"
        }
    }

    private val rolePermissions = mapOf(
        ProductionRole.Operator to setOf(
            ProductionPermission.LotStart,
            ProductionPermission.LotPause,
            ProductionPermission.DataExport
        ),
        ProductionRole.Engineer to setOf(
            ProductionPermission.LotCreate,
            ProductionPermission.RecipeCreate,
            ProductionPermission.CalibrationExecute,
            ProductionPermission.TaskRetry,
            ProductionPermission.ManualMotion,
            ProductionPermission.LaserOutputControl,
            ProductionPermission.DataExport
        ),
        ProductionRole.QualityEngineer to setOf(
            ProductionPermission.CalibrationApprove,
            ProductionPermission.QualityRuleEdit,
            ProductionPermission.AnomalyOverride,
            ProductionPermission.AuditRead,
            ProductionPermission.DataExport
        ),
        ProductionRole.Supervisor to setOf(
            ProductionPermission.LotApprove,
            ProductionPermission.LotStart,
            ProductionPermission.LotPause,
            ProductionPermission.LotAbort,
            ProductionPermission.TaskRetry,
            ProductionPermission.RecipeApprove,
            ProductionPermission.AnomalyOverride,
            ProductionPermission.AuditRead,
            ProductionPermission.DataExport
        ),
        ProductionRole.Administrator to ProductionPermission.entries.toSet(),
        ProductionRole.Auditor to setOf(
            ProductionPermission.AuditRead,
            ProductionPermission.DataExport
        ),
        ProductionRole.ServiceEngineer to setOf(
            ProductionPermission.ManualMotion,
            ProductionPermission.LaserOutputControl,
            ProductionPermission.CalibrationExecute,
            ProductionPermission.AuditRead
        )
    )
}

class DefaultProductionAuditService(
    private val repository: ProductionRepository,
    private val hasher: AuditHasher,
    private val nowEpochMs: () -> Long,
    private val idFactory: () -> String,
    private val applicationVersion: String,
    private val workstationId: String
) : ProductionAuditService {
    private val mutex = Mutex()

    override suspend fun record(
        actor: ProductionActor,
        action: String,
        targetType: String,
        targetId: String,
        correlationId: String,
        reason: String?,
        beforeJson: String?,
        afterJson: String?,
        success: Boolean,
        errorMessage: String?
    ): AuditEvent = mutex.withLock {
        val previous = repository.latestAuditEvent()?.eventHash
        val timestamp = nowEpochMs()
        val id = idFactory()
        val canonical = listOf(
            previous.orEmpty(),
            id,
            timestamp.toString(),
            actor.id,
            actor.roles.map { it.name }.sorted().joinToString(","),
            action,
            targetType,
            targetId,
            correlationId,
            reason.orEmpty(),
            beforeJson.orEmpty(),
            afterJson.orEmpty(),
            applicationVersion,
            workstationId,
            success.toString(),
            errorMessage.orEmpty()
        ).joinToString("|")
        val event = AuditEvent(
            id = id,
            timestampEpochMs = timestamp,
            actorId = actor.id,
            actorRoles = actor.roles,
            action = action,
            targetType = targetType,
            targetId = targetId,
            correlationId = correlationId,
            reason = reason,
            beforeJson = beforeJson,
            afterJson = afterJson,
            applicationVersion = applicationVersion,
            workstationId = workstationId,
            success = success,
            errorMessage = errorMessage,
            previousHash = previous,
            eventHash = hasher.hash(canonical)
        )
        repository.appendAuditEvent(event)
        event
    }
}
