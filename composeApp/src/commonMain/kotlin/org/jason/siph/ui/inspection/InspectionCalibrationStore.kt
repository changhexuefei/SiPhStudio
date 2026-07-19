package org.jason.siph.ui.inspection

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
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.inspection.CameraAcquisitionPort
import org.jason.siph.domain.inspection.CameraStageCalibration
import org.jason.siph.domain.inspection.DefaultInspectionCalibrationRunner
import org.jason.siph.domain.inspection.InspectionCalibrationRepository
import org.jason.siph.domain.inspection.InspectionCalibrationRunRequest
import org.jason.siph.domain.inspection.InspectionCalibrationRunner
import org.jason.siph.domain.inspection.InspectionCalibrationState
import org.jason.siph.domain.inspection.PivotCalibrationResult
import org.jason.siph.domain.inspection.ProbeHeightProfile
import org.jason.siph.domain.inspection.TemperatureRecalibrationPolicy
import org.jason.siph.domain.inspection.TemperatureRecalibrationResult
import org.jason.siph.domain.inspection.VisionFeatureKind
import org.jason.siph.domain.inspection.VisionPointPx
import org.jason.siph.domain.inspection.ZDisplacementSensorPort
import org.jason.siph.domain.oo.DeviceBackendMode
import org.jason.siph.domain.oo.TemperatureStabilityPolicy

data class InspectionEquipmentUiState(
    val camera: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val zSensor: AutonomyCapabilityStatus = AutonomyCapabilityStatus()
) {
    val readyCount: Int
        get() = listOf(camera, zSensor).count { it.healthy }
}

data class InspectionCalibrationUiState(
    val workflow: InspectionCalibrationState = InspectionCalibrationState(),
    val equipment: InspectionEquipmentUiState = InspectionEquipmentUiState(),
    val cameraCalibrations: List<CameraStageCalibration> = emptyList(),
    val heightProfiles: List<ProbeHeightProfile> = emptyList(),
    val pivotCalibrations: List<PivotCalibrationResult> = emptyList(),
    val recentRuns: List<TemperatureRecalibrationResult> = emptyList(),
    val simulationBackend: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Phase-three assets are loading",
    val errorMessage: String? = null
) {
    val verifiedCameraCalibrationCount: Int
        get() = cameraCalibrations.count { it.verified }

    val verifiedHeightCount: Int
        get() = heightProfiles.count { it.verified }

    val verifiedPivotCount: Int
        get() = pivotCalibrations.count { it.verified }

    val completedRunCount: Int
        get() = recentRuns.count { it.completed }

    val canRunDemo: Boolean
        get() = simulationBackend && !busy && !workflow.running && verifiedCameraCalibrationCount > 0
}

sealed interface InspectionCalibrationAction {
    data object Refresh : InspectionCalibrationAction
    data object RunDemo : InspectionCalibrationAction
    data object Stop : InspectionCalibrationAction
}

