package org.jason.siph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jason.siph.domain.positioner.OpticalCoordinateFrame
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.domain.positioner.plus
import org.jason.siph.ui.model.CouplingConfigUiState
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.model.CouplingStageUi
import org.jason.siph.ui.model.CouplingState
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolRunState
import org.jason.siph.ui.model.CouplingToolStatusState
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.PositionerUiState
import org.jason.siph.ui.siphtools.CouplingToolScreen

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun App() {
    MaterialTheme {
        var state by remember {
            mutableStateOf(
                CouplingToolUiState(
                    status = CouplingToolStatusState(
                        deviceText = "PI: Disconnected | Laser: Demo | PowerMeter: Demo",
                        powerText = "Power: -- dBm",
                        stateText = "State: Idle",
                        message = "Coupling Tool Ready"
                    )
                )
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CouplingToolScreen(
                state = state,
                onAction = { action ->
                    state = reduceCouplingToolAction(
                        state = state,
                        action = action
                    )
                }
            )
        }
    }
}

private fun reduceCouplingToolAction(
    state: CouplingToolUiState,
    action: CouplingToolAction
): CouplingToolUiState {
    return when (action) {
        is CouplingToolAction.SelectPage -> {
            state.copy(
                selectedPage = action.page,
                status = state.status.copy(
                    message = "Switched to ${action.page.title}"
                )
            )
        }

        CouplingToolAction.ConnectPositioner -> {
            state.copy(
                positioner = state.positioner.copy(
                    connected = true,
                    idn = "Demo PI Hexapod Controller - X/Y/Z/U/V/W",
                    errorMessage = null
                ),
                status = state.status.copy(
                    deviceText = "PI: Connected | Laser: Demo | PowerMeter: Demo",
                    message = "Positioner connected"
                )
            )
        }

        CouplingToolAction.DisconnectPositioner -> {
            state.copy(
                positioner = PositionerUiState(),
                status = state.status.copy(
                    deviceText = "PI: Disconnected | Laser: Demo | PowerMeter: Demo",
                    powerText = "Power: -- dBm",
                    message = "Positioner disconnected"
                )
            )
        }

        CouplingToolAction.ReadPose -> {
            state.copy(
                status = state.status.copy(
                    message = "Current pose: ${formatPose(state.positioner.currentPose)}"
                )
            )
        }

        CouplingToolAction.MoveSafe -> {
            val safePose = state.positioner.safePose

            state.copy(
                positioner = state.positioner.copy(
                    currentPose = safePose,
                    isMoving = false
                ),
                status = state.status.copy(
                    message = "Moved to safe pose"
                )
            )
        }

        CouplingToolAction.StopPositioner -> {
            state.copy(
                positioner = state.positioner.copy(
                    isMoving = false
                ),
                status = state.status.copy(
                    message = "Positioner stopped"
                )
            )
        }

        is CouplingToolAction.JogPositioner -> {
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

        is CouplingToolAction.UpdateLinearStep -> {
            state.copy(
                positioner = state.positioner.copy(
                    linearStepUm = action.valueUm
                )
            )
        }

        is CouplingToolAction.UpdateAngleStep -> {
            state.copy(
                positioner = state.positioner.copy(
                    angleStepDeg = action.valueDeg
                )
            )
        }

        is CouplingToolAction.UpdateCouplingConfig -> {
            state.copy(
                coupling = state.coupling.copy(
                    config = action.config
                )
            )
        }

        is CouplingToolAction.UpdateVirtualPivot -> {
            state.withVirtualPivot(
                pivot = action.pivot,
                message = if (action.pivot.enabled) {
                    "Virtual pivot updated"
                } else {
                    "Virtual pivot disabled"
                }
            )
        }

        CouplingToolAction.CapturePivotFromCurrentPose -> {
            val pose = state.positioner.currentPose
            val pivot = VirtualPivotPoint(
                xUm = pose.xUm,
                yUm = pose.yUm,
                zUm = pose.zUm,
                frame = OpticalCoordinateFrame.Positioner,
                enabled = true,
                name = "Current optical point"
            )

            state.withVirtualPivot(
                pivot = pivot,
                message = "Captured current pose as virtual pivot"
            )
        }

        CouplingToolAction.DisableVirtualPivot -> {
            state.withVirtualPivot(
                pivot = VirtualPivotPoint.Disabled,
                message = "Virtual pivot disabled"
            )
        }

        CouplingToolAction.StartCoupling -> {
            val demoSamples = buildDemoCouplingSamples(
                centerPose = state.positioner.currentPose
            )

            val best = demoSamples.maxByOrNull { it.powerDbm }
            val bestPose = best?.pose
            val bestPower = best?.powerDbm
            val currentPower = demoSamples.lastOrNull()?.powerDbm

            state.copy(
                runState = CouplingToolRunState.Idle,
                coupling = state.coupling.copy(
                    state = CouplingState.Coupled,
                    isRunning = false,
                    samples = demoSamples,
                    currentPowerDbm = currentPower,
                    bestPowerDbm = bestPower,
                    bestPose = bestPose,
                    message = "Demo coupling finished; best point found",
                    logs = buildList {
                        add("Start spiral coupling search.")
                        add("Plane: ${state.coupling.config.plane.text}")
                        add("Wavelength: ${state.coupling.config.wavelengthNm} nm")
                        add("Samples: ${demoSamples.size}")
                        add("Best power: ${formatPower(bestPower)}")
                        add("Best pose: ${bestPose?.let { formatPose(it) } ?: "--"}")
                        add("Pivot compensation: ${formatPivotConfig(state.coupling.config)}")
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
                    powerText = "Power: ${formatPower(bestPower)}",
                    stateText = "State: Coupled",
                    message = "Demo coupling finished"
                )
            )
        }

        CouplingToolAction.StopCoupling -> {
            state.copy(
                runState = CouplingToolRunState.Stopped,
                coupling = state.coupling.copy(
                    isRunning = false,
                    state = CouplingState.Stopped,
                    message = "Coupling stopped"
                ),
                status = state.status.copy(
                    stateText = "State: Stopped",
                    message = "Coupling stopped"
                )
            )
        }

        CouplingToolAction.ClearCouplingData -> {
            state.copy(
                runState = CouplingToolRunState.Idle,
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

        CouplingToolAction.SaveBestPose -> {
            val bestPose = state.coupling.bestPose

            state.copy(
                status = state.status.copy(
                    message = if (bestPose != null) {
                        "Saved best pose: ${formatPose(bestPose)}"
                    } else {
                        "No best pose to save"
                    }
                )
            )
        }
    }
}

private fun CouplingToolUiState.withVirtualPivot(
    pivot: VirtualPivotPoint,
    message: String
): CouplingToolUiState {
    return copy(
        coupling = coupling.copy(
            config = coupling.config.copy(
                virtualPivotPoint = pivot,
                enableSoftwarePivotCompensation = pivot.enabled
            )
        ),
        status = status.copy(
            message = message
        )
    )
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

private fun formatPower(value: Double?): String {
    return value?.let { "${round3(it)} dBm" } ?: "-- dBm"
}

private fun formatPivotConfig(config: CouplingConfigUiState): String {
    val pivot = config.virtualPivotPoint
    return if (pivot.enabled && config.enableSoftwarePivotCompensation) {
        "${pivot.name} @ X=${round3(pivot.xUm)} um, Y=${round3(pivot.yUm)} um, Z=${round3(pivot.zUm)} um (${pivot.frame.name})"
    } else {
        "Disabled"
    }
}

private fun formatPose(
    pose: OpticalPose
): String {
    return "X=${round3(pose.xUm)} um, " +
            "Y=${round3(pose.yUm)} um, " +
            "Z=${round3(pose.zUm)} um, " +
            "U=${round3(pose.uDeg)} deg, " +
            "V=${round3(pose.vDeg)} deg, " +
            "W=${round3(pose.wDeg)} deg"
}

private fun round3(
    value: Double
): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
