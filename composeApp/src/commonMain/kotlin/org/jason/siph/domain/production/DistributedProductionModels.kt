package org.jason.siph.domain.production

import kotlinx.serialization.Serializable

@Serializable
enum class DistributedCoordinatorBackend {
    InMemory,
    PostgreSql,
    Unavailable
}

@Serializable
data class DistributedCoordinatorStatus(
    val backend: DistributedCoordinatorBackend,
    val configured: Boolean,
    val healthy: Boolean,
    val detail: String
)

@Serializable
enum class ProductionWorkerAvailability {
    Starting,
    Ready,
    Busy,
    Draining,
    Offline,
    Error
}

@Serializable
data class ProductionWorkerRegistration(
    val workerId: String,
    val workstationId: String,
    val equipmentGroupId: String,
    val capabilities: Set<String>,
    val softwareVersion: String,
    val maximumParallelTasks: Int = 1,
    val registeredAtEpochMs: Long
) {
    init {
        require(workerId.isNotBlank())
        require(workstationId.isNotBlank())
        require(equipmentGroupId.isNotBlank())
        require(capabilities.isNotEmpty() && capabilities.all(String::isNotBlank))
        require(softwareVersion.isNotBlank())
        require(maximumParallelTasks > 0)
    }
}

@Serializable
data class ProductionWorkerSnapshot(
    val registration: ProductionWorkerRegistration,
    val availability: ProductionWorkerAvailability,
    val lastHeartbeatEpochMs: Long,
    val currentTaskIds: Set<String> = emptySet(),
    val detail: String = "Worker is ready"
) {
    init {
        require(currentTaskIds.all(String::isNotBlank))
        require(detail.isNotBlank())
    }
}

@Serializable
data class DistributedTaskSubmission(
    val task: ProductionTask,
    val requiredCapabilities: Set<String>,
    val submittedAtEpochMs: Long
) {
    init {
        require(requiredCapabilities.all(String::isNotBlank))
    }
}

@Serializable
data class DistributedTaskLease(
    val task: ProductionTask,
    val attemptId: String,
    val workerId: String,
    val fencingToken: Long,
    val reservedAtEpochMs: Long,
    val leaseExpiresAtEpochMs: Long
) {
    init {
        require(attemptId.isNotBlank())
        require(workerId.isNotBlank())
        require(fencingToken > 0L)
        require(leaseExpiresAtEpochMs > reservedAtEpochMs)
    }

    fun asReservation(): ReservedProductionTask = ReservedProductionTask(
        task = task,
        attemptId = attemptId,
        workerId = workerId,
        reservedAtEpochMs = reservedAtEpochMs,
        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs
    )
}

@Serializable
data class DistributedReapResult(
    val offlineWorkerIds: List<String> = emptyList(),
    val releasedTaskIds: List<String> = emptyList()
)

@Serializable
enum class ProductionOutboxState {
    Pending,
    Reserved,
    Delivered,
    DeadLetter
}

@Serializable
enum class ProductionOutboxDestination {
    Mes,
    AuditServer
}

@Serializable
data class ProductionOutboxEvent(
    val id: String,
    val destination: ProductionOutboxDestination,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val availableAtEpochMs: Long = createdAtEpochMs,
    val attemptCount: Int = 0,
    val maximumAttempts: Int = 10,
    val state: ProductionOutboxState = ProductionOutboxState.Pending,
    val leaseOwner: String? = null,
    val leaseExpiresAtEpochMs: Long? = null,
    val lastError: String? = null,
    val deliveredAtEpochMs: Long? = null
) {
    init {
        require(id.isNotBlank())
        require(eventType.isNotBlank())
        require(aggregateType.isNotBlank())
        require(aggregateId.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(payloadJson.isNotBlank())
        require(attemptCount >= 0)
        require(maximumAttempts > 0)
        require(leaseOwner == null || leaseOwner.isNotBlank())
    }
}

@Serializable
data class ProductionOutboxLease(
    val event: ProductionOutboxEvent,
    val dispatcherId: String,
    val fencingToken: Long,
    val reservedAtEpochMs: Long,
    val leaseExpiresAtEpochMs: Long
) {
    init {
        require(dispatcherId.isNotBlank())
        require(fencingToken > 0L)
        require(leaseExpiresAtEpochMs > reservedAtEpochMs)
    }
}

@Serializable
data class MesSubmissionResult(
    val accepted: Boolean,
    val remoteReference: String? = null,
    val message: String
) {
    init {
        require(message.isNotBlank())
    }
}

@Serializable
data class RemoteAuditReceipt(
    val accepted: Boolean,
    val remoteReference: String? = null,
    val message: String
) {
    init {
        require(message.isNotBlank())
    }
}
