package org.jason.siph.ui.oo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.OpticalCouplerDefinition
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.SiPhDieDefinition
import org.jason.siph.domain.autonomy.SiPhSubDieDefinition
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.WaferCoordinateTransform
import org.jason.siph.domain.autonomy.WaferDefinitionRepository
import org.jason.siph.domain.oo.DeviceBackendMode
import org.jason.siph.domain.oo.LaserSweepConfig
import org.jason.siph.domain.oo.OoMeasurementCheckpoint
import org.jason.siph.domain.oo.OoMeasurementRecipe
import org.jason.siph.domain.oo.OoMeasurementRepository
import org.jason.siph.domain.oo.OoMeasurementResult
import org.jason.siph.domain.oo.OoMeasurementRunner
import org.jason.siph.domain.oo.OoMeasurementState
import org.jason.siph.domain.oo.OoOpticalPowerMeterPort
import org.jason.siph.domain.oo.TemperatureControllerPort
import org.jason.siph.domain.oo.TunableLaserPort
import org.jason.siph.domain.oo.WaferProberPort

@kotlinx.serialization.Serializable
data class OoEquipmentUiState(
    val laser: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val powerMeter: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val prober: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val temperature: AutonomyCapabilityStatus = AutonomyCapabilityStatus()
) {
    val readyCount: Int
        get() = listOf(laser, powerMeter, prober, temperature).count { it.healthy }
}

data class OoMeasurementUiState(
    val workflow: OoMeasurementState = OoMeasurementState(),
    val equipment: OoEquipmentUiState = OoEquipmentUiState(),
    val wafers: List<SiPhWaferDefinition> = emptyList(),
    val checkpoints: List<OoMeasurementCheckpoint> = emptyList(),
    val recentResults: List<OoMeasurementResult> = emptyList(),
    val simulationBackend: Boolean = false,
    val busy: Boolean = false,
    val message: String = "O-O assets are loading",
    val errorMessage: String? = null
) {
    val latestWafer: SiPhWaferDefinition?
        get() = wafers.firstOrNull()

    val recoverableCount: Int
        get() = checkpoints.size

    val completedRunCount: Int
        get() = recentResults.count { it.completed }

    val canStartSimulation: Boolean
        get() = simulationBackend && !busy && !workflow.running
}

sealed interface OoMeasurementAction {
    data object Refresh : OoMeasurementAction
    data object StartLatestWaferDemo : OoMeasurementAction
    data class Start(
        val recipe: OoMeasurementRecipe,
        val wafer: SiPhWaferDefinition,
        val runId: String,
        val resumeFromCheckpoint: Boolean = false
    ) : OoMeasurementAction
    data object ResumeLatestCheckpoint : OoMeasurementAction
    data object Pause : OoMeasurementAction
    data object Continue : OoMeasurementAction
    data object Stop : OoMeasurementAction
}

