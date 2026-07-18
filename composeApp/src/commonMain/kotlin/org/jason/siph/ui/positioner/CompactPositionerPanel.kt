package org.jason.siph.ui.positioner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.PositionerUiState
import kotlin.math.abs

@Composable
fun CompactPositionerPanel(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit,
    motionEnabled: Boolean = true,
    connectLabel: String = "Connect",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Optical Positioner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.errorMessage ?: state.idn ?: "No device information",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (!motionEnabled) {
                        Text(
                            text = "Motion locked: apply a validated safety profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = when {
                                !motionEnabled -> "Interlock"
                                state.connecting -> "Connecting"
                                state.connected && state.isMoving -> "Moving"
                                state.connected -> "Connected"
                                else -> "Disconnected"
                            },
                            color = when {
                                state.errorMessage != null -> MaterialTheme.colorScheme.error
                                !motionEnabled -> MaterialTheme.colorScheme.tertiary
                                state.connected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            state.connecting -> "Connecting..."
                            state.connected -> "Disconnect"
                            else -> connectLabel
                        }
                    )
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.ReadPose) },
                    enabled = state.connected && !state.isMoving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Read Pose")
                }

                Button(
                    onClick = { onAction(CouplingToolAction.MoveSafe) },
                    enabled = motionEnabled && state.connected && !state.isMoving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Move Safe")
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.StopPositioner) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
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
            PoseMiniMetric("X", formatUm(pose.xUm), Modifier.weight(1f))
            PoseMiniMetric("Y", formatUm(pose.yUm), Modifier.weight(1f))
            PoseMiniMetric("Z", formatUm(pose.zUm), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PoseMiniMetric("U", formatDeg(pose.uDeg), Modifier.weight(1f))
            PoseMiniMetric("V", formatDeg(pose.vDeg), Modifier.weight(1f))
            PoseMiniMetric("W", formatDeg(pose.wDeg), Modifier.weight(1f))
        }
    }
}

@Composable
private fun PoseMiniMetric(
    name: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatUm(value: Double): String = "${formatNumber(value)} um"
private fun formatDeg(value: Double): String = "${formatNumber(value)} deg"

private fun formatNumber(value: Double): String {
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    return (kotlin.math.round(normalized * 1000.0) / 1000.0).toString()
}
