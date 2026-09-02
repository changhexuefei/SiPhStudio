package org.jason.siph.ui.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.domain.safety.AxisSoftLimit
import org.jason.siph.domain.safety.MotionSafetyConfig
import org.jason.siph.domain.safety.MotionSafetyPlanner
import org.jason.siph.ui.model.AxisSoftLimitUiState
import org.jason.siph.ui.model.MotionSafetyAction
import org.jason.siph.ui.model.MotionSafetyConfigUiState
import org.jason.siph.ui.model.MotionSafetyProfileSource
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.model.SafetyInterlockStatus

/**
 * 安全参数页面的单一状态源。
 *
 * 草稿只有在通过数据校验，并满足 Real 模式的配置名称和设备/夹具确认要求后，
 * 才会写入共享 [MotionSafetyPlanner] 并解除运动互锁。
 */
class MotionSafetySettingsStore(
    private val runtimeMode: HardwareRuntimeMode,
    private val planner: MotionSafetyPlanner
) {
    private val demoConfig = MotionSafetyConfig.demoDefault()
    private val demoUiConfig = demoConfig.toUi()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<MotionSafetyUiState> = _state.asStateFlow()

    fun dispatch(action: MotionSafetyAction) {
        when (action) {
            is MotionSafetyAction.UpdateDraft -> updateDraft(action.value)
            is MotionSafetyAction.UpdateProfileName -> updateProfileName(action.value)
            is MotionSafetyAction.SetFixtureConfirmed -> updateConfirmation(action.confirmed)
            MotionSafetyAction.ApplyProfile -> applyProfile()
            MotionSafetyAction.ResetDraftToApplied -> resetDraftToApplied()
            MotionSafetyAction.ClearAppliedProfile -> clearAppliedProfile()
            MotionSafetyAction.LoadDemoTemplate -> loadDemoTemplate()
        }
    }

    fun requireInterlockReady() {
        check(_state.value.interlockReady) {
            _state.value.errorMessage
                ?: "Motion safety interlock is not ready; apply a validated safety profile first"
        }
        planner.requireConfigured()
    }

    private fun initialState(): MotionSafetyUiState {
        return when (runtimeMode) {
            HardwareRuntimeMode.Demo -> {
                planner.updateConfig(demoConfig)
                MotionSafetyUiState(
                    runtimeMode = runtimeMode,
                    profileName = "Offline Demo Safety",
                    confirmedForCurrentFixture = true,
                    draft = demoUiConfig,
                    applied = demoUiConfig,
                    source = MotionSafetyProfileSource.DemoPreset,
                    interlockStatus = SafetyInterlockStatus.Ready,
                    isDirty = false,
                    message = "Demo safety profile applied. Do not use these limits on real hardware."
                )
            }

            HardwareRuntimeMode.Real -> {
                planner.updateConfig(null)
                MotionSafetyUiState(
                    runtimeMode = runtimeMode,
                    profileName = "",
                    confirmedForCurrentFixture = false,
                    draft = demoUiConfig,
                    applied = null,
                    source = MotionSafetyProfileSource.Unconfigured,
                    interlockStatus = SafetyInterlockStatus.NotReady,
                    isDirty = true,
                    message = "Real mode is locked until verified limits and clearance Z are applied."
                )
            }
        }
    }

    private fun updateDraft(value: MotionSafetyConfigUiState) {
        val validationError = runCatching { value.toDomain() }
            .exceptionOrNull()
            ?.message

        _state.update { current ->
            current.copy(
                draft = value,
                isDirty = value != current.applied,
                interlockStatus = when {
                    validationError != null -> SafetyInterlockStatus.Invalid
                    current.applied != null -> SafetyInterlockStatus.Ready
                    else -> SafetyInterlockStatus.NotReady
                },
                message = when {
                    validationError != null -> "Safety draft is invalid"
                    current.applied != null && value != current.applied ->
                        "Draft changed; the previously applied profile remains active"
                    current.applied != null -> "Applied safety profile is active"
                    else -> "Apply a validated profile to release the motion interlock"
                },
                errorMessage = validationError
            )
        }
    }

    private fun updateProfileName(value: String) {
        _state.update {
            it.copy(
                profileName = value,
                isDirty = true,
                message = if (it.applied != null) {
                    "Profile metadata changed; reapply to confirm"
                } else {
                    "Enter a profile name and verify it for the current fixture"
                },
                errorMessage = null
            )
        }
    }

    private fun updateConfirmation(confirmed: Boolean) {
        _state.update {
            it.copy(
                confirmedForCurrentFixture = confirmed,
                isDirty = true,
                message = if (confirmed) {
                    "Operator confirmation recorded; apply the profile to release the interlock"
                } else {
                    "Fixture confirmation removed"
                },
                errorMessage = null
            )
        }
    }

    private fun applyProfile() {
        val snapshot = _state.value
        val result = runCatching {
            val config = snapshot.draft.toDomain()
            if (runtimeMode == HardwareRuntimeMode.Real) {
                require(snapshot.profileName.isNotBlank()) {
                    "Real mode requires a non-empty safety profile name"
                }
                require(snapshot.confirmedForCurrentFixture) {
                    "Confirm that the limits and clearance Z match the current device and fixture"
                }
            }
            config
        }

        result.onSuccess { config ->
            planner.updateConfig(config)
            _state.update {
                it.copy(
                    applied = it.draft,
                    source = if (runtimeMode == HardwareRuntimeMode.Demo) {
                        MotionSafetyProfileSource.DemoPreset
                    } else {
                        MotionSafetyProfileSource.UserVerified
                    },
                    interlockStatus = SafetyInterlockStatus.Ready,
                    isDirty = false,
                    message = if (runtimeMode == HardwareRuntimeMode.Demo) {
                        "Demo safety profile applied"
                    } else {
                        "Verified safety profile '${it.profileName}' applied; motion interlock ready"
                    },
                    errorMessage = null
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    interlockStatus = SafetyInterlockStatus.Invalid,
                    message = "Safety profile was not applied",
                    errorMessage = error.message ?: "Invalid safety profile"
                )
            }
        }
    }

    private fun resetDraftToApplied() {
        _state.update { current ->
            val target = current.applied ?: demoUiConfig
            current.copy(
                draft = target,
                isDirty = false,
                interlockStatus = if (current.applied != null) {
                    SafetyInterlockStatus.Ready
                } else {
                    SafetyInterlockStatus.NotReady
                },
                message = if (current.applied != null) {
                    "Draft reset to the active safety profile"
                } else {
                    "Draft reset to the template; no profile is applied"
                },
                errorMessage = null
            )
        }
    }

    private fun clearAppliedProfile() {
        if (runtimeMode == HardwareRuntimeMode.Demo) {
            planner.updateConfig(demoConfig)
            _state.value = initialState()
            return
        }

        planner.updateConfig(null)
        _state.update {
            it.copy(
                applied = null,
                source = MotionSafetyProfileSource.Unconfigured,
                interlockStatus = SafetyInterlockStatus.NotReady,
                isDirty = true,
                confirmedForCurrentFixture = false,
                message = "Applied profile cleared; all motion is locked",
                errorMessage = null
            )
        }
    }

    private fun loadDemoTemplate() {
        _state.update {
            it.copy(
                draft = demoUiConfig,
                isDirty = demoUiConfig != it.applied,
                message = if (runtimeMode == HardwareRuntimeMode.Real) {
                    "Demo values loaded as an editing template only; verify every value before applying"
                } else {
                    "Demo safety template loaded"
                },
                errorMessage = null
            )
        }
    }
}

