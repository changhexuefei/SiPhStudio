package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.InMemoryDistributedProductionCoordinator
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.UnavailableDistributedProductionCoordinator
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.persistence.DriverManagerDataSource
import org.jason.siph.persistence.JdbcDistributedProductionCoordinator
import org.jason.siph.persistence.JvmJsonProductionRepository
import org.jason.siph.persistence.JvmSha256AuditHasher
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
    val jdbcUrl = System.getProperty(PROPERTY_POSTGRES_URL)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    if (jdbcUrl == null) {
        return when (runtimeMode) {
            HardwareRuntimeMode.Demo -> InMemoryDistributedProductionCoordinator()
            HardwareRuntimeMode.Real -> UnavailableDistributedProductionCoordinator(
                "Set -D$PROPERTY_POSTGRES_URL=jdbc:postgresql://host:5432/database to enable multi-workstation production"
            )
        }
    }
    val username = System.getProperty(PROPERTY_POSTGRES_USER)?.trim()?.takeIf(String::isNotEmpty)
    val password = System.getProperty(PROPERTY_POSTGRES_PASSWORD)
    return JdbcDistributedProductionCoordinator(
        dataSource = DriverManagerDataSource(jdbcUrl, username, password),
        backendDetail = "PostgreSQL coordinator at ${sanitizeJdbcUrl(jdbcUrl)}"
    )
}

private fun sanitizeJdbcUrl(value: String): String = value.substringBefore('?')

private const val PROPERTY_POSTGRES_URL = "siph.production.postgres.url"
private const val PROPERTY_POSTGRES_USER = "siph.production.postgres.user"
private const val PROPERTY_POSTGRES_PASSWORD = "siph.production.postgres.password"
