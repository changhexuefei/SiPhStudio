package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AerospacePanel(
            modifier = Modifier.fillMaxWidth(),
            elevated = true,
            highlighted = state.bestPowerDbm != null
        ) {
            AerospaceSectionHeader(
                eyebrow = "OPTICAL TELEMETRY",
                title = "COUPLING RESULT",
                caption = state.errorMessage ?: state.message ?: "No active result",
                trailing = {
                    TelemetryPill(
                        label = "SEQUENCE",
                        value = state.state.text.uppercase(),
                        tone = when {
                            state.errorMessage != null -> AerospacePalette.Danger
                            state.isRunning -> AerospacePalette.Accent
                            state.bestPowerDbm != null -> AerospacePalette.Success
                            else -> AerospacePalette.TextMuted
                        },
                        active = state.isRunning || state.bestPowerDbm != null
                    )
                }
            )

            if (state.isRunning || state.progress > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${state.currentStage?.text ?: state.state.text} // ${state.sampleCount} samples",
                            style = MaterialTheme.typography.labelLarge,
                            color = AerospacePalette.TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${(state.progress * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = AerospacePalette.AccentBright,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = AerospacePalette.Accent,
                        trackColor = AerospacePalette.Border
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "CURRENT POWER",
                    value = formatPower(state.currentPowerDbm),
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "BEST POWER",
                    value = formatPower(state.bestPowerDbm),
                    emphasized = state.bestPowerDbm != null,
                    accent = AerospacePalette.Success,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "SAMPLES",
                    value = state.sampleCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "ELAPSED",
                    value = formatDuration(state.startedAtMs, state.finishedAtMs),
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
                        .heightIn(min = 44.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AerospacePalette.TextPrimary,
                        contentColor = AerospacePalette.Void,
                        disabledContainerColor = AerospacePalette.PanelHover,
                        disabledContentColor = AerospacePalette.TextMuted
                    )
                ) {
                    Text("COMMIT BEST POSE", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.ClearCouplingData) },
                    enabled = !state.isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, AerospacePalette.BorderStrong),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AerospacePalette.TextSecondary,
                        disabledContentColor = AerospacePalette.TextMuted
                    )
                ) {
                    Text("CLEAR TELEMETRY")
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
private fun BestPoseBlock(pose: OpticalPose?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = AerospacePalette.Void.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "BEST POSE VECTOR",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.Accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = pose?.let { formatPose(it) } ?: "--",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
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
    if (finishedAtMs == null) return "RUNNING"

    val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
    return if (durationMs < 1000L) {
        "$durationMs ms"
    } else {
        "${round3(durationMs / 1000.0)} s"
    }
}

private fun formatPose(pose: OpticalPose): String {
    return "X=${round3(pose.xUm)} µm  |  " +
        "Y=${round3(pose.yUm)} µm  |  " +
        "Z=${round3(pose.zUm)} µm  |  " +
        "U=${round3(pose.uDeg)}°  |  " +
        "V=${round3(pose.vDeg)}°  |  " +
        "W=${round3(pose.wDeg)}°"
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
