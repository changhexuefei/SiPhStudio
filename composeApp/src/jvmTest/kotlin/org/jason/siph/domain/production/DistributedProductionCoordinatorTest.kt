package org.jason.siph.domain.production

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DistributedProductionCoordinatorTest {

    @Test
    fun workersReserveOnlyCapabilityCompatibleTasksWithoutDuplicates() = runBlocking {
        val coordinator = InMemoryDistributedProductionCoordinator()
        coordinator.initialize()
        coordinator.registerWorker(worker("worker-a", setOf("laser", "powerMeter"), 1_000L))
        coordinator.registerWorker(worker("worker-b", setOf("laser", "powerMeter", "electricalAnalyzer"), 1_000L))
        coordinator.enqueueTasks(
            listOf(
                submission(task("task-oe", priority = 20), setOf("laser", "powerMeter", "electricalAnalyzer")),
                submission(task("task-oo", priority = 10), setOf("laser", "powerMeter"))
            )
        )

        val leases = coroutineScope {
            listOf("worker-a", "worker-b").map { workerId ->
                async { coordinator.reserveNextTask(workerId, 5_000L, 2_000L) }
            }.awaitAll().filterNotNull()
        }

        assertEquals(2, leases.size)
        assertEquals(2, leases.map { it.task.id }.distinct().size)
        assertEquals("task-oo", leases.single { it.workerId == "worker-a" }.task.id)
        assertEquals("task-oe", leases.single { it.workerId == "worker-b" }.task.id)
    }

    @Test
    fun expiredLeaseGetsNewFencingTokenAndRejectsLateCompletion() = runBlocking {
        val coordinator = InMemoryDistributedProductionCoordinator()
        coordinator.registerWorker(worker("worker-a", allCapabilities, 1_000L))
        coordinator.registerWorker(worker("worker-b", allCapabilities, 1_000L))
        coordinator.enqueueTasks(listOf(submission(task("task-1"), allCapabilities)))

        val first = assertNotNull(coordinator.reserveNextTask("worker-a", 10L, 2_000L))
        val reaped = coordinator.reapExpiredWorkersAndLeases(workerTimeoutMs = 10_000L, nowEpochMs = 2_020L)
        assertEquals(listOf("task-1"), reaped.releasedTaskIds)
        val second = assertNotNull(coordinator.reserveNextTask("worker-b", 10L, 2_021L))

        assertTrue(second.fencingToken > first.fencingToken)
        assertNotEquals(first.workerId, second.workerId)
        assertFailsWith<IllegalArgumentException> {
            coordinator.completeTaskLease(first, "late-result", passed = true, nowEpochMs = 2_022L)
        }
        coordinator.completeTaskLease(second, "valid-result", passed = true, nowEpochMs = 2_022L)
        assertNull(coordinator.reserveNextTask("worker-a", 10L, 2_023L))
    }

    @Test
    fun outboxIsIdempotentRetryableAndDeliveredOnce() = runBlocking {
        var now = 10_000L
        val coordinator = InMemoryDistributedProductionCoordinator()
        val event = ProductionOutboxEvent(
            id = "mes-event-1",
            destination = ProductionOutboxDestination.Mes,
            eventType = "PRODUCTION_TASK_COMPLETED",
            aggregateType = "ProductionTask",
            aggregateId = "task-1",
            idempotencyKey = "MES:task-1",
            payloadJson = "{\"taskId\":\"task-1\"}",
            createdAtEpochMs = now
        )
        coordinator.enqueueOutbox(event)
        coordinator.enqueueOutbox(event)
        val dispatcher = ProductionOutboxDispatcher(
            coordinator = coordinator,
            mesGateway = MockMesGateway(),
            remoteAuditSink = InMemoryRemoteAuditSink(),
            nowEpochMs = { now++ }
        )

        val summary = dispatcher.dispatch(ProductionOutboxDestination.Mes, "dispatcher-1")

        assertEquals(1, summary.reserved)
        assertEquals(1, summary.delivered)
        assertEquals(0, summary.failed)
        assertEquals(1, coordinator.listOutboxEvents().size)
        assertEquals(ProductionOutboxState.Delivered, coordinator.listOutboxEvents().single().state)
        assertEquals(0, dispatcher.dispatch(ProductionOutboxDestination.Mes, "dispatcher-1").reserved)
    }

    @Test
    fun wormArchiveRejectsAuditMutationAndBrokenChain() = runBlocking {
        val archive = InMemoryWormAuditArchive()
        val first = audit("audit-1", previous = null, hash = "hash-1")
        archive.append(first)
        archive.append(first)

        assertFailsWith<IllegalArgumentException> {
            archive.append(first.copy(eventHash = "mutated"))
        }
        assertFailsWith<IllegalArgumentException> {
            archive.append(audit("audit-2", previous = "wrong", hash = "hash-2"))
        }
        archive.append(audit("audit-2", previous = "hash-1", hash = "hash-2"))
        assertEquals(2, archive.list().size)
    }

    private fun worker(
        workerId: String,
        capabilities: Set<String>,
        now: Long
    ) = ProductionWorkerRegistration(
        workerId = workerId,
        workstationId = "$workerId-station",
        equipmentGroupId = "digital-group",
        capabilities = capabilities,
        softwareVersion = "test",
        maximumParallelTasks = 1,
        registeredAtEpochMs = now
    )

    private fun submission(
        task: ProductionTask,
        capabilities: Set<String>
    ) = DistributedTaskSubmission(task, capabilities, submittedAtEpochMs = 1_500L)

    private fun task(id: String, priority: Int = 1) = ProductionTask(
        id = id,
        lotId = "lot-1",
        waferId = "wafer-1",
        site = MeasurementSiteKey(
            waferId = "wafer-1",
            die = DieIndex(row = 0, column = id.hashCode().and(0x7fff)),
            subDieId = "sub-1",
            couplerId = "coupler-1"
        ),
        recipeId = "recipe-1",
        recipeVersion = 1,
        priority = priority,
        maximumAttempts = 3,
        idempotencyKey = "lot-1:$id"
    )

    private fun audit(id: String, previous: String?, hash: String) = AuditEvent(
        id = id,
        timestampEpochMs = 1L,
        actorId = "actor",
        actorRoles = setOf(ProductionRole.Auditor),
        action = "READ",
        targetType = "Audit",
        targetId = id,
        correlationId = "correlation-$id",
        applicationVersion = "test",
        workstationId = "ci",
        success = true,
        previousHash = previous,
        eventHash = hash
    )

    private companion object {
        val allCapabilities = setOf("laser", "powerMeter", "electricalAnalyzer")
    }
}
