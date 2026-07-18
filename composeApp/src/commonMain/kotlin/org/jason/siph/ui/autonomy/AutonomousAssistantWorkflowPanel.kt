package org.jason.siph.ui.autonomy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.TelemetryPill

/**
 * 带真实能力端口状态的自主助手入口。
 *
 * 下方仍复用原有引导页面；上方新增端口连接、配置仓库和错误状态，
 * 使机器视觉、晶圆台和探针跟踪能够通过 Koin 适配器逐步接入。
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
        AutonomousRuntimePanel(
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
private fun AutonomousRuntimePanel(
    workflowState: AutonomousWorkflowUiState,
    onAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val error = workflowState.errorMessage

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        highlighted = workflowState.verifiedProfileApplied && error == null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "AUTONOMY RUNTIME",
            title = "ADAPTER AND CALIBRATION READINESS",
            caption = error ?: workflowState.message,
            trailing = {
                TelemetryPill(
                    label = "ENGINE",
                    value = when {
                        error != null -> "ERROR"
                        workflowState.busy -> "BUSY"
                        workflowState.verifiedProfileApplied -> "PROFILE READY"
                        else -> "SETUP"
                    },
                    tone = when {
                        error != null -> AerospacePalette.Danger
                        workflowState.busy -> AerospacePalette.Warning
                        workflowState.verifiedProfileApplied -> AerospacePalette.Success
                        else -> AerospacePalette.Accent
                    },
                    active = error == null
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Stretch
        ) {
            CapabilityRuntimeCard(
                title = "VISION ALIGNMENT",
                status = workflowState.vision,
                connectAction = AutonomousWorkflowAction.ConnectVision,
                disconnectAction = AutonomousWorkflowAction.DisconnectVision,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
            CapabilityRuntimeCard(
                title = "WAFER STAGE",
                status = workflowState.waferStage,
                connectAction = AutonomousWorkflowAction.ConnectWaferStage,
                disconnectAction = AutonomousWorkflowAction.DisconnectWaferStage,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
            CapabilityRuntimeCard(
                title = "PROBE TRACKING",
                status = workflowState.probeTracking,
                connectAction = AutonomousWorkflowAction.ConnectProbeTracking,
                disconnectAction = AutonomousWorkflowAction.DisconnectProbeTracking,
                busy = workflowState.busy,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
            CalibrationRuntimeCard(
                workflowState = workflowState,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CapabilityRuntimeCard(
    title: String,
    status: AutonomyCapabilityStatus,
    connectAction: AutonomousWorkflowAction,
    disconnectAction: AutonomousWorkflowAction,
    busy: Boolean,
    onAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = statusTone(status.state)
    val canConnect = status.configured && !status.connected && !busy
    val canDisconnect = status.connected && !busy

    androidx.compose.material3.Surface(
        modifier = modifier.heightIn(min = 132.dp),
        shape = MaterialTheme.shapes.medium,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = status.state.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = status.identity ?: status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted,
                maxLines = 2
            )

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = {
                    onAction(if (status.connected) disconnectAction else connectAction)
                },
                enabled = canConnect || canDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, tone.copy(alpha = 0.56f))
            ) {
                Text(
                    text = when {
                        !status.configured -> "ADAPTER REQUIRED"
                        status.connected -> "DISCONNECT"
                        else -> "CONNECT"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CalibrationRuntimeCard(
    workflowState: AutonomousWorkflowUiState,
    onAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = workflowState.activeProfile
    val tone = when {
        profile?.verified == true -> AerospacePalette.Success
        profile != null -> AerospacePalette.Warning
        else -> AerospacePalette.TextMuted
    }

    androidx.compose.material3.Surface(
        modifier = modifier.heightIn(min = 132.dp),
        shape = MaterialTheme.shapes.medium,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CALIBRATION PROFILE",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        profile?.verified == true -> "VERIFIED"
                        profile != null -> "UNVERIFIED"
                        else -> "NONE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = profile?.name ?: "No controller/fixture profile is active",
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted,
                maxLines = 2
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onAction(AutonomousWorkflowAction.Refresh) },
                    enabled = !workflowState.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("REFRESH", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = { onAction(AutonomousWorkflowAction.ClearActiveProfile) },
                    enabled = profile != null && !workflowState.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, AerospacePalette.Warning.copy(alpha = 0.52f))
                ) {
                    Text("CLEAR", style = MaterialTheme.typography.labelSmall)
                }
            }
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
