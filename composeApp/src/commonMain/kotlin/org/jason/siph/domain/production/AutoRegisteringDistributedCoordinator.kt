package org.jason.siph.domain.production

class AutoRegisteringDistributedProductionCoordinator(
    private val delegate: DistributedProductionCoordinator,
    private val registrationFactory: (workerId: String, nowEpochMs: Long) -> ProductionWorkerRegistration?
) : DistributedProductionCoordinator by delegate {

    override suspend fun reserveNextTask(
        workerId: String,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease? {
        if (delegate.findWorker(workerId) == null) {
            val registration = registrationFactory(workerId, nowEpochMs)
                ?: error("Worker is not registered and no verified registration is available: $workerId")
            delegate.registerWorker(registration)
        }
        return delegate.reserveNextTask(workerId, leaseDurationMs, nowEpochMs)
    }
}
