package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.theme.AerospacePalette
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.sqrt

private val HeatMapBackground = Color(0xFF080D14)
private val HeatMapGridMajor = Color(0xFF26313E)
private val HeatMapGridMinor = Color(0xFF18222E)

private val HeatMapColorStops = listOf(
    0.00f to Color(0xFF07111F),
    0.18f to Color(0xFF0B2A4A),
    0.42f to Color(0xFF0E63B6),
    0.66f to Color(0xFF16A6C9),
    0.84f to Color(0xFF39D98A),
    1.00f to Color(0xFFF5F7FA)
)

/**
 * Mission-control XY optical-power field.
 *
 * The component owns the spatial rendering so it can consistently show the
 * scan path, current sample, peak sample, engineering grid and contour bands.
 */
@Composable
fun AerospaceHeatMap(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val field = remember(samples) { buildAerospaceHeatField(samples) }

    if (field == null) {
        AerospaceHeatMapEmptyState(modifier)
        return
    }

    Column(
        modifier = modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(HeatMapBackground, MaterialTheme.shapes.small)
        ) {
            drawAerospaceHeatField(field)
        }

        AerospaceHeatMapLegend(field)
    }
}

@Composable
private fun AerospaceHeatMapEmptyState(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
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
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "XY",
                        color = AerospacePalette.AccentBright,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "NO SPATIAL FIELD",
                style = MaterialTheme.typography.labelLarge,
                color = AerospacePalette.TextPrimary,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "At least two distinct XY positions are required.",
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted
            )
        }
    }
}

