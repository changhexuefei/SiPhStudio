package org.jason.siph.ui.inspection

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
import org.jason.siph.domain.inspection.InspectionCalibrationStage
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

@Composable
fun InspectionCalibrationPanel(
    state: InspectionCalibrationUiState,
    onAction: (InspectionCalibrationAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val workflow = state.workflow
    val failed = workflow.stage == InspectionCalibrationStage.Failed
    val completed = workflow.stage == InspectionCalibrationStage.Completed
    val stopped = workflow.stage == InspectionCalibrationStage.Stopped
    val progress = if (workflow.totalTemperatureCount <= 0) {
        0
    } else {
        ((workflow.completedTemperatureCount.toDouble() / workflow.totalTemperatureCount) * 100.0)
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
            eyebrow = "AUTONOMY PHASE 3",
            title = "VISION + SENSOR CALIBRATION",
            caption = state.errorMessage ?: state.message,
            trailing = {
                TelemetryPill(
                    label = "INSPECTION",
                    value = when {
                        failed -> "FAILED"
                        stopped -> "STOPPED"
                        workflow.running -> "RUNNING"
                        completed -> "COMPLETE"
                        state.simulationBackend -> "DIGITAL READY"
                        else -> "REAL HOLD"
                    },
                    tone = when {
                        failed -> AerospacePalette.Danger
                        stopped -> AerospacePalette.Warning
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
                modifier = Modifier.weight(1.35f)
            )
            MetricTile(
                label = "PROGRESS",
                value = "$progress%",
                emphasized = completed,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.7f)
            )
            MetricTile(
                label = "CAMERA/Z",
                value = "${state.equipment.readyCount}/2",
                emphasized = state.equipment.readyCount == 2,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "CAM CAL",
                value = state.verifiedCameraCalibrationCount.toString(),
                emphasized = state.verifiedCameraCalibrationCount > 0,
                modifier = Modifier.weight(0.72f)
            )
            MetricTile(
                label = "HEIGHT",
                value = state.verifiedHeightCount.toString(),
                emphasized = state.verifiedHeightCount > 0,
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "PIVOT",
                value = state.verifiedPivotCount.toString(),
                emphasized = state.verifiedPivotCount > 0,
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "RUNS",
                value = state.completedRunCount.toString(),
                modifier = Modifier.weight(0.6f)
            )
            MetricTile(
                label = "TEMP",
                value = workflow.currentTemperatureC?.let { "${formatOne(it)} C" } ?: "--",
                modifier = Modifier.weight(0.68f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAction(InspectionCalibrationAction.RunDemo) },
                enabled = state.canRunDemo,
                modifier = Modifier
                    .weight(1.2f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text("RUN VISION + SENSOR SIM", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { onAction(InspectionCalibrationAction.Stop) },
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
                onClick = { onAction(InspectionCalibrationAction.Refresh) },
                enabled = !workflow.running && !state.busy,
                modifier = Modifier
                    .weight(0.55f)
                    .heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("REFRESH")
            }

            Text(
                text = if (state.simulationBackend) {
                    "SIMULATION // Synthetic camera pixels, feature detectors, Z feedback, pivot geometry and thermal drift share one deterministic environment."
                } else {
                    "REAL HOLD // Camera and Z sensor must be configured and HardwareVerified before automatic motion is allowed."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                modifier = Modifier.weight(2.3f)
            )
        }
    }
}

private fun formatOne(value: Double): String =
    kotlin.math.round(value * 10.0).div(10.0).toString()
