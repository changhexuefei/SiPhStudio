package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.ProductionRepository
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
