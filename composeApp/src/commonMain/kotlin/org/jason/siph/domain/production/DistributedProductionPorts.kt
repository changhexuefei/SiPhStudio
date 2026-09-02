package org.jason.siph.domain.production

interface DistributedProductionCoordinator {
    val status: DistributedCoordinatorStatus

    suspend fun initialize()

    suspend fun registerWorker(registration: ProductionWorkerRegistration): ProductionWorkerSnapshot

    suspend fun heartbeat(
        workerId: String,
        availability: ProductionWorkerAvailability,
        currentTaskIds: Set<String>,
        detail: String,
        nowEpochMs: Long
    ): ProductionWorkerSnapshot

    suspend fun findWorker(workerId: String): ProductionWorkerSnapshot?

    suspend fun listWorkers(): List<ProductionWorkerSnapshot>

    suspend fun enqueueTasks(submissions: List<DistributedTaskSubmission>)

    suspend fun reserveNextTask(
        workerId: String,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease?

    suspend fun renewTaskLease(
        lease: DistributedTaskLease,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease

    suspend fun completeTaskLease(
        lease: DistributedTaskLease,
        resultId: String,
        passed: Boolean,
        nowEpochMs: Long
    )

    suspend fun failTaskLease(
        lease: DistributedTaskLease,
        errorMessage: String,
        retryable: Boolean,
        nowEpochMs: Long
    )

    suspend fun reapExpiredWorkersAndLeases(
        workerTimeoutMs: Long,
        nowEpochMs: Long
    ): DistributedReapResult

    suspend fun enqueueOutbox(event: ProductionOutboxEvent)

    suspend fun reserveOutboxBatch(
        destination: ProductionOutboxDestination,
        dispatcherId: String,
        maximumBatchSize: Int,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): List<ProductionOutboxLease>

    suspend fun markOutboxDelivered(
        lease: ProductionOutboxLease,
        remoteReference: String?,
        nowEpochMs: Long
    )

    suspend fun markOutboxFailed(
        lease: ProductionOutboxLease,
        errorMessage: String,
        retryDelayMs: Long,
        nowEpochMs: Long
    )

    suspend fun listOutboxEvents(
        destination: ProductionOutboxDestination? = null,
        state: ProductionOutboxState? = null
    ): List<ProductionOutboxEvent>
}

interface MesGateway {
    suspend fun submit(event: ProductionOutboxEvent): MesSubmissionResult
}

interface RemoteAuditSink {
    suspend fun append(event: ProductionOutboxEvent): RemoteAuditReceipt
}

interface WormAuditArchive {
    suspend fun append(event: AuditEvent)
    suspend fun latest(): AuditEvent?
    suspend fun list(limit: Int = 500): List<AuditEvent>
}
