package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
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
import org.jason.siph.domain.positioner.OpticalCoordinateFrame
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import kotlin.math.abs

@Composable
fun PivotSetupPanel(
    state: CouplingToolUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.coupling.config
    val pivot = config.virtualPivotPoint

    Column(
        modifier = modifier
            .widthIn(max = 1120.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Virtual Pivot Point",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Use a virtual pivot when U/V/W angle moves should rotate around the optical interaction point, not the mechanical center.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AssistChip(
                        onClick = {},
                        label = {
                            Text(if (pivot.enabled) "Enabled" else "Disabled")
                        }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Practical rule: capture the pivot near the fiber tip, grating center, or edge-coupler interaction point before angle optimization. This reduces lateral drift when changing incident angle.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                PivotNameField(
                    pivot = pivot,
                    onAction = onAction
                )

                PivotCoordinateFields(
                    pivot = pivot,
                    onAction = onAction
                )

                FrameSelector(
                    selected = pivot.frame,
                    enabled = pivot.enabled,
                    onFrameChange = {
                        onAction(
                            CouplingToolAction.UpdateVirtualPivot(
                                pivot.copy(frame = it)
                            )
                        )
                    }
                )

                PivotCompensationSwitch(
                    enabled = pivot.enabled,
                    checked = config.enableSoftwarePivotCompensation,
                    onCheckedChange = {
                        onAction(
                            CouplingToolAction.UpdateVirtualPivot(
                                pivot.copy(enabled = it)
                            )
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onAction(CouplingToolAction.CapturePivotFromCurrentPose) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Text("Use Current Pose")
                    }

                    OutlinedButton(
                        onClick = { onAction(CouplingToolAction.DisableVirtualPivot) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disable Pivot")
                    }
                }
            }
        }

        PoseReferenceCard(
            pose = state.positioner.currentPose
        )
    }
}

@Composable
private fun PivotNameField(
    pivot: VirtualPivotPoint,
    onAction: (CouplingToolAction) -> Unit
) {
    OutlinedTextField(
        value = pivot.name,
        onValueChange = {
            onAction(
                CouplingToolAction.UpdateVirtualPivot(
                    pivot.copy(name = it)
                )
            )
        },
        label = { Text("Pivot name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PivotCoordinateFields(
    pivot: VirtualPivotPoint,
    onAction: (CouplingToolAction) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NumberField(
                label = "Pivot X um",
                value = pivot.xUm,
                onValueChange = {
                    onAction(
                        CouplingToolAction.UpdateVirtualPivot(
                            pivot.copy(xUm = it)
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )

            NumberField(
                label = "Pivot Y um",
                value = pivot.yUm,
                onValueChange = {
                    onAction(
                        CouplingToolAction.UpdateVirtualPivot(
                            pivot.copy(yUm = it)
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )

            NumberField(
                label = "Pivot Z um",
                value = pivot.zUm,
                onValueChange = {
                    onAction(
                        CouplingToolAction.UpdateVirtualPivot(
                            pivot.copy(zUm = it)
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FrameSelector(
    selected: OpticalCoordinateFrame,
    enabled: Boolean,
    onFrameChange: (OpticalCoordinateFrame) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Coordinate Frame",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OpticalCoordinateFrame.entries.forEach { frame ->
                FilterChip(
                    selected = selected == frame,
                    onClick = { onFrameChange(frame) },
                    enabled = enabled,
                    label = { Text(frame.name) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                )
            }
        }
    }
}

@Composable
private fun PivotCompensationSwitch(
    enabled: Boolean,
    checked: Boolean,
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
                text = "Software pivot compensation",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = if (checked && enabled) {
                    "Angle optimization can request moves around the configured pivot."
                } else {
                    "Angle moves use the positioner's default mechanical rotation center."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PoseReferenceCard(
    pose: OpticalPose
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Current Positioner Pose",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Use Current Pose stores X/Y/Z as the pivot reference in the Positioner frame.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "X=${formatNumber(pose.xUm)} um, Y=${formatNumber(pose.yUm)} um, Z=${formatNumber(pose.zUm)} um",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}

private fun formatNumber(value: Double): String {
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    return (kotlin.math.round(normalized * 1000.0) / 1000.0).toString()
}