private fun MotionSafetyConfigUiState.toDomain(): MotionSafetyConfig {
    return MotionSafetyConfig(
        enabled = enabled,
        xLimitUm = xLimitUm.toDomain("X"),
        yLimitUm = yLimitUm.toDomain("Y"),
        zLimitUm = zLimitUm.toDomain("Z"),
        uLimitDeg = uLimitDeg.toDomain("U"),
        vLimitDeg = vLimitDeg.toDomain("V"),
        wLimitDeg = wLimitDeg.toDomain("W"),
        protectedTransferEnabled = protectedTransferEnabled,
        clearanceZUm = clearanceZUm,
        protectedLinearThresholdUm = protectedLinearThresholdUm,
        protectedAngleThresholdDeg = protectedAngleThresholdDeg
    )
}

private fun AxisSoftLimitUiState.toDomain(axis: String): AxisSoftLimit {
    require(minimum.isFinite() && maximum.isFinite()) {
        "$axis limits must be finite"
    }
    return AxisSoftLimit(minimum, maximum)
}

private fun MotionSafetyConfig.toUi(): MotionSafetyConfigUiState {
    return MotionSafetyConfigUiState(
        enabled = enabled,
        xLimitUm = xLimitUm.toUi(),
        yLimitUm = yLimitUm.toUi(),
        zLimitUm = zLimitUm.toUi(),
        uLimitDeg = uLimitDeg.toUi(),
        vLimitDeg = vLimitDeg.toUi(),
        wLimitDeg = wLimitDeg.toUi(),
        protectedTransferEnabled = protectedTransferEnabled,
        clearanceZUm = clearanceZUm,
        protectedLinearThresholdUm = protectedLinearThresholdUm,
        protectedAngleThresholdDeg = protectedAngleThresholdDeg
    )
}

private fun AxisSoftLimit.toUi(): AxisSoftLimitUiState =
    AxisSoftLimitUiState(minimum = minimum, maximum = maximum)
