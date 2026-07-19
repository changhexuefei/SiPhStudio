package org.jason.siph.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.DistributedCoordinatorBackend
import org.jason.siph.domain.production.DistributedCoordinatorStatus
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.DistributedReapResult
import org.jason.siph.domain.production.DistributedTaskLease
import org.jason.siph.domain.production.DistributedTaskSubmission
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionOutboxLease
import org.jason.siph.domain.production.ProductionOutboxState
import org.jason.siph.domain.production.ProductionTask
import org.jason.siph.domain.production.ProductionTaskState
import org.jason.siph.domain.production.ProductionWorkerAvailability
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.ProductionWorkerSnapshot
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

class JdbcDistributedProductionCoordinator(
    private val dataSource: DataSource,
    private val backendDetail: String = "PostgreSQL distributed production coordinator",
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
) : DistributedProductionCoordinator {
    override val status = DistributedCoordinatorStatus(
        backend = DistributedCoordinatorBackend.PostgreSql,
        configured = true,
        healthy = true,
        detail = backendDetail
    )

    private val initializeMutex = Mutex()
    @Volatile
    private var initialized = false

    override suspend fun initialize() {
        if (initialized) return
        initializeMutex.withLock {
            if (initialized) return
            io {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        schemaStatements.forEach { sql ->
                            connection.createStatement().use { it.execute(sql) }
                        }
                        connection.commit()
                    } catch (error: Throwable) {
                        connection.rollback()
                        throw error
                    }
                }
            }
            initialized = true
        }
    }

    override suspend fun registerWorker(
        registration: ProductionWorkerRegistration
    ): ProductionWorkerSnapshot = transaction { connection ->
        val updated = connection.prepareStatement(
            """
            UPDATE siph_worker
               SET workstation_id=?, equipment_group_id=?, software_version=?, max_parallel_tasks=?,
                   availability=?, last_heartbeat_ms=?, detail=?
             WHERE worker_id=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, registration.workstationId)
            statement.setString(2, registration.equipmentGroupId)
            statement.setString(3, registration.softwareVersion)
            statement.setInt(4, registration.maximumParallelTasks)
            statement.setString(5, ProductionWorkerAvailability.Ready.name)
            statement.setLong(6, registration.registeredAtEpochMs)
            statement.setString(7, "Worker registered")
            statement.setString(8, registration.workerId)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                """
                INSERT INTO siph_worker(
                    worker_id, workstation_id, equipment_group_id, software_version,
                    max_parallel_tasks, availability, last_heartbeat_ms, detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, registration.workerId)
                statement.setString(2, registration.workstationId)
                statement.setString(3, registration.equipmentGroupId)
                statement.setString(4, registration.softwareVersion)
                statement.setInt(5, registration.maximumParallelTasks)
                statement.setString(6, ProductionWorkerAvailability.Ready.name)
                statement.setLong(7, registration.registeredAtEpochMs)
                statement.setString(8, "Worker registered")
                statement.executeUpdate()
            }
        }
        connection.prepareStatement("DELETE FROM siph_worker_capability WHERE worker_id=?").use {
            it.setString(1, registration.workerId)
            it.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO siph_worker_capability(worker_id, capability) VALUES (?, ?)"
        ).use { statement ->
            registration.capabilities.sorted().forEach { capability ->
                statement.setString(1, registration.workerId)
                statement.setString(2, capability)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        readWorker(connection, registration.workerId) ?: error("Worker registration was not persisted")
    }

    override suspend fun heartbeat(
        workerId: String,
        availability: ProductionWorkerAvailability,
        currentTaskIds: Set<String>,
        detail: String,
        nowEpochMs: Long
    ): ProductionWorkerSnapshot = transaction { connection ->
        val updated = connection.prepareStatement(
            "UPDATE siph_worker SET availability=?, last_heartbeat_ms=?, detail=? WHERE worker_id=?"
        ).use { statement ->
            statement.setString(1, availability.name)
            statement.setLong(2, nowEpochMs)
            statement.setString(3, detail)
            statement.setString(4, workerId)
            statement.executeUpdate()
        }
        require(updated == 1) { "Worker is not registered: $workerId" }
        val actualTasks = activeTaskIds(connection, workerId)
        require(actualTasks == currentTaskIds || currentTaskIds.isEmpty()) {
            "Worker heartbeat task set differs from database leases: reported=$currentTaskIds, actual=$actualTasks"
        }
        readWorker(connection, workerId) ?: error("Worker disappeared after heartbeat")
    }

    override suspend fun findWorker(workerId: String): ProductionWorkerSnapshot? = read { connection ->
        readWorker(connection, workerId)
    }

    override suspend fun listWorkers(): List<ProductionWorkerSnapshot> = read { connection ->
        connection.prepareStatement("SELECT worker_id FROM siph_worker ORDER BY worker_id").use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        readWorker(connection, result.getString(1))?.let(::add)
                    }
                }
            }
        }
    }

    override suspend fun enqueueTasks(submissions: List<DistributedTaskSubmission>) = transaction { connection ->
        submissions.forEach { submission ->
            val task = submission.task
            val existing = connection.prepareStatement(
                "SELECT task_id FROM siph_task_queue WHERE idempotency_key=?"
            ).use { statement ->
                statement.setString(1, task.idempotencyKey)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
            require(existing == null || existing == task.id) {
                "Task idempotency key already belongs to $existing"
            }
            if (existing == null) {
                connection.prepareStatement(
                    """
                    INSERT INTO siph_task_queue(
                        task_id, lot_id, idempotency_key, priority, state, task_payload,
                        attempt_count, max_attempts, lease_owner, lease_expires_ms,
                        fencing_token, result_id, submitted_ms, updated_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, NULL, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, task.id)
                    statement.setString(2, task.lotId)
                    statement.setString(3, task.idempotencyKey)
                    statement.setInt(4, task.priority)
                    statement.setString(5, ProductionTaskState.Pending.name)
                    statement.setString(6, json.encodeToString(task.copy(state = ProductionTaskState.Pending)))
                    statement.setInt(7, task.attemptCount)
                    statement.setInt(8, task.maximumAttempts)
                    statement.setLong(9, submission.submittedAtEpochMs)
                    statement.setLong(10, submission.submittedAtEpochMs)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO siph_task_capability(task_id, capability) VALUES (?, ?)"
                ).use { statement ->
                    submission.requiredCapabilities.sorted().forEach { capability ->
                        statement.setString(1, task.id)
                        statement.setString(2, capability)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override suspend fun reserveNextTask(
        workerId: String,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease? = transaction { connection ->
        require(leaseDurationMs > 0L)
        releaseExpiredTaskLeases(connection, nowEpochMs)
        val worker = readWorker(connection, workerId) ?: error("Worker is not registered: $workerId")
        require(worker.availability !in setOf(
            ProductionWorkerAvailability.Draining,
            ProductionWorkerAvailability.Offline,
            ProductionWorkerAvailability.Error
        )) { "Worker cannot reserve tasks while ${worker.availability}" }
        if (worker.currentTaskIds.size >= worker.registration.maximumParallelTasks) return@transaction null

        val row = connection.prepareStatement(
            """
            SELECT t.task_id, t.task_payload, t.attempt_count, t.fencing_token
              FROM siph_task_queue t
             WHERE t.state IN (?, ?)
               AND NOT EXISTS (
                    SELECT 1
                      FROM siph_task_capability tc
                     WHERE tc.task_id=t.task_id
                       AND NOT EXISTS (
                            SELECT 1
                              FROM siph_worker_capability wc
                             WHERE wc.worker_id=? AND wc.capability=tc.capability
                       )
               )
             ORDER BY t.priority DESC, t.submitted_ms ASC, t.task_id ASC
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ProductionTaskState.Pending.name)
            statement.setString(2, ProductionTaskState.RetryPending.name)
            statement.setString(3, workerId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else CandidateTask(
                    taskId = result.getString("task_id"),
                    task = json.decodeFromString(result.getString("task_payload")),
                    attemptCount = result.getInt("attempt_count"),
                    fencingToken = result.getLong("fencing_token")
                )
            }
        } ?: return@transaction null

        val attempt = row.attemptCount + 1
        val token = row.fencingToken + 1L
        val expires = nowEpochMs + leaseDurationMs
        val task = row.task.copy(
            state = ProductionTaskState.Reserved,
            attemptCount = attempt,
            leaseOwner = workerId,
            leaseExpiresAtEpochMs = expires,
            lastError = null
        )
        connection.prepareStatement(
            """
            UPDATE siph_task_queue
               SET state=?, task_payload=?, attempt_count=?, lease_owner=?, lease_expires_ms=?,
                   fencing_token=?, updated_ms=?
             WHERE task_id=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ProductionTaskState.Reserved.name)
            statement.setString(2, json.encodeToString(task))
            statement.setInt(3, attempt)
            statement.setString(4, workerId)
            statement.setLong(5, expires)
            statement.setLong(6, token)
            statement.setLong(7, nowEpochMs)
            statement.setString(8, row.taskId)
            require(statement.executeUpdate() == 1)
        }
        connection.prepareStatement(
            "UPDATE siph_worker SET availability=?, last_heartbeat_ms=?, detail=? WHERE worker_id=?"
        ).use { statement ->
            statement.setString(1, ProductionWorkerAvailability.Busy.name)
            statement.setLong(2, nowEpochMs)
            statement.setString(3, "Worker owns a distributed task lease")
            statement.setString(4, workerId)
            statement.executeUpdate()
        }
        DistributedTaskLease(
            task = task,
            attemptId = "${task.id}-attempt-$attempt",
            workerId = workerId,
            fencingToken = token,
            reservedAtEpochMs = nowEpochMs,
            leaseExpiresAtEpochMs = expires
        )
    }

    override suspend fun renewTaskLease(
        lease: DistributedTaskLease,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): DistributedTaskLease = transaction { connection ->
        require(leaseDurationMs > 0L)
        val expires = nowEpochMs + leaseDurationMs
        val updatedTask = lease.task.copy(leaseExpiresAtEpochMs = expires)
        val updated = connection.prepareStatement(
            """
            UPDATE siph_task_queue
               SET lease_expires_ms=?, task_payload=?, updated_ms=?
             WHERE task_id=? AND state=? AND lease_owner=? AND fencing_token=? AND lease_expires_ms>?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, expires)
            statement.setString(2, json.encodeToString(updatedTask))
            statement.setLong(3, nowEpochMs)
            statement.setString(4, lease.task.id)
            statement.setString(5, ProductionTaskState.Reserved.name)
            statement.setString(6, lease.workerId)
            statement.setLong(7, lease.fencingToken)
            statement.setLong(8, nowEpochMs)
            statement.executeUpdate()
        }
        require(updated == 1) { "Task lease is stale, expired, or owned by another worker" }
        lease.copy(task = updatedTask, leaseExpiresAtEpochMs = expires)
    }

    override suspend fun completeTaskLease(
        lease: DistributedTaskLease,
        resultId: String,
        passed: Boolean,
        nowEpochMs: Long
    ) = transaction { connection ->
        require(resultId.isNotBlank())
        val state = if (passed) ProductionTaskState.Passed else ProductionTaskState.Failed
        val task = lease.task.copy(
            state = state,
            leaseOwner = null,
            leaseExpiresAtEpochMs = null
        )
        val updated = connection.prepareStatement(
            """
            UPDATE siph_task_queue
               SET state=?, task_payload=?, lease_owner=NULL, lease_expires_ms=NULL,
                   result_id=?, updated_ms=?
             WHERE task_id=? AND state=? AND lease_owner=? AND fencing_token=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, json.encodeToString(task))
            statement.setString(3, resultId)
            statement.setLong(4, nowEpochMs)
            statement.setString(5, lease.task.id)
            statement.setString(6, ProductionTaskState.Reserved.name)
            statement.setString(7, lease.workerId)
            statement.setLong(8, lease.fencingToken)
            statement.executeUpdate()
        }
        require(updated == 1) { "Stale task completion rejected by fencing token" }
        updateWorkerAvailability(connection, lease.workerId, nowEpochMs)
    }

    override suspend fun failTaskLease(
        lease: DistributedTaskLease,
        errorMessage: String,
        retryable: Boolean,
        nowEpochMs: Long
    ) = transaction { connection ->
        require(errorMessage.isNotBlank())
        val retry = retryable && lease.task.attemptCount < lease.task.maximumAttempts
        val state = if (retry) ProductionTaskState.RetryPending else ProductionTaskState.Failed
        val task = lease.task.copy(
            state = state,
            leaseOwner = null,
            leaseExpiresAtEpochMs = null,
            lastError = errorMessage
        )
        val updated = connection.prepareStatement(
            """
            UPDATE siph_task_queue
               SET state=?, task_payload=?, lease_owner=NULL, lease_expires_ms=NULL, updated_ms=?
             WHERE task_id=? AND state=? AND lease_owner=? AND fencing_token=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, json.encodeToString(task))
            statement.setLong(3, nowEpochMs)
            statement.setString(4, lease.task.id)
            statement.setString(5, ProductionTaskState.Reserved.name)
            statement.setString(6, lease.workerId)
            statement.setLong(7, lease.fencingToken)
            statement.executeUpdate()
        }
        require(updated == 1) { "Stale task failure rejected by fencing token" }
        updateWorkerAvailability(connection, lease.workerId, nowEpochMs)
    }

    override suspend fun reapExpiredWorkersAndLeases(
        workerTimeoutMs: Long,
        nowEpochMs: Long
    ): DistributedReapResult = transaction { connection ->
        require(workerTimeoutMs > 0L)
        val threshold = nowEpochMs - workerTimeoutMs
        val offline = connection.prepareStatement(
            "SELECT worker_id FROM siph_worker WHERE last_heartbeat_ms<=? AND availability<>?"
        ).use { statement ->
            statement.setLong(1, threshold)
            statement.setString(2, ProductionWorkerAvailability.Offline.name)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
        connection.prepareStatement(
            "UPDATE siph_worker SET availability=?, detail=? WHERE last_heartbeat_ms<=? AND availability<>?"
        ).use { statement ->
            statement.setString(1, ProductionWorkerAvailability.Offline.name)
            statement.setString(2, "Worker heartbeat expired")
            statement.setLong(3, threshold)
            statement.setString(4, ProductionWorkerAvailability.Offline.name)
            statement.executeUpdate()
        }
        val released = expiredTaskIds(connection, nowEpochMs)
        releaseExpiredTaskLeases(connection, nowEpochMs)
        DistributedReapResult(offlineWorkerIds = offline, releasedTaskIds = released)
    }

    override suspend fun enqueueOutbox(event: ProductionOutboxEvent) = transaction { connection ->
        val existing = connection.prepareStatement(
            "SELECT event_id FROM siph_outbox WHERE idempotency_key=?"
        ).use { statement ->
            statement.setString(1, event.idempotencyKey)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        require(existing == null || existing == event.id) {
            "Outbox idempotency key already belongs to $existing"
        }
        if (existing == null) {
            connection.prepareStatement(
                """
                INSERT INTO siph_outbox(
                    event_id, destination, event_type, aggregate_type, aggregate_id,
                    idempotency_key, payload_json, created_ms, available_ms, attempt_count,
                    max_attempts, state, lease_owner, lease_expires_ms, fencing_token,
                    last_error, delivered_ms, remote_reference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?, ?, NULL)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, event.id)
                statement.setString(2, event.destination.name)
                statement.setString(3, event.eventType)
                statement.setString(4, event.aggregateType)
                statement.setString(5, event.aggregateId)
                statement.setString(6, event.idempotencyKey)
                statement.setString(7, event.payloadJson)
                statement.setLong(8, event.createdAtEpochMs)
                statement.setLong(9, event.availableAtEpochMs)
                statement.setInt(10, event.attemptCount)
                statement.setInt(11, event.maximumAttempts)
                statement.setString(12, event.state.name)
                statement.setString(13, event.lastError)
                event.deliveredAtEpochMs.setNullableLong(statement, 14)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun reserveOutboxBatch(
        destination: ProductionOutboxDestination,
        dispatcherId: String,
        maximumBatchSize: Int,
        leaseDurationMs: Long,
        nowEpochMs: Long
    ): List<ProductionOutboxLease> = transaction { connection ->
        require(dispatcherId.isNotBlank())
        require(maximumBatchSize > 0)
        require(leaseDurationMs > 0L)
        releaseExpiredOutboxLeases(connection, nowEpochMs)
        val rows = connection.prepareStatement(
            """
            SELECT * FROM siph_outbox
             WHERE destination=? AND state=? AND available_ms<=?
             ORDER BY created_ms ASC, event_id ASC
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, destination.name)
            statement.setString(2, ProductionOutboxState.Pending.name)
            statement.setLong(3, nowEpochMs)
            statement.setInt(4, maximumBatchSize)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(readOutboxRow(result)) }
            }
        }
        rows.map { row ->
            val token = row.fencingToken + 1L
            val expires = nowEpochMs + leaseDurationMs
            connection.prepareStatement(
                """
                UPDATE siph_outbox
                   SET state=?, lease_owner=?, lease_expires_ms=?, fencing_token=?
                 WHERE event_id=?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ProductionOutboxState.Reserved.name)
                statement.setString(2, dispatcherId)
                statement.setLong(3, expires)
                statement.setLong(4, token)
                statement.setString(5, row.event.id)
                require(statement.executeUpdate() == 1)
            }
            ProductionOutboxLease(
                event = row.event.copy(
                    state = ProductionOutboxState.Reserved,
                    leaseOwner = dispatcherId,
                    leaseExpiresAtEpochMs = expires
                ),
                dispatcherId = dispatcherId,
                fencingToken = token,
                reservedAtEpochMs = nowEpochMs,
                leaseExpiresAtEpochMs = expires
            )
        }
    }

    override suspend fun markOutboxDelivered(
        lease: ProductionOutboxLease,
        remoteReference: String?,
        nowEpochMs: Long
    ) = transaction { connection ->
        val updated = connection.prepareStatement(
            """
            UPDATE siph_outbox
               SET state=?, lease_owner=NULL, lease_expires_ms=NULL, delivered_ms=?, remote_reference=?
             WHERE event_id=? AND state=? AND lease_owner=? AND fencing_token=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ProductionOutboxState.Delivered.name)
            statement.setLong(2, nowEpochMs)
            statement.setString(3, remoteReference)
            statement.setString(4, lease.event.id)
            statement.setString(5, ProductionOutboxState.Reserved.name)
            statement.setString(6, lease.dispatcherId)
            statement.setLong(7, lease.fencingToken)
            statement.executeUpdate()
        }
        require(updated == 1) { "Stale outbox delivery rejected by fencing token" }
    }

    override suspend fun markOutboxFailed(
        lease: ProductionOutboxLease,
        errorMessage: String,
        retryDelayMs: Long,
        nowEpochMs: Long
    ) = transaction { connection ->
        require(errorMessage.isNotBlank())
        require(retryDelayMs >= 0L)
        val attempts = lease.event.attemptCount + 1
        val state = if (attempts >= lease.event.maximumAttempts) {
            ProductionOutboxState.DeadLetter
        } else {
            ProductionOutboxState.Pending
        }
        val updated = connection.prepareStatement(
            """
            UPDATE siph_outbox
               SET state=?, attempt_count=?, available_ms=?, lease_owner=NULL,
                   lease_expires_ms=NULL, last_error=?
             WHERE event_id=? AND state=? AND lease_owner=? AND fencing_token=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setInt(2, attempts)
            statement.setLong(3, nowEpochMs + retryDelayMs)
            statement.setString(4, errorMessage)
            statement.setString(5, lease.event.id)
            statement.setString(6, ProductionOutboxState.Reserved.name)
            statement.setString(7, lease.dispatcherId)
            statement.setLong(8, lease.fencingToken)
            statement.executeUpdate()
        }
        require(updated == 1) { "Stale outbox failure rejected by fencing token" }
    }

    override suspend fun listOutboxEvents(
        destination: ProductionOutboxDestination?,
        state: ProductionOutboxState?
    ): List<ProductionOutboxEvent> = read { connection ->
        val clauses = mutableListOf<String>()
        if (destination != null) clauses += "destination=?"
        if (state != null) clauses += "state=?"
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        connection.prepareStatement("SELECT * FROM siph_outbox$where ORDER BY created_ms, event_id").use { statement ->
            var index = 1
            if (destination != null) statement.setString(index++, destination.name)
            if (state != null) statement.setString(index, state.name)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(readOutboxRow(result).event) }
            }
        }
    }

    private suspend fun <T> read(block: (Connection) -> T): T {
        initialize()
        return io { dataSource.connection.use(block) }
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T {
        initialize()
        return io {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }
    }

    private fun readWorker(connection: Connection, workerId: String): ProductionWorkerSnapshot? {
        val row = connection.prepareStatement("SELECT * FROM siph_worker WHERE worker_id=?").use { statement ->
            statement.setString(1, workerId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else WorkerRow(
                    workerId = result.getString("worker_id"),
                    workstationId = result.getString("workstation_id"),
                    equipmentGroupId = result.getString("equipment_group_id"),
                    softwareVersion = result.getString("software_version"),
                    maximumParallelTasks = result.getInt("max_parallel_tasks"),
                    availability = ProductionWorkerAvailability.valueOf(result.getString("availability")),
                    lastHeartbeatEpochMs = result.getLong("last_heartbeat_ms"),
                    detail = result.getString("detail")
                )
            }
        } ?: return null
        val capabilities = connection.prepareStatement(
            "SELECT capability FROM siph_worker_capability WHERE worker_id=? ORDER BY capability"
        ).use { statement ->
            statement.setString(1, workerId)
            statement.executeQuery().use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
        return ProductionWorkerSnapshot(
            registration = ProductionWorkerRegistration(
                workerId = row.workerId,
                workstationId = row.workstationId,
                equipmentGroupId = row.equipmentGroupId,
                capabilities = capabilities,
                softwareVersion = row.softwareVersion,
                maximumParallelTasks = row.maximumParallelTasks,
                registeredAtEpochMs = row.lastHeartbeatEpochMs
            ),
            availability = row.availability,
            lastHeartbeatEpochMs = row.lastHeartbeatEpochMs,
            currentTaskIds = activeTaskIds(connection, workerId),
            detail = row.detail
        )
    }

    private fun activeTaskIds(connection: Connection, workerId: String): Set<String> =
        connection.prepareStatement(
            "SELECT task_id FROM siph_task_queue WHERE lease_owner=? AND state=? ORDER BY task_id"
        ).use { statement ->
            statement.setString(1, workerId)
            statement.setString(2, ProductionTaskState.Reserved.name)
            statement.executeQuery().use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }

    private fun updateWorkerAvailability(connection: Connection, workerId: String, nowEpochMs: Long) {
        val busy = activeTaskIds(connection, workerId).isNotEmpty()
        connection.prepareStatement(
            "UPDATE siph_worker SET availability=?, last_heartbeat_ms=?, detail=? WHERE worker_id=?"
        ).use { statement ->
            statement.setString(
                1,
                if (busy) ProductionWorkerAvailability.Busy.name else ProductionWorkerAvailability.Ready.name
            )
            statement.setLong(2, nowEpochMs)
            statement.setString(3, if (busy) "Worker owns distributed task leases" else "Worker is ready")
            statement.setString(4, workerId)
            statement.executeUpdate()
        }
    }

    private fun expiredTaskIds(connection: Connection, nowEpochMs: Long): List<String> =
        connection.prepareStatement(
            "SELECT task_id FROM siph_task_queue WHERE state=? AND lease_expires_ms<=?"
        ).use { statement ->
            statement.setString(1, ProductionTaskState.Reserved.name)
            statement.setLong(2, nowEpochMs)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }

    private fun releaseExpiredTaskLeases(connection: Connection, nowEpochMs: Long): Int {
        val expired = connection.prepareStatement(
            "SELECT task_id, task_payload, attempt_count, max_attempts FROM siph_task_queue WHERE state=? AND lease_expires_ms<=? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, ProductionTaskState.Reserved.name)
            statement.setLong(2, nowEpochMs)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ExpiredTask(
                                taskId = result.getString("task_id"),
                                task = json.decodeFromString(result.getString("task_payload")),
                                attemptCount = result.getInt("attempt_count"),
                                maximumAttempts = result.getInt("max_attempts")
                            )
                        )
                    }
                }
            }
        }
        expired.forEach { row ->
            val state = if (row.attemptCount < row.maximumAttempts) {
                ProductionTaskState.RetryPending
            } else {
                ProductionTaskState.Failed
            }
            val task = row.task.copy(
                state = state,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = "Worker lease expired"
            )
            connection.prepareStatement(
                "UPDATE siph_task_queue SET state=?, task_payload=?, lease_owner=NULL, lease_expires_ms=NULL, updated_ms=? WHERE task_id=?"
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setString(2, json.encodeToString(task))
                statement.setLong(3, nowEpochMs)
                statement.setString(4, row.taskId)
                statement.executeUpdate()
            }
        }
        return expired.size
    }

    private fun releaseExpiredOutboxLeases(connection: Connection, nowEpochMs: Long) {
        connection.prepareStatement(
            """
            UPDATE siph_outbox
               SET state=?, lease_owner=NULL, lease_expires_ms=NULL, last_error=?
             WHERE state=? AND lease_expires_ms<=?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ProductionOutboxState.Pending.name)
            statement.setString(2, "Dispatcher lease expired")
            statement.setString(3, ProductionOutboxState.Reserved.name)
            statement.setLong(4, nowEpochMs)
            statement.executeUpdate()
        }
    }

    private fun readOutboxRow(result: ResultSet): OutboxRow {
        val leaseExpires = result.getNullableLong("lease_expires_ms")
        val delivered = result.getNullableLong("delivered_ms")
        val event = ProductionOutboxEvent(
            id = result.getString("event_id"),
            destination = ProductionOutboxDestination.valueOf(result.getString("destination")),
            eventType = result.getString("event_type"),
            aggregateType = result.getString("aggregate_type"),
            aggregateId = result.getString("aggregate_id"),
            idempotencyKey = result.getString("idempotency_key"),
            payloadJson = result.getString("payload_json"),
            createdAtEpochMs = result.getLong("created_ms"),
            availableAtEpochMs = result.getLong("available_ms"),
            attemptCount = result.getInt("attempt_count"),
            maximumAttempts = result.getInt("max_attempts"),
            state = ProductionOutboxState.valueOf(result.getString("state")),
            leaseOwner = result.getString("lease_owner"),
            leaseExpiresAtEpochMs = leaseExpires,
            lastError = result.getString("last_error"),
            deliveredAtEpochMs = delivered
        )
        return OutboxRow(event, result.getLong("fencing_token"))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private data class CandidateTask(
        val taskId: String,
        val task: ProductionTask,
        val attemptCount: Int,
        val fencingToken: Long
    )

    private data class ExpiredTask(
        val taskId: String,
        val task: ProductionTask,
        val attemptCount: Int,
        val maximumAttempts: Int
    )

    private data class WorkerRow(
        val workerId: String,
        val workstationId: String,
        val equipmentGroupId: String,
        val softwareVersion: String,
        val maximumParallelTasks: Int,
        val availability: ProductionWorkerAvailability,
        val lastHeartbeatEpochMs: Long,
        val detail: String
    )

    private data class OutboxRow(
        val event: ProductionOutboxEvent,
        val fencingToken: Long
    )

    companion object {
        private val schemaStatements = listOf(
            """
            CREATE TABLE IF NOT EXISTS siph_worker(
                worker_id VARCHAR(200) PRIMARY KEY,
                workstation_id VARCHAR(200) NOT NULL,
                equipment_group_id VARCHAR(200) NOT NULL,
                software_version VARCHAR(100) NOT NULL,
                max_parallel_tasks INTEGER NOT NULL,
                availability VARCHAR(32) NOT NULL,
                last_heartbeat_ms BIGINT NOT NULL,
                detail TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS siph_worker_capability(
                worker_id VARCHAR(200) NOT NULL,
                capability VARCHAR(200) NOT NULL,
                PRIMARY KEY(worker_id, capability),
                FOREIGN KEY(worker_id) REFERENCES siph_worker(worker_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS siph_task_queue(
                task_id VARCHAR(250) PRIMARY KEY,
                lot_id VARCHAR(250) NOT NULL,
                idempotency_key VARCHAR(500) NOT NULL UNIQUE,
                priority INTEGER NOT NULL,
                state VARCHAR(32) NOT NULL,
                task_payload TEXT NOT NULL,
                attempt_count INTEGER NOT NULL,
                max_attempts INTEGER NOT NULL,
                lease_owner VARCHAR(200),
                lease_expires_ms BIGINT,
                fencing_token BIGINT NOT NULL DEFAULT 0,
                result_id VARCHAR(250),
                submitted_ms BIGINT NOT NULL,
                updated_ms BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS idx_siph_task_reserve
                ON siph_task_queue(state, priority, submitted_ms)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS siph_task_capability(
                task_id VARCHAR(250) NOT NULL,
                capability VARCHAR(200) NOT NULL,
                PRIMARY KEY(task_id, capability),
                FOREIGN KEY(task_id) REFERENCES siph_task_queue(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS siph_outbox(
                event_id VARCHAR(250) PRIMARY KEY,
                destination VARCHAR(32) NOT NULL,
                event_type VARCHAR(200) NOT NULL,
                aggregate_type VARCHAR(200) NOT NULL,
                aggregate_id VARCHAR(250) NOT NULL,
                idempotency_key VARCHAR(500) NOT NULL UNIQUE,
                payload_json TEXT NOT NULL,
                created_ms BIGINT NOT NULL,
                available_ms BIGINT NOT NULL,
                attempt_count INTEGER NOT NULL,
                max_attempts INTEGER NOT NULL,
                state VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(200),
                lease_expires_ms BIGINT,
                fencing_token BIGINT NOT NULL DEFAULT 0,
                last_error TEXT,
                delivered_ms BIGINT,
                remote_reference VARCHAR(500)
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS idx_siph_outbox_dispatch
                ON siph_outbox(destination, state, available_ms, created_ms)
            """.trimIndent()
        )
    }
}

class DriverManagerDataSource(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null
) : DataSource {
    override fun getConnection(): Connection = if (username.isNullOrEmpty()) {
        DriverManager.getConnection(jdbcUrl)
    } else {
        DriverManager.getConnection(jdbcUrl, username, password.orEmpty())
    }

    override fun getConnection(username: String, password: String): Connection =
        DriverManager.getConnection(jdbcUrl, username, password)

    override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
    override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
    override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun Long?.setNullableLong(statement: java.sql.PreparedStatement, index: Int) {
    if (this == null) statement.setNull(index, java.sql.Types.BIGINT) else statement.setLong(index, this)
}
