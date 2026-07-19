package org.jason.siph.ui.autonomy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.autonomy.AutonomyCapabilityState
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.SiPhWorkflowStage
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

/**
 * 自主助手入口：顶部显示第一阶段执行和持久化资产，底部保留几何与引导工作区。
 */
@Composable
fun AutonomousAssistantPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    workflowState: AutonomousWorkflowUiState,
    onAction: (CouplingToolAction) -> Unit,
    onWorkflowAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FirstStageRuntimePanel(
            workflowState = workflowState,
            onAction = onWorkflowAction,
            modifier = Modifier.fillMaxWidth()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AutonomousAssistantPanel(
                state = state,
                safetyState = safetyState,
                onAction = onAction,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun FirstStageRuntimePanel(
    workflowState: AutonomousWorkflowUiState,
    onAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val workflow = workflowState.workflow
    val failed = workflow.stage == SiPhWorkflowStage.Failed
    val stopped = workflow.stage == SiPhWorkflowStage.Stopped
    val completed = workflow.stage == SiPhWorkflowStage.Completed
    val progressPercent = if (workflow.totalStageCount <= 0) {
        0
    } else {
        ((workflow.completedStageCount.toDouble() / workflow.totalStageCount) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        highlighted = completed || (workflowState.readyForFirstStageWorkflow && !failed),
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "AUTONOMY PHASE 1",
            title = "CHECKPOINTED SILICON PHOTONICS WORKFLOW",
            caption = workflowState.errorMessage ?: workflowState.message,
            trailing = {
                TelemetryPill(
                    label = "ENGINE",
                    value = when {
                        failed -> "FAILED"
                        stopped -> "STOPPED"
                        workflow.running -> "RUNNING"
                        completed -> "COMPLETE"
                        workflowState.readyForFirstStageWorkflow -> "READY"
                        else -> "SETUP"
                    },
                    tone = when {
                        failed -> AerospacePalette.Danger
                        stopped -> AerospacePalette.Warning
                        workflow.running -> AerospacePalette.Accent
                        completed -> AerospacePalette.Success
                        workflowState.readyForFirstStageWorkflow -> AerospacePalette.Success
                        else -> AerospacePalette.TextMuted
                    },
                    active = !failed
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
                value = "$progressPercent%",
                emphasized = completed,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "CALIBRATION",
                value = if (workflowState.verifiedProfileApplied) "VERIFIED" else "REQUIRED",
                emphasized = workflowState.verifiedProfileApplied,
                accent = if (workflowState.verifiedProfileApplied) {
                    AerospacePalette.Success
                } else {
                    AerospacePalette.Warning
                },
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "POSITIONS",
                value = workflowState.verifiedPositionCount.toString(),
                emphasized = workflowState.verifiedPositionCount > 0,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "WAFERS",
                value = workflowState.wafers.size.toString(),
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "RECOVERY",
                value = workflowState.recoverableCheckpointCount.toString(),
                emphasized = workflowState.recoverableCheckpointCount > 0,
                accent = AerospacePalette.Warning,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "RECORDS",
                value = workflowState.recentRecords.size.toString(),
                modifier = Modifier.weight(0.7f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAction(AutonomousWorkflowAction.StartLatestTrainedPosition) },
                enabled = workflowState.readyForFirstStageWorkflow && !workflowState.busy,
                modifier = Modifier
                    .weight(1.15f)
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
                    text = "RUN LATEST TRAINED SITE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = { onAction(AutonomousWorkflowAction.ResumeLatestCheckpoint) },
                enabled = workflowState.recoverableCheckpointCount > 0 && !workflowState.busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Warning.copy(alpha = 0.68f))
            ) {
                Text("RESUME CHECKPOINT", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { onAction(AutonomousWorkflowAction.StopWorkflow) },
                enabled = workflow.running,
                modifier = Modifier
                    .weight(0.65f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Danger.copy(alpha = 0.75f))
            ) {
                Text("STOP", color = AerospacePalette.Danger)
            }

            OutlinedButton(
                onClick = { onAction(AutonomousWorkflowAction.Refresh) },
                enabled = !workflowState.busy,
                modifier = Modifier
                    .weight(0.65f)
                    .heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("REFRESH")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CapabilityControl(
                title = "VISION",
                status = workflowState.vision,
                connectAction = AutonomousWorkflowAction.ConnectVision,
                disconnectAction = AutonomousWorkflowAction.DisconnectVision,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
            CapabilityControl(
                title = "WAFER STAGE",
                status = workflowState.waferStage,
                connectAction = AutonomousWorkflowAction.ConnectWaferStage,
                disconnectAction = AutonomousWorkflowAction.DisconnectWaferStage,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
            CapabilityControl(
                title = "PROBE TRACKING",
                status = workflowState.probeTracking,
                connectAction = AutonomousWorkflowAction.ConnectProbeTracking,
                disconnectAction = AutonomousWorkflowAction.DisconnectProbeTracking,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.weight(0.25f))

            Column(
                modifier = Modifier.weight(1.15f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "ACTIVE CALIBRATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = workflowState.activeProfile?.name ?: "No profile selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (workflowState.verifiedProfileApplied) {
                        AerospacePalette.Success
                    } else {
                        AerospacePalette.TextMuted
                    },
                    maxLines = 1
                )
                Text(
                    text = "Phase 1 uses existing positioner and power meter only; optional adapters remain non-blocking.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun CapabilityControl(
    title: String,
    status: AutonomyCapabilityStatus,
    connectAction: AutonomousWorkflowAction,
    disconnectAction: AutonomousWorkflowAction,
    busy: Boolean,
    onAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = statusTone(status.state)
    OutlinedButton(
        onClick = {
            onAction(if (status.connected) disconnectAction else connectAction)
        },
        enabled = status.configured && !busy,
        modifier = modifier.heightIn(min = 38.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.48f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (status.configured) status.state.name.uppercase() else "OPTIONAL",
                style = MaterialTheme.typography.labelSmall,
                color = tone,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun statusTone(state: AutonomyCapabilityState): Color = when (state) {
    AutonomyCapabilityState.NotConfigured -> AerospacePalette.TextMuted
    AutonomyCapabilityState.Disconnected -> AerospacePalette.Warning
    AutonomyCapabilityState.Connecting -> AerospacePalette.Accent
    AutonomyCapabilityState.Ready -> AerospacePalette.Success
    AutonomyCapabilityState.Busy -> AerospacePalette.Warning
    AutonomyCapabilityState.Error -> AerospacePalette.Danger
}
