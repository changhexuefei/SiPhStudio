package org.jason.siph.ui.coupling


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Coupling Config",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Text(
                text = "Spiral Plane",
                style = MaterialTheme.typography.labelLarge
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CouplingPlane.entries.forEach { plane ->
                    if (state.plane == plane) {
                        Button(
                            onClick = {
                                onConfigChange(state.copy(plane = plane))
                            },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(plane.text)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                onConfigChange(state.copy(plane = plane))
                            },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(plane.text)
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberField(
                    label = "First Light dBm",
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberField(
                    label = "Spiral Step μm",
                    value = state.spiralStepUm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(spiralStepUm = it))
                    },
                    modifier = Modifier.weight(1f)
                )

                NumberField(
                    label = "Max Radius μm",
                    value = state.maxRadiusUm,
                    enabled = enabled,
                    onValueChange = {
                        onConfigChange(state.copy(maxRadiusUm = it))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            LongField(
                label = "Settle Delay ms",
                value = state.settleDelayMs,
                enabled = enabled,
                onValueChange = {
                    onConfigChange(state.copy(settleDelayMs = it))
                }
            )

            Row {
                Checkbox(
                    checked = state.enableFineXyz,
                    enabled = enabled,
                    onCheckedChange = {
                        onConfigChange(state.copy(enableFineXyz = it))
                    }
                )

                Text(
                    text = "Enable Fine XYZ",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Row {
                Checkbox(
                    checked = state.enableAngleOptimization,
                    enabled = enabled,
                    onCheckedChange = {
                        onConfigChange(state.copy(enableAngleOptimization = it))
                    }
                )

                Text(
                    text = "Enable U/V/W Angle Optimization",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartCoupling,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Coupling")
                }

                OutlinedButton(
                    onClick = onStopCoupling,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }
        }
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