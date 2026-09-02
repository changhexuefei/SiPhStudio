package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.AutoRegisteringDistributedProductionCoordinator
import org.jason.siph.domain.production.CoordinatedProductionScheduler
import org.jason.siph.domain.production.DefaultProductionAuditService
import org.jason.siph.domain.production.DefaultProductionCalibrationGate
import org.jason.siph.domain.production.DefaultProductionScheduler
import org.jason.siph.domain.production.DefaultProductionWorker
import org.jason.siph.domain.production.DefaultQualitySpcEngine
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.EnterpriseIdentityGateway
import org.jason.siph.domain.production.EnterpriseSessionService
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.ProductionAnomalyClassifier
import org.jason.siph.domain.production.ProductionAuditService
import org.jason.siph.domain.production.ProductionAuthorizationService
import org.jason.siph.domain.production.ProductionCalibrationGate
import org.jason.siph.domain.production.ProductionGovernanceService
import org.jason.siph.domain.production.ProductionMeasurementExecutor
import org.jason.siph.domain.production.ProductionOutboxDispatcher
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionScheduler
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.QualitySpcEngine
import org.jason.siph.domain.production.RemoteAuditSink
import org.jason.siph.domain.production.ReplicatingProductionAuditService
import org.jason.siph.domain.production.RoleBasedProductionAuthorizationService
import org.jason.siph.domain.production.RuleBasedProductionAnomalyClassifier
import org.jason.siph.domain.production.SimulatedProductionMeasurementExecutor
import org.jason.siph.domain.production.StrictDistributedProductionCoordinator
import org.jason.siph.domain.production.UnavailableProductionMeasurementExecutor
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.production.ProductionClusterStore
import org.jason.siph.ui.production.ProductionControlStore
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

fun createProductionModule(
    scope: CoroutineScope,
    runtimeMode: HardwareRuntimeMode,
    repository: ProductionRepository,
    auditHasher: AuditHasher,
    distributedCoordinator: DistributedProductionCoordinator
): Module {
    val epochClock = { Clock.System.now().toEpochMilliseconds() }
    val approvedRealRegistration = createPlatformWorkerRegistration(runtimeMode, epochClock())
    val workerId = approvedRealRegistration?.workerId ?: "production-worker-1"
    val workstationId = approvedRealRegistration?.workstationId ?: "${runtimeMode.name.lowercase()}-workstation"
    val equipmentIdentities = if (runtimeMode == HardwareRuntimeMode.Demo) {
        ProductionControlStore.DEMO_EQUIPMENT
    } else {
        emptyMap()
    }
    val digitalWorkerCapabilities = setOf(
        "fiberArray",
        "laser",
        "powerMeter",
        "electricalAnalyzer",
        "prober"
    )
    val strictCoordinator = StrictDistributedProductionCoordinator(distributedCoordinator)
    val coordinator = AutoRegisteringDistributedProductionCoordinator(
        delegate = strictCoordinator,
        registrationFactory = { requestedWorkerId, nowEpochMs ->
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> ProductionWorkerRegistration(
                    workerId = requestedWorkerId,
                    workstationId = workstationId,
                    equipmentGroupId = "digital-production",
                    capabilities = digitalWorkerCapabilities,
                    softwareVersion = "phase4.6-enterprise-digital",
                    maximumParallelTasks = 1,
                    registeredAtEpochMs = nowEpochMs
                )
                HardwareRuntimeMode.Real -> approvedRealRegistration
                    ?.takeIf { it.workerId == requestedWorkerId }
                    ?.copy(registeredAtEpochMs = nowEpochMs)
            }
        }
    )
    val platformMesGateway = createPlatformMesGateway(runtimeMode)
    val platformRemoteAuditSink = createPlatformRemoteAuditSink(runtimeMode)
    val platformIdentityGateway = createPlatformEnterpriseIdentityGateway(runtimeMode)
    var auditSequence = 0L

    return module {
        single<ProductionRepository> { repository }
        single<AuditHasher> { auditHasher }
        single<DistributedProductionCoordinator> { coordinator }
        single<EnterpriseIdentityGateway> { platformIdentityGateway }
        single { EnterpriseSessionService(gateway = get()) }
        single<MesGateway> { platformMesGateway }
        single<RemoteAuditSink> { platformRemoteAuditSink }
        single<ProductionScheduler> {
            if (coordinator.status.configured) {
                CoordinatedProductionScheduler(
                    repository = get(),
                    coordinator = get(),
                    nowEpochMs = epochClock
                )
            } else {
                DefaultProductionScheduler(
                    repository = get(),
                    nowEpochMs = epochClock
                )
            }
        }
        single<ProductionCalibrationGate> {
            DefaultProductionCalibrationGate(repository = get())
        }
        single<QualitySpcEngine> { DefaultQualitySpcEngine() }
        single<ProductionAnomalyClassifier> { RuleBasedProductionAnomalyClassifier() }
        single<ProductionAuthorizationService> { RoleBasedProductionAuthorizationService() }
        single {
            ProductionOutboxDispatcher(
                coordinator = get(),
                mesGateway = get(),
                remoteAuditSink = get(),
                nowEpochMs = epochClock
            )
        }
        single<ProductionAuditService> {
            val localAudit = DefaultProductionAuditService(
                repository = get(),
                hasher = get(),
                nowEpochMs = epochClock,
                idFactory = { "audit-${epochClock()}-${++auditSequence}" },
                applicationVersion = "phase4.6-enterprise",
                workstationId = workstationId
            )
            if (coordinator.status.configured) {
                ReplicatingProductionAuditService(
                    delegate = localAudit,
                    coordinator = get(),
                    nowEpochMs = epochClock
                )
            } else {
                localAudit
            }
        }
        single {
            ProductionGovernanceService(
                repository = get(),
                authorization = get(),
                audit = get(),
                nowEpochMs = epochClock
            )
        }
        single<ProductionMeasurementExecutor> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedProductionMeasurementExecutor(
                    repository = get(),
                    nowEpochMs = epochClock,
                    equipmentIdentities = equipmentIdentities
                )
                HardwareRuntimeMode.Real -> UnavailableProductionMeasurementExecutor()
            }
        }
        single {
            DefaultProductionWorker(
                workerId = workerId,
                repository = get(),
                scheduler = get(),
                calibrationGate = get(),
                executor = get(),
                anomalyClassifier = get(),
                audit = get(),
                authorization = get(),
                equipmentIdentities = { equipmentIdentities },
                cameraCalibrationId = { null },
                probeHeightProfileId = { null },
                pivotProfileId = { null },
                nowEpochMs = epochClock
            )
        }
        single {
            ProductionControlStore(
                scope = scope,
                runtimeMode = runtimeMode,
                repository = get(),
                scheduler = get(),
                worker = get(),
                nowEpochMs = epochClock
            )
        }
        single {
            ProductionClusterStore(
                scope = scope,
                runtimeMode = runtimeMode,
                coordinator = get(),
                dispatcher = get(),
                nowEpochMs = epochClock
            )
        }
    }
}
