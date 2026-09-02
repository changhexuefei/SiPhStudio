package org.jason.siph.ui.production

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
import org.jason.siph.domain.production.ProductionWorkerStage
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.round

@Composable
fun ProductionControlPanel(
    state: ProductionControlUiState,
    onAction: (ProductionControlAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val failed = state.worker.stage == ProductionWorkerStage.Failed
    val stopped = state.worker.stage == ProductionWorkerStage.Stopped
    val activeLot = state.lots.firstOrNull { it.state.name in setOf("Running", "Queued", "Paused") }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        highlighted = state.simulationBackend && !failed,
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "AUTONOMY PHASE 4",
            title = "PRODUCTION ORCHESTRATION + QUALITY",
            caption = state.errorMessage ?: state.message,
            trailing = {
                TelemetryPill(
                    label = "PRODUCTION",
                    value = when {
                        failed -> "FAILED"
                        stopped -> "STOPPED"
                        state.worker.running -> "RUNNING"
                        state.simulationBackend -> "DIGITAL READY"
                        else -> "REAL HOLD"
                    },
                    tone = when {
                        failed -> AerospacePalette.Danger
                        stopped -> AerospacePalette.Warning
                        state.worker.running -> AerospacePalette.Accent
                        state.simulationBackend -> AerospacePalette.Success
                        else -> AerospacePalette.TextMuted
                    },
                    active = state.worker.running || state.simulationBackend
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "LOT",
                value = activeLot?.lotNumber ?: "--",
                emphasized = activeLot != null,
                accent = AerospacePalette.Accent,
                modifier = Modifier.weight(1.2f)
            )
            MetricTile(
                label = "STAGE",
                value = state.worker.stage.name.uppercase(),
                emphasized = state.worker.running,
                modifier = Modifier.weight(1.15f)
            )
            MetricTile(
                label = "PENDING",
                value = state.pendingTaskCount.toString(),
                emphasized = state.pendingTaskCount > 0,
                modifier = Modifier.weight(0.7f)
            )
            MetricTile(
                label = "PASS",
                value = state.passedTaskCount.toString(),
                emphasized = state.passedTaskCount > 0,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "FAIL",
                value = state.failedTaskCount.toString(),
                emphasized = state.failedTaskCount > 0,
                accent = AerospacePalette.Danger,
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "YIELD",
                value = "${formatOne(state.yieldPercent)}%",
                emphasized = state.terminalTaskCount > 0,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "SPC",
                value = state.spcViolationCount.toString(),
                emphasized = state.spcViolationCount > 0,
                accent = if (state.spcViolationCount > 0) AerospacePalette.Warning else AerospacePalette.Success,
                modifier = Modifier.weight(0.6f)
            )
            MetricTile(
                label = "ANOMALY",
                value = state.anomalies.size.toString(),
                emphasized = state.anomalies.isNotEmpty(),
                accent = AerospacePalette.Warning,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "AUDIT",
                value = state.auditCount.toString(),
                modifier = Modifier.weight(0.65f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAction(ProductionControlAction.RunLot) },
                enabled = state.canRun,
                modifier = Modifier.weight(1.1f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text("RUN DIGITAL LOT", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { onAction(ProductionControlAction.RunNext) },
                enabled = state.canRun,
                modifier = Modifier.weight(0.75f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("RUN NEXT")
            }

            OutlinedButton(
                onClick = { onAction(ProductionControlAction.Stop) },
                enabled = state.worker.running,
                modifier = Modifier.weight(0.55f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Danger.copy(alpha = 0.72f))
            ) {
                Text("STOP", color = AerospacePalette.Danger)
            }

            OutlinedButton(
                onClick = { onAction(ProductionControlAction.Refresh) },
                enabled = !state.worker.running && !state.busy,
                modifier = Modifier.weight(0.55f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("REFRESH")
            }

            Text(
                text = if (state.simulationBackend) {
                    "SIMULATION // Fiber Array, O-E-O, Calibration Gate, Lot leases, idempotency, SPC, anomaly rules, RBAC and hash-chained audit run against persistent digital assets."
                } else {
                    "REAL HOLD // Approved recipe, valid calibration qualification, verified production executor and audited operator identity are required before production can start."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                modifier = Modifier.weight(2.4f)
            )
        }
    }
}

private fun formatOne(value: Double): String = round(value * 10.0).div(10.0).toString()
