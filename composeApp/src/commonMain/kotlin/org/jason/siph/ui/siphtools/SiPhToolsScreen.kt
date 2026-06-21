package org.jason.siph.ui.siphtools


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        CouplingToolTopBar(
            state = state,
            onAction = onAction
        )

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

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
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
                enabled = state.runState != CouplingToolRunState.Running,
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Text("Start Coupling")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = { onAction(CouplingToolAction.StopCoupling) },
                modifier = Modifier.heightIn(min = 40.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop")
            }
        }
    }
}
