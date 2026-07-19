package org.jason.siph.persistence

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresDistributedProductionStressTest {

    @Test
    fun realPostgresCoordinatesEightWorkersWithoutDuplicateReservations() = runBlocking {
        val environment = postgresEnvironmentOrNull() ?: return@runBlocking
        val schema = "stress_${UUID.randomUUID().toString().replace("-", "")}"
        createSchema(environment, schema)
        val pool = pool(environment, schema, "stress-main", maximumPoolSize = 20)
        try {
            val coordinator = JdbcDistributedProductionCoordinator(
                dataSource = pool,
                backendDetail = "real PostgreSQL CI stress test"
            )
            coordinator.initialize()
            val health = PostgresClusterHealthChecker(pool).check(requireWritablePrimary = true)
            assertTrue(health.healthy, health.message)
            assertEquals(PostgresNodeRole.Primary, health.role)

            val workerCount = 8
            val taskCount = 400
            val capabilities = setOf("laser", "powerMeter", "electricalAnalyzer", "prober")
            repeat(workerCount) { index ->
                coordinator.registerWorker(
                    ProductionWorkerRegistration(
                        workerId = "stress-worker-$index",
                        workstationId = "stress-station-$index",
                        equipmentGroupId = "ci-production",
                        capabilities = capabilities,
                        softwareVersion = "stress-test",
                        maximumParallelTasks = 1,
                        registeredAtEpochMs = 1_000L
                    )
                )
            }
            coordinator.enqueueTasks(
                (0 until taskCount).map { index ->
                    DistributedTaskSubmission(
                        task = task(index),
                        requiredCapabilities = capabilities,
                        submittedAtEpochMs = 2_000L + index
                    )
                }
            )

            val claimedByTask = ConcurrentHashMap<String, String>()
            val completed = AtomicInteger(0)
            coroutineScope {
                (0 until workerCount).map { workerIndex ->
                    async(Dispatchers.IO) {
                        val workerId = "stress-worker-$workerIndex"
                        var idleRounds = 0
                        while (completed.get() < taskCount && idleRounds < 500) {
                            val lease = coordinator.reserveNextTask(
                                workerId = workerId,
                                leaseDurationMs = 30_000L,
                                nowEpochMs = System.currentTimeMillis()
                            )
                            if (lease == null) {
                                idleRounds += 1
                                delay(2)
                                continue
                            }
                            idleRounds = 0
                            val previous = claimedByTask.putIfAbsent(lease.task.id, workerId)
                            check(previous == null) {
                                "Task ${lease.task.id} was reserved by both $previous and $workerId"
                            }
                            coordinator.completeTaskLease(
                                lease = lease,
                                resultId = "result-${lease.task.id}",
                                passed = true,
                                nowEpochMs = System.currentTimeMillis()
                            )
                            completed.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }

            assertEquals(taskCount, completed.get())
            assertEquals(taskCount, claimedByTask.size)
            assertEquals(taskCount, claimedByTask.keys.distinct().size)
        } finally {
            pool.close()
            dropSchema(environment, schema)
        }
    }

    @Test
    fun realPostgresDispatchesOutboxConcurrentlyExactlyOnce() = runBlocking {
        val environment = postgresEnvironmentOrNull() ?: return@runBlocking
        val schema = "outbox_${UUID.randomUUID().toString().replace("-", "")}"
        createSchema(environment, schema)
        val pool = pool(environment, schema, "stress-outbox", maximumPoolSize = 16)
        try {
            val coordinator = JdbcDistributedProductionCoordinator(pool, "real PostgreSQL outbox stress")
            coordinator.initialize()
            val eventCount = 240
            repeat(eventCount) { index ->
                coordinator.enqueueOutbox(
                    ProductionOutboxEvent(
                        id = "mes-event-$index",
                        destination = ProductionOutboxDestination.Mes,
                        eventType = "PRODUCTION_TASK_COMPLETED",
                        aggregateType = "ProductionTask",
                        aggregateId = "task-$index",
                        idempotencyKey = "MES:stress:$index",
                        payloadJson = "{\"taskId\":\"task-$index\"}",
                        createdAtEpochMs = 10_000L + index
                    )
                )
            }
            val gateway = MockMesGateway()
            val delivered = AtomicInteger(0)
            coroutineScope {
                (0 until 6).map { dispatcherIndex ->
                    async(Dispatchers.IO) {
                        val dispatcher = ProductionOutboxDispatcher(
                            coordinator = coordinator,
                            mesGateway = gateway,
                            remoteAuditSink = InMemoryRemoteAuditSink(),
                            nowEpochMs = { System.currentTimeMillis() }
                        )
                        var emptyRounds = 0
                        while (delivered.get() < eventCount && emptyRounds < 100) {
                            val summary = dispatcher.dispatch(
                                destination = ProductionOutboxDestination.Mes,
                                dispatcherId = "mes-dispatcher-$dispatcherIndex",
                                maximumBatchSize = 13,
                                leaseDurationMs = 30_000L
                            )
                            delivered.addAndGet(summary.delivered)
                            if (summary.reserved == 0) {
                                emptyRounds += 1
                                delay(2)
                            } else {
                                emptyRounds = 0
                            }
                        }
                    }
                }.awaitAll()
            }
            val events = coordinator.listOutboxEvents(ProductionOutboxDestination.Mes)
            assertEquals(eventCount, events.size)
            assertEquals(eventCount, events.count { it.state == ProductionOutboxState.Delivered })
            assertEquals(0, events.count { it.state == ProductionOutboxState.DeadLetter })
        } finally {
            pool.close()
            dropSchema(environment, schema)
        }
    }

    private fun task(index: Int) = ProductionTask(
        id = "stress-task-$index",
        lotId = "stress-lot",
        waferId = "stress-wafer",
        site = MeasurementSiteKey(
            waferId = "stress-wafer",
            die = DieIndex(row = index / 40, column = index % 40),
            subDieId = "sub-1",
            couplerId = "coupler-${index % 8}"
        ),
        recipeId = "stress-recipe",
        recipeVersion = 1,
        priority = index % 7,
        maximumAttempts = 3,
        idempotencyKey = "stress-lot:$index"
    )

    private fun pool(
        environment: PostgresEnvironment,
        schema: String,
        poolName: String,
        maximumPoolSize: Int
    ): HikariDataSource = HikariPostgresDataSourceFactory.create(
        PostgresPoolConfig(
            jdbcUrl = appendQuery(environment.url, "currentSchema=$schema"),
            username = environment.user,
            password = environment.password,
            poolName = poolName,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = maximumPoolSize.coerceAtMost(4),
            leakDetectionThresholdMs = 0L
        )
    )

    private fun createSchema(environment: PostgresEnvironment, schema: String) {
        DriverManager.getConnection(environment.url, environment.user, environment.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
    }

    private fun dropSchema(environment: PostgresEnvironment, schema: String) {
        runCatching {
            DriverManager.getConnection(environment.url, environment.user, environment.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }

    private fun appendQuery(url: String, parameter: String): String =
        if ('?' in url) "$url&$parameter" else "$url?$parameter"

    private fun postgresEnvironmentOrNull(): PostgresEnvironment? {
        val url = System.getenv("SIPH_TEST_POSTGRES_URL")?.takeIf(String::isNotBlank) ?: return null
        return PostgresEnvironment(
            url = url,
            user = System.getenv("SIPH_TEST_POSTGRES_USER") ?: "siphstudio",
            password = System.getenv("SIPH_TEST_POSTGRES_PASSWORD") ?: ""
        )
    }

    private data class PostgresEnvironment(
        val url: String,
        val user: String,
        val password: String
    )
}
