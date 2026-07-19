package org.jason.siph.ui.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.CalibrationProfile
import org.jason.siph.domain.autonomy.CalibrationProfileRepository
import org.jason.siph.domain.autonomy.MeasurementPositionRepository
import org.jason.siph.domain.autonomy.MeasurementPositionTrainer
import org.jason.siph.domain.autonomy.MeasurementPositionTrainingRequest
import org.jason.siph.domain.autonomy.MeasurementRecordRepository
import org.jason.siph.domain.autonomy.ProbeTrackingPort
import org.jason.siph.domain.autonomy.SiPhMeasurementRecord
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.SiPhWorkflowCheckpoint
import org.jason.siph.domain.autonomy.SiPhWorkflowRecipe
import org.jason.siph.domain.autonomy.SiPhWorkflowRunner
import org.jason.siph.domain.autonomy.SiPhWorkflowStage
import org.jason.siph.domain.autonomy.SiPhWorkflowState
import org.jason.siph.domain.autonomy.TrainedMeasurementPosition
import org.jason.siph.domain.autonomy.VisionAlignmentPort
import org.jason.siph.domain.autonomy.WaferDefinitionRepository
import org.jason.siph.domain.autonomy.WaferStagePort
import org.jason.siph.domain.autonomy.WorkflowCheckpointRepository

/** 自主工作流当前正在执行的操作。 */
enum class AutonomousWorkflowOperation {
    Refresh,
    VisionConnect,
    VisionDisconnect,
    WaferStageConnect,
    WaferStageDisconnect,
    ProbeTrackingConnect,
    ProbeTrackingDisconnect,
    ProfileSave,
    ProfileActivate,
    ProfileDelete,
    ProfileClear,
    PositionTrain,
    WaferSave,
    WaferDelete,
    WorkflowStart,
    WorkflowResume
}

data class AutonomousWorkflowUiState(
    val vision: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val waferStage: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val probeTracking: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val profiles: List<CalibrationProfile> = emptyList(),
    val activeProfile: CalibrationProfile? = null,
    val positions: List<TrainedMeasurementPosition> = emptyList(),
    val wafers: List<SiPhWaferDefinition> = emptyList(),
    val checkpoints: List<SiPhWorkflowCheckpoint> = emptyList(),
    val recentRecords: List<SiPhMeasurementRecord> = emptyList(),
    val workflow: SiPhWorkflowState = SiPhWorkflowState(),
    val runningOperation: AutonomousWorkflowOperation? = null,
    val message: String = "Autonomous workflow assets are loading",
    val errorMessage: String? = null
) {
    val configuredCapabilityCount: Int
        get() = listOf(vision, waferStage, probeTracking).count { it.configured }

    val connectedCapabilityCount: Int
        get() = listOf(vision, waferStage, probeTracking).count { it.connected }

    val verifiedProfileApplied: Boolean
        get() = activeProfile?.verified == true

    val verifiedPositionCount: Int
        get() = positions.count { it.verified }

    val recoverableCheckpointCount: Int
        get() = checkpoints.size

    val busy: Boolean
        get() = runningOperation != null || workflow.running

    val readyForFirstStageWorkflow: Boolean
        get() = verifiedProfileApplied && positions.any { position ->
            position.verified && position.calibrationProfileId == activeProfile?.id
        }
}

sealed interface AutonomousWorkflowAction {
    data object Refresh : AutonomousWorkflowAction
    data object ConnectVision : AutonomousWorkflowAction
    data object DisconnectVision : AutonomousWorkflowAction
    data object ConnectWaferStage : AutonomousWorkflowAction
    data object DisconnectWaferStage : AutonomousWorkflowAction
    data object ConnectProbeTracking : AutonomousWorkflowAction
    data object DisconnectProbeTracking : AutonomousWorkflowAction
    data class SaveProfile(val profile: CalibrationProfile) : AutonomousWorkflowAction
    data class ActivateProfile(val id: String) : AutonomousWorkflowAction
    data class DeleteProfile(val id: String) : AutonomousWorkflowAction
    data object ClearActiveProfile : AutonomousWorkflowAction
    data class TrainMeasurementPosition(
        val request: MeasurementPositionTrainingRequest
    ) : AutonomousWorkflowAction
    data class SaveWafer(val wafer: SiPhWaferDefinition) : AutonomousWorkflowAction
    data class DeleteWafer(val id: String) : AutonomousWorkflowAction
    data class StartWorkflow(
        val recipe: SiPhWorkflowRecipe,
        val runId: String,
        val resumeFromCheckpoint: Boolean = true
    ) : AutonomousWorkflowAction
    data object StartLatestTrainedPosition : AutonomousWorkflowAction
    data object ResumeLatestCheckpoint : AutonomousWorkflowAction
    data object StopWorkflow : AutonomousWorkflowAction
}

