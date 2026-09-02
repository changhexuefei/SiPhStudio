package org.jason.siph.domain.production

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class ProductionSoakAcceptancePlan(
    val id: String,
    val kind: ProductionAcceptanceKind,
    val environmentName: String,
    val minimumRunDurationMs: Long,
    val maximumRunDurationMs: Long,
    val maximumTasks: Int,
    val idlePollDelayMs: Long = 250L,
    val maximumConsecutiveIdlePolls: Int = 20,
    val criteria: ProductionAcceptanceCriteria,
    val evidenceReferences: List<String>,
    val limitations: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank() && environmentName.isNotBlank())
        require(minimumRunDurationMs > 0L)
        require(maximumRunDurationMs >= minimumRunDurationMs)
        require(maximumTasks > 0)
        require(idlePollDelayMs >= 0L)
        require(maximumConsecutiveIdlePolls > 0)
        require(evidenceReferences.all(String::isNotBlank))
    }
}

class ProductionSoakAcceptanceRunner(
    private val worker: DefaultProductionWorker,
    private val repository: ProductionRepository,
    private val evaluator: ProductionAcceptanceEvaluator = ProductionAcceptanceEvaluator(),
    private val nowEpochMs: () -> Long,
    private val wait: suspend (Long) -> Unit = { duration -> delay(duration) }
) {
    suspend fun run(
        plan: ProductionSoakAcceptancePlan,
        actor: ProductionActor
    ): ProductionAcceptanceReport {
        require(plan.kind != ProductionAcceptanceKind.FullProduction || plan.limitations.isEmpty()) {
            "FullProduction acceptance cannot be emitted while known limitations remain"
        }
        val startedAt = nowEpochMs()
        val initialResults = repository.listMeasurementResults()
        val latencies = mutableListOf<Long>()
        var executed = 0
        var consecutiveIdle = 0

        while (executed < plan.maximumTasks) {
            val elapsed = nowEpochMs() - startedAt
            if (elapsed >= plan.maximumRunDurationMs) break
            val taskStartedAt = nowEpochMs()
            val didRun = worker.runNext(actor)
            val taskFinishedAt = nowEpochMs()
            if (didRun) {
                executed += 1
                consecutiveIdle = 0
                latencies += (taskFinishedAt - taskStartedAt).coerceAtLeast(0L)
            } else {
                consecutiveIdle += 1
                if (elapsed >= plan.minimumRunDurationMs && consecutiveIdle >= plan.maximumConsecutiveIdlePolls) break
                if (plan.idlePollDelayMs > 0L) wait(plan.idlePollDelayMs)
            }
        }

        val finishedAt = nowEpochMs()
        val initialIds = initialResults.map { it.resultId }.toSet()
        val produced = repository.listMeasurementResults().filterNot { it.resultId in initialIds }
        val duplicateCount = produced.size - produced.distinctBy { it.idempotencyKey }.size
        val passed = produced.count { it.passed }
        val failed = produced.size - passed
        return evaluator.evaluate(
            id = plan.id,
            kind = plan.kind,
            environmentName = plan.environmentName,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            submittedTasks = plan.maximumTasks,
            passedTasks = passed,
            failedTasks = failed,
            duplicateResults = duplicateCount,
            taskLatenciesMs = latencies,
            criteria = plan.criteria,
            evidenceReferences = plan.evidenceReferences,
            limitations = plan.limitations
        )
    }
}
