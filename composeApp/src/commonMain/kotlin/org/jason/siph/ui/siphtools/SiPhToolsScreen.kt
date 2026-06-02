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
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.coupling.CouplingConfigPanel
import org.jason.siph.ui.coupling.CouplingResultPanel
import org.jason.siph.ui.coupling.CouplingWorkspace
import org.jason.siph.ui.model.SiPhPage
import org.jason.siph.ui.model.SiPhRunState
import org.jason.siph.ui.model.SiPhToolsAction
import org.jason.siph.ui.model.SiPhToolsUiState
import org.jason.siph.ui.positioner.PositionerControlPanel

@Composable
fun SiPhToolsScreen(
    state: SiPhToolsUiState,
    onAction: (SiPhToolsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SiPhTopBar(
            state = state,
            onAction = onAction
        )

        Divider()

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Surface(
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                SiPhNavigationPanel(
                    selectedPage = state.selectedPage,
                    onSelectPage = {
                        onAction(SiPhToolsAction.SelectPage(it))
                    },
                    modifier = Modifier
                        .width(224.dp)
                        .fillMaxHeight()
                )
            }

            Divider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
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
                    SiPhPage.Positioner -> {
                        PositionerControlPanel(
                            state = state.positioner,
                            onAction = onAction,
                            modifier = Modifier
                                .widthIn(max = 1280.dp)
                                .fillMaxWidth()
                        )
                    }

                    SiPhPage.Coupling -> {
                        CouplingWorkspace(
                            state = state,
                            onAction = onAction,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        PlaceholderPage(state.selectedPage.title)
                    }
                }
            }
        }

        Divider()

        SiPhStatusBar(
            state = state.status,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SiPhTopBar(
    state: SiPhToolsUiState,
    onAction: (SiPhToolsAction) -> Unit
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
                    text = "SiPhTools",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Silicon Photonics Test Client",
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
                onClick = { onAction(SiPhToolsAction.Start) },
                enabled = state.runState != SiPhRunState.Running
            ) {
                Text("Run")
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = { onAction(SiPhToolsAction.Stop) }
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun PlaceholderPage(
    title: String
) {
    Card(
        modifier = Modifier
            .widthIn(max = 960.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "该页面入口已预留，后续可以继续实现。",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

