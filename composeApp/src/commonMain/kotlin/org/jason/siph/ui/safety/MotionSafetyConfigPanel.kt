package org.jason.siph.ui.safety

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.model.AxisSoftLimitUiState
import org.jason.siph.ui.model.MotionSafetyAction
import org.jason.siph.ui.model.MotionSafetyConfigUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.model.SafetyInterlockStatus

@Composable
fun MotionSafetyConfigPanel(
    state: MotionSafetyUiState,
    onAction: (MotionSafetyAction) -> Unit,
    motionBusy: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val editable = !motionBusy

    Column(
        modifier = modifier
            .widthIn(max = 1240.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InterlockOverviewCard(state)

        ProfileCard(
            state = state,
            editable = editable,
            onAction = onAction
        )

        SoftLimitCard(
            config = state.draft,
            editable = editable,
            onConfigChange = { onAction(MotionSafetyAction.UpdateDraft(it)) }
        )

        ProtectedTransferCard(
            config = state.draft,
            editable = editable,
            onConfigChange = { onAction(MotionSafetyAction.UpdateDraft(it)) }
        )

        ActionCard(
            state = state,
            editable = editable,
            onAction = onAction
        )
    }
}

@Composable
private fun InterlockOverviewCard(state: MotionSafetyUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state.interlockStatus) {
                SafetyInterlockStatus.Ready -> MaterialTheme.colorScheme.primaryContainer
                SafetyInterlockStatus.NotReady -> MaterialTheme.colorScheme.tertiaryContainer
                SafetyInterlockStatus.Invalid -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Motion Safety Interlock",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.medium
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = state.interlockStatus.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (state.interlockStatus) {
                            SafetyInterlockStatus.Ready -> MaterialTheme.colorScheme.primary
                            SafetyInterlockStatus.NotReady -> MaterialTheme.colorScheme.tertiary
                            SafetyInterlockStatus.Invalid -> MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = "${state.runtimeMode.text} mode · ${state.source.text}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    state: MotionSafetyUiState,
    editable: Boolean,
    onAction: (MotionSafetyAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "Safety Profile",
                caption = if (state.runtimeMode == HardwareRuntimeMode.Real) {
                    "Real hardware requires a named and fixture-verified profile"
                } else {
                    "Demo parameters are active only for the simulated positioner"
                }
            )

            OutlinedTextField(
                value = state.profileName,
                onValueChange = {
                    onAction(MotionSafetyAction.UpdateProfileName(it))
                },
                enabled = editable,
                singleLine = true,
                label = { Text("Profile name") },
                supportingText = {
                    Text(
                        if (state.runtimeMode == HardwareRuntimeMode.Real) {
                            "Example: H-811 + edge coupler fixture A"
                        } else {
                            "Demo profile is not valid for physical hardware"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.requiresOperatorConfirmation) {
                ToggleRow(
                    title = "Verified for current device and fixture",
                    caption = "I confirmed axis directions, travel limits and clearance Z against the installed setup",
                    checked = state.confirmedForCurrentFixture,
                    enabled = editable,
                    onCheckedChange = {
                        onAction(MotionSafetyAction.SetFixtureConfirmed(it))
                    }
                )
            }
        }
    }
}

@Composable
private fun SoftLimitCard(
    config: MotionSafetyConfigUiState,
    editable: Boolean,
    onConfigChange: (MotionSafetyConfigUiState) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "Six-axis Soft Limits",
                caption = "Targets outside these inclusive ranges are rejected before reaching the driver"
            )

            ToggleRow(
                title = "Enable software limits",
                caption = "Controller-side limits and mechanical interlocks are still required",
                checked = config.enabled,
                enabled = editable,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) }
            )

            HorizontalDivider()

            AxisLimitRow("X", "um", config.xLimitUm, editable) {
                onConfigChange(config.copy(xLimitUm = it))
            }
            AxisLimitRow("Y", "um", config.yLimitUm, editable) {
                onConfigChange(config.copy(yLimitUm = it))
            }
            AxisLimitRow("Z", "um", config.zLimitUm, editable) {
                onConfigChange(config.copy(zLimitUm = it))
            }
            AxisLimitRow("U", "deg", config.uLimitDeg, editable) {
                onConfigChange(config.copy(uLimitDeg = it))
            }
            AxisLimitRow("V", "deg", config.vLimitDeg, editable) {
                onConfigChange(config.copy(vLimitDeg = it))
            }
            AxisLimitRow("W", "deg", config.wLimitDeg, editable) {
                onConfigChange(config.copy(wLimitDeg = it))
            }
        }
    }
}

