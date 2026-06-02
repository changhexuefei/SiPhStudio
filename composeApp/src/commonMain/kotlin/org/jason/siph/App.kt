package org.jason.siph


import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.plus
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.model.CouplingStageUi
import org.jason.siph.ui.model.CouplingState
import org.jason.siph.ui.model.PositionerUiState
import org.jason.siph.ui.model.SiPhRunState
import org.jason.siph.ui.model.SiPhStatusState
import org.jason.siph.ui.model.SiPhToolsAction
import org.jason.siph.ui.model.SiPhToolsUiState
import org.jason.siph.ui.siphtools.SiPhToolsScreen



@Composable
@androidx.compose.ui.tooling.preview.Preview
fun App() {
    MaterialTheme {
        var state by remember {
            mutableStateOf(
                SiPhToolsUiState(
                    status = SiPhStatusState(
                        deviceText = "PI: Disconnected | Laser: Demo | PowerMeter: Demo",
                        powerText = "Power: -- dBm",
                        stateText = "State: Idle",
                        message = "SiPhTools-Kotlin Ready"
                    )
                )
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SiPhToolsScreen(
                state = state,
                onAction = { action ->
                    state = reduceSiPhToolsAction(
                        state = state,
                        action = action
                    )
                }
            )
        }
    }
}

private fun reduceSiPhToolsAction(
    state: SiPhToolsUiState,
    action: SiPhToolsAction
): SiPhToolsUiState {
    return when (action) {
        is SiPhToolsAction.SelectPage -> {
            state.copy(
                selectedPage = action.page,
                status = state.status.copy(
                    message = "切换到 ${action.page.title}"
                )
            )
        }

        SiPhToolsAction.Start -> {
            state.copy(
                runState = SiPhRunState.Running,
                status = state.status.copy(
                    stateText = "State: Running",
                    message = "Run started"
                )
            )
        }

        SiPhToolsAction.Stop -> {
            state.copy(
                runState = SiPhRunState.Stopped,
                coupling = state.coupling.copy(
                    isRunning = false,
                    state = CouplingState.Stopped,
                    message = "用户停止运行"
                ),
                status = state.status.copy(
                    stateText = "State: Stopped",
                    message = "Run stopped"
                )
            )
        }

        SiPhToolsAction.ConnectPositioner -> {
            state.copy(
                positioner = state.positioner.copy(
                    connected = true,
                    idn = "Demo PI Hexapod Controller - X/Y/Z/U/V/W",
                    errorMessage = null
                ),
                status = state.status.copy(
                    deviceText = "PI: Connected | Laser: Demo | PowerMeter: Demo",
                    message = "PI 光学定位器已连接"
                )
            )
        }

        SiPhToolsAction.DisconnectPositioner -> {
            state.copy(
                positioner = PositionerUiState(),
                status = state.status.copy(
                    deviceText = "PI: Disconnected | Laser: Demo | PowerMeter: Demo",
                    powerText = "Power: -- dBm",
                    message = "PI 光学定位器已断开"
                )
            )
        }

        SiPhToolsAction.ReadPose -> {
            state.copy(
                status = state.status.copy(
                    message = "读取当前 Pose: ${formatPose(state.positioner.currentPose)}"
                )
            )
        }

        SiPhToolsAction.MoveSafe -> {
            val safePose = state.positioner.safePose

            state.copy(
                positioner = state.positioner.copy(
                    currentPose = safePose,
                    isMoving = false
                ),
                status = state.status.copy(
                    message = "已移动到安全位置"
                )
            )
        }

        SiPhToolsAction.StopPositioner -> {
            state.copy(
                positioner = state.positioner.copy(
                    isMoving = false
                ),
                status = state.status.copy(
                    message = "Positioner stopped"
                )
            )
        }

        is SiPhToolsAction.JogPositioner -> {
            val newPose = state.positioner.currentPose + action.delta

            state.copy(
                positioner = state.positioner.copy(
                    currentPose = newPose
                ),
                status = state.status.copy(
                    message = "Jog: ${formatPose(newPose)}"
                )
            )
        }

        is SiPhToolsAction.UpdateLinearStep -> {
            state.copy(
                positioner = state.positioner.copy(
                    linearStepUm = action.valueUm
                )
            )
        }

        is SiPhToolsAction.UpdateAngleStep -> {
            state.copy(
                positioner = state.positioner.copy(
                    angleStepDeg = action.valueDeg
                )
            )
        }

        is SiPhToolsAction.UpdateCouplingConfig -> {
            state.copy(
                coupling = state.coupling.copy(
                    config = action.config
                )
            )
        }

        SiPhToolsAction.StartCoupling -> {
            val demoSamples = buildDemoCouplingSamples(
                centerPose = state.positioner.currentPose
            )

            val best = demoSamples.maxByOrNull { it.powerDbm }
            val bestPose = best?.pose
            val bestPower = best?.powerDbm
            val currentPower = demoSamples.lastOrNull()?.powerDbm

            state.copy(
                runState = SiPhRunState.Running,
                coupling = state.coupling.copy(
                    state = CouplingState.Coupled,
                    isRunning = false,
                    samples = demoSamples,
                    currentPowerDbm = currentPower,
                    bestPowerDbm = bestPower,
                    bestPose = bestPose,
                    message = "Demo 耦光完成，找到最佳点",
                    logs = buildList {
                        add("Start spiral coupling search.")
                        add("Plane: ${state.coupling.config.plane.text}")
                        add("Wavelength: ${state.coupling.config.wavelengthNm} nm")
                        add("Samples: ${demoSamples.size}")
                        add("Best power: ${bestPower ?: "--"} dBm")
                        add("Best pose: ${bestPose?.let { formatPose(it) } ?: "--"}")
                    }
                ),
                positioner = if (bestPose != null) {
                    state.positioner.copy(
                        currentPose = bestPose
                    )
                } else {
                    state.positioner
                },
                status = state.status.copy(
                    powerText = "Power: ${bestPower?.let { "${round3(it)} dBm" } ?: "-- dBm"}",
                    stateText = "State: Coupled",
                    message = "Demo coupling finished"
                )
            )
        }

        SiPhToolsAction.StopCoupling -> {
            state.copy(
                coupling = state.coupling.copy(
                    isRunning = false,
                    state = CouplingState.Stopped,
                    message = "耦光已停止"
                ),
                status = state.status.copy(
                    stateText = "State: Stopped",
                    message = "Coupling stopped"
                )
            )
        }

        SiPhToolsAction.ClearCouplingData -> {
            state.copy(
                coupling = state.coupling.copy(
                    samples = emptyList(),
                    logs = emptyList(),
                    currentPowerDbm = null,
                    bestPowerDbm = null,
                    bestPose = null,
                    state = CouplingState.Idle,
                    message = null
                ),
                status = state.status.copy(
                    powerText = "Power: -- dBm",
                    stateText = "State: Idle",
                    message = "Coupling data cleared"
                )
            )
        }

        SiPhToolsAction.SaveBestPose -> {
            val bestPose = state.coupling.bestPose

            state.copy(
                status = state.status.copy(
                    message = if (bestPose != null) {
                        "保存 Best Pose: ${formatPose(bestPose)}"
                    } else {
                        "没有可保存的 Best Pose"
                    }
                )
            )
        }
    }
}

private fun buildDemoCouplingSamples(
    centerPose: OpticalPose
): List<CouplingSampleUi> {
    val offsets = listOf(
        -20.0 to -20.0,
        -10.0 to -20.0,
        0.0 to -20.0,
        10.0 to -20.0,
        20.0 to -20.0,

        -20.0 to -10.0,
        -10.0 to -10.0,
        0.0 to -10.0,
        10.0 to -10.0,
        20.0 to -10.0,

        -20.0 to 0.0,
        -10.0 to 0.0,
        0.0 to 0.0,
        10.0 to 0.0,
        20.0 to 0.0,

        -20.0 to 10.0,
        -10.0 to 10.0,
        0.0 to 10.0,
        10.0 to 10.0,
        20.0 to 10.0,

        -20.0 to 20.0,
        -10.0 to 20.0,
        0.0 to 20.0,
        10.0 to 20.0,
        20.0 to 20.0
    )

    return offsets.mapIndexed { index, offset ->
        val dx = offset.first
        val dy = offset.second

        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        val power = -8.0 - distance * 0.45

        CouplingSampleUi(
            index = index,
            pose = centerPose.copy(
                xUm = centerPose.xUm + dx,
                yUm = centerPose.yUm + dy
            ),
            powerDbm = power,
            stage = if (index < 12) {
                CouplingStageUi.SpiralFirstLight
            } else {
                CouplingStageUi.FineXyz
            },
            timestampMs = index.toLong()
        )
    }
}

private fun formatPose(
    pose: OpticalPose
): String {
    return "X=${round3(pose.xUm)} μm, " +
            "Y=${round3(pose.yUm)} μm, " +
            "Z=${round3(pose.zUm)} μm, " +
            "U=${round3(pose.uDeg)}°, " +
            "V=${round3(pose.vDeg)}°, " +
            "W=${round3(pose.wDeg)}°"
}

private fun round3(
    value: Double
): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}