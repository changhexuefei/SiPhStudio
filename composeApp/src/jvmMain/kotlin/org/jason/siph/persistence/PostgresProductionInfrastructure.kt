package org.jason.siph.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/**
 * Production JDBC pool configuration. A PostgreSQL multi-host URL can be used directly, for example:
 * jdbc:postgresql://db-a:5432,db-b:5432/siphstudio?targetServerType=primary&hostRecheckSeconds=5
 */
data class PostgresPoolConfig(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
    val poolName: String = "siph-production",
    val maximumPoolSize: Int = 12,
    val minimumIdle: Int = 2,
    val connectionTimeoutMs: Long = 10_000L,
    val validationTimeoutMs: Long = 5_000L,
    val idleTimeoutMs: Long = 600_000L,
    val maxLifetimeMs: Long = 1_800_000L,
    val keepaliveTimeMs: Long = 120_000L,
    val leakDetectionThresholdMs: Long = 60_000L
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql:"))
        require(poolName.isNotBlank())
        require(maximumPoolSize > 0)
        require(minimumIdle in 0..maximumPoolSize)
        require(connectionTimeoutMs >= 250L)
        require(validationTimeoutMs >= 250L)
        require(idleTimeoutMs >= 10_000L)
        require(maxLifetimeMs >= 30_000L)
        require(keepaliveTimeMs == 0L || keepaliveTimeMs >= 30_000L)
        require(leakDetectionThresholdMs == 0L || leakDetectionThresholdMs >= 2_000L)
    }
}

object HikariPostgresDataSourceFactory {
    fun create(config: PostgresPoolConfig): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            poolName = config.poolName
            maximumPoolSize = config.maximumPoolSize
            minimumIdle = config.minimumIdle
            connectionTimeout = config.connectionTimeoutMs
            validationTimeout = config.validationTimeoutMs
            idleTimeout = config.idleTimeoutMs
            maxLifetime = config.maxLifetimeMs
            keepaliveTime = config.keepaliveTimeMs
            leakDetectionThreshold = config.leakDetectionThresholdMs
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            isAutoCommit = true
            initializationFailTimeout = 10_000L
            addDataSourceProperty("ApplicationName", config.poolName)
            addDataSourceProperty("tcpKeepAlive", "true")
            addDataSourceProperty("reWriteBatchedInserts", "true")
        }
        return HikariDataSource(hikari)
    }
}

enum class PostgresNodeRole {
    Primary,
    Standby
}

data class PostgresClusterHealth(
    val healthy: Boolean,
    val role: PostgresNodeRole,
    val readOnly: Boolean,
    val database: String,
    val user: String,
    val serverVersion: String,
    val replicationLagSeconds: Double?,
    val checkedAtEpochMs: Long,
    val message: String
)

