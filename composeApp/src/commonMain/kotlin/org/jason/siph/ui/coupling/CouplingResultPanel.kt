package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.ui.model.CouplingUiState
import org.jason.siph.ui.model.SiPhToolsAction

@Composable
fun CouplingResultPanel(
    state: CouplingUiState,
    onAction: (SiPhToolsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Coupling Result",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "State: ${state.state.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Text(
                    text = "Current Power: ${formatPower(state.currentPowerDbm)}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Best Power: ${formatPower(state.bestPowerDbm)}",
                    style = MaterialTheme.typography.bodyMedium
                )

                state.bestPose?.let {
                    Text(
                        text = "Best Pose:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        text = formatPose(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onAction(SiPhToolsAction.SaveBestPose) },
                        enabled = state.bestPose != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Best Pose")
                    }

                    OutlinedButton(
                        onClick = { onAction(SiPhToolsAction.ClearCouplingData) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        CouplingPlotPanel(
            samples = state.samples,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        CouplingLogPanel(
            logs = state.logs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
        )
    }
}

private fun formatPower(value: Double?): String {
    return value?.let {
        "${round3(it)} dBm"
    } ?: "-- dBm"
}

private fun formatPose(pose: OpticalPose): String {
    return "X=${round3(pose.xUm)} μm, " +
            "Y=${round3(pose.yUm)} μm, " +
            "Z=${round3(pose.zUm)} μm, " +
            "U=${round3(pose.uDeg)}°, " +
            "V=${round3(pose.vDeg)}°, " +
            "W=${round3(pose.wDeg)}°"
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}