/**
 * 自主硅光页面的状态引擎。
 *
 * 第一阶段工作流直接编排已有位置器、功率计和耦光算法。所有运动仍由
 * [SiPhWorkflowRunner] 经过安全位置器端口执行，不在 UI Store 中发送运动命令。
 */
class AutonomousWorkflowStore(
    private val scope: CoroutineScope,
    private val vision: VisionAlignmentPort,
    private val waferStage: WaferStagePort,
    private val probeTracking: ProbeTrackingPort,
    private val profiles: CalibrationProfileRepository,
    private val positions: MeasurementPositionRepository,
    private val wafers: WaferDefinitionRepository,
    private val checkpoints: WorkflowCheckpointRepository,
    private val records: MeasurementRecordRepository,
    private val trainer: MeasurementPositionTrainer,
    private val workflowRunner: SiPhWorkflowRunner,
    private val nowEpochMs: () -> Long
) {
    private val mutableState = MutableStateFlow(AutonomousWorkflowUiState())
    val state: StateFlow<AutonomousWorkflowUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                vision.status,
                waferStage.status,
                probeTracking.status,
                profiles.activeProfile
            ) { visionStatus, waferStatus, trackingStatus, activeProfile ->
                CapabilitySnapshot(
                    vision = visionStatus,
                    waferStage = waferStatus,
                    probeTracking = trackingStatus,
                    activeProfile = activeProfile
                )
            }.collect { snapshot ->
                mutableState.update { current ->
                    current.copy(
                        vision = snapshot.vision,
                        waferStage = snapshot.waferStage,
                        probeTracking = snapshot.probeTracking,
                        activeProfile = snapshot.activeProfile,
                        message = if (current.workflow.stage == SiPhWorkflowStage.Idle) {
                            buildCapabilityMessage(snapshot)
                        } else {
                            current.message
                        },
                        errorMessage = null
                    )
                }
            }
        }
        scope.launch {
            workflowRunner.state.collect { workflow ->
                mutableState.update { current ->
                    current.copy(
                        workflow = workflow,
                        message = workflow.message,
                        errorMessage = workflow.lastFailure?.message
                    )
                }
                if (!workflow.running &&
                    workflow.stage in setOf(
                        SiPhWorkflowStage.Completed,
                        SiPhWorkflowStage.Failed,
                        SiPhWorkflowStage.Stopped
                    )
                ) {
                    refreshAssets()
                }
            }
        }
        dispatch(AutonomousWorkflowAction.Refresh)
    }

    fun dispatch(action: AutonomousWorkflowAction) {
        when (action) {
            AutonomousWorkflowAction.Refresh -> runOperation(
                operation = AutonomousWorkflowOperation.Refresh,
                successMessage = "Autonomous workflow assets refreshed"
            ) { refreshAssets() }

            AutonomousWorkflowAction.ConnectVision -> runOperation(
                AutonomousWorkflowOperation.VisionConnect,
                "Vision alignment connected"
            ) { vision.connect() }

            AutonomousWorkflowAction.DisconnectVision -> runOperation(
                AutonomousWorkflowOperation.VisionDisconnect,
                "Vision alignment disconnected"
            ) { vision.disconnect() }

            AutonomousWorkflowAction.ConnectWaferStage -> runOperation(
                AutonomousWorkflowOperation.WaferStageConnect,
                "Wafer stage connected"
            ) { waferStage.connect() }

            AutonomousWorkflowAction.DisconnectWaferStage -> runOperation(
                AutonomousWorkflowOperation.WaferStageDisconnect,
                "Wafer stage disconnected"
            ) { waferStage.disconnect() }

            AutonomousWorkflowAction.ConnectProbeTracking -> runOperation(
                AutonomousWorkflowOperation.ProbeTrackingConnect,
                "Probe tracking connected"
            ) { probeTracking.connect() }

            AutonomousWorkflowAction.DisconnectProbeTracking -> runOperation(
                AutonomousWorkflowOperation.ProbeTrackingDisconnect,
                "Probe tracking disconnected"
            ) { probeTracking.disconnect() }

            is AutonomousWorkflowAction.SaveProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileSave,
                "Calibration profile saved"
            ) {
                profiles.saveProfile(action.profile)
                refreshAssets()
            }

            is AutonomousWorkflowAction.ActivateProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileActivate,
                "Calibration profile activated"
            ) {
                profiles.activateProfile(action.id)
                refreshAssets()
            }

            is AutonomousWorkflowAction.DeleteProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileDelete,
                "Calibration profile deleted"
            ) {
                profiles.deleteProfile(action.id)
                refreshAssets()
            }

            AutonomousWorkflowAction.ClearActiveProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileClear,
                "Active calibration profile cleared"
            ) {
                profiles.clearActiveProfile()
                refreshAssets()
            }

            is AutonomousWorkflowAction.TrainMeasurementPosition -> runOperation(
                AutonomousWorkflowOperation.PositionTrain,
                "Measurement position trained and persisted"
            ) {
                trainer.train(action.request)
                refreshAssets()
            }

            is AutonomousWorkflowAction.SaveWafer -> runOperation(
                AutonomousWorkflowOperation.WaferSave,
                "Wafer definition saved"
            ) {
                wafers.saveWafer(action.wafer)
                refreshAssets()
            }

            is AutonomousWorkflowAction.DeleteWafer -> runOperation(
                AutonomousWorkflowOperation.WaferDelete,
                "Wafer definition deleted"
            ) {
                wafers.deleteWafer(action.id)
                refreshAssets()
            }

            is AutonomousWorkflowAction.StartWorkflow -> startWorkflow(
                recipe = action.recipe,
                runId = action.runId,
                resumeFromCheckpoint = action.resumeFromCheckpoint,
                operation = if (action.resumeFromCheckpoint) {
                    AutonomousWorkflowOperation.WorkflowResume
                } else {
                    AutonomousWorkflowOperation.WorkflowStart
                }
            )

            AutonomousWorkflowAction.StartLatestTrainedPosition -> startLatestTrainedPosition()
            AutonomousWorkflowAction.ResumeLatestCheckpoint -> resumeLatestCheckpoint()
            AutonomousWorkflowAction.StopWorkflow -> scope.launch {
                workflowRunner.requestStop()
            }
        }
    }

    private fun startLatestTrainedPosition() {
        val snapshot = mutableState.value
        val profile = snapshot.activeProfile
        if (profile?.verified != true) {
            updateError("A verified calibration profile is required")
            return
        }
        val position = snapshot.positions.firstOrNull {
            it.verified && it.calibrationProfileId == profile.id
        }
        if (position == null) {
            updateError("No verified trained position exists for the active calibration")
            return
        }
        val timestamp = nowEpochMs()
        val recipe = SiPhWorkflowRecipe(
            id = "guided-$timestamp",
            site = position.site,
            calibrationProfileId = profile.id,
            trainedPositionId = position.id
        )
        startWorkflow(
            recipe = recipe,
            runId = "run-$timestamp",
            resumeFromCheckpoint = false,
            operation = AutonomousWorkflowOperation.WorkflowStart
        )
    }

    private fun resumeLatestCheckpoint() {
        val checkpoint = mutableState.value.checkpoints.firstOrNull()
        if (checkpoint == null) {
            updateError("No recoverable workflow checkpoint exists")
            return
        }
        startWorkflow(
            recipe = checkpoint.recipe,
            runId = checkpoint.runId,
            resumeFromCheckpoint = true,
            operation = AutonomousWorkflowOperation.WorkflowResume
        )
    }

    private fun startWorkflow(
        recipe: SiPhWorkflowRecipe,
        runId: String,
        resumeFromCheckpoint: Boolean,
        operation: AutonomousWorkflowOperation
    ) {
        runOperation(
            operation = operation,
            successMessage = "Autonomous workflow completed"
        ) {
            workflowRunner.run(
                recipe = recipe,
                runId = runId,
                resumeFromCheckpoint = resumeFromCheckpoint
            )
            refreshAssets()
        }
    }

    private fun runOperation(
        operation: AutonomousWorkflowOperation,
        successMessage: String,
        block: suspend () -> Unit
    ) {
        if (mutableState.value.busy) return

        scope.launch {
            mutableState.update {
                it.copy(runningOperation = operation, errorMessage = null)
            }

            try {
                block()
                mutableState.update {
                    it.copy(
                        runningOperation = null,
                        message = successMessage,
                        errorMessage = null
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(runningOperation = null) }
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        runningOperation = null,
                        errorMessage = error.message ?: error::class.simpleName,
                        message = "Autonomous operation failed"
                    )
                }
            }
        }
    }

    private suspend fun refreshAssets() {
        val availableProfiles = profiles.listProfiles()
        val availablePositions = positions.listPositions()
        val availableWafers = wafers.listWafers()
        val recoverable = checkpoints.listCheckpoints()
        val recent = records.listRecords(limit = 20)
        mutableState.update {
            it.copy(
                profiles = availableProfiles,
                positions = availablePositions,
                wafers = availableWafers,
                checkpoints = recoverable,
                recentRecords = recent
            )
        }
    }

    private fun updateError(message: String) {
        mutableState.update { it.copy(errorMessage = message, message = message) }
    }

    private fun buildCapabilityMessage(snapshot: CapabilitySnapshot): String = when {
        snapshot.activeProfile?.verified == true ->
            "Verified calibration is active; first-stage workflow assets can be prepared"
        snapshot.activeProfile == null -> "No calibration profile is active"
        snapshot.activeProfile.verified.not() -> "Active calibration profile is not verified"
        else -> "Autonomous workflow setup is incomplete"
    }

    private data class CapabilitySnapshot(
        val vision: AutonomyCapabilityStatus,
        val waferStage: AutonomyCapabilityStatus,
        val probeTracking: AutonomyCapabilityStatus,
        val activeProfile: CalibrationProfile?
    )
}
