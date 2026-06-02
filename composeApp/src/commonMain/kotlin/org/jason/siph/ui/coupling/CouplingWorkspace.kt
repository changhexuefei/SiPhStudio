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
import org.jason.siph.ui.model.SiPhToolsAction
import org.jason.siph.ui.model.SiPhToolsUiState
import org.jason.siph.ui.positioner.CompactPositionerPanel

/**
 * Coupling 页面工作区。
 *
 * 这个页面不要直接复用完整的 PositionerControlPanel，
 * 否则左侧内容会过重、过高，Coupling Config 容易显示不全。
 *
 * 布局：
 * - 宽屏：左侧 Positioner + Config，右侧 Result + Plot + Log
 * - 窄屏：单列纵向显示
 */
@Composable
fun CouplingWorkspace(
    state: SiPhToolsUiState,
    onAction: (SiPhToolsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val useSingleColumn = maxWidth < 1100.dp

        if (useSingleColumn) {
            CouplingWorkspaceSingleColumn(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CouplingWorkspaceTwoColumns(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun CouplingWorkspaceTwoColumns(
    state: SiPhToolsUiState,
    onAction: (SiPhToolsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(max = 1480.dp)
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val leftScrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .verticalScroll(leftScrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CompactPositionerPanel(
                state = state.positioner,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth()
            )

            CouplingConfigPanel(
                state = state.coupling.config,
                enabled = !state.coupling.isRunning,
                onConfigChange = {
                    onAction(SiPhToolsAction.UpdateCouplingConfig(it))
                },
                onStartCoupling = {
                    onAction(SiPhToolsAction.StartCoupling)
                },
                onStopCoupling = {
                    onAction(SiPhToolsAction.StopCoupling)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        CouplingResultPanel(
            state = state.coupling,
            onAction = onAction,
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun CouplingWorkspaceSingleColumn(
    state: SiPhToolsUiState,
    onAction: (SiPhToolsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .widthIn(max = 960.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CompactPositionerPanel(
            state = state.positioner,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth()
        )

        CouplingConfigPanel(
            state = state.coupling.config,
            enabled = !state.coupling.isRunning,
            onConfigChange = {
                onAction(SiPhToolsAction.UpdateCouplingConfig(it))
            },
            onStartCoupling = {
                onAction(SiPhToolsAction.StartCoupling)
            },
            onStopCoupling = {
                onAction(SiPhToolsAction.StopCoupling)
            },
            modifier = Modifier.fillMaxWidth()
        )

        CouplingResultPanel(
            state = state.coupling,
            onAction = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 620.dp)
        )
    }
}