package org.jason.siph.ui.model

import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose

enum class SiPhPage(
    val title: String
) {
    Dashboard("Dashboard"),
    Devices("Devices"),
    Positioner("Positioner"),
    Coupling("Coupling"),
    Scan("Scan"),
    Calibration("Calibration"),
    Measurement("Measurement"),
    Data("Data")
}

enum class SiPhRunState(
    val text: String
) {
    Idle("Idle"),
    Running("Running"),
    Paused("Paused"),
    Stopped("Stopped"),
    Error("Error")
}

data class SiPhToolsUiState(
    val selectedPage: SiPhPage = SiPhPage.Coupling,
    val runState: SiPhRunState = SiPhRunState.Idle,
    val positioner: PositionerUiState = PositionerUiState(),
    val coupling: CouplingUiState = CouplingUiState(),
    val status: SiPhStatusState = SiPhStatusState()
)

data class SiPhStatusState(
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
    val enableAngleOptimization: Boolean = false
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

sealed interface SiPhToolsAction {

    data class SelectPage(
        val page: SiPhPage
    ) : SiPhToolsAction

    data object Start : SiPhToolsAction

    data object Stop : SiPhToolsAction

    data object ConnectPositioner : SiPhToolsAction

    data object DisconnectPositioner : SiPhToolsAction

    data object ReadPose : SiPhToolsAction

    data object MoveSafe : SiPhToolsAction

    data object StopPositioner : SiPhToolsAction

    data class JogPositioner(
        val delta: OpticalDelta
    ) : SiPhToolsAction

    data class UpdateLinearStep(
        val valueUm: Double
    ) : SiPhToolsAction

    data class UpdateAngleStep(
        val valueDeg: Double
    ) : SiPhToolsAction

    data class UpdateCouplingConfig(
        val config: CouplingConfigUiState
    ) : SiPhToolsAction

    data object StartCoupling : SiPhToolsAction

    data object StopCoupling : SiPhToolsAction

    data object ClearCouplingData : SiPhToolsAction

    data object SaveBestPose : SiPhToolsAction
}