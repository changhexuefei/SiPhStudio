package org.jason.siph.ui.coupling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jason.siph.ui.model.CouplingSampleUi

/** Public entry point retained for existing result and workspace screens. */
@Composable
fun CouplingPlotPanel(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    AerospaceCouplingPlotPanelV2(
        samples = samples,
        modifier = modifier
    )
}
