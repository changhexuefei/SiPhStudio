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
import org.jason.siph.ui.model.PositionerUiState
import org.jason.siph.ui.model.CouplingToolAction
import kotlin.math.abs

@Composable
fun CompactPositionerPanel(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit,
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
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Optical Positioner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = state.idn ?: "No device information",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = if (state.connected) "Connected" else "Disconnected",
                            color = if (state.connected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                )
            }

            CompactPoseGrid(
                pose = state.currentPose
            )

            JogControlPanel(
                linearStepUm = state.linearStepUm,
                angleStepDeg = state.angleStepDeg,
                enabled = state.connected && !state.isMoving,
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
                    onClick = { onAction(CouplingToolAction.ConnectPositioner) },
                    enabled = !state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect")
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.ReadPose) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Read Pose")
                }

                Button(
                    onClick = { onAction(CouplingToolAction.MoveSafe) },
                    enabled = state.connected && !state.isMoving,
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
private fun CompactPoseGrid(
    pose: OpticalPose
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

private fun formatUm(value: Double): String {
    return "${formatNumber(value)} um"
}

private fun formatDeg(value: Double): String {
    return "${formatNumber(value)} deg"
}

private fun formatNumber(value: Double): String {
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    return (kotlin.math.round(normalized * 1000.0) / 1000.0).toString()
}