@Composable
private fun AxisLimitRow(
    axis: String,
    unit: String,
    value: AxisSoftLimitUiState,
    editable: Boolean,
    onChange: (AxisSoftLimitUiState) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = axis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.16f)
        )
        SafetyNumberField(
            label = "Minimum $unit",
            value = value.minimum,
            enabled = editable,
            onValueChange = { onChange(value.copy(minimum = it)) },
            modifier = Modifier.weight(1f)
        )
        SafetyNumberField(
            label = "Maximum $unit",
            value = value.maximum,
            enabled = editable,
            onValueChange = { onChange(value.copy(maximum = it)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProtectedTransferCard(
    config: MotionSafetyConfigUiState,
    editable: Boolean,
    onConfigChange: (MotionSafetyConfigUiState) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "Protected Transfer",
                caption = "Large moves use retract → transfer → approach waypoints"
            )

            ToggleRow(
                title = "Enable protected transfer",
                caption = "Small coupling scan steps remain direct",
                checked = config.protectedTransferEnabled,
                enabled = editable,
                onCheckedChange = {
                    onConfigChange(config.copy(protectedTransferEnabled = it))
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SafetyNumberField(
                    label = "Clearance Z um",
                    value = config.clearanceZUm,
                    enabled = editable,
                    onValueChange = { onConfigChange(config.copy(clearanceZUm = it)) },
                    modifier = Modifier.weight(1f)
                )
                SafetyNumberField(
                    label = "Linear threshold um",
                    value = config.protectedLinearThresholdUm,
                    enabled = editable,
                    onValueChange = {
                        onConfigChange(config.copy(protectedLinearThresholdUm = it))
                    },
                    modifier = Modifier.weight(1f)
                )
                SafetyNumberField(
                    label = "Angle threshold deg",
                    value = config.protectedAngleThresholdDeg,
                    enabled = editable,
                    onValueChange = {
                        onConfigChange(config.copy(protectedAngleThresholdDeg = it))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Clearance Z must lie inside the configured Z range. The sign of safe Z depends on the actual installation and is never inferred automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(
    state: MotionSafetyUiState,
    editable: Boolean,
    onAction: (MotionSafetyAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isDirty) "Draft has unapplied changes" else "Draft matches applied profile",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Applying a profile atomically replaces the safety planner configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = { onAction(MotionSafetyAction.LoadDemoTemplate) },
                enabled = editable
            ) {
                Text("Load Template")
            }

            OutlinedButton(
                onClick = { onAction(MotionSafetyAction.ResetDraftToApplied) },
                enabled = editable && state.isDirty
            ) {
                Text("Reset Draft")
            }

            OutlinedButton(
                onClick = { onAction(MotionSafetyAction.ClearAppliedProfile) },
                enabled = editable && state.runtimeMode == HardwareRuntimeMode.Real && state.applied != null
            ) {
                Text("Lock Motion")
            }

            Button(
                onClick = { onAction(MotionSafetyAction.ApplyProfile) },
                enabled = editable && state.isDirty
            ) {
                Text("Apply Profile")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SafetyNumberField(
    label: String,
    value: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = formatNumber(value),
        onValueChange = { text -> text.toDoubleOrNull()?.let(onValueChange) },
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        modifier = modifier
    )
}

private fun formatNumber(value: Double): String {
    val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
    return rounded.toString()
}
