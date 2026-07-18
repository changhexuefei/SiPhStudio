package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.positioner.CompactPositionerPanel

@Composable
fun CouplingWorkspace(
    state: CouplingToolUiState,
    onAction: (CouplingToolAction) -> Unit,
    motionEnabled: Boolean,
    connectLabel: String,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useSingleColumn = maxWidth < 1180.dp

        if (useSingleColumn) {
            CouplingWorkspaceSingleColumn(
                state = state,
                onAction = onAction,
                motionEnabled = motionEnabled,
                connectLabel = connectLabel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CouplingWorkspaceTwoColumns(
                state = state,
                onAction = onAction,
                motionEnabled = motionEnabled,
                connectLabel = connectLabel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun CouplingWorkspaceTwoColumns(
    state: CouplingToolUiState,
    onAction: (CouplingToolAction) -> Unit,
    motionEnabled: Boolean,
    connectLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(max = 1680.dp)
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        val leftScrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .weight(0.86f)
                .fillMaxHeight()
                .verticalScroll(leftScrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CompactPositionerPanel(
                state = state.positioner,
                onAction = onAction,
                motionEnabled = motionEnabled,
                connectLabel = connectLabel,
                modifier = Modifier.fillMaxWidth()
            )

            CouplingConfigPanel(
                state = state.coupling.config,
                previousRunStartPose = state.coupling.previousRunStartPose,
                safePose = state.positioner.safePose,
                enabled = !state.coupling.isRunning,
                canStart = motionEnabled && state.canStartCoupling,
                onConfigChange = {
                    onAction(CouplingToolAction.UpdateCouplingConfig(it))
                },
                onStartCoupling = {
                    onAction(CouplingToolAction.StartCoupling)
                },
                onStopCoupling = {
                    onAction(CouplingToolAction.StopCoupling)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        CouplingResultPanel(
            state = state.coupling,
            onAction = onAction,
            scrollable = true,
            modifier = Modifier
                .weight(1.14f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun CouplingWorkspaceSingleColumn(
    state: CouplingToolUiState,
    onAction: (CouplingToolAction) -> Unit,
    motionEnabled: Boolean,
    connectLabel: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .widthIn(max = 1040.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CompactPositionerPanel(
            state = state.positioner,
            onAction = onAction,
            motionEnabled = motionEnabled,
            connectLabel = connectLabel,
            modifier = Modifier.fillMaxWidth()
        )

        CouplingConfigPanel(
            state = state.coupling.config,
            previousRunStartPose = state.coupling.previousRunStartPose,
            safePose = state.positioner.safePose,
            enabled = !state.coupling.isRunning,
            canStart = motionEnabled && state.canStartCoupling,
            onConfigChange = {
                onAction(CouplingToolAction.UpdateCouplingConfig(it))
            },
            onStartCoupling = {
                onAction(CouplingToolAction.StartCoupling)
            },
            onStopCoupling = {
                onAction(CouplingToolAction.StopCoupling)
            },
            modifier = Modifier.fillMaxWidth()
        )

        CouplingResultPanel(
            state = state.coupling,
            onAction = onAction,
            scrollable = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 1140.dp)
        )
    }
}
