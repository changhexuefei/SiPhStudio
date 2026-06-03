package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingConfigUiState
import org.jason.siph.ui.model.CouplingPlane

@Composable
fun CouplingConfigPanel(
    state: CouplingConfigUiState,
    enabled: Boolean,
    onConfigChange: (CouplingConfigUiState) -> Unit,
    onStartCoupling: () -> Unit,
    onStopCoupling: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PanelHeader(
                title = "Coupling Config",
                caption = if (enabled) "Ready to run" else "Running"
            )

            SectionLabel("Optical Setup")

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NumberField(
                    label = "Wavelength nm",
                    value = state.wavelengthNm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(wavelengthNm = it))
                    },
                    modifier = Modifier.weight(1f)
                )

                IntField(
                    label = "PM Channel",
                    value = state.powerMeterChannel,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(powerMeterChannel = it))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            SectionLabel("Search Plane")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CouplingPlane.entries.forEach { plane ->
                    FilterChip(
                        selected = state.plane == plane,
                        onClick = {
                            onConfigChange(state.copy(plane = plane))
                        },
                        enabled = enabled,
                        label = {
                            Text(plane.text)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp)
                    )
                }
            }

            HorizontalDivider()

            SectionLabel("Power Targets")

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NumberField(
                    label = "First light dBm",
                    value = state.firstLightThresholdDbm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(firstLightThresholdDbm = it))
                    },
                    modifier = Modifier.weight(1f)
                )

                NumberField(
                    label = "Target dBm",
                    value = state.targetPowerDbm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(targetPowerDbm = it))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            SectionLabel("Search Window")

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NumberField(
                    label = "Spiral step um",
                    value = state.spiralStepUm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(spiralStepUm = it))
                    },
                    modifier = Modifier.weight(1f)
                )

                NumberField(
                    label = "Max radius um",
                    value = state.maxRadiusUm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(maxRadiusUm = it))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            LongField(
                label = "Settle delay ms",
                value = state.settleDelayMs,
                enabled = enabled,
                onValueChange = {
                    onConfigChange(state.copy(settleDelayMs = it))
                }
            )

            HorizontalDivider()

            ToggleRow(
                title = "Fine XYZ",
                caption = "Refine around first light",
                checked = state.enableFineXyz,
                enabled = enabled,
                onCheckedChange = {
                    onConfigChange(state.copy(enableFineXyz = it))
                }
            )

            ToggleRow(
                title = "U/V/W angle optimization",
                caption = angleOptimizationCaption(state),
                checked = state.enableAngleOptimization,
                enabled = enabled,
                onCheckedChange = {
                    onConfigChange(state.copy(enableAngleOptimization = it))
                }
            )

            PivotStatusRow(state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartCoupling,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                ) {
                    Text("Start Coupling")
                }

                OutlinedButton(
                    onClick = onStopCoupling,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun PivotStatusRow(
    state: CouplingConfigUiState
) {
    val pivot = state.virtualPivotPoint
    val pivotEnabled = pivot.enabled && state.enableSoftwarePivotCompensation

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Virtual pivot",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = if (pivotEnabled) {
                    "${pivot.name}: X=${round3(pivot.xUm)} um, Y=${round3(pivot.yUm)} um, Z=${round3(pivot.zUm)} um (${pivot.frame.name})"
                } else {
                    "Disabled. Angle moves use the default mechanical rotation center."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PanelHeader(
    title: String,
    caption: String
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionLabel(
    text: String
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
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
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
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
private fun NumberField(
    label: String,
    value: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun LongField(
    label: String,
    value: Long,
    enabled: Boolean,
    onValueChange: (Long) -> Unit
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toLongOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun angleOptimizationCaption(
    state: CouplingConfigUiState
): String {
    return if (state.virtualPivotPoint.enabled && state.enableSoftwarePivotCompensation) {
        "Run around configured virtual pivot"
    } else {
        "Run after XYZ refinement"
    }
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
