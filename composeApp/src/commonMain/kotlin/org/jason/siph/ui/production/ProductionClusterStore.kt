package org.jason.siph.ui.production

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jason.siph.domain.production.DistributedCoordinatorStatus
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxDispatcher
import org.jason.siph.domain.production.ProductionOutboxState
import org.jason.siph.domain.production.ProductionWorkerAvailability
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.ProductionWorkerSnapshot
import org.jason.siph.domain.runtime.HardwareRuntimeMode

sealed interface ProductionClusterAction {
    data object Refresh : ProductionClusterAction
    data object Heartbeat : ProductionClusterAction
    data object DispatchOutbox : ProductionClusterAction
    data object ReapExpired : ProductionClusterAction
}

data class ProductionClusterUiState(
    val coordinator: DistributedCoordinatorStatus,
    val workers: List<ProductionWorkerSnapshot> = emptyList(),
    val pendingMesEvents: Int = 0,
    val pendingAuditEvents: Int = 0,
    val deliveredEvents: Int = 0,
    val deadLetterEvents: Int = 0,
    val simulationBackend: Boolean,
    val busy: Boolean = false,
    val message: String = "Production cluster is loading",
    val errorMessage: String? = null
) {
    val onlineWorkers: Int
        get() = workers.count { it.availability != ProductionWorkerAvailability.Offline }

    val busyWorkers: Int
        get() = workers.count { it.availability == ProductionWorkerAvailability.Busy }

    val canOperateDemo: Boolean
        get() = simulationBackend && coordinator.configured && !busy
}

class ProductionClusterStore(
    private val scope: CoroutineScope,
    private val runtimeMode: HardwareRuntimeMode,
    private val coordinator: DistributedProductionCoordinator,
    private val dispatcher: ProductionOutboxDispatcher,
    private val nowEpochMs: () -> Long
) {
    private val mutableState = MutableStateFlow(
        ProductionClusterUiState(
            coordinator = coordinator.status,
            simulationBackend = runtimeMode == HardwareRuntimeMode.Demo
        )
    )
    val state: StateFlow<ProductionClusterUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    init {
        dispatch(ProductionClusterAction.Refresh)
    }

    fun dispatch(action: ProductionClusterAction) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            mutableState.update { it.copy(busy = true, errorMessage = null) }
            runCatching {
                coordinator.initialize()
                when (action) {
                    ProductionClusterAction.Refresh -> seedDemoWorkersIfNeeded()
                    ProductionClusterAction.Heartbeat -> heartbeatDemoWorkers()
                    ProductionClusterAction.DispatchOutbox -> dispatchDemoOutbox()
                    ProductionClusterAction.ReapExpired -> {
                        coordinator.reapExpiredWorkersAndLeases(
                            workerTimeoutMs = 120_000L,
                            nowEpochMs = nowEpochMs()
                        )
                    }
                }
                refreshAssets()
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = when (action) {
                            ProductionClusterAction.Refresh -> "Cluster telemetry refreshed"
                            ProductionClusterAction.Heartbeat -> "Digital worker heartbeats updated"
                            ProductionClusterAction.DispatchOutbox -> "Digital MES and audit outbox dispatched"
                            ProductionClusterAction.ReapExpired -> "Expired worker and dispatcher leases reaped"
                        },
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        busy = false,
                        errorMessage = error.message ?: error::class.simpleName,
                        message = "Production cluster operation failed"
                    )
                }
            }
            activeJob = null
        }
    }

    private suspend fun seedDemoWorkersIfNeeded() {
        if (runtimeMode != HardwareRuntimeMode.Demo || !coordinator.status.configured) return
        val existing = coordinator.listWorkers().map { it.registration.workerId }.toSet()
        demoWorkers(nowEpochMs()).filterNot { it.workerId in existing }.forEach {
            coordinator.registerWorker(it)
        }
    }

    private suspend fun heartbeatDemoWorkers() {
        require(runtimeMode == HardwareRuntimeMode.Demo) {
            "Worker heartbeat control is disabled outside Demo mode"
        }
        seedDemoWorkersIfNeeded()
        val now = nowEpochMs()
        coordinator.listWorkers().forEach { worker ->
            coordinator.heartbeat(
                workerId = worker.registration.workerId,
                availability = if (worker.currentTaskIds.isEmpty()) {
                    ProductionWorkerAvailability.Ready
                } else {
                    ProductionWorkerAvailability.Busy
                },
                currentTaskIds = worker.currentTaskIds,
                detail = "Digital worker heartbeat",
                nowEpochMs = now
            )
        }
    }

    private suspend fun dispatchDemoOutbox() {
        require(runtimeMode == HardwareRuntimeMode.Demo) {
            "Outbox dispatch is disabled until real MES and audit integrations are configured"
        }
        dispatcher.dispatch(
            destination = ProductionOutboxDestination.Mes,
            dispatcherId = "digital-mes-dispatcher"
        )
        dispatcher.dispatch(
            destination = ProductionOutboxDestination.AuditServer,
            dispatcherId = "digital-audit-dispatcher"
        )
    }

    private suspend fun refreshAssets() {
        val outbox = coordinator.listOutboxEvents()
        mutableState.update {
            it.copy(
                coordinator = coordinator.status,
                workers = coordinator.listWorkers(),
                pendingMesEvents = outbox.count {
                    it.destination == ProductionOutboxDestination.Mes &&
                        it.state == ProductionOutboxState.Pending
                },
                pendingAuditEvents = outbox.count {
                    it.destination == ProductionOutboxDestination.AuditServer &&
                        it.state == ProductionOutboxState.Pending
                },
                deliveredEvents = outbox.count { it.state == ProductionOutboxState.Delivered },
                deadLetterEvents = outbox.count { it.state == ProductionOutboxState.DeadLetter },
                busy = false
            )
        }
    }

    private fun demoWorkers(nowEpochMs: Long): List<ProductionWorkerRegistration> = listOf(
        ProductionWorkerRegistration(
            workerId = "production-worker-1",
            workstationId = "digital-station-a",
            equipmentGroupId = "digital-production",
            capabilities = fullProductionCapabilities,
            softwareVersion = "phase4.5-digital",
            registeredAtEpochMs = nowEpochMs
        ),
        ProductionWorkerRegistration(
            workerId = "production-worker-2",
            workstationId = "digital-station-b",
            equipmentGroupId = "digital-production",
            capabilities = fullProductionCapabilities,
            softwareVersion = "phase4.5-digital",
            registeredAtEpochMs = nowEpochMs
        ),
        ProductionWorkerRegistration(
            workerId = "quality-worker-1",
            workstationId = "digital-quality-station",
            equipmentGroupId = "digital-quality",
            capabilities = setOf("calibrationWafer", "qualityReview", "spc"),
            softwareVersion = "phase4.5-digital",
            registeredAtEpochMs = nowEpochMs
        )
    )

    private companion object {
        val fullProductionCapabilities = setOf(
            "fiberArray",
            "laser",
            "powerMeter",
            "electricalAnalyzer",
            "prober"
        )
    }
}
