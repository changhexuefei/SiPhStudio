package org.jason.siph.ui.siphtools


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.coupling.CouplingWorkspace
import org.jason.siph.ui.coupling.PivotSetupPanel
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolPage
import org.jason.siph.ui.model.CouplingToolRunState
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.positioner.PositionerControlPanel

@Composable
fun CouplingToolScreen(
    state: CouplingToolUiState,
    onAction: (CouplingToolAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        CouplingToolTopBar(
            state = state,
            onAction = onAction
        )

        HorizontalDivider()

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Surface(
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                CouplingToolNavigationPanel(
                    selectedPage = state.selectedPage,
                    onSelectPage = {
                        onAction(CouplingToolAction.SelectPage(it))
                    },
                    modifier = Modifier
                        .width(224.dp)
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
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(20.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                when (state.selectedPage) {
                    CouplingToolPage.Coupling -> {
                        CouplingWorkspace(
                            state = state,
                            onAction = onAction,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    CouplingToolPage.PivotSetup -> {
                        PivotSetupPanel(
                            state = state,
                            onAction = onAction,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    CouplingToolPage.ManualControl -> {
                        PositionerControlPanel(
                            state = state.positioner,
                            onAction = onAction,
                            modifier = Modifier
                                .widthIn(max = 1280.dp)
                                .fillMaxWidth()
                        )
                    }
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
    onAction: (CouplingToolAction) -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SiPh Coupling Tool",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Optical Coupling Alignment Tool",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = state.runState.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )

            Button(
                onClick = { onAction(CouplingToolAction.StartCoupling) },
                enabled = state.runState != CouplingToolRunState.Running
            ) {
                Text("Start Coupling")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.StopCoupling) }
            ) {
                Text("Stop")
            }
        }
    }
}
