package org.jason.siph.domain.production

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionAcceptanceEvaluatorTest {

    @Test
    fun digitalInfrastructureAcceptanceRetainsHardwareLimitation() {
        val report = ProductionAcceptanceEvaluator().evaluate(
            id = "acceptance-1",
            kind = ProductionAcceptanceKind.PostgreSqlConcurrency,
            environmentName = "github-postgres-service",
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 61_000L,
            submittedTasks = 400,
            passedTasks = 400,
            failedTasks = 0,
            duplicateResults = 0,
            taskLatenciesMs = List(400) { 25L + it % 10 },
            criteria = ProductionAcceptanceCriteria(
                minimumCompletedTasks = 400,
                minimumThroughputTasksPerHour = 20_000.0,
                minimumSuccessRate = 1.0,
                maximumP95TaskLatencyMs = 100L,
                minimumContinuousRunMs = 60_000L
            ),
            evidenceReferences = listOf("ci://postgres-stress"),
            limitations = listOf("No optical, electrical, motion, thermal, or wafer hardware was exercised")
        )
        assertTrue(report.passed)
        assertEquals(ProductionAcceptanceKind.PostgreSqlConcurrency, report.kind)
        assertTrue(report.limitations.isNotEmpty())
    }
}
