package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DefaultProductionAuditService
import org.jason.siph.domain.production.DefaultProductionCalibrationGate
import org.jason.siph.domain.production.DefaultProductionScheduler
import org.jason.siph.domain.production.DefaultProductionWorker
import org.jason.siph.domain.production.DefaultQualitySpcEngine
import org.jason.siph.domain.production.ProductionAnomalyClassifier
import org.jason.siph.domain.production.ProductionAuditService
import org.jason.siph.domain.production.ProductionAuthorizationService
import org.jason.siph.domain.production.ProductionCalibrationGate
import org.jason.siph.domain.production.ProductionGovernanceService
import org.jason.siph.domain.production.ProductionMeasurementExecutor
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionScheduler
import org.jason.siph.domain.production.QualitySpcEngine
import org.jason.siph.domain.production.RoleBasedProductionAuthorizationService
import org.jason.siph.domain.production.RuleBasedProductionAnomalyClassifier
import org.jason.siph.domain.production.SimulatedProductionMeasurementExecutor
import org.jason.siph.domain.production.UnavailableProductionMeasurementExecutor
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.production.ProductionControlStore
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

fun createProductionModule(
    scope: CoroutineScope,
    runtimeMode: HardwareRuntimeMode,
    repository: ProductionRepository,
    auditHasher: AuditHasher
): Module {
    val epochClock = { Clock.System.now().toEpochMilliseconds() }
    val equipmentIdentities = if (runtimeMode == HardwareRuntimeMode.Demo) {
        ProductionControlStore.DEMO_EQUIPMENT
    } else {
        emptyMap()
    }
    var auditSequence = 0L

    return module {
        single<ProductionRepository> { repository }
        single<AuditHasher> { auditHasher }
        single<ProductionScheduler> {
            DefaultProductionScheduler(
                repository = get(),
                nowEpochMs = epochClock
            )
        }
        single<ProductionCalibrationGate> {
            DefaultProductionCalibrationGate(repository = get())
        }
        single<QualitySpcEngine> { DefaultQualitySpcEngine() }
        single<ProductionAnomalyClassifier> { RuleBasedProductionAnomalyClassifier() }
        single<ProductionAuthorizationService> { RoleBasedProductionAuthorizationService() }
        single<ProductionAuditService> {
            DefaultProductionAuditService(
                repository = get(),
                hasher = get(),
                nowEpochMs = epochClock,
                idFactory = { "audit-${epochClock()}-${++auditSequence}" },
                applicationVersion = "phase4-digital",
                workstationId = "${runtimeMode.name.lowercase()}-workstation"
            )
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