class PostgresClusterHealthChecker(
    private val dataSource: DataSource,
    private val maximumAllowedReplicationLagSeconds: Double = 30.0,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(maximumAllowedReplicationLagSeconds >= 0.0)
    }

    suspend fun check(requireWritablePrimary: Boolean): PostgresClusterHealth = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT version() AS server_version,
                       current_database() AS database_name,
                       current_user AS user_name,
                       pg_is_in_recovery() AS in_recovery,
                       current_setting('transaction_read_only') AS read_only,
                       CASE
                           WHEN pg_is_in_recovery() THEN
                               EXTRACT(EPOCH FROM (clock_timestamp() - pg_last_xact_replay_timestamp()))
                           ELSE NULL
                       END AS replication_lag_seconds
                """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 5
                statement.executeQuery().use { result ->
                    check(result.next()) { "PostgreSQL health query returned no row" }
                    val standby = result.getBoolean("in_recovery")
                    val readOnly = result.getString("read_only").equals("on", ignoreCase = true)
                    val lag = result.getDouble("replication_lag_seconds").let {
                        if (result.wasNull()) null else it
                    }
                    val role = if (standby) PostgresNodeRole.Standby else PostgresNodeRole.Primary
                    val writable = !standby && !readOnly
                    val lagHealthy = lag == null || lag <= maximumAllowedReplicationLagSeconds
                    val healthy = connection.isValid(3) && lagHealthy && (!requireWritablePrimary || writable)
                    PostgresClusterHealth(
                        healthy = healthy,
                        role = role,
                        readOnly = readOnly,
                        database = result.getString("database_name"),
                        user = result.getString("user_name"),
                        serverVersion = result.getString("server_version"),
                        replicationLagSeconds = lag,
                        checkedAtEpochMs = nowEpochMs(),
                        message = when {
                            requireWritablePrimary && !writable -> "Connected node is not a writable primary"
                            !lagHealthy -> "Standby replication lag exceeds ${maximumAllowedReplicationLagSeconds}s"
                            healthy -> "PostgreSQL cluster connection is healthy"
                            else -> "PostgreSQL cluster health check failed"
                        }
                    )
                }
            }
        }
    }
}

data class PostgresBackupResult(
    val backupFile: Path,
    val manifestFile: Path,
    val sha256: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val verified: Boolean
)

/**
 * Runs the PostgreSQL client tools installed on the workstation/server. The database URI must be a PostgreSQL URI,
 * not a JDBC URL. Credentials are passed through PGPASSWORD and are never written to the manifest.
 */
class PostgresBackupService(
    private val databaseUri: String,
    private val password: String?,
    private val pgDumpExecutable: String = "pg_dump",
    private val pgRestoreExecutable: String = "pg_restore",
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(databaseUri.startsWith("postgresql://") || databaseUri.startsWith("postgres://"))
        require(pgDumpExecutable.isNotBlank() && pgRestoreExecutable.isNotBlank())
    }

    suspend fun createAndVerifyBackup(outputDirectory: Path): PostgresBackupResult = withContext(Dispatchers.IO) {
        Files.createDirectories(outputDirectory)
        val createdAt = nowEpochMs()
        val backup = outputDirectory.resolve("siphstudio-$createdAt.dump")
        runProcess(
            command = listOf(
                pgDumpExecutable,
                "--format=custom",
                "--no-owner",
                "--no-privileges",
                "--file=${backup.toAbsolutePath()}",
                "--dbname=$databaseUri"
            ),
            operation = "PostgreSQL backup"
        )
        check(Files.isRegularFile(backup) && Files.size(backup) > 0L) {
            "pg_dump completed without producing a non-empty backup"
        }
        runProcess(
            command = listOf(pgRestoreExecutable, "--list", backup.toAbsolutePath().toString()),
            operation = "PostgreSQL backup verification"
        )
        val hash = sha256(backup)
        val manifest = outputDirectory.resolve("${backup.fileName}.manifest.txt")
        val manifestText = buildString {
            appendLine("format=pg_dump-custom")
            appendLine("createdAtEpochMs=$createdAt")
            appendLine("createdAtIso=${Instant.ofEpochMilli(createdAt)}")
            appendLine("backupFile=${backup.fileName}")
            appendLine("sizeBytes=${Files.size(backup)}")
            appendLine("sha256=$hash")
            appendLine("verifiedWith=pg_restore --list")
        }
        Files.writeString(manifest, manifestText, StandardCharsets.UTF_8)
        PostgresBackupResult(
            backupFile = backup,
            manifestFile = manifest,
            sha256 = hash,
            sizeBytes = Files.size(backup),
            createdAtEpochMs = createdAt,
            verified = true
        )
    }

    suspend fun verifyExistingBackup(backup: Path, expectedSha256: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(backup) || Files.size(backup) <= 0L) return@withContext false
            expectedSha256?.let { expected ->
                if (!sha256(backup).equals(expected, ignoreCase = true)) return@withContext false
            }
            runCatching {
                runProcess(
                    command = listOf(pgRestoreExecutable, "--list", backup.toAbsolutePath().toString()),
                    operation = "PostgreSQL backup verification"
                )
            }.isSuccess
        }

    suspend fun restoreBackup(
        backup: Path,
        targetDatabaseUri: String,
        destructiveRestoreApproved: Boolean
    ) = withContext(Dispatchers.IO) {
        require(destructiveRestoreApproved) {
            "Restore requires an explicit destructiveRestoreApproved=true acknowledgement"
        }
        require(targetDatabaseUri.startsWith("postgresql://") || targetDatabaseUri.startsWith("postgres://"))
        check(verifyExistingBackup(backup)) { "Backup cannot be verified before restore" }
        runProcess(
            command = listOf(
                pgRestoreExecutable,
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-privileges",
                "--dbname=$targetDatabaseUri",
                backup.toAbsolutePath().toString()
            ),
            operation = "PostgreSQL restore"
        )
    }

    private fun runProcess(command: List<String>, operation: String) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { password?.let { environment()["PGPASSWORD"] = it } }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "$operation failed with exit=$exit: ${output.takeLast(4_000)}" }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

fun Connection.requireWritablePostgresPrimary() {
    prepareStatement("SELECT NOT pg_is_in_recovery() AND current_setting('transaction_read_only')='off'").use {
        it.executeQuery().use { result ->
            require(result.next() && result.getBoolean(1)) {
                "Production coordination requires a writable PostgreSQL primary"
            }
        }
    }
}
