package org.jason.siph.ui.autonomy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
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
 * 自主硅光操作中心。
 *
 * 当前组件只编排 SiPhStudio 已经具备的安全、位置器和耦光能力。
 * 相机、晶圆台、探针跟踪等能力在真实适配器接入前只显示为未配置，
 * 不会模拟为已连接硬件，也不会绕过原有运动安全层。
 */
@Composable
fun AutonomousAssistantPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTemplate by remember {
        mutableStateOf(AssistantTemplate.VerticalGrating)
    }
    val readiness = remember(state, safetyState) {
        AssistantReadiness.from(state, safetyState)
    }
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableWidth = this.maxWidth
        val singleColumn = availableWidth < 1080.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AssistantHeader(
                state = state,
                safetyState = safetyState,
                readiness = readiness,
                onAction = onAction
            )

            TemplateSelector(
                selected = selectedTemplate,
                onSelect = { selectedTemplate = it }
            )

            if (singleColumn) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AlignmentGeometryPanel(
                        state = state,
                        safetyState = safetyState,
                        template = selectedTemplate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 390.dp)
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
                    AlignmentGeometryPanel(
                        state = state,
                        safetyState = safetyState,
                        template = selectedTemplate,
                        modifier = Modifier
                            .weight(0.95f)
                            .heightIn(min = 560.dp)
                    )
                    GuidedWorkflowPanel(
                        state = state,
                        safetyState = safetyState,
                        readiness = readiness,
                        onAction = onAction,
                        modifier = Modifier.weight(1.05f)
                    )
                }
            }

            CapabilityMatrix(
                safetyState = safetyState,
                template = selectedTemplate,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "AUTONOMY NOTE // Vision, wafer-stage and probe-tracking adapters are not configured in this build. No synthetic hardware success is reported.",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AssistantHeader(
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
            caption = "Train the setup, verify readiness and launch repeatable optical-alignment workflows.",
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
                    text = if (state.coupling.isRunning) {
                        "SEQUENCE ACTIVE"
                    } else {
                        "SEARCH FIRST LIGHT"
                    },
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
                border = BorderStroke(1.dp, AerospacePalette.Accent.copy(alpha = 0.65f))
            ) {
                Text("OPEN COUPLING ANALYTICS")
            }
        }
    }
}

@Composable
private fun TemplateSelector(
    selected: AssistantTemplate,
    onSelect: (AssistantTemplate) -> Unit
) {
    AerospacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "PROBE CONFIGURATION",
            title = "WORKFLOW TEMPLATE",
            caption = "Templates organize guidance only and do not send device commands."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistantTemplate.entries.forEach { template ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(template) },
                    shape = MaterialTheme.shapes.small,
                    color = if (selected == template) {
                        AerospacePalette.AccentContainer.copy(alpha = 0.72f)
                    } else {
                        AerospacePalette.PanelRaised
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected == template) {
                            AerospacePalette.Accent
                        } else {
                            AerospacePalette.Border
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = template.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected == template) {
                                AerospacePalette.AccentBright
                            } else {
                                AerospacePalette.TextPrimary
                            },
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
        }
    }
}

