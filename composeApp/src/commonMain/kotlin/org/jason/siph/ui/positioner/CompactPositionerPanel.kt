package org.jason.siph.ui.positioner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.PositionerUiState
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.abs

@Composable
fun CompactPositionerPanel(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit,
    motionEnabled: Boolean = true,
    connectLabel: String = "CONNECT",
    modifier: Modifier = Modifier
) {
    AerospacePanel(
        modifier = modifier.fillMaxWidth(),
        elevated = true
    ) {
        AerospaceSectionHeader(
            eyebrow = "POSITIONING SYSTEM",
            title = "PI HEXAPOD TELEMETRY",
            caption = state.errorMessage
                ?: state.idn
                ?: "Awaiting positioner connection",
            trailing = {
                TelemetryPill(
                    label = "LINK",
                    value = when {
                        state.connecting -> "CONNECTING"
                        state.connected -> "ONLINE"
                        else -> "OFFLINE"
                    },
                    tone = when {
                        state.errorMessage != null -> AerospacePalette.Danger
                        state.connected -> AerospacePalette.Success
                        else -> AerospacePalette.TextMuted
                    },
                    active = state.connected || state.connecting
                )
                TelemetryPill(
                    label = "MOTION",
                    value = when {
                        !motionEnabled -> "LOCKED"
                        state.isMoving -> "ACTIVE"
                        else -> "READY"
                    },
                    tone = when {
                        !motionEnabled -> AerospacePalette.Warning
                        state.isMoving -> AerospacePalette.Accent
                        else -> AerospacePalette.Success
                    },
                    active = motionEnabled
                )
            }
        )

        if (!motionEnabled) {
            Text(
                text = "INTERLOCK HOLD // Apply a verified motion-safety profile before enabling movement.",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.Warning,
                fontFamily = FontFamily.Monospace
            )
        }

        CompactPoseGrid(pose = state.currentPose)

        JogControlPanel(
            linearStepUm = state.linearStepUm,
            angleStepDeg = state.angleStepDeg,
            enabled = motionEnabled && state.connected && !state.connecting && !state.isMoving,
            onLinearStepChange = {
                onAction(CouplingToolAction.UpdateLinearStep(it))
            },
            onAngleStepChange = {
                onAction(CouplingToolAction.UpdateAngleStep(it))
            },
            onJog = {
                onAction(CouplingToolAction.JogPositioner(it))
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    onAction(
                        if (state.connected) {
                            CouplingToolAction.DisconnectPositioner
                        } else {
                            CouplingToolAction.ConnectPositioner
                        }
                    )
                },
                enabled = !state.connecting && !state.isMoving && (state.connected || motionEnabled),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text(
                    text = when {
                        state.connecting -> "CONNECTING"
                        state.connected -> "DISCONNECT"
                        else -> connectLabel
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.ReadPose) },
                enabled = state.connected && !state.isMoving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.BorderStrong)
            ) {
                Text("SYNC POSE")
            }

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.MoveSafe) },
                enabled = motionEnabled && state.connected && !state.isMoving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Accent.copy(alpha = 0.68f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AerospacePalette.AccentBright
                )
            ) {
                Text("SAFE POSE")
            }

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.StopPositioner) },
                enabled = state.connected,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Danger.copy(alpha = 0.72f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AerospacePalette.Danger
                )
            ) {
                Text("STOP")
            }
        }
    }
}

@Composable
private fun CompactPoseGrid(pose: OpticalPose) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile("X AXIS", formatUm(pose.xUm), Modifier.weight(1f))
            MetricTile("Y AXIS", formatUm(pose.yUm), Modifier.weight(1f))
            MetricTile("Z AXIS", formatUm(pose.zUm), Modifier.weight(1f), emphasized = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile("U ROT", formatDeg(pose.uDeg), Modifier.weight(1f))
            MetricTile("V ROT", formatDeg(pose.vDeg), Modifier.weight(1f))
            MetricTile("W ROT", formatDeg(pose.wDeg), Modifier.weight(1f))
        }
    }
}

private fun formatUm(value: Double): String = "${formatNumber(value)} µm"
private fun formatDeg(value: Double): String = "${formatNumber(value)}°"

private fun formatNumber(value: Double): String {
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    return (kotlin.math.round(normalized * 1000.0) / 1000.0).toString()
}
