package org.jason.siph.ui.siphtools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.autonomy.AutonomousAssistantPanel
import org.jason.siph.ui.autonomy.AutonomousWorkflowAction
import org.jason.siph.ui.autonomy.AutonomousWorkflowUiState
import org.jason.siph.ui.coupling.CouplingWorkspace
import org.jason.siph.ui.coupling.PivotSetupPanel
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolPage
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyAction
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.model.SafetyInterlockStatus
import org.jason.siph.ui.positioner.PositionerControlPanel
import org.jason.siph.ui.safety.MotionSafetyConfigPanel
import org.jason.siph.ui.theme.AerospaceBackdrop
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.TelemetryPill

@Composable
fun CouplingToolScreen(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    autonomousState: AutonomousWorkflowUiState,
    onAction: (CouplingToolAction) -> Unit,
    onSafetyAction: (MotionSafetyAction) -> Unit,
    onAutonomousAction: (AutonomousWorkflowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = safetyState.interlockReady
    val connectLabel = if (safetyState.runtimeMode == HardwareRuntimeMode.Demo) {
        "CONNECT DEMO"
    } else {
        "CONNECT REAL"
    }

    AerospaceBackdrop(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MissionControlTopBar(
                state = state,
                safetyState = safetyState,
                onAction = onAction
            )

            if (state.coupling.isRunning || state.coupling.progress > 0f) {
                CouplingExecutionStrip(
                    state = state,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Surface(
                    tonalElevation = 0.dp,
                    color = AerospacePalette.Void.copy(alpha = 0.98f),
                    modifier = Modifier
                        .width(244.dp)
                        .fillMaxHeight()
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        NavigationMissionHeader(
                            runtimeMode = safetyState.runtimeMode,
                            interlockReady = safetyState.interlockReady
                        )
                        HorizontalDivider(color = AerospacePalette.Border)
                        CouplingToolNavigationPanel(
                            selectedPage = state.selectedPage,
                            onSelectPage = {
                                onAction(CouplingToolAction.SelectPage(it))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = AerospacePalette.Border
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    MissionPageHeader(
                        page = state.selectedPage,
                        motionEnabled = motionEnabled,
                        deviceConnected = state.positioner.connected
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        when (state.selectedPage) {
                            CouplingToolPage.AutonomousAssistant -> AutonomousAssistantPanel(
                                state = state,
                                safetyState = safetyState,
                                workflowState = autonomousState,
                                onAction = onAction,
                                onWorkflowAction = onAutonomousAction,
                                modifier = Modifier.fillMaxSize()
                            )

                            CouplingToolPage.Coupling -> CouplingWorkspace(
                                state = state,
                                onAction = onAction,
                                motionEnabled = motionEnabled,
                                connectLabel = connectLabel,
                                modifier = Modifier.fillMaxSize()
                            )

                            CouplingToolPage.PivotSetup -> PivotSetupPanel(
                                state = state,
                                onAction = onAction,
                                modifier = Modifier
                                    .widthIn(max = 1280.dp)
                                    .fillMaxWidth()
                            )

                            CouplingToolPage.ManualControl -> PositionerControlPanel(
                                state = state.positioner,
                                onAction = onAction,
                                motionEnabled = motionEnabled,
                                connectLabel = connectLabel,
                                modifier = Modifier
                                    .widthIn(max = 1280.dp)
                                    .fillMaxWidth()
                            )

                            CouplingToolPage.MotionSafety -> MotionSafetyConfigPanel(
                                state = safetyState,
                                onAction = onSafetyAction,
                                motionBusy = state.positioner.isMoving || state.coupling.isRunning,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = AerospacePalette.Border)
            Surface(
                color = AerospacePalette.Void.copy(alpha = 0.98f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                CouplingToolStatusBar(
                    state = state.status,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MissionControlTopBar(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    onAction: (CouplingToolAction) -> Unit
) {
    Surface(
        color = AerospacePalette.Void.copy(alpha = 0.98f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.dp, Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "SIPH // ALIGNMENT",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                )
                Text(
                    text = "PHOTONIC COUPLING MISSION CONTROL",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TelemetryPill(
                label = "MODE",
                value = safetyState.runtimeMode.text.uppercase(),
                tone = if (safetyState.runtimeMode == HardwareRuntimeMode.Demo) {
                    AerospacePalette.Warning
                } else {
                    AerospacePalette.Accent
                }
            )

            TelemetryPill(
                label = "INTERLOCK",
                value = safetyState.interlockStatus.text.uppercase(),
                tone = when (safetyState.interlockStatus) {
                    SafetyInterlockStatus.Ready -> AerospacePalette.Success
                    SafetyInterlockStatus.NotReady -> AerospacePalette.Warning
                    SafetyInterlockStatus.Invalid -> AerospacePalette.Danger
                },
                active = safetyState.interlockReady
            )

            TelemetryPill(
                label = "RUN STATE",
                value = state.runState.text.uppercase(),
                tone = when {
                    state.status.isError -> AerospacePalette.Danger
                    state.coupling.isRunning -> AerospacePalette.AccentBright
                    else -> AerospacePalette.TextSecondary
                },
                active = state.coupling.isRunning || state.runState.text != "Idle"
            )

            Button(
                onClick = { onAction(CouplingToolAction.StartCoupling) },
                enabled = safetyState.interlockReady && state.canStartCoupling,
                modifier = Modifier.heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text("START", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.StopCoupling) },
                enabled = state.coupling.isRunning,
                modifier = Modifier.heightIn(min = 42.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Danger.copy(alpha = 0.72f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AerospacePalette.Danger,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text(if (state.coupling.stopRequested) "STOPPING" else "ABORT")
            }
        }
    }
}

@Composable
private fun NavigationMissionHeader(
    runtimeMode: HardwareRuntimeMode,
    interlockReady: Boolean
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "MISSION MODULES",
            style = MaterialTheme.typography.labelSmall,
            color = AerospacePalette.TextMuted
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(2.dp)
                    .background(
                        if (interlockReady) AerospacePalette.Success else AerospacePalette.Warning
                    )
            )
            Text(
                text = "${runtimeMode.text.uppercase()} SYSTEM",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun MissionPageHeader(
    page: CouplingToolPage,
    motionEnabled: Boolean,
    deviceConnected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "ACTIVE MODULE / ${page.name.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.Accent
            )
            Text(
                text = page.title.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = page.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TelemetryPill(
            label = "MOTION",
            value = if (motionEnabled) "ARMED" else "LOCKED",
            tone = if (motionEnabled) AerospacePalette.Success else AerospacePalette.Warning,
            active = motionEnabled
        )
        TelemetryPill(
            label = "POSITIONER",
            value = if (deviceConnected) "ONLINE" else "OFFLINE",
            tone = if (deviceConnected) AerospacePalette.Accent else AerospacePalette.TextMuted,
            active = deviceConnected
        )
    }
}

@Composable
private fun CouplingExecutionStrip(
    state: CouplingToolUiState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = AerospacePalette.PanelRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE SEQUENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.Accent
                )
                Text(
                    text = state.coupling.currentStage?.text?.uppercase()
                        ?: state.coupling.state.text.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${state.coupling.sampleCount} SAMPLES" +
                        state.coupling.estimatedSamples.takeIf { it > 0 }
                            ?.let { " / EST $it" }
                            .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${(state.coupling.progress * 100f).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = AerospacePalette.AccentBright,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }

            LinearProgressIndicator(
                progress = { state.coupling.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = AerospacePalette.Accent,
                trackColor = AerospacePalette.Border
            )
        }
    }
}
