package org.jason.siph.ui.coupling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.compose.PlotPanel
import org.jetbrains.letsPlot.coord.coordCartesian
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomTile
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillGradientN
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
    var rendererBackend by remember { mutableStateOf(SurfaceRendererBackend.ComposeCanvas) }
    val plotSummary = remember(samples) {
        buildPlotSummary(samples)
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Signal Maps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = plotSummary.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
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
            }

            PlotMetricsStrip(
                summary = plotSummary,
                modifier = Modifier.padding(top = 10.dp)
            )

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
                        text = "Power Surface",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    PowerSurface3d(
                        samples = samples,
                        selectedBackend = rendererBackend,
                        onSelectBackend = {
                            rendererBackend = it
                        },
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
    Surface3d("3D")
}

private enum class SurfaceRendererBackend(
    val label: String,
    val caption: String,
    val enabled: Boolean
) {
    ComposeCanvas(
        label = "Canvas",
        caption = "Common fallback renderer",
        enabled = true
    ),
    JavaFx3d(
        label = "JavaFX 3D",
        caption = "Desktop renderer hook",
        enabled = true
    ),
    Lwjgl(
        label = "LWJGL",
        caption = "OpenGL desktop renderer",
        enabled = true
    )
}

private data class PlotSummary(
    val sampleCount: Int,
    val peakPowerDbm: Double?,
    val currentPowerDbm: Double?,
    val dynamicRangeDb: Double?
) {
    val caption: String
        get() = if (sampleCount == 0) {
            "Waiting for coupling samples"
        } else {
            "$sampleCount samples across the current alignment run"
        }
}