class OoMeasurementStore(
    private val scope: CoroutineScope,
    private val runner: OoMeasurementRunner,
    private val repository: OoMeasurementRepository,
    private val wafers: WaferDefinitionRepository,
    private val laser: TunableLaserPort,
    private val powerMeter: OoOpticalPowerMeterPort,
    private val prober: WaferProberPort,
    private val temperature: TemperatureControllerPort,
    private val nowEpochMs: () -> Long
) {
    private val mutableState = MutableStateFlow(
        OoMeasurementUiState(
            simulationBackend = listOf(
                laser.descriptor.backendMode,
                powerMeter.descriptor.backendMode,
                prober.descriptor.backendMode,
                temperature.descriptor.backendMode
            ).all { it == DeviceBackendMode.Simulation }
        )
    )
    val state: StateFlow<OoMeasurementUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    init {
        scope.launch {
            combine(
                laser.status,
                powerMeter.status,
                prober.status,
                temperature.status
            ) { laserStatus, meterStatus, proberStatus, temperatureStatus ->
                OoEquipmentUiState(
                    laser = laserStatus,
                    powerMeter = meterStatus,
                    prober = proberStatus,
                    temperature = temperatureStatus
                )
            }.collect { equipment ->
                mutableState.update { it.copy(equipment = equipment) }
            }
        }
        scope.launch {
            runner.state.collect { workflow ->
                mutableState.update { current ->
                    current.copy(
                        workflow = workflow,
                        busy = workflow.running || activeJob?.isActive == true,
                        message = workflow.message,
                        errorMessage = workflow.latestFailure?.message
                    )
                }
                if (!workflow.running) refreshAssets()
            }
        }
        dispatch(OoMeasurementAction.Refresh)
    }

    fun dispatch(action: OoMeasurementAction) {
        when (action) {
            OoMeasurementAction.Refresh -> refresh()
            OoMeasurementAction.StartLatestWaferDemo -> startLatestWaferDemo()
            is OoMeasurementAction.Start -> start(
                recipe = action.recipe,
                wafer = action.wafer,
                runId = action.runId,
                resumeFromCheckpoint = action.resumeFromCheckpoint
            )
            OoMeasurementAction.ResumeLatestCheckpoint -> resumeLatest()
            OoMeasurementAction.Pause -> scope.launch { runner.requestPause() }
            OoMeasurementAction.Continue -> scope.launch { runner.resume() }
            OoMeasurementAction.Stop -> scope.launch { runner.requestStop() }
        }
    }

    private fun refresh() {
        if (mutableState.value.workflow.running) return
        scope.launch {
            mutableState.update { it.copy(busy = true, errorMessage = null) }
            runCatching { refreshAssets() }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            busy = false,
                            message = "O-O assets refreshed",
                            errorMessage = null
                        )
                    }
                }
                .onFailure(::publishFailure)
        }
    }

    private fun startLatestWaferDemo() {
        val snapshot = mutableState.value
        if (!snapshot.simulationBackend) {
            publishFailure(IllegalStateException("Demo recipe is only available for simulation devices"))
            return
        }
        val timestamp = nowEpochMs()
        val wafer = snapshot.latestWafer ?: builtInDemoWafer(createdAtEpochMs = timestamp)
        val recipe = OoMeasurementRecipe(
            id = "oo-demo-$timestamp",
            waferId = wafer.id,
            temperaturesC = listOf(25.0),
            sweep = LaserSweepConfig(
                startWavelengthNm = 1549.8,
                stopWavelengthNm = 1550.2,
                stepWavelengthNm = 0.1,
                powerDbm = 0.0,
                dwellMs = 0L
            ),
            contactBeforeMeasurement = true,
            manageDeviceConnections = true
        )
        start(
            recipe = recipe,
            wafer = wafer,
            runId = "oo-run-$timestamp",
            resumeFromCheckpoint = false
        )
    }

    private fun resumeLatest() {
        val checkpoint = mutableState.value.checkpoints.firstOrNull()
        if (checkpoint == null) {
            publishFailure(IllegalStateException("No recoverable O-O checkpoint exists"))
            return
        }
        start(
            recipe = checkpoint.recipe,
            wafer = checkpoint.waferSnapshot,
            runId = checkpoint.runId,
            resumeFromCheckpoint = true
        )
    }

    private fun start(
        recipe: OoMeasurementRecipe,
        wafer: SiPhWaferDefinition,
        runId: String,
        resumeFromCheckpoint: Boolean
    ) {
        if (activeJob?.isActive == true || mutableState.value.workflow.running) return
        activeJob = scope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    message = if (resumeFromCheckpoint) {
                        "Restoring O-O workflow"
                    } else {
                        "Starting O-O workflow"
                    },
                    errorMessage = null
                )
            }
            try {
                runner.run(
                    recipe = recipe,
                    wafer = wafer,
                    runId = runId,
                    resumeFromCheckpoint = resumeFromCheckpoint
                )
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = "O-O workflow completed", errorMessage = null)
                }
            } catch (cancelled: CancellationException) {
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = cancelled.message ?: "O-O workflow stopped")
                }
            } catch (error: Throwable) {
                refreshAssets()
                publishFailure(error)
            } finally {
                activeJob = null
            }
        }
    }

    private suspend fun refreshAssets() {
        val availableWafers = wafers.listWafers()
        val checkpoints = repository.listCheckpoints()
        val results = repository.listResults(limit = 20)
        mutableState.update {
            it.copy(
                wafers = availableWafers,
                checkpoints = checkpoints,
                recentResults = results
            )
        }
    }

    private fun publishFailure(error: Throwable) {
        mutableState.update {
            it.copy(
                busy = false,
                message = "O-O operation failed",
                errorMessage = error.message ?: error::class.simpleName
            )
        }
    }
}

private fun builtInDemoWafer(createdAtEpochMs: Long): SiPhWaferDefinition = SiPhWaferDefinition(
    id = "demo-wafer",
    diameterMm = 200.0,
    transform = WaferCoordinateTransform(
        originStageXUm = 0.0,
        originStageYUm = 0.0,
        diePitchXUm = 1_000.0,
        diePitchYUm = 1_000.0
    ),
    dies = listOf(
        demoDie(0, 0),
        demoDie(1, 0),
        demoDie(1, 1),
        demoDie(0, 1)
    ),
    createdAtEpochMs = createdAtEpochMs
)

private fun demoDie(column: Int, row: Int): SiPhDieDefinition = SiPhDieDefinition(
    index = DieIndex(column = column, row = row),
    label = "D${row}_$column",
    subDies = listOf(
        SiPhSubDieDefinition(
            id = "sub-a",
            name = "Sub A",
            originOffsetXUm = 0.0,
            originOffsetYUm = 0.0,
            couplers = listOf(
                OpticalCouplerDefinition(
                    id = "gc-input",
                    name = "Input Grating",
                    geometry = PhotonicCouplingGeometry.VerticalGrating,
                    offsetXUm = 10.0,
                    offsetYUm = 10.0
                )
            )
        )
    )
)
