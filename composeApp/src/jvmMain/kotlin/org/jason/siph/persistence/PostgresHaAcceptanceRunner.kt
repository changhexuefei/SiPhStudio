package org.jason.siph.persistence

import kotlinx.coroutines.delay
import java.sql.Connection
import javax.sql.DataSource


data class PostgresHaAcceptancePlan(
    val observationDurationMs: Long,
    val pollIntervalMs: Long = 1_000L,
    val maximumAllowedOutageMs: Long = 30_000L,
    val minimumObservedRoleTransitions: Int = 1,
    val requireWriteProbe: Boolean = true
) {
    init {
        require(observationDurationMs > 0L)
        require(pollIntervalMs > 0L)
        require(maximumAllowedOutageMs >= 0L)
        require(minimumObservedRoleTransitions >= 0)
    }
}

data class PostgresHaSample(
    val timestampEpochMs: Long,
    val healthy: Boolean,
    val role: PostgresNodeRole?,
    val writable: Boolean,
    val replicationLagSeconds: Double?,
    val message: String
)

data class PostgresHaAcceptanceReport(
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val samples: List<PostgresHaSample>,
    val observedRoleTransitions: Int,
    val outageCount: Int,
    val longestOutageMs: Long,
    val recovered: Boolean,
    val passed: Boolean,
    val message: String
)

/**
 * Run this while the external HA manager performs a controlled switchover/failover. The runner does not initiate
 * failover because that authority belongs to Patroni/repmgr/cloud operations, not the measurement desktop process.
 */
class PostgresHaAcceptanceRunner(
    private val dataSource: DataSource,
    private val healthChecker: PostgresClusterHealthChecker,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun observe(plan: PostgresHaAcceptancePlan): PostgresHaAcceptanceReport {
        val startedAt = nowEpochMs()
        val samples = mutableListOf<PostgresHaSample>()
        var outageStartedAt: Long? = null
        var outageCount = 0
        var longestOutage = 0L
        var previousRole: PostgresNodeRole? = null
        var transitions = 0

        while (nowEpochMs() - startedAt < plan.observationDurationMs) {
            val timestamp = nowEpochMs()
            val sample = runCatching {
                val health = healthChecker.check(requireWritablePrimary = true)
                val writeProbe = !plan.requireWriteProbe || runWriteProbe()
                PostgresHaSample(
                    timestampEpochMs = timestamp,
                    healthy = health.healthy && writeProbe,
                    role = health.role,
                    writable = !health.readOnly && health.role == PostgresNodeRole.Primary && writeProbe,
                    replicationLagSeconds = health.replicationLagSeconds,
                    message = if (writeProbe) health.message else "Writable-primary probe failed"
                )
            }.getOrElse { error ->
                PostgresHaSample(
                    timestampEpochMs = timestamp,
                    healthy = false,
                    role = null,
                    writable = false,
                    replicationLagSeconds = null,
                    message = error.message ?: error::class.simpleName ?: "PostgreSQL HA probe failed"
                )
            }
            samples += sample
            if (sample.role != null && previousRole != null && sample.role != previousRole) transitions += 1
            if (sample.role != null) previousRole = sample.role

            if (!sample.healthy) {
                if (outageStartedAt == null) {
                    outageStartedAt = timestamp
                    outageCount += 1
                }
            } else {
                outageStartedAt?.let { start ->
                    longestOutage = maxOf(longestOutage, timestamp - start)
                    outageStartedAt = null
                }
            }
            wait(plan.pollIntervalMs)
        }

        val finishedAt = nowEpochMs()
        outageStartedAt?.let { start -> longestOutage = maxOf(longestOutage, finishedAt - start) }
        val recovered = samples.lastOrNull()?.healthy == true
        val enoughTransitions = transitions >= plan.minimumObservedRoleTransitions
        val passed = recovered && longestOutage <= plan.maximumAllowedOutageMs && enoughTransitions
        return PostgresHaAcceptanceReport(
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            samples = samples,
            observedRoleTransitions = transitions,
            outageCount = outageCount,
            longestOutageMs = longestOutage,
            recovered = recovered,
            passed = passed,
            message = when {
                !recovered -> "PostgreSQL cluster did not recover to a writable primary"
                longestOutage > plan.maximumAllowedOutageMs -> "Observed outage exceeded allowed RTO"
                !enoughTransitions -> "No required HA role transition was observed"
                else -> "PostgreSQL HA failover acceptance criteria passed"
            }
        )
    }

    private fun runWriteProbe(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TEMP TABLE IF NOT EXISTS siph_ha_probe(value INTEGER)")
                    statement.execute("INSERT INTO siph_ha_probe(value) VALUES (1)")
                }
                connection.rollback()
                true
            } catch (error: Throwable) {
                connection.rollbackQuietly()
                throw error
            }
        }
    }.getOrDefault(false)

    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }
}
