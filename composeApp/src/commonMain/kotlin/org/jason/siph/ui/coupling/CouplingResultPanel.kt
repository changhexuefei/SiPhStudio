package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingUiState

@Composable
fun CouplingResultPanel(
    state: CouplingUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val scrollState = rememberScrollState()
    val contentModifier = if (scrollable) {
        modifier.verticalScroll(scrollState)
    } else {
        modifier
    }

    Column(
        modifier = contentModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Coupling Result",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.errorMessage ?: state.message ?: "No active result",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.errorMessage != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    AssistChip(
                        onClick = {},
                        label = { Text(state.state.text) }
                    )
                }

                if (state.isRunning || state.progress > 0f) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = state.currentStage?.text ?: state.state.text,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${(state.progress * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ResultMetric(
                        label = "Current",
                        value = formatPower(state.currentPowerDbm),
                        emphasized = false,
                        modifier = Modifier.weight(1f)
                    )
                    ResultMetric(
                        label = "Best",
                        value = formatPower(state.bestPowerDbm),
                        emphasized = state.bestPowerDbm != null,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ResultMetric(
                        label = "Samples",
                        value = state.sampleCount.toString(),
                        emphasized = false,
                        modifier = Modifier.weight(1f)
                    )
                    ResultMetric(
                        label = "Duration",
                        value = formatDuration(state.startedAtMs, state.finishedAtMs),
                        emphasized = false,
                        modifier = Modifier.weight(1f)
                    )
                }

                BestPoseBlock(pose = state.bestPose)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onAction(CouplingToolAction.SaveBestPose) },
                        enabled = state.bestPose != null && !state.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Text("Save Best Pose")
                    }

                    OutlinedButton(
                        onClick = { onAction(CouplingToolAction.ClearCouplingData) },
                        enabled = !state.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        CouplingPlotPanel(
            samples = state.samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(620.dp)
        )

        CouplingLogPanel(
            logs = state.logs,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        )
    }
}

@Composable
private fun ResultMetric(
    label: String,
    value: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BestPoseBlock(pose: OpticalPose?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Best Pose",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = pose?.let { formatPose(it) } ?: "--",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatPower(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let {
        "${round3(it)} dBm"
    } ?: "-- dBm"
}

private fun formatDuration(startedAtMs: Long?, finishedAtMs: Long?): String {
    if (startedAtMs == null) return "--"
    if (finishedAtMs == null) return "Running"

    val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
    return if (durationMs < 1000L) {
        "$durationMs ms"
    } else {
        "${round3(durationMs / 1000.0)} s"
    }
}

private fun formatPose(pose: OpticalPose): String {
    return "X=${round3(pose.xUm)} um, " +
        "Y=${round3(pose.yUm)} um, " +
        "Z=${round3(pose.zUm)} um, " +
        "U=${round3(pose.uDeg)} deg, " +
        "V=${round3(pose.vDeg)} deg, " +
        "W=${round3(pose.wDeg)} deg"
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
