package org.jason.siph.ui.autonomy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolPage
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.absoluteValue

/**
 * Guided silicon-photonics operations center inspired by autonomous probe-system
 * workflows. It intentionally exposes only capabilities that are currently backed
 * by SiPhStudio state; camera, wafer-map and probe-tracking integrations are shown
 * as unconfigured rather than simulated as real hardware.
 */
@Composable
fun AutonomousAssistantPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTemplate by remember {
        mutableStateOf(AssistantWorkflowTemplate.VerticalGrating)
    }
    val scrollState = rememberScrollState()
    val readiness = remember(state, safetyState) {
        buildAssistantReadiness(state, safetyState)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useSingleColumn = this.maxWidth < 1120.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AssistantMissionHeader(
                state = state,
                safetyState = safetyState,
                readiness = readiness,
                onAction = onAction
            )

            WorkflowTemplateSelector(
                selected = selectedTemplate,
                onSelect = { selectedTemplate = it }
            )

            if (useSingleColumn) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    VisionAlignmentPanel(
                        state = state,
                        safetyState = safetyState,
                        template = selectedTemplate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 430.dp)
                    )
                    GuidedWorkflowPanel(
                        state = state,
                        safetyState = safetyState,
                        readiness = readiness,
                        onAction = onAction,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    VisionAlignmentPanel(
                        state = state,
                        safetyState = safetyState,
                        template = selectedTemplate,
                        modifier = Modifier
                            .weight(0.96f)
                            .heightIn(min = 610.dp)
                    )
                    GuidedWorkflowPanel(
                        state = state,
                        safetyState = safetyState,
                        readiness = readiness,
                        onAction = onAction,
                        modifier = Modifier.weight(1.04f)
                    )
                }
            }

            CapabilityMatrix(
                safetyState = safetyState,
                selectedTemplate = selectedTemplate,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "AUTONOMY NOTE // Vision, wafer-stage, sub-die and optical-tracking adapters are not configured in the current build. Their status is shown explicitly and no synthetic hardware success is reported.",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AssistantMissionHeader(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    readiness: AssistantReadiness,
    onAction: (CouplingToolAction) -> Unit
) {
    AerospacePanel(
        modifier = Modifier.fillMaxWidth(),
        elevated = true,
        highlighted = readiness.readyForFirstLight
    ) {
        AerospaceSectionHeader(
            eyebrow = "AUTONOMOUS SILICON PHOTONICS",
            title = "GUIDED OPERATIONS CENTER",
            caption = "Train the setup, verify readiness and launch repeatable optical alignment workflows.",
            trailing = {
                TelemetryPill(
                    label = "ASSISTANT",
                    value = if (readiness.readyForFirstLight) "READY" else "HOLD",
                    tone = if (readiness.readyForFirstLight) {
                        AerospacePalette.Success
                    } else {
                        AerospacePalette.Warning
                    },
                    active = readiness.readyForFirstLight
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "INTERLOCK",
                value = safetyState.interlockStatus.text.uppercase(),
                emphasized = safetyState.interlockReady,
                accent = if (safetyState.interlockReady) {
                    AerospacePalette.Success
                } else {
                    AerospacePalette.Warning
                },
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "POSITIONER",
                value = if (state.positioner.connected) "ONLINE" else "OFFLINE",
                emphasized = state.positioner.connected,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "OPTICAL PEAK",
                value = formatPower(state.coupling.bestPowerDbm),
                emphasized = state.coupling.bestPowerDbm != null,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "SAMPLES",
                value = state.coupling.sampleCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onAction(CouplingToolAction.StartCoupling) },
                enabled = readiness.readyForFirstLight,
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
                Text(
                    text = if (state.coupling.isRunning) "SEQUENCE ACTIVE" else "SEARCH FIRST LIGHT",
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    onAction(CouplingToolAction.SelectPage(CouplingToolPage.Coupling))
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Accent.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AerospacePalette.AccentBright
                )
            ) {
                Text("OPEN COUPLING ANALYTICS")
            }
        }
    }
}

@Composable
private fun WorkflowTemplateSelector(
    selected: AssistantWorkflowTemplate,
    onSelect: (AssistantWorkflowTemplate) -> Unit
) {
    AerospacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "PROBE CONFIGURATION",
            title = "WORKFLOW TEMPLATE",
            caption = "Templates organize operator guidance only; they do not change device commands by themselves."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistantWorkflowTemplate.entries.forEach { template ->
                WorkflowTemplateButton(
                    template = template,
                    selected = selected == template,
                    onClick = { onSelect(template) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WorkflowTemplateButton(
    template: AssistantWorkflowTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            AerospacePalette.AccentContainer.copy(alpha = 0.72f)
        } else {
            AerospacePalette.PanelRaised
        },
        border = BorderStroke(
            1.dp,
            if (selected) AerospacePalette.Accent else AerospacePalette.Border
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = template.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) AerospacePalette.AccentBright else AerospacePalette.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = template.caption,
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted
            )
        }
    }
}

@Composable
private fun VisionAlignmentPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    template: AssistantWorkflowTemplate,
    modifier: Modifier = Modifier
) {
    AerospacePanel(
        modifier = modifier,
        elevated = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "VISION ALIGNMENT",
            title = "OPTICAL PROBE GEOMETRY",
            caption = if (safetyState.runtimeMode == HardwareRuntimeMode.Demo) {
                "Simulated geometry derived from the current positioner pose; not a camera stream."
            } else {
                "Vision adapter is not configured. Geometry remains a coordinate visualization."
            },
            trailing = {
                TelemetryPill(
                    label = "VISION LINK",
                    value = "NOT CONFIGURED",
                    tone = AerospacePalette.Warning,
                    active = false
                )
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 330.dp)
                .background(AerospacePalette.Void, MaterialTheme.shapes.medium)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawVisionGrid()
                drawAlignmentGeometry(
                    template = template,
                    xUm = state.positioner.currentPose.xUm,
                    yUm = state.positioner.currentPose.yUm,
                    zUm = state.positioner.currentPose.zUm
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = AerospacePalette.PanelRaised.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, AerospacePalette.Border)
            ) {
                Text(
                    text = "${template.shortCode} // COORDINATE VIEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.AccentBright,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "X ${formatCoordinate(state.positioner.currentPose.xUm)}  /  Y ${formatCoordinate(state.positioner.currentPose.yUm)}  /  Z ${formatCoordinate(state.positioner.currentPose.zUm)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextPrimary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (state.positioner.connected) {
                        "POSITION SOURCE // LIVE POSITIONER TELEMETRY"
                    } else {
                        "POSITION SOURCE // DISCONNECTED STATE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.positioner.connected) {
                        AerospacePalette.Success
                    } else {
                        AerospacePalette.TextMuted
                    },
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryPill(
                label = "COUPLING",
                value = template.couplingMode,
                tone = AerospacePalette.Accent,
                modifier = Modifier.weight(1f)
            )
            TelemetryPill(
                label = "PROBE",
                value = template.probeMode,
                tone = AerospacePalette.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            TelemetryPill(
                label = "CAMERA",
                value = "ADAPTER REQUIRED",
                tone = AerospacePalette.Warning,
                active = false,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GuidedWorkflowPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    readiness: AssistantReadiness,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = buildWorkflowSteps(state, safetyState, readiness)

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "SIPH-TOOLS WORKFLOW",
            title = "TRAIN · CALIBRATE · VERIFY",
            caption = "A guided readiness chain separates setup tasks from live coupling operations.",
            trailing = {
                TelemetryPill(
                    label = "CHECKS",
                    value = "${readiness.completedChecks}/${readiness.totalChecks}",
                    tone = if (readiness.readyForFirstLight) {
                        AerospacePalette.Success
                    } else {
                        AerospacePalette.Accent
                    }
                )
            }
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { index, step ->
                WorkflowStepCard(
                    index = index + 1,
                    step = step,
                    onClick = {
                        when (step.action) {
                            AssistantStepAction.OpenSafety -> {
                                onAction(CouplingToolAction.SelectPage(CouplingToolPage.MotionSafety))
                            }
                            AssistantStepAction.OpenManual -> {
                                onAction(CouplingToolAction.SelectPage(CouplingToolPage.ManualControl))
                            }
                            AssistantStepAction.OpenPivot -> {
                                onAction(CouplingToolAction.SelectPage(CouplingToolPage.PivotSetup))
                            }
                            AssistantStepAction.StartFirstLight -> {
                                onAction(CouplingToolAction.StartCoupling)
                            }
                            AssistantStepAction.OpenAnalytics -> {
                                onAction(CouplingToolAction.SelectPage(CouplingToolPage.Coupling))
                            }
                            AssistantStepAction.None -> Unit
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkflowStepCard(
    index: Int,
    step: AssistantWorkflowStep,
    onClick: () -> Unit
) {
    val tone = step.status.tone

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = step.enabled && step.action != AssistantStepAction.None, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(1.dp, tone.copy(alpha = if (step.status.isComplete) 0.55f else 0.30f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = tone.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, tone.copy(alpha = 0.62f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = AerospacePalette.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = step.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = step.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = AerospacePalette.TextMuted
                )
            }

            if (step.action != AssistantStepAction.None) {
                Text(
                    text = if (step.enabled) "OPEN" else "HOLD",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.enabled) AerospacePalette.AccentBright else AerospacePalette.TextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CapabilityMatrix(
    safetyState: MotionSafetyUiState,
    selectedTemplate: AssistantWorkflowTemplate,
    modifier: Modifier = Modifier
) {
    val capabilities = listOf(
        AssistantCapability(
            title = "Automatic First Light",
            caption = "Existing spiral and fine-XYZ search workflow",
            status = "AVAILABLE",
            tone = AerospacePalette.Success
        ),
        AssistantCapability(
            title = "Protected Motion",
            caption = "Soft limits and clearance-Z transfer planning",
            status = if (safetyState.interlockReady) "ARMED" else "LOCKED",
            tone = if (safetyState.interlockReady) AerospacePalette.Success else AerospacePalette.Warning
        ),
        AssistantCapability(
            title = "${selectedTemplate.title} Template",
            caption = "Operator workflow and geometry guidance",
            status = "SELECTED",
            tone = AerospacePalette.Accent
        ),
        AssistantCapability(
            title = "Machine Vision Alignment",
            caption = "Camera calibration and feature-detection adapter",
            status = "NOT CONFIGURED",
            tone = AerospacePalette.Warning
        ),
        AssistantCapability(
            title = "Optical Probe Tracking",
            caption = "Requires vision or displacement-sensor feedback",
            status = "ADAPTER REQUIRED",
            tone = AerospacePalette.Warning
        ),
        AssistantCapability(
            title = "Wafer / Sub-Die Management",
            caption = "Requires prober map and subsite integration",
            status = "NOT LOADED",
            tone = AerospacePalette.TextMuted
        ),
        AssistantCapability(
            title = "Calibration Wafer Verification",
            caption = "Workflow shell available; reference data not configured",
            status = "PENDING",
            tone = AerospacePalette.TextMuted
        ),
        AssistantCapability(
            title = "Physical Collision Sensing",
            caption = "Software limits do not replace hardware sensing",
            status = "NOT PRESENT",
            tone = AerospacePalette.Danger
        )
    )

    AerospacePanel(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "INTEGRATION STATUS",
            title = "AUTONOMY CAPABILITY MATRIX",
            caption = "Implemented, configured and deferred functions are intentionally distinguished."
        )

        capabilities.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { capability ->
                    CapabilityCard(
                        capability = capability,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    capability: AssistantCapability,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(1.dp, capability.tone.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .background(capability.tone, MaterialTheme.shapes.extraSmall)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = capability.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = AerospacePalette.TextPrimary
                )
                Text(
                    text = capability.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = AerospacePalette.TextMuted
                )
            }
            Text(
                text = capability.status,
                style = MaterialTheme.typography.labelSmall,
                color = capability.tone,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun buildAssistantReadiness(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState
): AssistantReadiness {
    val checks = listOf(
        safetyState.interlockReady,
        state.positioner.connected,
        state.coupling.config.virtualPivotPoint.enabled,
        state.coupling.samples.isNotEmpty(),
        state.coupling.bestPowerDbm != null
    )

    return AssistantReadiness(
        completedChecks = checks.count { it },
        totalChecks = checks.size,
        readyForFirstLight = safetyState.interlockReady && state.canStartCoupling
    )
}

private fun buildWorkflowSteps(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    readiness: AssistantReadiness
): List<AssistantWorkflowStep> {
    val positionAvailable = state.positioner.connected
    val hasSamples = state.coupling.samples.isNotEmpty()
    val hasPeak = state.coupling.bestPowerDbm != null

    return listOf(
        AssistantWorkflowStep(
            title = "Safety Profile",
            caption = safetyState.profileName.takeIf { it.isNotBlank() }
                ?.let { "Applied profile: $it" }
                ?: "Load and verify six-axis limits plus clearance Z.",
            status = if (safetyState.interlockReady) AssistantStepStatus.Complete else AssistantStepStatus.Required,
            action = AssistantStepAction.OpenSafety,
            enabled = !state.coupling.isRunning
        ),
        AssistantWorkflowStep(
            title = "Positioner Link",
            caption = state.positioner.idn ?: "Connect the six-axis optical positioner and synchronize its pose.",
            status = if (positionAvailable) AssistantStepStatus.Complete else AssistantStepStatus.Required,
            action = AssistantStepAction.OpenManual,
            enabled = !state.coupling.isRunning
        ),
        AssistantWorkflowStep(
            title = "Measurement Position",
            caption = if (positionAvailable) {
                "Current coordinate is available; explicit training persistence is not configured."
            } else {
                "Positioner connection is required before a measurement position can be prepared."
            },
            status = if (positionAvailable) AssistantStepStatus.Available else AssistantStepStatus.Blocked,
            action = AssistantStepAction.OpenManual,
            enabled = positionAvailable && !state.coupling.isRunning
        ),
        AssistantWorkflowStep(
            title = "Wafer / Probe Height Training",
            caption = "Requires wafer-stage map, camera or displacement-sensor integration.",
            status = AssistantStepStatus.NotConfigured,
            action = AssistantStepAction.None,
            enabled = false
        ),
        AssistantWorkflowStep(
            title = "Pivot Point Calibration",
            caption = if (state.coupling.config.virtualPivotPoint.enabled) {
                "Virtual pivot compensation is configured for angle optimization."
            } else {
                "Capture or enter the optical-tip rotation center."
            },
            status = if (state.coupling.config.virtualPivotPoint.enabled) {
                AssistantStepStatus.Complete
            } else {
                AssistantStepStatus.Available
            },
            action = AssistantStepAction.OpenPivot,
            enabled = !state.coupling.isRunning
        ),
        AssistantWorkflowStep(
            title = "Search First Light",
            caption = if (hasSamples) {
                "${state.coupling.sampleCount} samples recorded; current peak ${formatPower(state.coupling.bestPowerDbm)}."
            } else {
                "Launch the protected spiral search and optional fine XYZ optimization."
            },
            status = when {
                state.coupling.isRunning -> AssistantStepStatus.Running
                hasSamples -> AssistantStepStatus.Complete
                readiness.readyForFirstLight -> AssistantStepStatus.Available
                else -> AssistantStepStatus.Blocked
            },
            action = AssistantStepAction.StartFirstLight,
            enabled = readiness.readyForFirstLight
        ),
        AssistantWorkflowStep(
            title = "Optical Alignment Verification",
            caption = if (hasPeak) {
                "Review power trace, XY field, best pose and repeatability evidence."
            } else {
                "A completed alignment result is required before verification."
            },
            status = if (hasPeak) AssistantStepStatus.Available else AssistantStepStatus.NotConfigured,
            action = AssistantStepAction.OpenAnalytics,
            enabled = hasPeak
        )
    )
}

private fun DrawScope.drawVisionGrid() {
    drawRect(AerospacePalette.Void)
    val minor = 42.dp.toPx()
    var x = 0f
    var index = 0
    while (x <= size.width) {
        drawLine(
            color = if (index % 4 == 0) AerospacePalette.Border else AerospacePalette.Border.copy(alpha = 0.40f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (index % 4 == 0) 0.8.dp.toPx() else 0.45.dp.toPx()
        )
        x += minor
        index += 1
    }
    var y = 0f
    index = 0
    while (y <= size.height) {
        drawLine(
            color = if (index % 4 == 0) AerospacePalette.Border else AerospacePalette.Border.copy(alpha = 0.40f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (index % 4 == 0) 0.8.dp.toPx() else 0.45.dp.toPx()
        )
        y += minor
        index += 1
    }
}

private fun DrawScope.drawAlignmentGeometry(
    template: AssistantWorkflowTemplate,
    xUm: Double,
    yUm: Double,
    zUm: Double
) {
    val center = Offset(size.width * 0.52f, size.height * 0.54f)
    val xShift = normalizeVisualOffset(xUm) * size.width * 0.14f
    val yShift = normalizeVisualOffset(yUm) * size.height * 0.14f
    val probeTip = Offset(center.x + xShift, center.y - size.height * 0.20f + yShift)

    drawCircle(
        color = AerospacePalette.PanelRaised,
        radius = minOf(size.width, size.height) * 0.26f,
        center = center
    )
    drawCircle(
        color = AerospacePalette.BorderStrong,
        radius = minOf(size.width, size.height) * 0.26f,
        center = center,
        style = Stroke(width = 1.dp.toPx())
    )

    val reticleRadius = minOf(size.width, size.height) * 0.075f
    drawCircle(
        color = AerospacePalette.Success.copy(alpha = 0.65f),
        radius = reticleRadius,
        center = center,
        style = Stroke(width = 1.1.dp.toPx())
    )
    drawLine(
        AerospacePalette.Success.copy(alpha = 0.72f),
        Offset(center.x - reticleRadius * 1.55f, center.y),
        Offset(center.x + reticleRadius * 1.55f, center.y),
        strokeWidth = 0.8.dp.toPx()
    )
    drawLine(
        AerospacePalette.Success.copy(alpha = 0.72f),
        Offset(center.x, center.y - reticleRadius * 1.55f),
        Offset(center.x, center.y + reticleRadius * 1.55f),
        strokeWidth = 0.8.dp.toPx()
    )

    when (template) {
        AssistantWorkflowTemplate.VerticalGrating -> {
            drawRoundRect(
                color = AerospacePalette.AccentContainer,
                topLeft = Offset(center.x - 34.dp.toPx(), center.y - 11.dp.toPx()),
                size = Size(68.dp.toPx(), 22.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            repeat(7) { index ->
                val gx = center.x - 25.dp.toPx() + index * 8.4.dp.toPx()
                drawLine(
                    AerospacePalette.AccentBright.copy(alpha = 0.72f),
                    Offset(gx, center.y - 7.dp.toPx()),
                    Offset(gx, center.y + 7.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawLine(
                AerospacePalette.TextPrimary,
                Offset(probeTip.x, 10.dp.toPx()),
                probeTip,
                strokeWidth = 3.dp.toPx()
            )
        }
        AssistantWorkflowTemplate.EdgeCoupling -> {
            val facetX = center.x + 26.dp.toPx()
            drawRoundRect(
                color = AerospacePalette.PanelHover,
                topLeft = Offset(center.x - 58.dp.toPx(), center.y - 36.dp.toPx()),
                size = Size(84.dp.toPx(), 72.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            drawLine(
                AerospacePalette.Success,
                Offset(facetX, center.y - 24.dp.toPx()),
                Offset(facetX, center.y + 24.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                AerospacePalette.TextPrimary,
                Offset(size.width - 10.dp.toPx(), center.y + yShift),
                Offset(facetX + xShift, center.y + yShift),
                strokeWidth = 3.dp.toPx()
            )
        }
        AssistantWorkflowTemplate.FiberArray -> {
            drawRoundRect(
                color = AerospacePalette.AccentContainer,
                topLeft = Offset(center.x - 58.dp.toPx(), center.y - 14.dp.toPx()),
                size = Size(116.dp.toPx(), 28.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            repeat(6) { index ->
                val localX = center.x - 45.dp.toPx() + index * 18.dp.toPx()
                drawCircle(
                    color = AerospacePalette.AccentBright,
                    radius = 3.dp.toPx(),
                    center = Offset(localX, center.y)
                )
                drawLine(
                    AerospacePalette.TextPrimary.copy(alpha = 0.85f),
                    Offset(localX + xShift, 12.dp.toPx()),
                    Offset(localX + xShift, center.y - 18.dp.toPx() + yShift),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }

    drawCircle(
        color = AerospacePalette.Warning.copy(alpha = 0.20f),
        radius = 12.dp.toPx(),
        center = probeTip
    )
    drawCircle(
        color = AerospacePalette.Warning,
        radius = 4.dp.toPx(),
        center = probeTip
    )

    val zRatio = (zUm.absoluteValue / 50.0).toFloat().coerceIn(0f, 1f)
    val barHeight = size.height * 0.36f
    val barLeft = size.width - 32.dp.toPx()
    val barTop = size.height * 0.26f
    drawRect(
        color = AerospacePalette.Border,
        topLeft = Offset(barLeft, barTop),
        size = Size(7.dp.toPx(), barHeight)
    )
    drawRect(
        color = AerospacePalette.Accent,
        topLeft = Offset(barLeft, barTop + barHeight * (1f - zRatio)),
        size = Size(7.dp.toPx(), barHeight * zRatio)
    )
}

private fun normalizeVisualOffset(value: Double): Float =
    (value / 50.0).toFloat().coerceIn(-1f, 1f)

private fun formatCoordinate(value: Double): String =
    "${kotlin.math.round(value * 1000.0) / 1000.0} µm"

private fun formatPower(value: Double?): String =
    value?.takeIf { it.isFinite() }
        ?.let { "${kotlin.math.round(it * 100.0) / 100.0} dBm" }
        ?: "-- dBm"

private enum class AssistantWorkflowTemplate(
    val title: String,
    val caption: String,
    val shortCode: String,
    val couplingMode: String,
    val probeMode: String
) {
    VerticalGrating(
        title = "Vertical Grating",
        caption = "Surface grating and incident-angle workflow",
        shortCode = "VERTICAL",
        couplingMode = "SURFACE",
        probeMode = "SINGLE / ARRAY"
    ),
    EdgeCoupling(
        title = "Edge Coupling",
        caption = "Facet-gap and horizontal approach workflow",
        shortCode = "EDGE",
        couplingMode = "FACET",
        probeMode = "SINGLE FIBER"
    ),
    FiberArray(
        title = "Fiber Array",
        caption = "Multi-channel array geometry workflow",
        shortCode = "ARRAY",
        couplingMode = "MULTI-CHANNEL",
        probeMode = "FIBER ARRAY"
    )
}

private data class AssistantReadiness(
    val completedChecks: Int,
    val totalChecks: Int,
    val readyForFirstLight: Boolean
)

private data class AssistantWorkflowStep(
    val title: String,
    val caption: String,
    val status: AssistantStepStatus,
    val action: AssistantStepAction,
    val enabled: Boolean
)

private enum class AssistantStepStatus(
    val label: String,
    val tone: Color,
    val isComplete: Boolean = false
) {
    Complete("COMPLETE", AerospacePalette.Success, true),
    Running("RUNNING", AerospacePalette.AccentBright),
    Available("AVAILABLE", AerospacePalette.Accent),
    Required("REQUIRED", AerospacePalette.Warning),
    Blocked("BLOCKED", AerospacePalette.Warning),
    NotConfigured("NOT CONFIGURED", AerospacePalette.TextMuted)
}

private enum class AssistantStepAction {
    OpenSafety,
    OpenManual,
    OpenPivot,
    StartFirstLight,
    OpenAnalytics,
    None
}

private data class AssistantCapability(
    val title: String,
    val caption: String,
    val status: String,
    val tone: Color
)
