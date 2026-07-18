package org.jason.siph.ui.autonomy

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
import org.jason.siph.domain.autonomy.ProbeTrackingPort
import org.jason.siph.domain.autonomy.VisionAlignmentPort
import org.jason.siph.domain.autonomy.WaferStagePort

/** 自主工作流当前正在执行的外部能力操作。 */
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
    ProfileClear
}

data class AutonomousWorkflowUiState(
    val vision: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val waferStage: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val probeTracking: AutonomyCapabilityStatus = AutonomyCapabilityStatus(),
    val profiles: List<CalibrationProfile> = emptyList(),
    val activeProfile: CalibrationProfile? = null,
    val runningOperation: AutonomousWorkflowOperation? = null,
    val message: String = "Autonomous adapters are not configured",
    val errorMessage: String? = null
) {
    val configuredCapabilityCount: Int
        get() = listOf(vision, waferStage, probeTracking).count { it.configured }

    val connectedCapabilityCount: Int
        get() = listOf(vision, waferStage, probeTracking).count { it.connected }

    val verifiedProfileApplied: Boolean
        get() = activeProfile?.verified == true

    val busy: Boolean
        get() = runningOperation != null
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
}

/**
 * 自主硅光页面的工作流状态引擎。
 *
 * 它只编排能力端口与配置仓库，不直接发送六轴运动命令。所有真正的位置器运动
 * 仍必须经过已有的 CouplingToolStore 和 SafetyCheckedOpticalPositioner。
 */
class AutonomousWorkflowStore(
    private val scope: CoroutineScope,
    private val vision: VisionAlignmentPort,
    private val waferStage: WaferStagePort,
    private val probeTracking: ProbeTrackingPort,
    private val profiles: CalibrationProfileRepository
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
                        message = buildMessage(snapshot),
                        errorMessage = null
                    )
                }
            }
        }
        dispatch(AutonomousWorkflowAction.Refresh)
    }

    fun dispatch(action: AutonomousWorkflowAction) {
        when (action) {
            AutonomousWorkflowAction.Refresh -> runOperation(
                AutonomousWorkflowOperation.Refresh,
                successMessage = "Autonomous capability state refreshed"
            ) {
                refreshProfiles()
            }

            AutonomousWorkflowAction.ConnectVision -> runOperation(
                AutonomousWorkflowOperation.VisionConnect,
                successMessage = "Vision alignment connected"
            ) { vision.connect() }

            AutonomousWorkflowAction.DisconnectVision -> runOperation(
                AutonomousWorkflowOperation.VisionDisconnect,
                successMessage = "Vision alignment disconnected"
            ) { vision.disconnect() }

            AutonomousWorkflowAction.ConnectWaferStage -> runOperation(
                AutonomousWorkflowOperation.WaferStageConnect,
                successMessage = "Wafer stage connected"
            ) { waferStage.connect() }

            AutonomousWorkflowAction.DisconnectWaferStage -> runOperation(
                AutonomousWorkflowOperation.WaferStageDisconnect,
                successMessage = "Wafer stage disconnected"
            ) { waferStage.disconnect() }

            AutonomousWorkflowAction.ConnectProbeTracking -> runOperation(
                AutonomousWorkflowOperation.ProbeTrackingConnect,
                successMessage = "Probe tracking connected"
            ) { probeTracking.connect() }

            AutonomousWorkflowAction.DisconnectProbeTracking -> runOperation(
                AutonomousWorkflowOperation.ProbeTrackingDisconnect,
                successMessage = "Probe tracking disconnected"
            ) { probeTracking.disconnect() }

            is AutonomousWorkflowAction.SaveProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileSave,
                successMessage = "Calibration profile saved"
            ) {
                profiles.saveProfile(action.profile)
                refreshProfiles()
            }

            is AutonomousWorkflowAction.ActivateProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileActivate,
                successMessage = "Calibration profile activated"
            ) { profiles.activateProfile(action.id) }

            is AutonomousWorkflowAction.DeleteProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileDelete,
                successMessage = "Calibration profile deleted"
            ) {
                profiles.deleteProfile(action.id)
                refreshProfiles()
            }

            AutonomousWorkflowAction.ClearActiveProfile -> runOperation(
                AutonomousWorkflowOperation.ProfileClear,
                successMessage = "Active calibration profile cleared"
            ) { profiles.clearActiveProfile() }
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
                it.copy(
                    runningOperation = operation,
                    errorMessage = null
                )
            }

            runCatching { block() }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            runningOperation = null,
                            message = successMessage,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
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

    private suspend fun refreshProfiles() {
        val available = profiles.listProfiles()
        mutableState.update { it.copy(profiles = available) }
    }

    private fun buildMessage(snapshot: CapabilitySnapshot): String = when {
        snapshot.activeProfile?.verified == true &&
            listOf(snapshot.vision, snapshot.waferStage, snapshot.probeTracking)
                .all { !it.configured || it.connected } ->
            "Verified profile and configured adapters are ready"

        snapshot.activeProfile == null ->
            "No calibration profile is active"

        snapshot.activeProfile.verified.not() ->
            "Active calibration profile is not verified"

        else ->
            "One or more configured adapters are not ready"
    }

    private data class CapabilitySnapshot(
        val vision: AutonomyCapabilityStatus,
        val waferStage: AutonomyCapabilityStatus,
        val probeTracking: AutonomyCapabilityStatus,
        val activeProfile: CalibrationProfile?
    )
}
