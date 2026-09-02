package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.InMemoryDistributedProductionCoordinator
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.UnavailableDistributedProductionCoordinator
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.persistence.HikariPostgresDataSourceFactory
import org.jason.siph.persistence.JdbcDistributedProductionCoordinator
import org.jason.siph.persistence.JvmJsonProductionRepository
import org.jason.siph.persistence.JvmSha256AuditHasher
import org.jason.siph.persistence.PostgresPoolConfig
import java.nio.file.Path

actual fun createPlatformProductionRepository(): ProductionRepository {
    val configuredDirectory = System.getProperty("siph.data.dir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val directory = configuredDirectory?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".siphstudio")
    return JvmJsonProductionRepository(directory.resolve("production-runtime.json"))
}

actual fun createPlatformAuditHasher(): AuditHasher = JvmSha256AuditHasher()

actual fun createPlatformDistributedProductionCoordinator(
    runtimeMode: HardwareRuntimeMode
): DistributedProductionCoordinator {
    val jdbcUrl = property(PROPERTY_POSTGRES_URL)
    if (jdbcUrl == null) {
        return when (runtimeMode) {
            HardwareRuntimeMode.Demo -> InMemoryDistributedProductionCoordinator()
            HardwareRuntimeMode.Real -> UnavailableDistributedProductionCoordinator(
                "Set -D$PROPERTY_POSTGRES_URL=jdbc:postgresql://host:5432/database to enable multi-workstation production"
            )
        }
    }
    val pool = HikariPostgresDataSourceFactory.create(
        PostgresPoolConfig(
            jdbcUrl = jdbcUrl,
            username = property(PROPERTY_POSTGRES_USER),
            password = System.getProperty(PROPERTY_POSTGRES_PASSWORD),
            poolName = property(PROPERTY_POSTGRES_POOL_NAME) ?: "siph-production-${runtimeMode.name.lowercase()}",
            maximumPoolSize = intProperty(PROPERTY_POSTGRES_MAX_POOL, 12, minimum = 2),
            minimumIdle = intProperty(PROPERTY_POSTGRES_MIN_IDLE, 2, minimum = 0),
            connectionTimeoutMs = longProperty(PROPERTY_POSTGRES_CONNECTION_TIMEOUT, 10_000L, minimum = 250L),
            validationTimeoutMs = longProperty(PROPERTY_POSTGRES_VALIDATION_TIMEOUT, 5_000L, minimum = 250L),
            leakDetectionThresholdMs = longProperty(PROPERTY_POSTGRES_LEAK_DETECTION, 60_000L, minimum = 0L)
        )
    )
    return JdbcDistributedProductionCoordinator(
        dataSource = pool,
        backendDetail = "PostgreSQL/Hikari coordinator at ${sanitizeJdbcUrl(jdbcUrl)}"
    )
}

private fun property(name: String): String? = System.getProperty(name)
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun intProperty(name: String, default: Int, minimum: Int): Int =
    property(name)?.toIntOrNull()?.also { require(it >= minimum) { "$name must be >= $minimum" } } ?: default

private fun longProperty(name: String, default: Long, minimum: Long): Long =
    property(name)?.toLongOrNull()?.also { require(it >= minimum) { "$name must be >= $minimum" } } ?: default

private fun sanitizeJdbcUrl(value: String): String = value.substringBefore('?')

private const val PROPERTY_POSTGRES_URL = "siph.production.postgres.url"
private const val PROPERTY_POSTGRES_USER = "siph.production.postgres.user"
private const val PROPERTY_POSTGRES_PASSWORD = "siph.production.postgres.password"
private const val PROPERTY_POSTGRES_POOL_NAME = "siph.production.postgres.poolName"
private const val PROPERTY_POSTGRES_MAX_POOL = "siph.production.postgres.maximumPoolSize"
private const val PROPERTY_POSTGRES_MIN_IDLE = "siph.production.postgres.minimumIdle"
private const val PROPERTY_POSTGRES_CONNECTION_TIMEOUT = "siph.production.postgres.connectionTimeoutMs"
private const val PROPERTY_POSTGRES_VALIDATION_TIMEOUT = "siph.production.postgres.validationTimeoutMs"
private const val PROPERTY_POSTGRES_LEAK_DETECTION = "siph.production.postgres.leakDetectionThresholdMs"