class InspectionCalibrationStore(
    private val scope: CoroutineScope,
    private val runner: InspectionCalibrationRunner,
    private val repository: InspectionCalibrationRepository,
    private val camera: CameraAcquisitionPort,
    private val zSensor: ZDisplacementSensorPort,
    private val nowEpochMs: () -> Long
) {
    private val mutableState = MutableStateFlow(
        InspectionCalibrationUiState(
            simulationBackend = listOf(
                camera.descriptor.backendMode,
                zSensor.descriptor.backendMode
            ).all { it == DeviceBackendMode.Simulation }
        )
    )
    val state: StateFlow<InspectionCalibrationUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    init {
        scope.launch {
            combine(camera.status, zSensor.status) { cameraStatus, sensorStatus ->
                InspectionEquipmentUiState(cameraStatus, sensorStatus)
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
                        errorMessage = workflow.errorMessage
                    )
                }
                if (!workflow.running) refreshAssets()
            }
        }
        dispatch(InspectionCalibrationAction.Refresh)
    }

    fun dispatch(action: InspectionCalibrationAction) {
        when (action) {
            InspectionCalibrationAction.Refresh -> refresh()
            InspectionCalibrationAction.RunDemo -> runDemo()
            InspectionCalibrationAction.Stop -> scope.launch { runner.requestStop() }
        }
    }

    private fun refresh() {
        if (mutableState.value.workflow.running) return
        scope.launch {
            mutableState.update { it.copy(busy = true, errorMessage = null) }
            runCatching {
                ensureDemoCalibration()
                refreshAssets()
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = "Phase-three assets refreshed",
                        errorMessage = null
                    )
                }
            }.onFailure(::publishFailure)
        }
    }

    private fun runDemo() {
        val snapshot = mutableState.value
        if (!snapshot.simulationBackend) {
            publishFailure(IllegalStateException("Phase-three demo is only available for simulation devices"))
            return
        }
        val calibration = snapshot.cameraCalibrations.firstOrNull { it.verified }
        if (calibration == null) {
            publishFailure(IllegalStateException("No verified camera calibration is available"))
            return
        }
        if (activeJob?.isActive == true || snapshot.workflow.running) return
        val timestamp = nowEpochMs()
        activeJob = scope.launch {
            mutableState.update {
                it.copy(busy = true, message = "Starting phase-three digital calibration", errorMessage = null)
            }
            try {
                runner.run(
                    InspectionCalibrationRunRequest(
                        runId = "inspection-run-$timestamp",
                        site = demoSite(),
                        targetKind = VisionFeatureKind.Grating,
                        cameraCalibrationId = calibration.id,
                        policy = TemperatureRecalibrationPolicy(
                            temperaturesC = listOf(25.0, 45.0),
                            runProbeHeightTraining = true,
                            runPivotCalibrationAtFirstTemperature = true,
                            rerunPivotWhenOffsetExceedsUm = 1.0
                        ),
                        temperatureStability = TemperatureStabilityPolicy(
                            targetToleranceC = 0.2,
                            maximumSlopeCPerMinute = 1.0,
                            stableWindowMs = 0L,
                            timeoutMs = 1_000L,
                            pollIntervalMs = 1L
                        ),
                        approachDirectionSign = -1,
                        manageConnections = true
                    )
                )
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = "Phase-three digital calibration completed")
                }
            } catch (cancelled: CancellationException) {
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = cancelled.message ?: "Phase-three calibration stopped")
                }
            } catch (error: Throwable) {
                refreshAssets()
                publishFailure(error)
            } finally {
                activeJob = null
            }
        }
    }

    private suspend fun ensureDemoCalibration() {
        if (!mutableState.value.simulationBackend) return
        if (repository.findCameraCalibration(DEMO_CAMERA_CALIBRATION_ID) != null) return
        repository.saveCameraCalibration(
            CameraStageCalibration(
                id = DEMO_CAMERA_CALIBRATION_ID,
                cameraId = camera.descriptor.id,
                opticalCenterPx = VisionPointPx(80.0, 60.0),
                micrometersPerPixelX = 1.0,
                micrometersPerPixelY = 1.0,
                cameraToStageRotationDeg = 0.0,
                calibratedAtEpochMs = nowEpochMs(),
                verified = true,
                rmsErrorUm = 0.05
            )
        )
    }

    private suspend fun refreshAssets() {
        mutableState.update {
            it.copy(
                cameraCalibrations = repository.listCameraCalibrations(),
                heightProfiles = repository.listHeightProfiles(),
                pivotCalibrations = repository.listPivotCalibrations(),
                recentRuns = repository.listTemperatureRecalibrations(limit = 20)
            )
        }
    }

    private fun publishFailure(error: Throwable) {
        mutableState.update {
            it.copy(
                busy = false,
                message = "Phase-three operation failed",
                errorMessage = error.message ?: error::class.simpleName
            )
        }
    }

    private fun demoSite() = MeasurementSiteKey(
        waferId = "inspection-demo-wafer",
        die = DieIndex(column = 0, row = 0),
        subDieId = "sub-0",
        couplerId = "grating-0"
    )

    private companion object {
        const val DEMO_CAMERA_CALIBRATION_ID = "demo-camera-stage-calibration"
    }
}