@Composable
private fun AlignmentGeometryPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    template: AssistantTemplate,
    modifier: Modifier = Modifier
) {
    AerospacePanel(
        modifier = modifier,
        elevated = true,
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "VISION ALIGNMENT",
            title = "OPTICAL PROBE GEOMETRY",
            caption = if (safetyState.runtimeMode == HardwareRuntimeMode.Demo) {
                "Coordinate visualization derived from the simulated positioner; this is not a camera stream."
            } else {
                "Vision adapter is not configured; the panel remains a coordinate visualization."
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
                .heightIn(min = 310.dp)
                .background(AerospacePalette.Void, MaterialTheme.shapes.medium)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val grid = 42.dp.toPx()
                var x = 0f
                while (x <= size.width) {
                    drawLine(
                        color = AerospacePalette.Border.copy(alpha = 0.32f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 0.7f
                    )
                    x += grid
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(
                        color = AerospacePalette.Border.copy(alpha = 0.32f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 0.7f
                    )
                    y += grid
                }

                val center = Offset(size.width * 0.52f, size.height * 0.52f)
                val positionScale = 0.8f
                val marker = Offset(
                    x = center.x + state.positioner.currentPose.xUm.toFloat() * positionScale,
                    y = center.y - state.positioner.currentPose.yUm.toFloat() * positionScale
                )

                drawLine(
                    color = AerospacePalette.Accent.copy(alpha = 0.65f),
                    start = Offset(center.x - 58f, center.y),
                    end = Offset(center.x + 58f, center.y),
                    strokeWidth = 1.2f
                )
                drawLine(
                    color = AerospacePalette.Accent.copy(alpha = 0.65f),
                    start = Offset(center.x, center.y - 58f),
                    end = Offset(center.x, center.y + 58f),
                    strokeWidth = 1.2f
                )
                drawCircle(
                    color = AerospacePalette.Accent,
                    radius = 34f,
                    center = center,
                    style = Stroke(width = 1.4f)
                )

                drawLine(
                    color = AerospacePalette.Success.copy(alpha = 0.62f),
                    start = center,
                    end = marker,
                    strokeWidth = 1.4f
                )
                drawCircle(
                    color = AerospacePalette.Success.copy(alpha = 0.20f),
                    radius = 15f,
                    center = marker
                )
                drawCircle(
                    color = AerospacePalette.TextPrimary,
                    radius = 4.5f,
                    center = marker
                )

                if (template == AssistantTemplate.EdgeCoupling) {
                    drawLine(
                        color = AerospacePalette.Warning,
                        start = Offset(size.width * 0.72f, size.height * 0.22f),
                        end = Offset(size.width * 0.72f, size.height * 0.82f),
                        strokeWidth = 2.2f
                    )
                } else {
                    drawCircle(
                        color = AerospacePalette.Warning.copy(alpha = 0.65f),
                        radius = if (template == AssistantTemplate.FiberArray) 48f else 24f,
                        center = Offset(size.width * 0.72f, size.height * 0.42f),
                        style = Stroke(width = 1.3f)
                    )
                }
            }

            TelemetryPill(
                label = "GEOMETRY",
                value = template.shortCode,
                tone = AerospacePalette.Accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "X ${formatCoordinate(state.positioner.currentPose.xUm)}  /  " +
                        "Y ${formatCoordinate(state.positioner.currentPose.yUm)}  /  " +
                        "Z ${formatCoordinate(state.positioner.currentPose.zUm)}",
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
        contentPadding = PaddingValues(14.dp)
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
            .clickable(
                enabled = step.enabled && step.action != AssistantStepAction.None,
                onClick = onClick
            ),
        shape = MaterialTheme.shapes.small,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(
            1.dp,
            tone.copy(alpha = if (step.status.complete) 0.55f else 0.30f)
        )
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
                    color = if (step.enabled) {
                        AerospacePalette.AccentBright
                    } else {
                        AerospacePalette.TextMuted
                    },
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CapabilityMatrix(
    safetyState: MotionSafetyUiState,
    template: AssistantTemplate,
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
            tone = if (safetyState.interlockReady) {
                AerospacePalette.Success
            } else {
                AerospacePalette.Warning
            }
        ),
        AssistantCapability(
            title = "${template.title} Template",
            caption = "Operator workflow and coordinate guidance",
            status = "SELECTED",
            tone = AerospacePalette.Accent
        ),
        AssistantCapability(
            title = "Machine Vision Alignment",
            caption = "Requires camera calibration and feature detection",
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
            title = "Calibration Profile",
            caption = "Repository and identity binding will be added next",
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
        contentPadding = PaddingValues(14.dp)
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
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        color = AerospacePalette.PanelRaised,
                        border = BorderStroke(1.dp, capability.tone.copy(alpha = 0.30f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = capability.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AerospacePalette.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = capability.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = capability.tone,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = capability.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = AerospacePalette.TextMuted
                            )
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun buildWorkflowSteps(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    readiness: AssistantReadiness
): List<AssistantWorkflowStep> = listOf(
    AssistantWorkflowStep(
        title = "Safety Profile",
        caption = safetyState.message,
        status = if (safetyState.interlockReady) {
            AssistantStepStatus.Complete
        } else {
            AssistantStepStatus.Required
        },
        action = AssistantStepAction.OpenSafety,
        enabled = !state.coupling.isRunning
    ),
    AssistantWorkflowStep(
        title = "Positioner Link",
        caption = if (state.positioner.connected) {
            state.positioner.idn ?: "Positioner communication established"
        } else {
            "Connect and inspect the six-axis positioner"
        },
        status = if (state.positioner.connected) {
            AssistantStepStatus.Complete
        } else {
            AssistantStepStatus.Required
        },
        action = AssistantStepAction.OpenManual,
        enabled = !state.coupling.isRunning
    ),
    AssistantWorkflowStep(
        title = "Measurement Position",
        caption = "Review the live X/Y/Z/U/V/W pose before training",
        status = if (readiness.measurementPositionAvailable) {
            AssistantStepStatus.Available
        } else {
            AssistantStepStatus.Blocked
        },
        action = AssistantStepAction.OpenManual,
        enabled = readiness.measurementPositionAvailable && !state.coupling.isRunning
    ),
    AssistantWorkflowStep(
        title = "Wafer / Probe Height Training",
        caption = "Requires wafer-stage and probe-height adapters",
        status = AssistantStepStatus.NotConfigured,
        action = AssistantStepAction.None,
        enabled = false
    ),
    AssistantWorkflowStep(
        title = "Pivot Point Calibration",
        caption = "Capture or verify the virtual rotation center",
        status = if (state.coupling.config.virtualPivotPoint.enabled) {
            AssistantStepStatus.Complete
        } else {
            AssistantStepStatus.Available
        },
        action = AssistantStepAction.OpenPivot,
        enabled = readiness.measurementPositionAvailable && !state.coupling.isRunning
    ),
    AssistantWorkflowStep(
        title = "Search First Light",
        caption = "Launch the existing protected spiral and fine-XYZ sequence",
        status = when {
            state.coupling.isRunning -> AssistantStepStatus.Running
            readiness.readyForFirstLight -> AssistantStepStatus.Available
            else -> AssistantStepStatus.Blocked
        },
        action = AssistantStepAction.StartFirstLight,
        enabled = readiness.readyForFirstLight
    ),
    AssistantWorkflowStep(
        title = "Optical Alignment Verification",
        caption = "Review peak power, field map, surface and best-pose telemetry",
        status = if (state.coupling.bestPowerDbm != null) {
            AssistantStepStatus.Complete
        } else {
            AssistantStepStatus.Pending
        },
        action = AssistantStepAction.OpenAnalytics,
        enabled = state.coupling.samples.isNotEmpty()
    )
)

private data class AssistantReadiness(
    val readyForFirstLight: Boolean,
    val measurementPositionAvailable: Boolean,
    val completedChecks: Int,
    val totalChecks: Int
) {
    companion object {
        fun from(
            state: CouplingToolUiState,
            safetyState: MotionSafetyUiState
        ): AssistantReadiness {
            val safetyReady = safetyState.interlockReady
            val positionerReady = state.positioner.connected && !state.positioner.connecting
            val motionIdle = !state.positioner.isMoving && !state.coupling.isRunning
            val startPoseReady = state.coupling.canResolveStartPose
            val measurementPositionAvailable = positionerReady && safetyReady
            val ready = safetyReady && positionerReady && motionIdle && startPoseReady
            val checks = listOf(
                safetyReady,
                positionerReady,
                motionIdle,
                startPoseReady,
                state.coupling.bestPowerDbm != null
            )

            return AssistantReadiness(
                readyForFirstLight = ready,
                measurementPositionAvailable = measurementPositionAvailable,
                completedChecks = checks.count { it },
                totalChecks = checks.size
            )
        }
    }
}

private enum class AssistantTemplate(
    val title: String,
    val caption: String,
    val shortCode: String,
    val couplingMode: String,
    val probeMode: String
) {
    VerticalGrating(
        title = "Vertical Grating",
        caption = "Top-side coupling through a grating coupler",
        shortCode = "VGC",
        couplingMode = "VERTICAL",
        probeMode = "SINGLE FIBER"
    ),
    EdgeCoupling(
        title = "Edge Coupling",
        caption = "Facet alignment with controlled lateral approach",
        shortCode = "EDGE",
        couplingMode = "FACET",
        probeMode = "LENSED FIBER"
    ),
    FiberArray(
        title = "Fiber Array",
        caption = "Multi-channel alignment around a shared reference",
        shortCode = "ARRAY",
        couplingMode = "MULTI-CHANNEL",
        probeMode = "FIBER ARRAY"
    )
}

private enum class AssistantStepAction {
    OpenSafety,
    OpenManual,
    OpenPivot,
    StartFirstLight,
    OpenAnalytics,
    None
}

private enum class AssistantStepStatus(
    val label: String,
    val tone: androidx.compose.ui.graphics.Color,
    val complete: Boolean
) {
    Complete("COMPLETE", AerospacePalette.Success, true),
    Available("AVAILABLE", AerospacePalette.Accent, false),
    Required("REQUIRED", AerospacePalette.Warning, false),
    Blocked("BLOCKED", AerospacePalette.TextMuted, false),
    Running("RUNNING", AerospacePalette.AccentBright, false),
    Pending("PENDING", AerospacePalette.TextMuted, false),
    NotConfigured("NOT CONFIGURED", AerospacePalette.Warning, false)
}

private data class AssistantWorkflowStep(
    val title: String,
    val caption: String,
    val status: AssistantStepStatus,
    val action: AssistantStepAction,
    val enabled: Boolean
)

private data class AssistantCapability(
    val title: String,
    val caption: String,
    val status: String,
    val tone: androidx.compose.ui.graphics.Color
)

private fun formatPower(value: Double?): String =
    value?.let { "${roundTwoDecimals(it)} dBm" } ?: "-- dBm"

private fun formatCoordinate(value: Double): String =
    "${roundTwoDecimals(value)} µm"

private fun roundTwoDecimals(value: Double): Double {
    if (value.absoluteValue < 0.005) return 0.0
    return kotlin.math.round(value * 100.0) / 100.0
}
