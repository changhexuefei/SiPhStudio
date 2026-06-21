package org.jason.siph.ui.positioner


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.PositionerUiState
import org.jason.siph.ui.model.CouplingToolAction

@Composable
fun PositionerControlPanel(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PositionerHeaderCard(
            state = state,
            onAction = onAction
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(0.85f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PoseDisplayCard(
                    title = "Current Pose",
                    pose = state.currentPose
                )

                PositionerSafetyCard(
                    state = state,
                    onAction = onAction
                )
            }

            JogControlPanel(
                linearStepUm = state.linearStepUm,
                angleStepDeg = state.angleStepDeg,
                enabled = state.connected && !state.isMoving,
                onLinearStepChange = {
                    onAction(CouplingToolAction.UpdateLinearStep(it))
                },
                onAngleStepChange = {
                    onAction(CouplingToolAction.UpdateAngleStep(it))
                },
                onJog = {
                    onAction(CouplingToolAction.JogPositioner(it))
                },
                modifier = Modifier.weight(1.15f)
            )
        }
    }
}

@Composable
private fun PositionerHeaderCard(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Optical Positioner",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (state.connected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            state.idn?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onAction(CouplingToolAction.ConnectPositioner) },
                    enabled = !state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect")
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.DisconnectPositioner) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.ReadPose) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Read Pose")
                }
            }
        }
    }
}

@Composable
private fun PositionerSafetyCard(
    state: PositionerUiState,
    onAction: (CouplingToolAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Motion Safety",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Move the optical head to safe pose before prober movement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onAction(CouplingToolAction.MoveSafe) },
                    enabled = state.connected && !state.isMoving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Move Safe")
                }

                OutlinedButton(
                    onClick = { onAction(CouplingToolAction.StopPositioner) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }
        }
    }
}
