package org.jason.siph.ui.model

import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint

enum class CouplingToolPage(
    val title: String,
    val caption: String
) {
    Coupling("Auto Coupling", "Search and optimize power"),
    PivotSetup("Pivot Setup", "Set rotation center"),
    ManualControl("Manual Control", "Jog positioner axes"),
    MotionSafety("Motion Safety", "Soft limits, clearance path and interlock")
}

enum class CouplingToolRunState(
    val text: String
) {
    Idle("Idle"),
    Running("Running"),
    Completed("Completed"),
    Stopped("Stopped"),
    Error("Error")
}

data class CouplingToolUiState(
    val selectedPage: CouplingToolPage = CouplingToolPage.Coupling,
    val runState: CouplingToolRunState = CouplingToolRunState.Idle,
    val positioner: PositionerUiState = PositionerUiState(),
    val coupling: CouplingUiState = CouplingUiState(),
    val status: CouplingToolStatusState = CouplingToolStatusState()
) {
    val canStartCoupling: Boolean
        get() = positioner.connected &&
            !positioner.connecting &&
            !positioner.isMoving &&
            !coupling.isRunning &&
            coupling.canResolveStartPose
}

data class CouplingToolStatusState(
    val deviceText: String = "PI: Disconnected | PowerMeter: Disconnected",
    val powerText: String = "Power: -- dBm",
    val stateText: String = "State: Idle",
    val message: String = "Ready",
    val isError: Boolean = false
)

data class PositionerUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val idn: String? = null,
    val currentPose: OpticalPose = OpticalPose.ZERO,
    val safePose: OpticalPose = OpticalPose.ZERO,
    val linearStepUm: Double = 1.0,
    val angleStepDeg: Double = 0.01,
    val isMoving: Boolean = false,
    val errorMessage: String? = null
)

enum class CouplingState(
    val text: String
) {
    Idle("Idle"),
    Initializing("Initializing"),
    SpiralSearching("Spiral Searching"),
    FineOptimizing("Fine Optimizing"),
    AngleOptimizing("Angle Optimizing"),
    Finalizing("Finalizing"),
    Coupled("Coupled"),
    Completed("Completed"),
    Failed("Failed"),
    Stopped("Stopped")
}

enum class CouplingPlane(
    val text: String
) {
    XY("XY"),
    YZ("YZ"),
    XZ("XZ")
}

/** 自动耦光每一轮的起点策略。 */
enum class CouplingStartMode(
    val text: String,
    val caption: String
) {
    CurrentPose(
        text = "Current",
        caption = "Start from the current positioner pose"
    ),

    PreviousRunStart(
        text = "Previous Start",
        caption = "Return to the previous run start pose"
    ),

    SafePose(
        text = "Safe Pose",
        caption = "Move to the saved safe pose before searching"
    )
}

data class CouplingConfigUiState(
    val wavelengthNm: Double = 1550.0,
    val powerMeterChannel: Int = 1,
    val plane: CouplingPlane = CouplingPlane.XY,
    val startMode: CouplingStartMode = CouplingStartMode.CurrentPose,
    val firstLightThresholdDbm: Double = -40.0,
    val targetPowerDbm: Double = -10.0,
    val spiralStepUm: Double = 3.0,
    val maxRadiusUm: Double = 35.0,
    val settleDelayMs: Long = 8L,
    val powerAverageCount: Int = 3,
    val powerAverageDelayMs: Long = 3L,
    val enableFineXyz: Boolean = true,
    val minImproveDb: Double = 0.02,
    val maxFinePassesPerStep: Int = 10,
    val enableAngleOptimization: Boolean = false,
    val virtualPivotPoint: VirtualPivotPoint = VirtualPivotPoint.Disabled,
    val enableSoftwarePivotCompensation: Boolean = false,
    val maxTotalSamples: Int = 1500,

    /** 达到目标功率后是否立即结束本轮搜索。 */
    val stopWhenTargetReached: Boolean = false
)

enum class CouplingStageUi(
    val text: String
) {
    Initial("Initial"),
    SpiralFirstLight("Spiral"),
    FineXyz("Fine XYZ"),
    OptimizeU("Optimize U"),
    OptimizeV("Optimize V"),
    OptimizeW("Optimize W"),
    Final("Final")
}

data class CouplingSampleUi(
    val index: Int,
    val pose: OpticalPose,
    val powerDbm: Double,
    val stage: CouplingStageUi,
    val timestampMs: Long = 0L
)

data class CouplingUiState(
    val state: CouplingState = CouplingState.Idle,
    val config: CouplingConfigUiState = CouplingConfigUiState(),
    val currentStage: CouplingStageUi? = null,
    val currentPowerDbm: Double? = null,
    val bestPowerDbm: Double? = null,
    val bestPose: OpticalPose? = null,
    val activeRunStartPose: OpticalPose? = null,
    val previousRunStartPose: OpticalPose? = null,
    val samples: List<CouplingSampleUi> = emptyList(),
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val stopRequested: Boolean = false,
    val progress: Float = 0f,
    val estimatedSamples: Int = 0,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val message: String? = null,
    val errorMessage: String? = null
) {
    val sampleCount: Int
        get() = samples.size

    val canResolveStartPose: Boolean
        get() = config.startMode != CouplingStartMode.PreviousRunStart ||
            previousRunStartPose != null
}

sealed interface CouplingToolAction {
    data class SelectPage(val page: CouplingToolPage) : CouplingToolAction

    data object ConnectPositioner : CouplingToolAction
    data object DisconnectPositioner : CouplingToolAction
    data object ReadPose : CouplingToolAction
    data object MoveSafe : CouplingToolAction
    data object StopPositioner : CouplingToolAction
    data class JogPositioner(val delta: OpticalDelta) : CouplingToolAction
    data class UpdateLinearStep(val valueUm: Double) : CouplingToolAction
    data class UpdateAngleStep(val valueDeg: Double) : CouplingToolAction

    data class UpdateCouplingConfig(
        val config: CouplingConfigUiState
    ) : CouplingToolAction

    data class UpdateVirtualPivot(
        val pivot: VirtualPivotPoint
    ) : CouplingToolAction

    data object CapturePivotFromCurrentPose : CouplingToolAction
    data object DisableVirtualPivot : CouplingToolAction
    data object StartCoupling : CouplingToolAction
    data object StopCoupling : CouplingToolAction
    data object ClearCouplingData : CouplingToolAction
    data object SaveBestPose : CouplingToolAction
}
