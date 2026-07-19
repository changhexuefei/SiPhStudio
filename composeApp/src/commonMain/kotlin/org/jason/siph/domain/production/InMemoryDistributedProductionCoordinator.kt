package org.jason.siph.domain.production

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryDistributedProductionCoordinator : DistributedProductionCoordinator {
    override val status = DistributedCoordinatorStatus(
        backend = DistributedCoordinatorBackend.InMemory,
        configured = true,
        healthy = true,
        detail = "In-memory distributed production coordinator"
    )

    private val mutex = Mutex()
    private val workers = linkedMapOf<String, ProductionWorkerSnapshot>()
    private val tasks = linkedMapOf<String, TaskEntry>()
    private val outbox = linkedMapOf<String, OutboxEntry>()
    private val outboxIdempotency = linkedMapOf<String, String>()

    override suspend fun initialize() = Unit

    override suspend fun registerWorker(
        registration: ProductionWorkerRegistration
    ): ProductionWorkerSnapshot = mutex.withLock {
        val existing = workers[registration.workerId]
        val snapshot = ProductionWorkerSnapshot(
            registration = registration,
            availability = ProductionWorkerAvailability.Ready,
            lastHeartbeatEpochMs = registration.registeredAtEpochMs,
            currentTaskIds = existing?.currentTaskIds.orEmpty(),
            detail = "Worker registered"
        )
        workers[registration.workerId] = snapshot
        snapshot
    }

    override suspend fun heartbeat(
        workerId: String,
        availability: ProductionWorkerAvailability,
        currentTaskIds: Set<String>,
        detail: String,
        nowEpochMs: Long
    ): ProductionWorkerSnapshot = mutex.withLock {
        val current = workers[workerId] ?: error("Worker is not registered: $workerId")
        val updated = current.copy(
            availability = availability,
            lastHeartbeatEpochMs = nowEpochMs,
            currentTaskIds = currentTaskIds,
            detail = detail
        )
        workers[workerId] = updated
        updated
    }

    override suspend fun findWorker(workerId: String): ProductionWorkerSnapshot? = mutex.withLock {
        workers[workerId]
    }

    override suspend fun listWorkers(): List<ProductionWorkerSnapshot> = mutex.withLock {
        workers.values.sortedBy { it.registration.workerId }
    }

    override suspend fun enqueueTasks(submissions: List<DistributedTaskSubmission>) = mutex.withLock {
        submissions.forEach { submission ->
            val existing = tasks.values.firstOrNull {
                it.submission.task.idempotencyKey == submission.task.idempotencyKey
            }
            require(existing == null || existing.submission.task.id == submission.task.id) {
                "Task idempotency key already belongs to ${existing?.submission?.task?.id}"
            }
            tasks.putIfAbsent(submission.task.id, TaskEntry(submission = submission))
        }
    }

    override suspend fun reserveNextTask(
        workerId: String,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease? = mutex.withLock {
        require(leaseDurationMs > 0L)
        releaseExpiredTaskLeases(nowEpochMs)
        val worker = workers[workerId] ?: error("Worker is not registered: $workerId")
        require(worker.availability !in setOf(
            ProductionWorkerAvailability.Draining,
            ProductionWorkerAvailability.Offline,
            ProductionWorkerAvailability.Error
        )) { "Worker cannot reserve tasks while ${worker.availability}" }
        val activeCount = tasks.values.count { entry ->
            entry.lease?.workerId == workerId && entry.state == ProductionTaskState.Reserved
        }
        if (activeCount >= worker.registration.maximumParallelTasks) return@withLock null

        val capabilities = worker.registration.capabilities
        val entry = tasks.values
            .filter { it.state == ProductionTaskState.Pending || it.state == ProductionTaskState.RetryPending }
            .filter { capabilities.containsAll(it.submission.requiredCapabilities) }
            .sortedWith(
                compareByDescending<TaskEntry> { it.submission.task.priority }
                    .thenBy { it.submission.submittedAtEpochMs }
                    .thenBy { it.submission.task.id }
            )
            .firstOrNull() ?: return@withLock null

        val attempt = entry.attemptCount + 1
        val token = entry.fencingToken + 1L
        val task = entry.submission.task.copy(
            state = ProductionTaskState.Reserved,
            attemptCount = attempt,
            leaseOwner = workerId,
            leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs,
            lastError = null
        )
        val lease = DistributedTaskLease(
            task = task,
            attemptId = "${task.id}-attempt-$attempt",
            workerId = workerId,
            fencingToken = token,
            reservedAtEpochMs = nowEpochMs,
            leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs
        )
        entry.state = ProductionTaskState.Reserved
        entry.attemptCount = attempt
        entry.fencingToken = token
        entry.lease = lease
        entry.submission = entry.submission.copy(task = task)
        updateWorkerTasks(workerId, nowEpochMs)
        lease
    }

    override suspend fun renewTaskLease(
        lease: DistributedTaskLease,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease = mutex.withLock {
        require(leaseDurationMs > 0L)
        val entry = requireCurrentLease(lease)
        require(entry.lease!!.leaseExpiresAtEpochMs > nowEpochMs) { "Task lease already expired" }
        val updatedTask = entry.submission.task.copy(
            leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs
        )
        val updated = lease.copy(
            task = updatedTask,
            leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs
        )
        entry.submission = entry.submission.copy(task = updatedTask)
        entry.lease = updated
        updated
    }

    override suspend fun completeTaskLease(
        lease: DistributedTaskLease,
        resultId: String,
        passed: Boolean,
        nowEpochMs: Long
    ) = mutex.withLock {
        require(resultId.isNotBlank())
        val entry = requireCurrentLease(lease)
        entry.state = if (passed) ProductionTaskState.Passed else ProductionTaskState.Failed
        entry.resultId = resultId
        entry.submission = entry.submission.copy(
            task = entry.submission.task.copy(
                state = entry.state,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null
            )
        )
        entry.lease = null
        updateWorkerTasks(lease.workerId, nowEpochMs)
    }

    override suspend fun failTaskLease(
        lease: DistributedTaskLease,
        errorMessage: String,
        retryable: Boolean,
        nowEpochMs: Long
    ) = mutex.withLock {
        require(errorMessage.isNotBlank())
        val entry = requireCurrentLease(lease)
        val retry = retryable && entry.attemptCount < entry.submission.task.maximumAttempts
        entry.state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed
        entry.submission = entry.submission.copy(
            task = entry.submission.task.copy(
                state = entry.state,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = errorMessage
            )
        )
        entry.lease = null
        updateWorkerTasks(lease.workerId, nowEpochMs)
    }

    override suspend fun reapExpiredWorkersAndLeases(
        workerTimeoutMs: Long,
        nowEpochMs: Long
    ): DistributedReapResult = mutex.withLock {
        require(workerTimeoutMs > 0L)
        val offline = workers.values.filter {
            nowEpochMs - it.lastHeartbeatEpochMs >= workerTimeoutMs &&
                it.availability != ProductionWorkerAvailability.Offline
        }
        offline.forEach { snapshot ->
            workers[snapshot.registration.workerId] = snapshot.copy(
                availability = ProductionWorkerAvailability.Offline,
                currentTaskIds = emptySet(),
                detail = "Worker heartbeat expired"
            )
        }
        val released = releaseExpiredTaskLeases(nowEpochMs)
        DistributedReapResult(
            offlineWorkerIds = offline.map { it.registration.workerId },
            releasedTaskIds = released
        )
    }

    override suspend fun enqueueOutbox(event: ProductionOutboxEvent) = mutex.withLock {
        val existingId = outboxIdempotency[event.idempotencyKey]
        require(existingId == null || existingId == event.id) {
            "Outbox idempotency key already belongs to $existingId"
        }
        if (event.id !in outbox) {
            outbox[event.id] = OutboxEntry(event = event)
            outboxIdempotency[event.idempotencyKey] = event.id
        }
    }

    override suspend fun reserveOutboxBatch(
        destination: ProductionOutboxDestination,
        dispatcherId: String,
        maximumBatchSize: Int,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): List<ProductionOutboxLease> = mutex.withLock {
        require(dispatcherId.isNotBlank())
        require(maximumBatchSize > 0)
        require(leaseDurationMs > 0L)
        releaseExpiredOutboxLeases(nowEpochMs)
        outbox.values
            .filter {
                it.event.destination == destination &&
                    it.event.state == ProductionOutboxState.Pending &&
                    it.event.availableAtEpochMs <= nowEpochMs
            }
            .sortedWith(compareBy<OutboxEntry> { it.event.createdAtEpochMs }.thenBy { it.event.id })
            .take(maximumBatchSize)
            .map { entry ->
                val token = entry.fencingToken + 1L
                val updatedEvent = entry.event.copy(
                    state = ProductionOutboxState.Reserved,
                    leaseOwner = dispatcherId,
                    leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs
                )
                val lease = ProductionOutboxLease(
                    event = updatedEvent,
                    dispatcherId = dispatcherId,
                    fencingToken = token,
                    reservedAtEpochMs = nowEpochMs,
                    leaseExpiresAtEpochMs = nowEpochMs + leaseDurationMs
                )
                entry.event = updatedEvent
                entry.fencingToken = token
                entry.lease = lease
                lease
            }
    }

    override suspend fun markOutboxDelivered(
        lease: ProductionOutboxLease,
        remoteReference: String?,
        nowEpochMs: Long
    ) = mutex.withLock {
        val entry = requireCurrentOutboxLease(lease)
        entry.event = entry.event.copy(
            state = ProductionOutboxState.Delivered,
            leaseOwner = null,
            leaseExpiresAtEpochMs = null,
            deliveredAtEpochMs = nowEpochMs,
            lastError = remoteReference?.let { "remoteReference=$it" }
        )
        entry.lease = null
    }

    override suspend fun markOutboxFailed(
        lease: ProductionOutboxLease,
        errorMessage: String,
        retryDelayMs: Long,
        nowEpochMs: Long
    ) = mutex.withLock {
        require(errorMessage.isNotBlank())
        require(retryDelayMs >= 0L)
        val entry = requireCurrentOutboxLease(lease)
        val attempts = entry.event.attemptCount + 1
        val deadLetter = attempts >= entry.event.maximumAttempts
        entry.event = entry.event.copy(
            state = if (deadLetter) ProductionOutboxState.DeadLetter else ProductionOutboxState.Pending,
            attemptCount = attempts,
            availableAtEpochMs = nowEpochMs + retryDelayMs,
            leaseOwner = null,
            leaseExpiresAtEpochMs = null,
            lastError = errorMessage
        )
        entry.lease = null
    }

    override suspend fun listOutboxEvents(
        destination: ProductionOutboxDestination?,
        state: ProductionOutboxState?
    ): List<ProductionOutboxEvent> = mutex.withLock {
        outbox.values.map { it.event }
            .filter { destination == null || it.destination == destination }
            .filter { state == null || it.state == state }
            .sortedBy { it.createdAtEpochMs }
    }

    private fun requireCurrentLease(lease: DistributedTaskLease): TaskEntry {
        val entry = tasks[lease.task.id] ?: error("Distributed task was not found: ${lease.task.id}")
        val current = entry.lease ?: error("Task is not currently leased: ${lease.task.id}")
        require(current.workerId == lease.workerId) { "Task lease belongs to ${current.workerId}" }
        require(current.fencingToken == lease.fencingToken) {
            "Stale task fencing token: expected=${current.fencingToken}, actual=${lease.fencingToken}"
        }
        return entry
    }

    private fun requireCurrentOutboxLease(lease: ProductionOutboxLease): OutboxEntry {
        val entry = outbox[lease.event.id] ?: error("Outbox event was not found: ${lease.event.id}")
        val current = entry.lease ?: error("Outbox event is not currently leased: ${lease.event.id}")
        require(current.dispatcherId == lease.dispatcherId)
        require(current.fencingToken == lease.fencingToken) {
            "Stale outbox fencing token: expected=${current.fencingToken}, actual=${lease.fencingToken}"
        }
        return entry
    }

    private fun releaseExpiredTaskLeases(nowEpochMs: Long): List<String> {
        val released = mutableListOf<String>()
        tasks.values.forEach { entry ->
            val lease = entry.lease ?: return@forEach
            if (lease.leaseExpiresAtEpochMs > nowEpochMs) return@forEach
            val retry = entry.attemptCount < entry.submission.task.maximumAttempts
            entry.state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed
            entry.submission = entry.submission.copy(
                task = entry.submission.task.copy(
                    state = entry.state,
                    leaseOwner = null,
                    leaseExpiresAtEpochMs = null,
                    lastError = "Worker lease expired"
                )
            )
            entry.lease = null
            released += entry.submission.task.id
            updateWorkerTasks(lease.workerId, nowEpochMs)
        }
        return released
    }

    private fun releaseExpiredOutboxLeases(nowEpochMs: Long) {
        outbox.values.forEach { entry ->
            val lease = entry.lease ?: return@forEach
            if (lease.leaseExpiresAtEpochMs > nowEpochMs) return@forEach
            entry.event = entry.event.copy(
                state = ProductionOutboxState.Pending,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = "Dispatcher lease expired"
            )
            entry.lease = null
        }
    }

    private fun updateWorkerTasks(workerId: String, nowEpochMs: Long) {
        val worker = workers[workerId] ?: return
        val currentTaskIds = tasks.values.mapNotNull { entry ->
            entry.lease?.takeIf { it.workerId == workerId }?.task?.id
        }.toSet()
        workers[workerId] = worker.copy(
            availability = if (currentTaskIds.isEmpty()) {
                ProductionWorkerAvailability.Ready
            } else {
                ProductionWorkerAvailability.Busy
            },
            lastHeartbeatEpochMs = maxOf(worker.lastHeartbeatEpochMs, nowEpochMs),
            currentTaskIds = currentTaskIds,
            detail = if (currentTaskIds.isEmpty()) "Worker is ready" else "Worker owns ${currentTaskIds.size} task lease(s)"
        )
    }

    private data class TaskEntry(
        var submission: DistributedTaskSubmission,
        var state: ProductionTaskState = submission.task.state,
        var attemptCount: Int = submission.task.attemptCount,
        var fencingToken: Long = 0L,
        var lease: DistributedTaskLease? = null,
        var resultId: String? = null
    )

    private data class OutboxEntry(
        var event: ProductionOutboxEvent,
        var fencingToken: Long = 0L,
        var lease: ProductionOutboxLease? = null
    )
}

class UnavailableDistributedProductionCoordinator(
    private val reason: String = "PostgreSQL production coordination is not configured"
) : DistributedProductionCoordinator {
    override val status = DistributedCoordinatorStatus(
        backend = DistributedCoordinatorBackend.Unavailable,
        configured = false,
        healthy = false,
        detail = reason
    )

    override suspend fun initialize() = Unit
    override suspend fun findWorker(workerId: String): ProductionWorkerSnapshot? = null
    override suspend fun listWorkers(): List<ProductionWorkerSnapshot> = emptyList()
    override suspend fun listOutboxEvents(
        destination: ProductionOutboxDestination?,
        state: ProductionOutboxState?
    ): List<ProductionOutboxEvent> = emptyList()

    override suspend fun registerWorker(registration: ProductionWorkerRegistration): ProductionWorkerSnapshot = unavailable()
    override suspend fun heartbeat(
        workerId: String,
        availability: ProductionWorkerAvailability,
        currentTaskIds: Set<String>,
        detail: String,
        nowEpochMs: Long
    ): ProductionWorkerSnapshot = unavailable()
    override suspend fun enqueueTasks(submissions: List<DistributedTaskSubmission>) = unavailable<Unit>()
    override suspend fun reserveNextTask(workerId: String, leaseDurationMs: Long, nowEpochMs: Long): DistributedTaskLease? = unavailable()
    override suspend fun renewTaskLease(
        lease: DistributedTaskLease,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease = unavailable()
    override suspend fun completeTaskLease(
        lease: DistributedTaskLease,
        resultId: String,
        passed: Boolean,
        nowEpochMs: Long
    ) = unavailable<Unit>()
    override suspend fun failTaskLease(
        lease: DistributedTaskLease,
        errorMessage: String,
        retryable: Boolean,
        nowEpochMs: Long
    ) = unavailable<Unit>()
    override suspend fun reapExpiredWorkersAndLeases(workerTimeoutMs: Long, nowEpochMs: Long): DistributedReapResult = unavailable()
    override suspend fun enqueueOutbox(event: ProductionOutboxEvent) = unavailable<Unit>()
    override suspend fun reserveOutboxBatch(
        destination: ProductionOutboxDestination,
        dispatcherId: String,
        maximumBatchSize: Int,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): List<ProductionOutboxLease> = unavailable()
    override suspend fun markOutboxDelivered(
        lease: ProductionOutboxLease,
        remoteReference: String?,
        nowEpochMs: Long
    ) = unavailable<Unit>()
    override suspend fun markOutboxFailed(
        lease: ProductionOutboxLease,
        errorMessage: String,
        retryDelayMs: Long,
        nowEpochMs: Long
    ) = unavailable<Unit>()

    private fun <T> unavailable(): T = error(reason)
}
