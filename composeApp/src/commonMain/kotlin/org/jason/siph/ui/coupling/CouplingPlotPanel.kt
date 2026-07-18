package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val PlotBackground = Color(0xFF080D14)
private val PlotPanel = Color(0xFF0B1119)
private val PlotGridMajor = Color(0xFF26313E)
private val PlotGridMinor = Color(0xFF18222E)
private val PlotText = Color(0xFFF5F7FA)

@Composable
fun CouplingPlotPanel(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CouplingPlotViewMode.Planar) }
    var rendererBackend by remember { mutableStateOf(SurfaceRendererBackend.ComposeCanvas) }
    val summary = remember(samples) { buildPlotSummary(samples) }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "SIGNAL ANALYTICS",
            title = "COUPLING FIELD",
            caption = summary.caption,
            trailing = {
                TelemetryPill(
                    label = "VIEW",
                    value = viewMode.label,
                    tone = AerospacePalette.Accent,
                    active = true
                )
            }
        )

        PlotModeSelector(
            selected = viewMode,
            onSelect = { viewMode = it }
        )

        PlotMetricsStrip(summary)

        when (viewMode) {
            CouplingPlotViewMode.Planar -> PlanarTelemetryView(
                samples = samples,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            CouplingPlotViewMode.Surface3d -> PowerSurface3d(
                samples = samples,
                selectedBackend = rendererBackend,
                onSelectBackend = { rendererBackend = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private enum class CouplingPlotViewMode(val label: String) {
    Planar("2D ANALYTICS"),
    Surface3d("3D SURFACE")
}

private enum class SurfaceRendererBackend(
    val label: String,
    val caption: String,
    val enabled: Boolean
) {
    ComposeCanvas(
        label = "CANVAS",
        caption = "Portable mission renderer",
        enabled = true
    ),
    JavaFx3d(
        label = "JAVAFX",
        caption = "Desktop accelerated surface",
        enabled = true
    ),
    Lwjgl(
        label = "LWJGL",
        caption = "OpenGL surface renderer",
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
            "Awaiting optical samples from the active alignment sequence"
        } else {
            "$sampleCount samples mapped across the current coupling field"
        }
}

@Composable
private fun PlotModeSelector(
    selected: CouplingPlotViewMode,
    onSelect: (CouplingPlotViewMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CouplingPlotViewMode.entries.forEach { mode ->
            MissionFilterChip(
                selected = selected == mode,
                label = mode.label,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MissionFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clickable(
                enabled = enabled,
                onClick = onClick
            ),
        shape = MaterialTheme.shapes.small,
        color = when {
            !enabled -> AerospacePalette.Panel
            selected -> AerospacePalette.AccentContainer.copy(alpha = 0.72f)
            else -> AerospacePalette.PanelRaised
        },
        contentColor = when {
            !enabled -> AerospacePalette.TextMuted
            selected -> AerospacePalette.AccentBright
            else -> AerospacePalette.TextSecondary
        },
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> AerospacePalette.Border.copy(alpha = 0.45f)
                selected -> AerospacePalette.Accent.copy(alpha = 0.72f)
                else -> AerospacePalette.Border
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PlotMetricsStrip(summary: PlotSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricTile(
            label = "SAMPLES",
            value = summary.sampleCount.toString(),
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "CURRENT",
            value = formatDbm(summary.currentPowerDbm),
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "PEAK",
            value = formatDbm(summary.peakPowerDbm),
            emphasized = summary.peakPowerDbm != null,
            accent = AerospacePalette.Success,
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "DYNAMIC RANGE",
            value = summary.dynamicRangeDb?.let { "${round2(it)} dB" } ?: "-- dB",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlanarTelemetryView(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlotFrame(
            eyebrow = "SEQUENCE TREND",
            title = "POWER VS SAMPLE",
            caption = "Optical power progression through the active search",
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f)
        ) {
            if (samples.isEmpty()) {
                EmptyPlotState(
                    title = "NO TREND DATA",
                    caption = "Start a coupling sequence to populate the power timeline."
                )
            } else {
                PowerVsStepChart(
                    samples = samples,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        PlotFrame(
            eyebrow = "SPATIAL FIELD",
            title = "XY POWER MAP",
            caption = "Interpolated sample footprint with the peak position marked",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f)
        ) {
            XyHeatmapCanvas(
                samples = samples,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PlotFrame(
    eyebrow: String,
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = PlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall,
                        color = AerospacePalette.Accent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = PlotText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(PlotBackground, MaterialTheme.shapes.small)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EmptyPlotState(
    title: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = MaterialTheme.shapes.small,
                color = AerospacePalette.AccentContainer.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, AerospacePalette.Accent.copy(alpha = 0.45f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "∿",
                        color = AerospacePalette.AccentBright,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = PlotText,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted
            )
        }
    }
}

@Composable
private fun PowerVsStepChart(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val figure = remember(samples) { buildPowerVsStepFigure(samples) }
    LetsPlotChart(figure = figure, modifier = modifier)
}

@Composable
private fun LetsPlotChart(
    figure: Figure,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(PlotBackground, MaterialTheme.shapes.small)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawPlotGrid()
        }
        PlotPanel(
            figure = figure,
            preserveAspectRatio = false,
            modifier = Modifier.fillMaxSize(),
            computationMessagesHandler = {}
        )
    }
}

private fun buildPowerVsStepFigure(samples: List<CouplingSampleUi>): Figure {
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
        aerospacePlotTheme()
}

private fun aerospacePlotTheme() = theme(
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

@Composable
private fun XyHeatmapCanvas(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val field = remember(samples) { buildHeatmapField(samples) }

    if (field == null) {
        EmptyPlotState(
            title = "NO SPATIAL FIELD",
            caption = "At least two distinct XY positions are required.",
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            drawHeatmapField(field)
        }
        PowerLegend(
            minPower = field.minPower,
            maxPower = field.maxPower
        )
    }
}

@Composable
private fun PowerLegend(
    minPower: Double,
    maxPower: Double
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "${round2(minPower)} dBm",
            style = MaterialTheme.typography.labelSmall,
            color = AerospacePalette.TextMuted,
            fontFamily = FontFamily.Monospace
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
        ) {
            drawRoundRect(
                brush = Brush.horizontalGradient(SurfaceColorStops.map { it.second }),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                color = AerospacePalette.BorderStrong,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Text(
            text = "${round2(maxPower)} dBm",
            style = MaterialTheme.typography.labelSmall,
            color = AerospacePalette.TextPrimary,
            fontFamily = FontFamily.Monospace
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = AerospacePalette.TextPrimary,
                border = BorderStroke(1.dp, AerospacePalette.AccentBright)
            ) {}
            Text(
                text = "PEAK",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextSecondary
            )
        }
    }
}

private data class HeatmapField(
    val points: List<HeatmapPoint>,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val minPower: Double,
    val maxPower: Double,
    val peak: HeatmapPoint
)

private data class HeatmapPoint(
    val xUm: Double,
    val yUm: Double,
    val powerDbm: Double
)

private fun buildHeatmapField(samples: List<CouplingSampleUi>): HeatmapField? {
    if (samples.isEmpty()) return null

    val points = samples
        .groupBy { it.pose.xUm to it.pose.yUm }
        .map { (position, grouped) ->
            HeatmapPoint(
                xUm = position.first,
                yUm = position.second,
                powerDbm = grouped.map { it.powerDbm }.average()
            )
        }

    if (points.size < 2) return null

    val xMin = points.minOf { it.xUm }
    val xMax = points.maxOf { it.xUm }
    val yMin = points.minOf { it.yUm }
    val yMax = points.maxOf { it.yUm }
    val pMin = points.minOf { it.powerDbm }
    val pMax = points.maxOf { it.powerDbm }

    if ((xMax - xMin).absoluteValue < 1e-9 && (yMax - yMin).absoluteValue < 1e-9) {
        return null
    }

    return HeatmapField(
        points = points,
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax,
        minPower = pMin,
        maxPower = pMax,
        peak = points.maxBy { it.powerDbm }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeatmapField(
    field: HeatmapField
) {
    drawPlotGrid()

    val left = 18.dp.toPx()
    val top = 12.dp.toPx()
    val right = size.width - 18.dp.toPx()
    val bottom = size.height - 12.dp.toPx()
    val plotWidth = (right - left).coerceAtLeast(1f)
    val plotHeight = (bottom - top).coerceAtLeast(1f)
    val countScale = max(1.0, kotlin.math.sqrt(field.points.size.toDouble()))
    val pointRadius = (minOf(plotWidth, plotHeight) / (countScale * 2.5))
        .toFloat()
        .coerceIn(5.dp.toPx(), 18.dp.toPx())

    field.points.forEach { point ->
        val xRatio = normalize(point.xUm, field.xMin, field.xMax)
        val yRatio = normalize(point.yUm, field.yMin, field.yMax)
        val powerRatio = normalize(point.powerDbm, field.minPower, field.maxPower)
        val center = Offset(
            x = left + xRatio * plotWidth,
            y = bottom - yRatio * plotHeight
        )

        drawCircle(
            color = surfaceColor(powerRatio).copy(alpha = 0.22f),
            radius = pointRadius * 1.65f,
            center = center
        )
        drawCircle(
            color = surfaceColor(powerRatio),
            radius = pointRadius,
            center = center
        )
        drawCircle(
            color = AerospacePalette.Void.copy(alpha = 0.45f),
            radius = pointRadius,
            center = center,
            style = Stroke(width = 0.7.dp.toPx())
        )
    }

    val peakX = left + normalize(field.peak.xUm, field.xMin, field.xMax) * plotWidth
    val peakY = bottom - normalize(field.peak.yUm, field.yMin, field.yMax) * plotHeight
    val peakCenter = Offset(peakX, peakY)

    drawCircle(
        color = AerospacePalette.TextPrimary.copy(alpha = 0.22f),
        radius = pointRadius * 2.3f,
        center = peakCenter
    )
    drawCircle(
        color = AerospacePalette.TextPrimary,
        radius = max(3.5.dp.toPx(), pointRadius * 0.32f),
        center = peakCenter
    )
    drawCircle(
        color = AerospacePalette.AccentBright,
        radius = pointRadius * 1.35f,
        center = peakCenter,
        style = Stroke(width = 1.2.dp.toPx())
    )

    drawRect(
        color = AerospacePalette.BorderStrong,
        topLeft = Offset(left, top),
        size = Size(plotWidth, plotHeight),
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlotGrid() {
    drawRect(color = PlotBackground)
    val minor = 34.dp.toPx()
    val majorEvery = 4

    var index = 0
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = if (index % majorEvery == 0) PlotGridMajor else PlotGridMinor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (index % majorEvery == 0) 0.75.dp.toPx() else 0.45.dp.toPx()
        )
        x += minor
        index += 1
    }

    index = 0
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = if (index % majorEvery == 0) PlotGridMajor else PlotGridMinor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (index % majorEvery == 0) 0.75.dp.toPx() else 0.45.dp.toPx()
        )
        y += minor
        index += 1
    }
}

@Composable
private fun PowerSurface3d(
    samples: List<CouplingSampleUi>,
    selectedBackend: SurfaceRendererBackend,
    onSelectBackend: (SurfaceRendererBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    val mesh = remember(samples) { buildSurfaceMesh(samples) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SurfaceViewport(
                        title = "PRIMARY ORBIT",
                        mesh = mesh,
                        initialYaw = -0.62,
                        initialPitch = 0.58,
                        modifier = Modifier.weight(1f)
                    )
                    SurfaceViewport(
                        title = "CROSS AXIS",
                        mesh = mesh,
                        initialYaw = 0.58,
                        initialPitch = 0.66,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SurfaceRendererBackend.JavaFx3d -> PlatformSurfaceFrame(
                title = "JAVAFX SURFACE",
                caption = selectedBackend.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                JavaFxPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier.fillMaxSize()
                )
            }

            SurfaceRendererBackend.Lwjgl -> PlatformSurfaceFrame(
                title = "LWJGL SURFACE",
                caption = selectedBackend.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LwjglPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun RendererBackendStrip(
    selectedBackend: SurfaceRendererBackend,
    onSelectBackend: (SurfaceRendererBackend) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RENDER PATH",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                fontFamily = FontFamily.Monospace
            )
            SurfaceRendererBackend.entries.forEach { backend ->
                MissionFilterChip(
                    selected = selectedBackend == backend,
                    enabled = backend.enabled,
                    label = backend.label,
                    onClick = { onSelectBackend(backend) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = selectedBackend.caption.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PlatformSurfaceFrame(
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    PlotFrame(
        eyebrow = "ACCELERATED RENDERER",
        title = title,
        caption = caption,
        modifier = modifier,
        content = content
    )
}

@Composable
private fun RendererUnavailablePanel(
    backend: SurfaceRendererBackend,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = PlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        EmptyPlotState(
            title = "${backend.label} UNAVAILABLE",
            caption = "This renderer is reserved for the JVM desktop implementation."
        )
    }
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
        shape = MaterialTheme.shapes.medium,
        color = PlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 210.dp)
                .background(PlotBackground)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPlotGrid()
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

            TelemetryPill(
                label = "VIEWPORT",
                value = title,
                tone = AerospacePalette.Accent,
                active = mesh != null,
                modifier = Modifier.align(Alignment.TopStart)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("FIT", "PEAK", "RESET").forEach { label ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                yaw = initialYaw
                                pitch = initialPitch
                            },
                        shape = MaterialTheme.shapes.extraSmall,
                        color = AerospacePalette.PanelRaised.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, AerospacePalette.BorderStrong)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = AerospacePalette.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
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
    if (samples.size < 3) return null

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

        if (distanceSquared < 1e-9) return sample.powerDbm

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

    drawFloorProjection(projected)

    cells.sortedBy { it.depth }.forEach { cell ->
        val path = Path().apply {
            moveTo(cell.vertices[0].offset.x, cell.vertices[0].offset.y)
            cell.vertices.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
            close()
        }

        drawPath(
            path = path,
            color = surfaceColor(cell.powerRatio)
        )
        drawPath(
            path = path,
            color = AerospacePalette.Void.copy(alpha = 0.46f),
            style = Stroke(width = 0.62f)
        )
    }

    drawPlotBox(projected)
    drawColorBar(
        minPower = mesh.minPower,
        maxPower = mesh.maxPower,
        x = size.width - 54f,
        y = 30f,
        height = size.height - 92f
    )
    drawPeakMarker(projected)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlotBox(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val frameColor = AerospacePalette.BorderStrong
    val back = projected.first()
    val front = projected.last()
    val left = projected.map { it.first() }
    val right = projected.map { it.last() }
    val topOffset = Offset(0f, -projected.flatten().maxOf { it.heightPixels })

    listOf(back, front, left, right).forEach { row ->
        drawLine(frameColor, row.first().offset, row.last().offset, strokeWidth = 1.1f)
        drawLine(
            frameColor.copy(alpha = 0.55f),
            row.first().offset + topOffset,
            row.last().offset + topOffset,
            strokeWidth = 0.75f
        )
        drawLine(
            frameColor.copy(alpha = 0.42f),
            row.first().offset,
            row.first().offset + topOffset,
            strokeWidth = 0.75f
        )
        drawLine(
            frameColor.copy(alpha = 0.42f),
            row.last().offset,
            row.last().offset + topOffset,
            strokeWidth = 0.75f
        )
    }

    projected.first().indices.forEach { index ->
        if (index == 0 || index == projected.first().lastIndex || index % 5 == 0) {
            drawLine(
                PlotGridMajor,
                back[index].offset,
                front[index].offset,
                strokeWidth = 0.65f
            )
        }
    }

    projected.indices.forEach { index ->
        if (index == 0 || index == projected.lastIndex || index % 5 == 0) {
            drawLine(
                PlotGridMajor,
                projected[index].first().offset,
                projected[index].last().offset,
                strokeWidth = 0.65f
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
                color = AerospacePalette.Accent.copy(alpha = 0.42f),
                strokeWidth = 0.9f
            )
        }
    }

    floorRows.first().indices.forEach { index ->
        if (index % 2 == 0 || index == floorRows.first().lastIndex) {
            drawPolyline(
                points = floorRows.map { it[index].offset },
                color = AerospacePalette.BorderStrong.copy(alpha = 0.48f),
                strokeWidth = 0.8f
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
        color = AerospacePalette.BorderStrong,
        topLeft = Offset(x, y),
        size = Size(width, height),
        style = Stroke(width = 1f)
    )

    listOf(1f, 0.75f, 0.5f, 0.25f, 0f).forEach { ratio ->
        val tickY = y + (1f - ratio) * height
        drawLine(
            AerospacePalette.TextSecondary,
            Offset(x + width, tickY),
            Offset(x + width + 5f, tickY),
            strokeWidth = 1f
        )
    }

    @Suppress("UNUSED_VARIABLE")
    val range = maxPower - minPower
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPeakMarker(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val peak = projected.flatten().maxByOrNull { it.powerRatio } ?: return
    drawCircle(
        color = AerospacePalette.TextPrimary.copy(alpha = 0.18f),
        radius = 12f,
        center = peak.offset
    )
    drawCircle(
        color = AerospacePalette.AccentBright,
        radius = 7f,
        center = peak.offset,
        style = Stroke(width = 1.2f)
    )
    drawCircle(
        color = AerospacePalette.TextPrimary,
        radius = 3.8f,
        center = peak.offset
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSurfacePlaceholder() {
    val center = Offset(size.width * 0.5f, size.height * 0.48f)
    val radiusX = size.width * 0.28f
    val radiusY = size.height * 0.16f

    drawLine(
        PlotGridMajor,
        Offset(size.width * 0.18f, size.height * 0.72f),
        Offset(size.width * 0.82f, size.height * 0.72f),
        strokeWidth = 1f
    )
    drawLine(
        PlotGridMajor,
        Offset(size.width * 0.18f, size.height * 0.72f),
        Offset(size.width * 0.5f, size.height * 0.20f),
        strokeWidth = 1f
    )
    drawLine(
        PlotGridMajor,
        Offset(size.width * 0.82f, size.height * 0.72f),
        Offset(size.width * 0.5f, size.height * 0.20f),
        strokeWidth = 1f
    )
    drawOval(
        color = AerospacePalette.Accent.copy(alpha = 0.38f),
        topLeft = Offset(center.x - radiusX, center.y - radiusY),
        size = Size(radiusX * 2f, radiusY * 2f),
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

private val SurfaceColorStops = listOf(
    0.00f to Color(0xFF071425),
    0.22f to Color(0xFF0B3C68),
    0.46f to Color(0xFF007E99),
    0.68f to Color(0xFF3C9DFF),
    0.86f to Color(0xFFFFB547),
    1.00f to Color(0xFFF5F7FA)
)

private fun surfaceColor(powerRatio: Float): Color {
    val ratio = powerRatio.coerceIn(0f, 1f)
    val rightIndex = SurfaceColorStops
        .indexOfFirst { ratio <= it.first }
        .takeIf { it > 0 }
        ?: SurfaceColorStops.lastIndex
    val left = SurfaceColorStops[rightIndex - 1]
    val right = SurfaceColorStops[rightIndex]
    val local = ((ratio - left.first) / (right.first - left.first)).coerceIn(0f, 1f)
    return lerpColor(left.second, right.second, local)
}

private fun ProjectedSurfacePoint.copy(offset: Offset): ProjectedSurfacePoint =
    ProjectedSurfacePoint(
        offset = offset,
        floorOffset = floorOffset,
        depth = depth,
        heightPixels = heightPixels,
        powerRatio = powerRatio
    )

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

private fun normalize(
    value: Double,
    min: Double,
    max: Double
): Float {
    val span = max - min
    if (span.absoluteValue < 1e-9) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun buildPlotSummary(samples: List<CouplingSampleUi>): PlotSummary {
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

private fun formatDbm(value: Double?): String =
    value?.let { "${round2(it)} dBm" } ?: "-- dBm"

private fun round2(value: Double): Double =
    kotlin.math.round(value * 100.0) / 100.0

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
