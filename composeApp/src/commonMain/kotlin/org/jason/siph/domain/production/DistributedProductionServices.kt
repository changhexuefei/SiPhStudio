package org.jason.siph.domain.production

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min

class CoordinatedProductionScheduler(
    private val repository: ProductionRepository,
    private val coordinator: DistributedProductionCoordinator,
    private val nowEpochMs: () -> Long,
    private val workerTimeoutMs: Long = 120_000L,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
) : ProductionScheduler {
    private val mutex = Mutex()
    private val leasesByAttemptId = linkedMapOf<String, DistributedTaskLease>()

    override suspend fun enqueueLot(lot: ProductionLot, tasks: List<ProductionTask>) {
        require(tasks.isNotEmpty())
        require(tasks.all { it.lotId == lot.id })
        coordinator.initialize()
        val queuedLot = lot.copy(state = LotState.Queued)
        repository.saveLot(queuedLot)
        val submissions = tasks.map { task ->
            val recipe = repository.findRecipe(task.recipeId, task.recipeVersion)
                ?: error("Production recipe was not found for distributed task ${task.id}")
            val pending = task.copy(state = ProductionTaskState.Pending)
            repository.saveTask(pending)
            DistributedTaskSubmission(
                task = pending,
                requiredCapabilities = recipe.requiredDeviceCapabilities,
                submittedAtEpochMs = nowEpochMs()
            )
        }
        coordinator.enqueueTasks(submissions)
    }

    override suspend fun reserveNext(workerId: String, leaseDurationMs: Long): ReservedProductionTask? {
        coordinator.initialize()
        val lease = coordinator.reserveNextTask(
            workerId = workerId,
            leaseDurationMs = leaseDurationMs,
            nowEpochMs = nowEpochMs()
        ) ?: return null
        mutex.withLock { leasesByAttemptId[lease.attemptId] = lease }
        repository.saveTask(lease.task)
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = lease.task.id,
                attemptId = lease.attemptId,
                stage = ProductionCheckpointStage.Reserved,
                updatedAtEpochMs = lease.reservedAtEpochMs,
                message = "Distributed task reserved by $workerId with fencing token ${lease.fencingToken}"
            )
        )
        repository.findLot(lease.task.lotId)?.let { lot ->
            if (lot.state == LotState.Queued) repository.saveLot(lot.copy(state = LotState.Running))
        }
        return lease.asReservation()
    }

    override suspend fun renewLease(
        reservation: ReservedProductionTask,
        leaseDurationMs: Long
    ): ReservedProductionTask {
        val current = requireLease(reservation)
        val renewed = coordinator.renewTaskLease(current, leaseDurationMs, nowEpochMs())
        mutex.withLock { leasesByAttemptId[reservation.attemptId] = renewed }
        repository.saveTask(renewed.task)
        return renewed.asReservation()
    }

    override suspend fun complete(
        reservation: ReservedProductionTask,
        result: ProductionMeasurementResult
    ) {
        val lease = requireLease(reservation)
        val now = nowEpochMs()
        coordinator.completeTaskLease(
            lease = lease,
            resultId = result.resultId,
            passed = result.passed,
            nowEpochMs = now
        )
        val existing = repository.findMeasurementResultByIdempotencyKey(result.idempotencyKey)
        if (existing == null) repository.saveMeasurementResult(result)
        repository.saveTask(
            lease.task.copy(
                state = if (result.passed) ProductionTaskState.Passed else ProductionTaskState.Failed,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = result.failureMessage
            )
        )
        repository.saveCheckpoint(
            ProductionCheckpoint(
                taskId = lease.task.id,
                attemptId = lease.attemptId,
                stage = ProductionCheckpointStage.Completed,
                updatedAtEpochMs = now,
                resultId = existing?.resultId ?: result.resultId,
                message = "Distributed result committed with fencing token ${lease.fencingToken}"
            )
        )
        coordinator.enqueueOutbox(
            ProductionOutboxEvent(
                id = "mes-result-${result.resultId}",
                destination = ProductionOutboxDestination.Mes,
                eventType = "PRODUCTION_TASK_COMPLETED",
                aggregateType = "ProductionTask",
                aggregateId = result.taskId,
                idempotencyKey = "MES:${result.idempotencyKey}",
                payloadJson = json.encodeToString(result),
                createdAtEpochMs = now
            )
        )
        mutex.withLock { leasesByAttemptId.remove(reservation.attemptId) }
        updateLotTerminalState(lease.task.lotId)
    }

    override suspend fun fail(
        reservation: ReservedProductionTask,
        error: Throwable,
        retryable: Boolean
    ) {
        val lease = requireLease(reservation)
        val message = error.message ?: error::class.simpleName ?: "Distributed task failed"
        coordinator.failTaskLease(
            lease = lease,
            errorMessage = message,
            retryable = retryable,
            nowEpochMs = nowEpochMs()
        )
        val retry = retryable && lease.task.attemptCount < lease.task.maximumAttempts
        repository.saveTask(
            lease.task.copy(
                state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = message
            )
        )
        mutex.withLock { leasesByAttemptId.remove(reservation.attemptId) }
        updateLotTerminalState(lease.task.lotId)
    }

    override suspend fun releaseExpiredLeases(nowEpochMs: Long): Int {
        val result = coordinator.reapExpiredWorkersAndLeases(workerTimeoutMs, nowEpochMs)
        result.releasedTaskIds.forEach { taskId ->
            repository.findTask(taskId)?.let { task ->
                val retry = task.attemptCount < task.maximumAttempts
                repository.saveTask(
                    task.copy(
                        state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed,
                        leaseOwner = null,
                        leaseExpiresAtEpochMs = null,
                        lastError = "Distributed worker lease expired"
                    )
                )
            }
        }
        mutex.withLock {
            val released = result.releasedTaskIds.toSet()
            leasesByAttemptId.entries.removeAll { it.value.task.id in released }
        }
        return result.releasedTaskIds.size
    }

    private suspend fun requireLease(reservation: ReservedProductionTask): DistributedTaskLease {
        return mutex.withLock {
            leasesByAttemptId[reservation.attemptId]
                ?: error("Distributed lease is not active for attempt ${reservation.attemptId}")
        }.also { lease ->
            require(lease.workerId == reservation.workerId)
            require(lease.task.id == reservation.task.id)
        }
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
        val state = if (tasks.any { it.state == ProductionTaskState.Failed }) {
            LotState.Failed
        } else {
            LotState.Completed
        }
        repository.saveLot(lot.copy(state = state))
    }
}

