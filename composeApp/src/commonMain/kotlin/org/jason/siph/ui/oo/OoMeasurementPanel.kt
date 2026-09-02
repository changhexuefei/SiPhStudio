package org.jason.siph.ui.oo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.oo.OoMeasurementStage
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

@Composable
fun OoMeasurementPanel(
    state: OoMeasurementUiState,
    onAction: (OoMeasurementAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val workflow = state.workflow
    val failed = workflow.stage == OoMeasurementStage.Failed
    val completed = workflow.stage == OoMeasurementStage.Completed
    val stopped = workflow.stage == OoMeasurementStage.Stopped
    val progress = if (workflow.totalMeasurements <= 0) {
        0
    } else {
        ((workflow.completedMeasurements.toDouble() / workflow.totalMeasurements) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        highlighted = completed || (state.simulationBackend && !failed),
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "AUTONOMY PHASE 2",
            title = "WAFER O-O MEASUREMENT ORCHESTRATOR",
            caption = state.errorMessage ?: state.message,
            trailing = {
                TelemetryPill(
                    label = "O-O ENGINE",
                    value = when {
                        failed -> "FAILED"
                        stopped -> "STOPPED"
                        workflow.paused -> "PAUSED"
                        workflow.running -> "RUNNING"
                        completed -> "COMPLETE"
                        state.simulationBackend -> "SIM READY"
                        else -> "REAL HOLD"
                    },
                    tone = when {
                        failed -> AerospacePalette.Danger
                        stopped || workflow.paused -> AerospacePalette.Warning
                        workflow.running -> AerospacePalette.Accent
                        completed -> AerospacePalette.Success
                        state.simulationBackend -> AerospacePalette.Success
                        else -> AerospacePalette.TextMuted
                    },
                    active = workflow.running || completed || state.simulationBackend
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "STAGE",
                value = workflow.stage.name.uppercase(),
                emphasized = workflow.running,
                accent = AerospacePalette.Accent,
                modifier = Modifier.weight(1.4f)
            )
            MetricTile(
                label = "PROGRESS",
                value = "$progress%",
                emphasized = completed,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "EQUIPMENT",
                value = "${state.equipment.readyCount}/4",
                emphasized = state.equipment.readyCount == 4,
                modifier = Modifier.weight(0.8f)
            )
            MetricTile(
                label = "WAFERS",
                value = state.wafers.size.toString(),
                emphasized = state.wafers.isNotEmpty(),
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "RECOVERY",
                value = state.recoverableCount.toString(),
                emphasized = state.recoverableCount > 0,
                accent = AerospacePalette.Warning,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "RUNS",
                value = state.completedRunCount.toString(),
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "TEMP",
                value = workflow.temperatureC?.let { "${formatOne(it)} C" } ?: "--",
                modifier = Modifier.weight(0.7f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAction(OoMeasurementAction.StartLatestWaferDemo) },
                enabled = state.canStartSimulation,
                modifier = Modifier
                    .weight(1.15f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text("RUN O-O SIMULATION", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { onAction(OoMeasurementAction.ResumeLatestCheckpoint) },
                enabled = state.recoverableCount > 0 && !state.busy && !workflow.running,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Warning.copy(alpha = 0.68f))
            ) {
                Text("RESUME O-O")
            }

            OutlinedButton(
                onClick = {
                    onAction(
                        if (workflow.paused) OoMeasurementAction.Continue else OoMeasurementAction.Pause
                    )
                },
                enabled = workflow.running,
                modifier = Modifier
                    .weight(0.65f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(if (workflow.paused) "CONTINUE" else "PAUSE")
            }

            OutlinedButton(
                onClick = { onAction(OoMeasurementAction.Stop) },
                enabled = workflow.running,
                modifier = Modifier
                    .weight(0.55f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Danger.copy(alpha = 0.72f))
            ) {
                Text("STOP", color = AerospacePalette.Danger)
            }

            OutlinedButton(
                onClick = { onAction(OoMeasurementAction.Refresh) },
                enabled = !workflow.running && !state.busy,
                modifier = Modifier
                    .weight(0.55f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("REFRESH")
            }
        }

        Text(
            text = if (state.simulationBackend) {
                "SIMULATION // Laser, power meter, prober, temperature and photonic response share one deterministic digital environment."
            } else {
                "REAL HOLD // Protocol adapters must be explicitly configured and marked HardwareVerified before O-O execution."
            },
            style = MaterialTheme.typography.labelSmall,
            color = AerospacePalette.TextMuted
        )
    }
}

private fun formatOne(value: Double): String =
    kotlin.math.round(value * 10.0).div(10.0).toString()
