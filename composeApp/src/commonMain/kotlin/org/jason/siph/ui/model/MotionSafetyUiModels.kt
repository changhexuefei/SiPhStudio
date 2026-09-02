package org.jason.siph.ui.model

import org.jason.siph.domain.runtime.HardwareRuntimeMode

/** 软限位编辑值。 */
data class AxisSoftLimitUiState(
    val minimum: Double,
    val maximum: Double
)

/** Compose 页面中的安全配置草稿。 */
data class MotionSafetyConfigUiState(
    val enabled: Boolean = true,
    val xLimitUm: AxisSoftLimitUiState = AxisSoftLimitUiState(-100.0, 100.0),
    val yLimitUm: AxisSoftLimitUiState = AxisSoftLimitUiState(-100.0, 100.0),
    val zLimitUm: AxisSoftLimitUiState = AxisSoftLimitUiState(-50.0, 50.0),
    val uLimitDeg: AxisSoftLimitUiState = AxisSoftLimitUiState(-5.0, 5.0),
    val vLimitDeg: AxisSoftLimitUiState = AxisSoftLimitUiState(-5.0, 5.0),
    val wLimitDeg: AxisSoftLimitUiState = AxisSoftLimitUiState(-5.0, 5.0),
    val protectedTransferEnabled: Boolean = true,
    val clearanceZUm: Double = 20.0,
    val protectedLinearThresholdUm: Double = 15.0,
    val protectedAngleThresholdDeg: Double = 0.15
)

enum class SafetyInterlockStatus(
    val text: String
) {
    Ready("Ready"),
    NotReady("Not Ready"),
    Invalid("Invalid")
}

enum class MotionSafetyProfileSource(
    val text: String
) {
    DemoPreset("Demo preset"),
    UserVerified("User verified"),
    Unconfigured("Unconfigured")
}

data class MotionSafetyUiState(
    val runtimeMode: HardwareRuntimeMode,
    val profileName: String = "",
    val confirmedForCurrentFixture: Boolean = false,
    val draft: MotionSafetyConfigUiState = MotionSafetyConfigUiState(),
    val applied: MotionSafetyConfigUiState? = null,
    val source: MotionSafetyProfileSource = MotionSafetyProfileSource.Unconfigured,
    val interlockStatus: SafetyInterlockStatus = SafetyInterlockStatus.NotReady,
    val isDirty: Boolean = false,
    val message: String = "No validated safety profile is applied",
    val errorMessage: String? = null
) {
    val interlockReady: Boolean
        get() = interlockStatus == SafetyInterlockStatus.Ready && applied != null

    val requiresOperatorConfirmation: Boolean
        get() = runtimeMode == HardwareRuntimeMode.Real
}

sealed interface MotionSafetyAction {
    data class UpdateDraft(
        val value: MotionSafetyConfigUiState
    ) : MotionSafetyAction

    data class UpdateProfileName(
        val value: String
    ) : MotionSafetyAction

    data class SetFixtureConfirmed(
        val confirmed: Boolean
    ) : MotionSafetyAction

    data object ApplyProfile : MotionSafetyAction
    data object ResetDraftToApplied : MotionSafetyAction
    data object ClearAppliedProfile : MotionSafetyAction
    data object LoadDemoTemplate : MotionSafetyAction
}
