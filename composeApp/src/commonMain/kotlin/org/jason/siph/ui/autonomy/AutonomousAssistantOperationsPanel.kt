package org.jason.siph.ui.autonomy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.oo.OoMeasurementAction
import org.jason.siph.ui.oo.OoMeasurementPanel
import org.jason.siph.ui.oo.OoMeasurementUiState

/** 第一阶段自主耦光与第二阶段 Wafer O-O 编排的统一入口。 */
@Composable
fun AutonomousAssistantOperationsPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    workflowState: AutonomousWorkflowUiState,
    ooState: OoMeasurementUiState,
    onAction: (CouplingToolAction) -> Unit,
    onWorkflowAction: (AutonomousWorkflowAction) -> Unit,
    onOoAction: (OoMeasurementAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OoMeasurementPanel(
            state = ooState,
            onAction = onOoAction,
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AutonomousAssistantPanel(
                state = state,
                safetyState = safetyState,
                workflowState = workflowState,
                onAction = onAction,
                onWorkflowAction = onWorkflowAction,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