@Composable
private fun PlotMetricsStrip(
    summary: PlotSummary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlotMetric(
            label = "Samples",
            value = summary.sampleCount.toString(),
            modifier = Modifier.weight(1f)
        )

        PlotMetric(
            label = "Current",
            value = formatDbm(summary.currentPowerDbm),
            modifier = Modifier.weight(1f)
        )

        PlotMetric(
            label = "Peak",
            value = formatDbm(summary.peakPowerDbm),
            emphasized = summary.peakPowerDbm != null,
            modifier = Modifier.weight(1f)
        )

        PlotMetric(
            label = "Range",
            value = summary.dynamicRangeDb?.let { "${round2(it)} dB" } ?: "-- dB",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlotMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
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
            coordCartesian() +
            ggsize(820, 360) +
            heatmapPlotTheme()
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

private fun heatmapPlotTheme() = theme(
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

private data class HeatmapPoint(
    val xUm: Double,
    val yUm: Double,
    val powerDbm: Double
)

@Composable
private fun PowerSurface3d(
    samples: List<CouplingSampleUi>,
    selectedBackend: SurfaceRendererBackend,
    onSelectBackend: (SurfaceRendererBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    val mesh = remember(samples) {
        buildSurfaceMesh(samples)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RendererBackendStrip(
            selectedBackend = selectedBackend,
            onSelectBackend = onSelectBackend
        )

        when (selectedBackend) {
            SurfaceRendererBackend.ComposeCanvas -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SurfaceViewport(
                        title = "3-D surface",
                        mesh = mesh,
                        initialYaw = -0.62,
                        initialPitch = 0.58,
                        modifier = Modifier.weight(1f)
                    )

                    SurfaceViewport(
                        title = "Rotated view",
                        mesh = mesh,
                        initialYaw = 0.58,
                        initialPitch = 0.66,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SurfaceRendererBackend.JavaFx3d -> {
                JavaFxPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            SurfaceRendererBackend.Lwjgl -> {
                LwjglPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RendererBackendStrip(
    selectedBackend: SurfaceRendererBackend,
    onSelectBackend: (SurfaceRendererBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Renderer",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SurfaceRendererBackend.entries.forEach { backend ->
            FilterChip(
                selected = selectedBackend == backend,
                enabled = backend.enabled,
                onClick = {
                    onSelectBackend(backend)
                },
                label = {
                    Text(backend.label)
                }
            )
        }

        Text(
            text = selectedBackend.caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RendererUnavailablePanel(
    backend: SurfaceRendererBackend,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape = MaterialTheme.shapes.small
            ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = backend.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Renderer backend is reserved for the JVM desktop implementation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
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

private fun buildPlotSummary(
    samples: List<CouplingSampleUi>
): PlotSummary {
    val minPower = samples.minOfOrNull { it.powerDbm }
    val maxPower = samples.maxOfOrNull { it.powerDbm }

    return PlotSummary(
        sampleCount = samples.size,
        peakPowerDbm = maxPower,
        currentPowerDbm = samples.lastOrNull()?.powerDbm,
        dynamicRangeDb = if (minPower != null && maxPower != null) {
            maxPower - minPower
        } else {
            null
        }
    )
}

private fun formatDbm(
    value: Double?
): String {
    return value?.let { "${round2(it)} dBm" } ?: "-- dBm"
}

private fun round2(
    value: Double
): Double {
    return kotlin.math.round(value * 100.0) / 100.0
}

@Composable
private fun SurfaceViewport(
    title: String,
    mesh: SurfaceMesh?,
    initialYaw: Double,
    initialPitch: Double,
    modifier: Modifier = Modifier
) {
    var yaw by remember(title) { mutableStateOf(initialYaw) }
    var pitch by remember(title) { mutableStateOf(initialPitch) }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.small,
        color = Color(0xFFF8FAFC),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 190.dp)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (mesh == null) {
                    drawSurfacePlaceholder()
                } else {
                    drawEngineeringSurfacePlot(
                        mesh = mesh,
                        yaw = yaw,
                        pitch = pitch
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(title) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            yaw += dragAmount.x * 0.008
                            pitch = (pitch - dragAmount.y * 0.008).coerceIn(0.24, 1.18)
                        }
                    }
            )

            Surface(
                modifier = Modifier.align(Alignment.TopStart),
                shape = MaterialTheme.shapes.extraSmall,
                color = Color.White.copy(alpha = 0.86f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("View", "Fit", "Peak", "Reset").forEach { label ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                when (label) {
                                    "View", "Fit", "Reset" -> {
                                        yaw = initialYaw
                                        pitch = initialPitch
                                    }
                                }
                            },
                        shape = MaterialTheme.shapes.extraSmall,
                        color = Color(0xFFE7ECF3),
                        contentColor = Color(0xFF334155)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun buildSurfaceMesh(
    samples: List<CouplingSampleUi>,
    resolution: Int = 26
): SurfaceMesh? {
    if (samples.size < 3) {
        return null
    }

    val xMin = samples.minOf { it.pose.xUm }
    val xMax = samples.maxOf { it.pose.xUm }
    val yMin = samples.minOf { it.pose.yUm }
    val yMax = samples.maxOf { it.pose.yUm }
    val pMin = samples.minOf { it.powerDbm }
    val pMax = samples.maxOf { it.powerDbm }

    if (
        (xMax - xMin).absoluteValue < 1e-9 ||
        (yMax - yMin).absoluteValue < 1e-9 ||
        (pMax - pMin).absoluteValue < 1e-9
    ) {
        return null
    }

    val points = List(resolution) { yIndex ->
        List(resolution) { xIndex ->
            val x = xMin + (xMax - xMin) * xIndex / (resolution - 1)
            val y = yMin + (yMax - yMin) * yIndex / (resolution - 1)
            val power = interpolatePower(samples, x, y)
            SurfacePoint(
                x = normalize(x, xMin, xMax).toDouble() * 2.0 - 1.0,
                y = normalize(y, yMin, yMax).toDouble() * 2.0 - 1.0,
                z = normalize(power, pMin, pMax).toDouble(),
                power = power
            )
        }
    }

    return SurfaceMesh(
        points = points,
        minPower = pMin,
        maxPower = pMax
    )
}

private fun interpolatePower(
    samples: List<CouplingSampleUi>,
    x: Double,
    y: Double
): Double {
    var weightedPower = 0.0
    var totalWeight = 0.0

    samples.forEach { sample ->
        val dx = sample.pose.xUm - x
        val dy = sample.pose.yUm - y
        val distanceSquared = dx * dx + dy * dy

        if (distanceSquared < 1e-9) {
            return sample.powerDbm
        }

        val weight = 1.0 / distanceSquared
        weightedPower += sample.powerDbm * weight
        totalWeight += weight
    }

    return weightedPower / totalWeight
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEngineeringSurfacePlot(
    mesh: SurfaceMesh,
    yaw: Double,
    pitch: Double
) {
    val plotWidth = size.width - 86f
    val plotHeight = size.height
    val projected = mesh.points.map { row ->
        row.map { point ->
            projectSurfacePoint(point, yaw, pitch, plotWidth, plotHeight)
        }
    }
    val cells = mutableListOf<SurfaceCell>()

    for (yIndex in 0 until projected.lastIndex) {
        for (xIndex in 0 until projected[yIndex].lastIndex) {
            val vertices = listOf(
                projected[yIndex][xIndex],
                projected[yIndex][xIndex + 1],
                projected[yIndex + 1][xIndex + 1],
                projected[yIndex + 1][xIndex]
            )
            val power = listOf(
                mesh.points[yIndex][xIndex].power,
                mesh.points[yIndex][xIndex + 1].power,
                mesh.points[yIndex + 1][xIndex + 1].power,
                mesh.points[yIndex + 1][xIndex].power
            ).average()

            cells += SurfaceCell(
                vertices = vertices,
                depth = vertices.map { it.depth }.average(),
                powerRatio = normalize(power, mesh.minPower, mesh.maxPower)
            )
        }
    }

    drawPlotBox(projected)
    drawFloorProjection(projected)

    cells.sortedBy { it.depth }.forEach { cell ->
        val path = Path().apply {
            moveTo(cell.vertices[0].offset.x, cell.vertices[0].offset.y)
            cell.vertices.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
            close()
        }

        drawPath(
            path = path,
            color = surfaceColor(cell.powerRatio),
        )
        drawPath(
            path = path,
            color = Color(0xFF334155).copy(alpha = 0.34f),
            style = Stroke(width = 0.62f)
        )
    }

    drawPlotBox(projected)
    drawColorBar(
        minPower = mesh.minPower,
        maxPower = mesh.maxPower,
        x = size.width - 54f,
        y = 22f,
        height = size.height - 72f
    )
    drawPeakMarker(projected)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlotBox(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val stroke = Stroke(width = 1.2f)
    val frameColor = Color(0xFF94A3B8)
    val back = projected.first()
    val front = projected.last()
    val left = projected.map { it.first() }
    val right = projected.map { it.last() }
    val topOffset = Offset(0f, -projected.flatten().maxOf { it.heightPixels })

    listOf(back, front, left, right).forEach { row ->
        drawLine(frameColor, row.first().offset, row.last().offset, strokeWidth = stroke.width)
        drawLine(frameColor.copy(alpha = 0.52f), row.first().offset + topOffset, row.last().offset + topOffset, strokeWidth = 0.8f)
        drawLine(frameColor.copy(alpha = 0.42f), row.first().offset, row.first().offset + topOffset, strokeWidth = 0.8f)
        drawLine(frameColor.copy(alpha = 0.42f), row.last().offset, row.last().offset + topOffset, strokeWidth = 0.8f)
    }

    projected.first().indices.forEach { index ->
        if (index == 0 || index == projected.first().lastIndex || index % 5 == 0) {
            drawLine(frameColor.copy(alpha = 0.28f), back[index].offset, front[index].offset, strokeWidth = 0.7f)
            drawLine(frameColor.copy(alpha = 0.20f), back[index].offset + topOffset, front[index].offset + topOffset, strokeWidth = 0.6f)
        }
    }

    projected.indices.forEach { index ->
        if (index == 0 || index == projected.lastIndex || index % 5 == 0) {
            drawLine(
                frameColor.copy(alpha = 0.28f),
                projected[index].first().offset,
                projected[index].last().offset,
                strokeWidth = 0.7f
            )
            drawLine(
                frameColor.copy(alpha = 0.20f),
                projected[index].first().offset + topOffset,
                projected[index].last().offset + topOffset,
                strokeWidth = 0.6f
            )
        }
    }

}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloorProjection(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val floorRows = projected.map { row ->
        row.map { it.copy(offset = it.floorOffset) }
    }

    floorRows.forEachIndexed { index, row ->
        if (index % 2 == 0 || index == floorRows.lastIndex) {
            drawPolyline(
                points = row.map { it.offset },
                color = Color(0xFFDC2626).copy(alpha = 0.72f),
                strokeWidth = 1.0f
            )
        }
    }

    floorRows.first().indices.forEach { index ->
        if (index % 2 == 0 || index == floorRows.first().lastIndex) {
            drawPolyline(
                points = floorRows.map { it[index].offset },
                color = Color(0xFF2563EB).copy(alpha = 0.52f),
                strokeWidth = 0.9f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    points.zipWithNext().forEach { (start, end) ->
        drawLine(color, start, end, strokeWidth = strokeWidth)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawColorBar(
    minPower: Double,
    maxPower: Double,
    x: Float,
    y: Float,
    height: Float
) {
    val width = 16f
    val steps = height.toInt().coerceAtLeast(1)

    for (i in 0..steps) {
        val ratio = 1f - i / steps.toFloat()
        drawLine(
            color = surfaceColor(ratio),
            start = Offset(x, y + i),
            end = Offset(x + width, y + i),
            strokeWidth = 1f
        )
    }

    drawRect(
        color = Color(0xFF64748B),
        topLeft = Offset(x, y),
        size = androidx.compose.ui.geometry.Size(width, height),
        style = Stroke(width = 1f)
    )

    listOf(1f, 0.75f, 0.5f, 0.25f, 0f).forEach { ratio ->
        val tickY = y + (1f - ratio) * height
        drawLine(Color(0xFF64748B), Offset(x + width, tickY), Offset(x + width + 5f, tickY), strokeWidth = 1f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPeakMarker(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val peak = projected.flatten().maxByOrNull { it.powerRatio } ?: return
    drawCircle(
        color = Color(0xFFE11D48).copy(alpha = 0.22f),
        radius = 10f,
        center = peak.offset
    )
    drawCircle(
        color = Color(0xFFE11D48),
        radius = 3.8f,
        center = peak.offset
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSurfacePlaceholder() {
    val frame = Color(0xFFCBD5E1)
    val center = Offset(size.width * 0.5f, size.height * 0.48f)
    val radiusX = size.width * 0.28f
    val radiusY = size.height * 0.16f

    drawLine(frame, Offset(size.width * 0.18f, size.height * 0.72f), Offset(size.width * 0.82f, size.height * 0.72f))
    drawLine(frame, Offset(size.width * 0.18f, size.height * 0.72f), Offset(size.width * 0.5f, size.height * 0.20f))
    drawLine(frame, Offset(size.width * 0.82f, size.height * 0.72f), Offset(size.width * 0.5f, size.height * 0.20f))
    drawOval(
        color = Color(0xFFE7ECF3),
        topLeft = Offset(center.x - radiusX, center.y - radiusY),
        size = androidx.compose.ui.geometry.Size(radiusX * 2f, radiusY * 2f),
        style = Stroke(width = 1.2f)
    )
}

private fun projectSurfacePoint(
    point: SurfacePoint,
    yaw: Double,
    pitch: Double,
    width: Float,
    height: Float
): ProjectedSurfacePoint {
    val z = point.z * 1.28
    val yawCos = cos(yaw)
    val yawSin = sin(yaw)
    val pitchCos = cos(pitch)
    val pitchSin = sin(pitch)
    val rotatedX = point.x * yawCos - point.y * yawSin
    val rotatedY = point.x * yawSin + point.y * yawCos
    val projectedY = rotatedY * pitchCos - z * pitchSin
    val depth = rotatedY * pitchSin + z * pitchCos
    val scale = minOf(width * 0.34, height * 0.46)
    val floorY = height * 0.82f + (rotatedY * pitchCos * scale).toFloat()
    val heightPixels = (z * pitchSin * scale).toFloat()

    return ProjectedSurfacePoint(
        offset = Offset(
            x = width * 0.50f + (rotatedX * scale).toFloat(),
            y = height * 0.82f + (projectedY * scale).toFloat()
        ),
        floorOffset = Offset(
            x = width * 0.50f + (rotatedX * scale).toFloat(),
            y = floorY
        ),
        depth = depth,
        heightPixels = heightPixels,
        powerRatio = point.z.toFloat()
    )
}

private fun surfaceColor(powerRatio: Float): Color {
    val stops = listOf(
        0.00f to Color(0xFF2563EB),
        0.28f to Color(0xFF2DD4BF),
        0.52f to Color(0xFF22C55E),
        0.74f to Color(0xFFFACC15),
        1.00f to Color(0xFFEF4444)
    )
    val rightIndex = stops.indexOfFirst { powerRatio <= it.first }.takeIf { it > 0 } ?: stops.lastIndex
    val left = stops[rightIndex - 1]
    val right = stops[rightIndex]
    val local = ((powerRatio - left.first) / (right.first - left.first)).coerceIn(0f, 1f)

    return lerpColor(left.second, right.second, local)
}

private fun ProjectedSurfacePoint.copy(
    offset: Offset
): ProjectedSurfacePoint {
    return ProjectedSurfacePoint(
        offset = offset,
        floorOffset = floorOffset,
        depth = depth,
        heightPixels = heightPixels,
        powerRatio = powerRatio
    )
}

private fun lerpColor(
    start: Color,
    end: Color,
    fraction: Float
): Color {
    val t = fraction.coerceIn(0f, 1f)

    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t
    )
}

internal data class SurfaceMesh(
    val points: List<List<SurfacePoint>>,
    val minPower: Double,
    val maxPower: Double
)

internal data class SurfacePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val power: Double
)

private data class ProjectedSurfacePoint(
    val offset: Offset,
    val floorOffset: Offset,
    val depth: Double,
    val heightPixels: Float,
    val powerRatio: Float
)

private data class SurfaceCell(
    val vertices: List<ProjectedSurfacePoint>,
    val depth: Double,
    val powerRatio: Float
)

@Composable
internal expect fun JavaFxPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier = Modifier
)

@Composable
internal expect fun LwjglPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier = Modifier
)
