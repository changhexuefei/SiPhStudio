package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresBackupRestoreAcceptanceTest {

    @Test
    fun pgDumpBackupIsHashedVerifiedAndRestoredIntoIsolatedDatabase() = runBlocking {
        val environment = postgresEnvironmentOrNull() ?: return@runBlocking
        if (!commandAvailable("pg_dump") || !commandAvailable("pg_restore")) return@runBlocking

        val databaseName = "siph_restore_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val adminUrl = environment.url.substringBeforeLast('/') + "/postgres"
        DriverManager.getConnection(environment.url, environment.user, environment.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS backup_acceptance_marker")
                statement.execute("CREATE TABLE backup_acceptance_marker(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("INSERT INTO backup_acceptance_marker(id, value) VALUES (1, 'verified-backup-data')")
            }
        }
        createDatabase(adminUrl, environment, databaseName)
        val output = createTempDirectory("siph-postgres-backup")
        try {
            val service = PostgresBackupService(
                databaseUri = environment.url.removePrefix("jdbc:"),
                password = environment.password
            )
            val result = service.createAndVerifyBackup(output)

            assertTrue(result.verified)
            assertTrue(result.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(Files.size(result.backupFile) > 0L)
            assertTrue(Files.readString(result.manifestFile).contains("verifiedWith=pg_restore --list"))
            assertTrue(service.verifyExistingBackup(result.backupFile, result.sha256))

            val targetUri = environment.url.substringBeforeLast('/')
                .removePrefix("jdbc:") + "/$databaseName"
            service.restoreBackup(
                backup = result.backupFile,
                targetDatabaseUri = targetUri,
                destructiveRestoreApproved = true
            )
            val targetJdbc = "jdbc:$targetUri"
            DriverManager.getConnection(targetJdbc, environment.user, environment.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM backup_acceptance_marker WHERE id=1").use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("verified-backup-data", resultSet.getString(1))
                    }
                }
            }
        } finally {
            dropDatabase(adminUrl, environment, databaseName)
        }
    }

    private fun createDatabase(adminUrl: String, environment: PostgresEnvironment, databaseName: String) {
        DriverManager.getConnection(adminUrl, environment.user, environment.password).use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") }
        }
    }

    private fun dropDatabase(adminUrl: String, environment: PostgresEnvironment, databaseName: String) {
        runCatching {
            DriverManager.getConnection(adminUrl, environment.user, environment.password).use { connection ->
                connection.autoCommit = true
                connection.createStatement().use { statement ->
                    statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$databaseName'")
                    statement.execute("DROP DATABASE IF EXISTS $databaseName")
                }
            }
        }
    }

    private fun commandAvailable(command: String): Boolean = runCatching {
        ProcessBuilder(command, "--version")
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

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