class ProductionOutboxDispatcher(
    private val coordinator: DistributedProductionCoordinator,
    private val mesGateway: MesGateway,
    private val remoteAuditSink: RemoteAuditSink,
    private val nowEpochMs: () -> Long
) {
    suspend fun dispatch(
        destination: ProductionOutboxDestination,
        dispatcherId: String,
        maximumBatchSize: Int = 50,
        leaseDurationMs: Long = 30_000L
    ): OutboxDispatchSummary {
        val leases = coordinator.reserveOutboxBatch(
            destination = destination,
            dispatcherId = dispatcherId,
            maximumBatchSize = maximumBatchSize,
            leaseDurationMs = leaseDurationMs,
            nowEpochMs = nowEpochMs()
        )
        var delivered = 0
        var failed = 0
        leases.forEach { lease ->
            runCatching {
                when (destination) {
                    ProductionOutboxDestination.Mes -> mesGateway.submit(lease.event).let {
                        check(it.accepted) { it.message }
                        it.remoteReference
                    }
                    ProductionOutboxDestination.AuditServer -> remoteAuditSink.append(lease.event).let {
                        check(it.accepted) { it.message }
                        it.remoteReference
                    }
                }
            }.onSuccess { remoteReference ->
                coordinator.markOutboxDelivered(lease, remoteReference, nowEpochMs())
                delivered += 1
            }.onFailure { error ->
                val attempt = lease.event.attemptCount + 1
                val retryDelay = min(300_000L, 1_000L * (1L shl min(8, attempt - 1)))
                coordinator.markOutboxFailed(
                    lease = lease,
                    errorMessage = error.message ?: error::class.simpleName ?: "Outbox delivery failed",
                    retryDelayMs = retryDelay,
                    nowEpochMs = nowEpochMs()
                )
                failed += 1
            }
        }
        return OutboxDispatchSummary(leases.size, delivered, failed)
    }
}

