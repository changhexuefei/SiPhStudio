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
import org.jason.siph.domain.production.InMemoryRemoteAuditSink
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.MockMesGateway
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
import org.jason.siph.domain.production.UnavailableMesGateway
import org.jason.siph.domain.production.UnavailableProductionMeasurementExecutor
import org.jason.siph.domain.production.UnavailableRemoteAuditSink
import org.jason.siph.domain.runtime.HardwareRuntimeMode
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
    val workstationId = "${runtimeMode.name.lowercase()}-workstation"
    val equipmentIdentities = if (runtimeMode == HardwareRuntimeMode.Demo) {
        ProductionControlStore.DEMO_EQUIPMENT
    } else {
        emptyMap()
    }
    val workerCapabilities = if (runtimeMode == HardwareRuntimeMode.Demo) {
        setOf(
            "fiberArray",
            "laser",
            "powerMeter",
            "electricalAnalyzer",
            "prober"
        )
    } else {
        emptySet()
    }
    val strictCoordinator = StrictDistributedProductionCoordinator(distributedCoordinator)
    val coordinator = AutoRegisteringDistributedProductionCoordinator(
        delegate = strictCoordinator,
        registrationFactory = { workerId, nowEpochMs ->
            workerCapabilities.takeIf { it.isNotEmpty() }?.let { capabilities ->
                ProductionWorkerRegistration(
                    workerId = workerId,
                    workstationId = workstationId,
                    equipmentGroupId = if (runtimeMode == HardwareRuntimeMode.Demo) "digital-production" else "unverified-real",
                    capabilities = capabilities,
                    softwareVersion = "phase4.5-digital",
                    maximumParallelTasks = 1,
                    registeredAtEpochMs = nowEpochMs
                )
            }
        }
    )
    var auditSequence = 0L

    return module {
        single<ProductionRepository> { repository }
        single<AuditHasher> { auditHasher }
        single<DistributedProductionCoordinator> { coordinator }
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
        single<MesGateway> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> MockMesGateway()
                HardwareRuntimeMode.Real -> UnavailableMesGateway()
            }
        }
        single<RemoteAuditSink> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> InMemoryRemoteAuditSink()
                HardwareRuntimeMode.Real -> UnavailableRemoteAuditSink()
            }
        }
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
                applicationVersion = "phase4.5-distributed",
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
                workerId = "production-worker-1",
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
    }
}
