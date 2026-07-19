package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.runtime.HardwareRuntimeMode

expect fun createPlatformProductionRepository(): ProductionRepository

expect fun createPlatformAuditHasher(): AuditHasher

expect fun createPlatformDistributedProductionCoordinator(
    runtimeMode: HardwareRuntimeMode
): DistributedProductionCoordinator