data class OutboxDispatchSummary(
    val reserved: Int,
    val delivered: Int,
    val failed: Int
)

class MockMesGateway(
    private val rejectedEventTypes: Set<String> = emptySet()
) : MesGateway {
    private val mutex = Mutex()
    private val acceptedByIdempotencyKey = linkedMapOf<String, String>()

    override suspend fun submit(event: ProductionOutboxEvent): MesSubmissionResult = mutex.withLock {
        if (event.eventType in rejectedEventTypes) {
            return@withLock MesSubmissionResult(false, message = "Mock MES rejected ${event.eventType}")
        }
        val reference = acceptedByIdempotencyKey.getOrPut(event.idempotencyKey) {
            "MES-${acceptedByIdempotencyKey.size + 1}"
        }
        MesSubmissionResult(
            accepted = true,
            remoteReference = reference,
            message = "Mock MES accepted the idempotent production event"
        )
    }
}

class InMemoryRemoteAuditSink : RemoteAuditSink {
    private val mutex = Mutex()
    private val references = linkedMapOf<String, String>()

    override suspend fun append(event: ProductionOutboxEvent): RemoteAuditReceipt = mutex.withLock {
        val reference = references.getOrPut(event.idempotencyKey) {
            "AUDIT-${references.size + 1}"
        }
        RemoteAuditReceipt(
            accepted = true,
            remoteReference = reference,
            message = "Remote append-only audit mock accepted the event"
        )
    }
}

class InMemoryWormAuditArchive : WormAuditArchive {
    private val mutex = Mutex()
    private val events = mutableListOf<AuditEvent>()

    override suspend fun append(event: AuditEvent) = mutex.withLock {
        val duplicate = events.firstOrNull { it.id == event.id }
        if (duplicate != null) {
            require(duplicate.eventHash == event.eventHash) {
                "WORM archive rejects mutation of audit event ${event.id}"
            }
            return@withLock
        }
        val expectedPrevious = events.lastOrNull()?.eventHash
        require(event.previousHash == expectedPrevious) {
            "WORM audit chain mismatch: expected=$expectedPrevious, actual=${event.previousHash}"
        }
        events += event
    }

    override suspend fun latest(): AuditEvent? = mutex.withLock { events.lastOrNull() }

    override suspend fun list(limit: Int): List<AuditEvent> {
        require(limit > 0)
        return mutex.withLock { events.takeLast(limit).reversed() }
    }
}

class ReplicatingProductionAuditService(
    private val delegate: ProductionAuditService,
    private val coordinator: DistributedProductionCoordinator,
    private val nowEpochMs: () -> Long,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
) : ProductionAuditService {
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
    ): AuditEvent {
        val event = delegate.record(
            actor = actor,
            action = action,
            targetType = targetType,
            targetId = targetId,
            correlationId = correlationId,
            reason = reason,
            beforeJson = beforeJson,
            afterJson = afterJson,
            success = success,
            errorMessage = errorMessage
        )
        coordinator.enqueueOutbox(
            ProductionOutboxEvent(
                id = "audit-outbox-${event.id}",
                destination = ProductionOutboxDestination.AuditServer,
                eventType = "AUDIT_EVENT_APPENDED",
                aggregateType = "AuditEvent",
                aggregateId = event.id,
                idempotencyKey = "AUDIT:${event.eventHash}",
                payloadJson = json.encodeToString(event),
                createdAtEpochMs = nowEpochMs()
            )
        )
        return event
    }
}
