package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.InMemoryDistributedProductionCoordinator
import org.jason.siph.domain.production.InMemoryProductionRepository
import org.jason.siph.domain.production.PortableAuditHasher
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.UnavailableDistributedProductionCoordinator
import org.jason.siph.domain.runtime.HardwareRuntimeMode

actual fun createPlatformProductionRepository(): ProductionRepository = InMemoryProductionRepository()

actual fun createPlatformAuditHasher(): AuditHasher = PortableAuditHasher()

actual fun createPlatformDistributedProductionCoordinator(
    runtimeMode: HardwareRuntimeMode
): DistributedProductionCoordinator = when (runtimeMode) {
    HardwareRuntimeMode.Demo -> InMemoryDistributedProductionCoordinator()
    HardwareRuntimeMode.Real -> UnavailableDistributedProductionCoordinator(
        "PostgreSQL distributed production coordination is only available on the JVM target"
    )
}
