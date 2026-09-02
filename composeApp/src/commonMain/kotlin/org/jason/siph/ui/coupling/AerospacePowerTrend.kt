package org.jason.siph.ui.coupling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.themes.elementLine
import org.jetbrains.letsPlot.themes.elementRect
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import org.jason.siph.ui.model.CouplingSampleUi

@Composable
internal fun AerospacePowerTrend(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val figure = remember(samples) { buildAerospacePowerFigure(samples) }

    Box(
        modifier = modifier.background(
            color = AerospacePlotBackground,
            shape = MaterialTheme.shapes.small
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(AerospacePlotBackground)
            val minor = 34.dp.toPx()
            var index = 0
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = if (index % 4 == 0) AerospacePlotGridMajor else AerospacePlotGridMinor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (index % 4 == 0) 0.75.dp.toPx() else 0.45.dp.toPx()
                )
                x += minor
                index += 1
            }
            index = 0
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = if (index % 4 == 0) AerospacePlotGridMajor else AerospacePlotGridMinor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (index % 4 == 0) 0.75.dp.toPx() else 0.45.dp.toPx()
                )
                y += minor
                index += 1
            }
        }

        PlotPanel(
            figure = figure,
            preserveAspectRatio = false,
            modifier = Modifier.fillMaxSize(),
            computationMessagesHandler = {}
        )
    }
}

private fun buildAerospacePowerFigure(samples: List<CouplingSampleUi>): Figure {
    val data = mapOf(
        "sample" to samples.map { it.index },
        "power" to samples.map { it.powerDbm },
        "stage" to samples.map { it.stage.text }
    )

    return letsPlot(data) {
        x = "sample"
        y = "power"
    } +
        geomLine(
            color = "#75BCFF",
            size = 1.55
        ) +
        geomPoint(
            color = "#05070A",
            fill = "#F5F7FA",
            shape = 21,
            size = 3.0,
            stroke = 0.75
        ) +
        labs(
            x = "Sample index",
            y = "Optical power (dBm)"
        ) +
        ggsize(900, 340) +
        theme(
            panelBackground = elementRect(
                fill = "#080D14",
                color = "#26313E",
                size = 0.7
            ),
            plotBackground = elementRect(
                fill = "#080D14",
                color = "transparent"
            ),
            panelGridMajor = elementLine(
                color = "#26313E",
                size = 0.48
            ),
            panelGridMinor = elementLine(
                color = "#18222E",
                size = 0.28
            ),
            axisTitle = elementText(
                color = "#98A6B7",
                size = 11
            ),
            axisText = elementText(
                color = "#98A6B7",
                size = 10
            ),
            legendTitle = elementText(
                color = "#F5F7FA",
                size = 10
            ),
            legendText = elementText(
                color = "#98A6B7",
                size = 9
            ),
            plotMargin = listOf(6, 10, 6, 6)
        )
}
