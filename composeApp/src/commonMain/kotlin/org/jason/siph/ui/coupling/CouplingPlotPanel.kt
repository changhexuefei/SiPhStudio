package org.jason.siph.ui.coupling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.coord.coordCartesian
import org.jetbrains.letsPlot.coord.coordFixed
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomPolygon
import org.jetbrains.letsPlot.geom.geomTile
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillGradientN
import org.jetbrains.letsPlot.themes.elementBlank
import org.jetbrains.letsPlot.themes.elementLine
import org.jetbrains.letsPlot.themes.elementRect
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import org.jason.siph.ui.model.CouplingSampleUi
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CouplingPlotPanel(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CouplingPlotViewMode.Planar) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "Plot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CouplingPlotViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = viewMode == mode,
                        onClick = {
                            viewMode = mode
                        },
                        label = {
                            Text(mode.label)
                        }
                    )
                }
            }

            when (viewMode) {
                CouplingPlotViewMode.Planar -> {
                    Text(
                        text = "Power vs Step",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    PowerVsStepChart(
                        samples = samples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 4.dp)
                    )

                    Text(
                        text = "XY Heatmap",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    XyHeatmap(
                        samples = samples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.1f)
                            .padding(top = 4.dp)
                    )
                }

                CouplingPlotViewMode.Surface3d -> {
                    Text(
                        text = "2.5D Power Projection",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    PowerSurface3d(
                        samples = samples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private enum class CouplingPlotViewMode(
    val label: String
) {
    Planar("2D"),
    Surface3d("2.5D")
}

@Composable
private fun PowerVsStepChart(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    LetsPlotChart(
        figure = remember(samples) {
            buildPowerVsStepFigure(samples)
        },
        modifier = modifier
    )
}

@Composable
private fun LetsPlotChart(
    figure: Figure,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                    shape = MaterialTheme.shapes.small
                )
        ) {
            // Keeps an immediate, stable background while PlotPanel initializes its renderer.
        }

        PlotPanel(
            figure = figure,
            preserveAspectRatio = false,
            modifier = Modifier.fillMaxSize(),
            computationMessagesHandler = {}
        )
    }
}

private fun buildPowerVsStepFigure(
    samples: List<CouplingSampleUi>
): Figure {
    val data = mapOf(
        "step" to samples.map { it.index },
        "power" to samples.map { it.powerDbm },
        "stage" to samples.map { it.stage.text }
    )

    return letsPlot(data) {
        x = "step"
        y = "power"
    } +
            geomLine(
                color = "#0F766E",
                size = 1.35
            ) +
            geomPoint(
                color = "#063F39",
                fill = "#CFF7EF",
                shape = 21,
                size = 3.2,
                stroke = 0.8
            ) +
            labs(
                x = "Step",
                y = "Power (dBm)"
            ) +
            ggsize(760, 320) +
            compactPlotTheme()
}

private fun buildXyHeatmapFigure(
    samples: List<CouplingSampleUi>
): Figure {
    val averagedSamples = samples
        .groupBy { it.pose.xUm to it.pose.yUm }
        .map { (position, groupedSamples) ->
            HeatmapPoint(
                xUm = position.first,
                yUm = position.second,
                powerDbm = groupedSamples.map { it.powerDbm }.average()
            )
        }

    val data = mapOf(
        "x" to averagedSamples.map { it.xUm },
        "y" to averagedSamples.map { it.yUm },
        "power" to averagedSamples.map { it.powerDbm }
    )

    return letsPlot(data) {
        x = "x"
        y = "y"
    } +
            geomTile(
                color = "#FFFFFF",
                size = 0.18,
                mapping = {
                    fill = "power"
                }
            ) +
            geomPoint(
                color = "#18202F",
                size = 1.2,
                alpha = 0.62
            ) +
            scaleFillGradientN(
                colors = listOf("#2563EB", "#0F766E", "#F59E0B"),
                name = "Power (dBm)"
            ) +
            labs(
                x = "X (um)",
                y = "Y (um)"
            ) +
            coordFixed() +
            ggsize(760, 360) +
            compactPlotTheme()
}

private fun buildPowerProjectionFigure(
    samples: List<CouplingSampleUi>
): Figure {
    val xValues = samples.map { it.pose.xUm }.distinct().sorted()
    val yValues = samples.map { it.pose.yUm }.distinct().sorted()
    val pMin = samples.minOfOrNull { it.powerDbm } ?: 0.0
    val pMax = samples.maxOfOrNull { it.powerDbm } ?: 1.0
    val pSpan = (pMax - pMin).takeIf { it.absoluteValue > 1e-9 } ?: 1.0

    val cells = samples
        .groupBy { it.pose.xUm to it.pose.yUm }
        .mapValues { (_, groupedSamples) ->
            groupedSamples.map { it.powerDbm }.average()
        }

    fun project(
        xValue: Double,
        yValue: Double,
        powerDbm: Double
    ): ProjectedPoint {
        if (xValues.isEmpty() || yValues.isEmpty()) {
            return ProjectedPoint(
                screenX = 0.0,
                screenY = 0.0,
                depth = 0.0,
                powerDbm = powerDbm
            )
        }

        val xNorm = normalize(xValue, xValues.first(), xValues.last()).toDouble() * 2.0 - 1.0
        val yNorm = normalize(yValue, yValues.first(), yValues.last()).toDouble() * 2.0 - 1.0
        val zNorm = ((powerDbm - pMin) / pSpan).coerceIn(0.0, 1.0)
        val z = zNorm * 1.2

        val yaw = 0.72
        val pitch = 0.82
        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val pitchCos = cos(pitch)
        val pitchSin = sin(pitch)

        val rotatedX = xNorm * yawCos - yNorm * yawSin
        val rotatedY = xNorm * yawSin + yNorm * yawCos
        val projectedY = rotatedY * pitchCos - z * pitchSin
        val depth = rotatedY * pitchSin + z * pitchCos

        return ProjectedPoint(
            screenX = rotatedX * 0.9,
            screenY = projectedY * 1.75,
            depth = depth,
            powerDbm = powerDbm
        )
    }

    val projectedCells = mutableListOf<ProjectedCell>()

    for (xIndex in 0 until xValues.lastIndex) {
        for (yIndex in 0 until yValues.lastIndex) {
            val bottomLeftPower = cells[xValues[xIndex] to yValues[yIndex]]
            val bottomRightPower = cells[xValues[xIndex + 1] to yValues[yIndex]]
            val topRightPower = cells[xValues[xIndex + 1] to yValues[yIndex + 1]]
            val topLeftPower = cells[xValues[xIndex] to yValues[yIndex + 1]]

            if (
                bottomLeftPower != null &&
                bottomRightPower != null &&
                topRightPower != null &&
                topLeftPower != null
            ) {
                val vertices = listOf(
                    project(xValues[xIndex], yValues[yIndex], bottomLeftPower),
                    project(xValues[xIndex + 1], yValues[yIndex], bottomRightPower),
                    project(xValues[xIndex + 1], yValues[yIndex + 1], topRightPower),
                    project(xValues[xIndex], yValues[yIndex + 1], topLeftPower)
                )

                projectedCells += ProjectedCell(
                    vertices = vertices,
                    depth = vertices.map { it.depth }.average(),
                    powerDbm = vertices.map { it.powerDbm }.average()
                )
            }
        }
    }

    val sortedCells = projectedCells.sortedBy { it.depth }
    val polygonRows = sortedCells.flatMapIndexed { index, cell ->
        cell.vertices.map { vertex ->
            ProjectionPolygonRow(
                screenX = vertex.screenX,
                screenY = vertex.screenY,
                powerDbm = cell.powerDbm,
                cell = "cell_$index"
            )
        }
    }
    val projectedSamples = samples.map {
        project(
            xValue = it.pose.xUm,
            yValue = it.pose.yUm,
            powerDbm = it.powerDbm
        )
    }

    val polygonData = mapOf(
        "screenX" to polygonRows.map { it.screenX },
        "screenY" to polygonRows.map { it.screenY },
        "power" to polygonRows.map { it.powerDbm },
        "cell" to polygonRows.map { it.cell }
    )
    val sampleData = mapOf(
        "screenX" to projectedSamples.map { it.screenX },
        "screenY" to projectedSamples.map { it.screenY }
    )

    return letsPlot(polygonData) {
        x = "screenX"
        y = "screenY"
        fill = "power"
        group = "cell"
    } +
            geomPolygon(
                color = "#FFFFFF",
                size = 0.24,
                alpha = 0.9
            ) +
            geomPoint(
                data = sampleData,
                inheritAes = false,
                color = "#18202F",
                fill = "#FFFFFF",
                shape = 21,
                size = 1.7,
                stroke = 0.5,
                alpha = 0.72,
                mapping = {
                    x = "screenX"
                    y = "screenY"
                }
            ) +
            scaleFillGradientN(
                colors = listOf("#2563EB", "#0F766E", "#F59E0B"),
                name = "Power (dBm)"
            ) +
            labs(
                x = "",
                y = ""
            ) +
            coordCartesian() +
            ggsize(820, 520) +
            projectionPlotTheme()
}

private fun compactPlotTheme() = theme(
    panelBackground = elementRect(fill = "#EEF3F8", color = "#C7D0DC", size = 0.6),
    plotBackground = elementRect(fill = "transparent", color = "transparent"),
    panelGridMajor = elementLine(color = "#D7DEE8", size = 0.45),
    panelGridMinor = elementLine(color = "#E5EAF1", size = 0.3),
    axisTitle = elementText(color = "#526070", size = 11),
    axisText = elementText(color = "#526070", size = 10),
    legendTitle = elementText(color = "#526070", size = 10),
    legendText = elementText(color = "#526070", size = 9),
    plotMargin = listOf(4, 8, 4, 4)
)

private fun projectionPlotTheme() = theme(
    panelBackground = elementRect(fill = "#EEF3F8", color = "#C7D0DC", size = 0.6),
    plotBackground = elementRect(fill = "transparent", color = "transparent"),
    panelGrid = elementBlank(),
    axisTitle = elementBlank(),
    axisText = elementBlank(),
    axisTicks = elementBlank(),
    axisLine = elementBlank(),
    legendTitle = elementText(color = "#526070", size = 10),
    legendText = elementText(color = "#526070", size = 9),
    plotMargin = listOf(6, 8, 6, 6)
)

private data class HeatmapPoint(
    val xUm: Double,
    val yUm: Double,
    val powerDbm: Double
)

private data class ProjectedPoint(
    val screenX: Double,
    val screenY: Double,
    val depth: Double,
    val powerDbm: Double
)

private data class ProjectedCell(
    val vertices: List<ProjectedPoint>,
    val depth: Double,
    val powerDbm: Double
)

private data class ProjectionPolygonRow(
    val screenX: Double,
    val screenY: Double,
    val powerDbm: Double,
    val cell: String
)

@Composable
private fun PowerSurface3d(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    LetsPlotChart(
        figure = remember(samples) {
            buildPowerProjectionFigure(samples)
        },
        modifier = modifier
    )
}

@Composable
private fun XyHeatmap(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    LetsPlotChart(
        figure = remember(samples) {
            buildXyHeatmapFigure(samples)
        },
        modifier = modifier
    )
}

private fun normalize(
    value: Double,
    min: Double,
    max: Double
): Float {
    val span = max - min

    if (span.absoluteValue < 1e-9) {
        return 0.5f
    }

    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}