@Composable
private fun AerospaceHeatMapLegend(field: AerospaceHeatField) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${roundHeatValue(field.minPower)} dBm",
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
                    brush = Brush.horizontalGradient(HeatMapColorStops.map { it.second }),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
                drawRoundRect(
                    color = AerospacePalette.BorderStrong,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            Text(
                text = "${roundHeatValue(field.maxPower)} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextPrimary,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeatLegendItem(
                label = "CURRENT",
                tone = AerospacePalette.Warning,
                value = "${roundHeatValue(field.current.powerDbm)} dBm"
            )
            HeatLegendItem(
                label = "PEAK",
                tone = AerospacePalette.Success,
                value = "${roundHeatValue(field.peak.powerDbm)} dBm"
            )
            Text(
                text = "X ${roundHeatValue(field.peak.xUm)} µm  /  Y ${roundHeatValue(field.peak.yUm)} µm",
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeatLegendItem(
    label: String,
    tone: Color,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier.size(7.dp),
            shape = CircleShape,
            color = tone
        ) {}
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelSmall,
            color = AerospacePalette.TextSecondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

private data class AerospaceHeatField(
    val points: List<AerospaceHeatPoint>,
    val scanPath: List<AerospaceHeatPoint>,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val minPower: Double,
    val maxPower: Double,
    val current: AerospaceHeatPoint,
    val peak: AerospaceHeatPoint
)

private data class AerospaceHeatPoint(
    val xUm: Double,
    val yUm: Double,
    val powerDbm: Double
)

private fun buildAerospaceHeatField(samples: List<CouplingSampleUi>): AerospaceHeatField? {
    if (samples.isEmpty()) return null

    val aggregated = samples
        .groupBy { it.pose.xUm to it.pose.yUm }
        .map { (position, grouped) ->
            AerospaceHeatPoint(
                xUm = position.first,
                yUm = position.second,
                powerDbm = grouped.map { it.powerDbm }.average()
            )
        }

    if (aggregated.size < 2) return null

    val xMin = aggregated.minOf { it.xUm }
    val xMax = aggregated.maxOf { it.xUm }
    val yMin = aggregated.minOf { it.yUm }
    val yMax = aggregated.maxOf { it.yUm }

    if (
        (xMax - xMin).absoluteValue < 1e-9 &&
        (yMax - yMin).absoluteValue < 1e-9
    ) {
        return null
    }

    val path = samples.map {
        AerospaceHeatPoint(
            xUm = it.pose.xUm,
            yUm = it.pose.yUm,
            powerDbm = it.powerDbm
        )
    }

    return AerospaceHeatField(
        points = aggregated,
        scanPath = path,
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax,
        minPower = aggregated.minOf { it.powerDbm },
        maxPower = aggregated.maxOf { it.powerDbm },
        current = path.last(),
        peak = aggregated.maxBy { it.powerDbm }
    )
}

private fun DrawScope.drawAerospaceHeatField(field: AerospaceHeatField) {
    val left = 22.dp.toPx()
    val top = 16.dp.toPx()
    val right = size.width - 22.dp.toPx()
    val bottom = size.height - 16.dp.toPx()
    val plotWidth = (right - left).coerceAtLeast(1f)
    val plotHeight = (bottom - top).coerceAtLeast(1f)

    drawHeatGrid(
        left = left,
        top = top,
        width = plotWidth,
        height = plotHeight
    )

    val toOffset: (AerospaceHeatPoint) -> Offset = { point ->
        Offset(
            x = left + normalizeHeat(point.xUm, field.xMin, field.xMax) * plotWidth,
            y = bottom - normalizeHeat(point.yUm, field.yMin, field.yMax) * plotHeight
        )
    }

    drawScanPath(field.scanPath.map(toOffset))

    val countScale = max(1.0, sqrt(field.points.size.toDouble()))
    val pointRadius = (minOf(plotWidth, plotHeight) / (countScale * 2.7))
        .toFloat()
        .coerceIn(4.5.dp.toPx(), 16.dp.toPx())

    field.points
        .sortedBy { it.powerDbm }
        .forEach { point ->
            val ratio = normalizeHeat(point.powerDbm, field.minPower, field.maxPower)
            val center = toOffset(point)

            drawCircle(
                color = heatColor(ratio).copy(alpha = 0.18f),
                radius = pointRadius * 1.9f,
                center = center
            )
            drawCircle(
                color = heatColor(ratio),
                radius = pointRadius,
                center = center
            )
            drawCircle(
                color = AerospacePalette.Void.copy(alpha = 0.52f),
                radius = pointRadius,
                center = center,
                style = Stroke(width = 0.7.dp.toPx())
            )
        }

    val peakCenter = toOffset(field.peak)
    drawPeakContours(
        center = peakCenter,
        baseRadius = pointRadius,
        plotSize = minOf(plotWidth, plotHeight)
    )
    drawPeakMarker(peakCenter, pointRadius)

    val currentCenter = toOffset(field.current)
    drawCurrentMarker(currentCenter, pointRadius)

    drawRect(
        color = AerospacePalette.BorderStrong,
        topLeft = Offset(left, top),
        size = Size(plotWidth, plotHeight),
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.drawHeatGrid(
    left: Float,
    top: Float,
    width: Float,
    height: Float
) {
    drawRect(
        color = HeatMapBackground,
        topLeft = Offset(left, top),
        size = Size(width, height)
    )

    val subdivisions = 12
    for (index in 0..subdivisions) {
        val major = index % 3 == 0
        val x = left + width * index / subdivisions
        val y = top + height * index / subdivisions
        val color = if (major) HeatMapGridMajor else HeatMapGridMinor
        val stroke = if (major) 0.8.dp.toPx() else 0.45.dp.toPx()

        drawLine(color, Offset(x, top), Offset(x, top + height), strokeWidth = stroke)
        drawLine(color, Offset(left, y), Offset(left + width, y), strokeWidth = stroke)
    }
}

private fun DrawScope.drawScanPath(points: List<Offset>) {
    if (points.size < 2) return

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { point -> lineTo(point.x, point.y) }
    }

    drawPath(
        path = path,
        color = AerospacePalette.Accent.copy(alpha = 0.30f),
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.drawPeakContours(
    center: Offset,
    baseRadius: Float,
    plotSize: Float
) {
    val contourBase = max(baseRadius * 2.2f, plotSize * 0.075f)
    listOf(1f, 1.65f, 2.35f).forEachIndexed { index, scale ->
        drawCircle(
            color = AerospacePalette.Success.copy(alpha = 0.40f - index * 0.09f),
            radius = contourBase * scale,
            center = center,
            style = Stroke(width = (1.15f - index * 0.18f).dp.toPx())
        )
    }
}

private fun DrawScope.drawPeakMarker(center: Offset, pointRadius: Float) {
    drawCircle(
        color = AerospacePalette.Success.copy(alpha = 0.22f),
        radius = pointRadius * 2.4f,
        center = center
    )
    drawCircle(
        color = AerospacePalette.TextPrimary,
        radius = max(3.5.dp.toPx(), pointRadius * 0.34f),
        center = center
    )
    drawCircle(
        color = AerospacePalette.Success,
        radius = pointRadius * 1.38f,
        center = center,
        style = Stroke(width = 1.25.dp.toPx())
    )
}

private fun DrawScope.drawCurrentMarker(center: Offset, pointRadius: Float) {
    val radius = pointRadius * 1.15f
    drawCircle(
        color = AerospacePalette.Warning.copy(alpha = 0.18f),
        radius = radius * 1.7f,
        center = center
    )
    drawCircle(
        color = AerospacePalette.Warning,
        radius = radius,
        center = center,
        style = Stroke(width = 1.2.dp.toPx())
    )
    drawLine(
        color = AerospacePalette.Warning,
        start = Offset(center.x - radius * 1.55f, center.y),
        end = Offset(center.x + radius * 1.55f, center.y),
        strokeWidth = 0.8.dp.toPx()
    )
    drawLine(
        color = AerospacePalette.Warning,
        start = Offset(center.x, center.y - radius * 1.55f),
        end = Offset(center.x, center.y + radius * 1.55f),
        strokeWidth = 0.8.dp.toPx()
    )
}

private fun normalizeHeat(value: Double, min: Double, max: Double): Float {
    val span = max - min
    if (span.absoluteValue < 1e-9) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun heatColor(ratio: Float): Color {
    val clamped = ratio.coerceIn(0f, 1f)
    val rightIndex = HeatMapColorStops
        .indexOfFirst { clamped <= it.first }
        .takeIf { it > 0 }
        ?: HeatMapColorStops.lastIndex
    val left = HeatMapColorStops[rightIndex - 1]
    val right = HeatMapColorStops[rightIndex]
    val local = ((clamped - left.first) / (right.first - left.first)).coerceIn(0f, 1f)

    return Color(
        red = left.second.red + (right.second.red - left.second.red) * local,
        green = left.second.green + (right.second.green - left.second.green) * local,
        blue = left.second.blue + (right.second.blue - left.second.blue) * local,
        alpha = 1f
    )
}

private fun roundHeatValue(value: Double): Double =
    kotlin.math.round(value * 100.0) / 100.0
