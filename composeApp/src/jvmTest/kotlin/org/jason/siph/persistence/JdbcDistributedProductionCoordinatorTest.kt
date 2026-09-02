package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.production.DistributedTaskSubmission
import org.jason.siph.domain.production.InMemoryRemoteAuditSink
import org.jason.siph.domain.production.MockMesGateway
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxDispatcher
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionOutboxState
import org.jason.siph.domain.production.ProductionTask
import org.jason.siph.domain.production.ProductionWorkerRegistration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdbcDistributedProductionCoordinatorTest {

    @Test
    fun postgresqlModeCoordinatesMultipleWorkersAndOutbox() = runBlocking {
        val coordinator = coordinator("coordination")
        coordinator.initialize()
        coordinator.registerWorker(worker("worker-a"))
        coordinator.registerWorker(worker("worker-b"))
        coordinator.enqueueTasks(
            listOf(
                submission(task("task-1", 20)),
                submission(task("task-2", 10))
            )
        )

        val first = assertNotNull(
            coordinator.reserveNextTask("worker-a", leaseDurationMs = 5_000L, nowEpochMs = 2_000L)
        )
        val second = assertNotNull(
            coordinator.reserveNextTask("worker-b", leaseDurationMs = 5_000L, nowEpochMs = 2_001L)
        )
        val leases = listOf(first, second)

        assertEquals(2, leases.map { it.task.id }.distinct().size)
        leases.forEachIndexed { index, lease ->
            coordinator.completeTaskLease(
                lease = lease,
                resultId = "result-${index + 1}",
                passed = true,
                nowEpochMs = 2_100L
            )
        }

        val event = ProductionOutboxEvent(
            id = "event-1",
            destination = ProductionOutboxDestination.Mes,
            eventType = "PRODUCTION_TASK_COMPLETED",
            aggregateType = "ProductionTask",
            aggregateId = "task-1",
            idempotencyKey = "MES:task-1",
            payloadJson = "{\"taskId\":\"task-1\"}",
            createdAtEpochMs = 2_200L
        )
        coordinator.enqueueOutbox(event)
        val dispatcher = ProductionOutboxDispatcher(
            coordinator = coordinator,
            mesGateway = MockMesGateway(),
            remoteAuditSink = InMemoryRemoteAuditSink(),
            nowEpochMs = { 2_300L }
        )
        assertEquals(1, dispatcher.dispatch(ProductionOutboxDestination.Mes, "dispatcher-1").delivered)
        assertEquals(ProductionOutboxState.Delivered, coordinator.listOutboxEvents().single().state)
    }

    @Test
    fun jdbcFencingRejectsWorkerThatLostItsLease() = runBlocking {
        val coordinator = coordinator("fencing")
        coordinator.initialize()
        coordinator.registerWorker(worker("worker-a"))
        coordinator.registerWorker(worker("worker-b"))
        coordinator.enqueueTasks(listOf(submission(task("task-fence", 1))))

        val first = assertNotNull(coordinator.reserveNextTask("worker-a", 10L, 5_000L))
        val reaped = coordinator.reapExpiredWorkersAndLeases(10_000L, 5_020L)
        assertEquals(listOf("task-fence"), reaped.releasedTaskIds)
        val second = assertNotNull(coordinator.reserveNextTask("worker-b", 10L, 5_021L))

        assertTrue(second.fencingToken > first.fencingToken)
        assertFailsWith<IllegalArgumentException> {
            coordinator.completeTaskLease(first, "late-result", true, 5_022L)
        }
        coordinator.completeTaskLease(second, "valid-result", true, 5_022L)
    }

    private fun coordinator(name: String) = JdbcDistributedProductionCoordinator(
        dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        ),
        backendDetail = "H2 PostgreSQL compatibility test"
    )

    private fun worker(id: String) = ProductionWorkerRegistration(
        workerId = id,
        workstationId = "$id-station",
        equipmentGroupId = "test-equipment",
        capabilities = capabilities,
        softwareVersion = "test",
        maximumParallelTasks = 1,
        registeredAtEpochMs = 1_000L
    )

    private fun submission(task: ProductionTask) = DistributedTaskSubmission(
        task = task,
        requiredCapabilities = capabilities,
        submittedAtEpochMs = 1_500L
    )

    private fun task(id: String, priority: Int) = ProductionTask(
        id = id,
        lotId = "lot-jdbc",
        waferId = "wafer-jdbc",
        site = MeasurementSiteKey(
            waferId = "wafer-jdbc",
            die = DieIndex(row = 0, column = id.hashCode().and(0x7fff)),
            subDieId = "sub-1",
            couplerId = "coupler-1"
        ),
        recipeId = "recipe-jdbc",
        recipeVersion = 1,
        priority = priority,
        maximumAttempts = 3,
        idempotencyKey = "lot-jdbc:$id"
    )

    private companion object {
        val capabilities = setOf("laser", "powerMeter", "electricalAnalyzer")
    }
}
