package org.jason.siph.ui.autonomy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.inspection.InspectionCalibrationAction
import org.jason.siph.ui.inspection.InspectionCalibrationPanel
import org.jason.siph.ui.inspection.InspectionCalibrationUiState
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolUiState
import org.jason.siph.ui.model.MotionSafetyUiState
import org.jason.siph.ui.oo.OoMeasurementAction
import org.jason.siph.ui.oo.OoMeasurementPanel
import org.jason.siph.ui.oo.OoMeasurementUiState
import org.jason.siph.ui.production.ProductionClusterAction
import org.jason.siph.ui.production.ProductionClusterPanel
import org.jason.siph.ui.production.ProductionClusterUiState
import org.jason.siph.ui.production.ProductionControlAction
import org.jason.siph.ui.production.ProductionControlPanel
import org.jason.siph.ui.production.ProductionControlUiState

/**
 * Phase 4 → Phase 1 的统一自主生产工作区。
 *
 * 所有生产、基础设施、视觉、O-O 和自主助手面板共享同一个外层滚动容器，
 * 避免多个自然高度面板在有限窗口内把后续内容裁掉。底部自主助手保留自己的
 * 有界视口，用于显示其几何和引导工作区。
 */
@Composable
fun AutonomousAssistantOperationsPanel(
    state: CouplingToolUiState,
    safetyState: MotionSafetyUiState,
    workflowState: AutonomousWorkflowUiState,
    productionState: ProductionControlUiState,
    productionClusterState: ProductionClusterUiState,
    inspectionState: InspectionCalibrationUiState,
    ooState: OoMeasurementUiState,
    onAction: (CouplingToolAction) -> Unit,
    onWorkflowAction: (AutonomousWorkflowAction) -> Unit,
    onProductionAction: (ProductionControlAction) -> Unit,
    onProductionClusterAction: (ProductionClusterAction) -> Unit,
    onInspectionAction: (InspectionCalibrationAction) -> Unit,
    onOoAction: (OoMeasurementAction) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        val assistantViewportHeight = maxHeight.coerceAtLeast(880.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProductionControlPanel(
                state = productionState,
                onAction = onProductionAction,
                modifier = Modifier.fillMaxWidth()
            )

            ProductionClusterPanel(
                state = productionClusterState,
                onAction = onProductionClusterAction,
                modifier = Modifier.fillMaxWidth()
            )

            InspectionCalibrationPanel(
                state = inspectionState,
                onAction = onInspectionAction,
                modifier = Modifier.fillMaxWidth()
            )

            OoMeasurementPanel(
                state = ooState,
                onAction = onOoAction,
                modifier = Modifier.fillMaxWidth()
            )

            AutonomousAssistantPanel(
                state = state,
                safetyState = safetyState,
                workflowState = workflowState,
                onAction = onAction,
                onWorkflowAction = onWorkflowAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(assistantViewportHeight)
            )
        }
    }
}
