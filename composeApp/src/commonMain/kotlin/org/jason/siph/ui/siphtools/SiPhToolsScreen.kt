package org.jason.siph.ui.siphtools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.runtime.HardwareRuntimeMode
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

@Composable
fun CouplingToolScreen(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    onAction: (CouplingToolAction) -> Unit,
    onSafetyAction: (MotionSafetyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = safetyState.interlockReady
    val connectLabel = if (safetyState.runtimeMode == HardwareRuntimeMode.Demo) {
        "Connect Demo"
    } else {
        "Connect Real"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CouplingToolTopBar(
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
                color = MaterialTheme.colorScheme.surface
            ) {
                CouplingToolNavigationPanel(
                    selectedPage = state.selectedPage,
                    onSelectPage = {
                        onAction(CouplingToolAction.SelectPage(it))
                    },
                    modifier = Modifier
                        .width(236.dp)
                        .fillMaxHeight()
                )
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                            )
                        )
                    )
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                when (state.selectedPage) {
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
                        modifier = Modifier.fillMaxWidth()
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

        HorizontalDivider()
        CouplingToolStatusBar(
            state = state.status,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CouplingToolTopBar(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    onAction: (CouplingToolAction) -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SiPh Studio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Optical coupling alignment and PI hexapod control",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            InterlockBadge(
                state = safetyState,
                modifier = Modifier.padding(end = 10.dp)
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = when {
                    state.status.isError -> MaterialTheme.colorScheme.errorContainer
                    state.coupling.isRunning -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = when {
                    state.status.isError -> MaterialTheme.colorScheme.onErrorContainer
                    state.coupling.isRunning -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(end = 14.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.medium
                    )
            ) {
                Text(
                    text = state.runState.text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Button(
                onClick = { onAction(CouplingToolAction.StartCoupling) },
                enabled = safetyState.interlockReady && state.canStartCoupling,
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Text("Start Coupling")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.StopCoupling) },
                enabled = state.coupling.isRunning,
                modifier = Modifier.heightIn(min = 40.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (state.coupling.stopRequested) "Stopping..." else "Stop")
            }
        }
    }
}

@Composable
private fun InterlockBadge(
    state: MotionSafetyUiState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.medium
        ),
        shape = MaterialTheme.shapes.medium,
        color = when (state.interlockStatus) {
            SafetyInterlockStatus.Ready -> MaterialTheme.colorScheme.primaryContainer
            SafetyInterlockStatus.NotReady -> MaterialTheme.colorScheme.tertiaryContainer
            SafetyInterlockStatus.Invalid -> MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "Safety ${state.interlockStatus.text}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${state.runtimeMode.text} · ${state.source.text}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CouplingExecutionStrip(
    state: CouplingToolUiState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.coupling.currentStage?.text ?: state.coupling.state.text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${state.coupling.sampleCount} samples" +
                        state.coupling.estimatedSamples.takeIf { it > 0 }?.let { " / ~$it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LinearProgressIndicator(
                progress = { state.coupling.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
