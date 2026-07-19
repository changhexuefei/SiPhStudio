package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.EnterpriseIdentityGateway
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.RemoteAuditSink
import org.jason.siph.domain.runtime.HardwareRuntimeMode

expect fun createPlatformProductionRepository(): ProductionRepository

expect fun createPlatformAuditHasher(): AuditHasher

expect fun createPlatformDistributedProductionCoordinator(
    runtimeMode: HardwareRuntimeMode
): DistributedProductionCoordinator

expect fun createPlatformMesGateway(runtimeMode: HardwareRuntimeMode): MesGateway

expect fun createPlatformRemoteAuditSink(runtimeMode: HardwareRuntimeMode): RemoteAuditSink

expect fun createPlatformEnterpriseIdentityGateway(
    runtimeMode: HardwareRuntimeMode
): EnterpriseIdentityGateway

expect fun createPlatformWorkerRegistration(
    runtimeMode: HardwareRuntimeMode,
    nowEpochMs: Long
): ProductionWorkerRegistration?
