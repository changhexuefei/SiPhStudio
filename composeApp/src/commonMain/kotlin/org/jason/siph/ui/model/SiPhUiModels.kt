package org.jason.siph.ui.model

import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint

enum class CouplingToolPage(
    val title: String,
    val caption: String
) {
    Coupling(
        title = "Auto Coupling",
        caption = "Search and optimize power"
    ),
    PivotSetup(
        title = "Pivot Setup",
        caption = "Set rotation center"
    ),
    ManualControl(
        title = "Manual Control",
        caption = "Jog positioner axes"
    )
}

enum class CouplingToolRunState(
    val text: String
) {
    Idle("Idle"),
    Running("Running"),
    Stopped("Stopped"),
    Error("Error")
}

data class CouplingToolUiState(
    val selectedPage: CouplingToolPage = CouplingToolPage.Coupling,
    val runState: CouplingToolRunState = CouplingToolRunState.Idle,
    val positioner: PositionerUiState = PositionerUiState(),
    val coupling: CouplingUiState = CouplingUiState(),
    val status: CouplingToolStatusState = CouplingToolStatusState()
)

data class CouplingToolStatusState(
    val deviceText: String = "PI: Disconnected | Laser: Disconnected | PowerMeter: Disconnected",
    val powerText: String = "Power: -- dBm",
    val stateText: String = "State: Idle",
    val message: String = "Ready",
    val isError: Boolean = false
)

data class PositionerUiState(
    val connected: Boolean = false,
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
    SpiralSearching("Spiral Searching"),
    FineOptimizing("Fine Optimizing"),
    AngleOptimizing("Angle Optimizing"),
    Coupled("Coupled"),
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

data class CouplingConfigUiState(
    val wavelengthNm: Double = 1550.0,
    val powerMeterChannel: Int = 1,
    val plane: CouplingPlane = CouplingPlane.XY,
    val firstLightThresholdDbm: Double = -40.0,
    val targetPowerDbm: Double = -10.0,
    val spiralStepUm: Double = 2.0,
    val maxRadiusUm: Double = 50.0,
    val settleDelayMs: Long = 50L,
    val enableFineXyz: Boolean = true,
    val enableAngleOptimization: Boolean = false,
    val virtualPivotPoint: VirtualPivotPoint = VirtualPivotPoint.Disabled,
    val enableSoftwarePivotCompensation: Boolean = false
)

enum class CouplingStageUi(
    val text: String
) {
    SpiralFirstLight("Spiral"),
    FineXyz("Fine XYZ"),
    OptimizeU("U"),
    OptimizeV("V"),
    OptimizeW("W"),
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
    val currentPowerDbm: Double? = null,
    val bestPowerDbm: Double? = null,
    val bestPose: OpticalPose? = null,
    val samples: List<CouplingSampleUi> = emptyList(),
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val message: String? = null
)

sealed interface CouplingToolAction {

    data class SelectPage(
        val page: CouplingToolPage
    ) : CouplingToolAction

    data object ConnectPositioner : CouplingToolAction

    data object DisconnectPositioner : CouplingToolAction

    data object ReadPose : CouplingToolAction

    data object MoveSafe : CouplingToolAction

    data object StopPositioner : CouplingToolAction

    data class JogPositioner(
        val delta: OpticalDelta
    ) : CouplingToolAction

    data class UpdateLinearStep(
        val valueUm: Double
    ) : CouplingToolAction

    data class UpdateAngleStep(
        val valueDeg: Double
    ) : CouplingToolAction

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
