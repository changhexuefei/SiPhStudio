package org.jason.siph.domain.production

class StrictDistributedProductionCoordinator(
    private val delegate: DistributedProductionCoordinator
) : DistributedProductionCoordinator by delegate {

    override suspend fun renewTaskLease(
        lease: DistributedTaskLease,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease {
        requireLeaseActive(lease.leaseExpiresAtEpochMs, nowEpochMs, "task")
        return delegate.renewTaskLease(lease, leaseDurationMs, nowEpochMs)
    }

    override suspend fun completeTaskLease(
        lease: DistributedTaskLease,
        resultId: String,
        passed: Boolean,
        nowEpochMs: Long
    ) {
        requireLeaseActive(lease.leaseExpiresAtEpochMs, nowEpochMs, "task")
        delegate.completeTaskLease(lease, resultId, passed, nowEpochMs)
    }

    override suspend fun failTaskLease(
        lease: DistributedTaskLease,
        errorMessage: String,
        retryable: Boolean,
        nowEpochMs: Long
    ) {
        requireLeaseActive(lease.leaseExpiresAtEpochMs, nowEpochMs, "task")
        delegate.failTaskLease(lease, errorMessage, retryable, nowEpochMs)
    }

    override suspend fun markOutboxDelivered(
        lease: ProductionOutboxLease,
        remoteReference: String?,
        nowEpochMs: Long
    ) {
        requireLeaseActive(lease.leaseExpiresAtEpochMs, nowEpochMs, "outbox")
        delegate.markOutboxDelivered(lease, remoteReference, nowEpochMs)
    }

    override suspend fun markOutboxFailed(
        lease: ProductionOutboxLease,
        errorMessage: String,
        retryDelayMs: Long,
        nowEpochMs: Long
    ) {
        requireLeaseActive(lease.leaseExpiresAtEpochMs, nowEpochMs, "outbox")
        delegate.markOutboxFailed(lease, errorMessage, retryDelayMs, nowEpochMs)
    }

    private fun requireLeaseActive(expiresAtEpochMs: Long, nowEpochMs: Long, kind: String) {
        require(nowEpochMs < expiresAtEpochMs) {
            "Expired $kind lease rejected: expiresAt=$expiresAtEpochMs, now=$nowEpochMs"
        }
    }
}
