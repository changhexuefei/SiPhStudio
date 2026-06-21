package org.jason.siph.ui.coupling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import kotlin.math.absoluteValue

@Composable
fun CouplingPlotPanel(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
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

            Text(
                text = "Power vs Step",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            PowerVsStepChart(
                samples = samples,
                modifier = Modifier
                    .height(140.dp)
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
                    .height(160.dp)
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PowerVsStepChart(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val pointHaloColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor, MaterialTheme.shapes.small)
    ) {
        val padding = 12.dp.toPx()
        val width = size.width - padding * 2f
        val height = size.height - padding * 2f

        repeat(4) { index ->
            val y = padding + height * (index + 1) / 5f
            drawLine(
                color = gridColor,
                start = Offset(padding, y),
                end = Offset(padding + width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        drawRect(
            color = axisColor,
            topLeft = Offset(padding, padding),
            size = Size(width, height),
            style = Stroke(width = 1.dp.toPx())
        )

        if (samples.isEmpty()) {
            return@Canvas
        }

        val minPower = samples.minOf { it.powerDbm }
        val maxPower = samples.maxOf { it.powerDbm }
        val powerSpan = (maxPower - minPower).takeIf { it.absoluteValue > 1e-9 } ?: 1.0

        val points = samples.mapIndexed { index, sample ->
            val x = if (samples.size <= 1) {
                padding
            } else {
                padding + width * index / (samples.size - 1)
            }

            val yNorm = ((sample.powerDbm - minPower) / powerSpan).toFloat()
            val y = padding + height * (1f - yNorm)

            Offset(x, y)
        }

        points.zipWithNext().forEach { (a, b) ->
            drawLine(
                color = lineColor,
                start = a,
                end = b,
                strokeWidth = 2.dp.toPx()
            )
        }

        points.forEach {
            drawCircle(
                color = pointHaloColor,
                radius = 5.dp.toPx(),
                center = it
            )
            drawCircle(
                color = lineColor,
                radius = 2.8.dp.toPx(),
                center = it
            )
        }
    }
}

@Composable
private fun XyHeatmap(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor, MaterialTheme.shapes.small)
    ) {
        val padding = 12.dp.toPx()
        val width = size.width - padding * 2f
        val height = size.height - padding * 2f

        drawRect(
            color = borderColor,
            topLeft = Offset(padding, padding),
            size = Size(width, height),
            style = Stroke(width = 1.dp.toPx())
        )

        if (samples.isEmpty()) {
            return@Canvas
        }

        val xMin = samples.minOf { it.pose.xUm }
        val xMax = samples.maxOf { it.pose.xUm }
        val yMin = samples.minOf { it.pose.yUm }
        val yMax = samples.maxOf { it.pose.yUm }

        val pMin = samples.minOf { it.powerDbm }
        val pMax = samples.maxOf { it.powerDbm }
        val pSpan = (pMax - pMin).takeIf { it.absoluteValue > 1e-9 } ?: 1.0
        val pointSize = 8.dp.toPx()

        samples.forEach { sample ->
            val xNorm = normalize(sample.pose.xUm, xMin, xMax)
            val yNorm = normalize(sample.pose.yUm, yMin, yMax)
            val pNorm = ((sample.powerDbm - pMin) / pSpan).toFloat().coerceIn(0f, 1f)

            val x = padding + width * xNorm
            val y = padding + height * (1f - yNorm)

            drawRect(
                color = heatColor(pNorm),
                topLeft = Offset(
                    x = x - pointSize / 2f,
                    y = y - pointSize / 2f
                ),
                size = Size(pointSize, pointSize)
            )
        }
    }
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

private fun heatColor(
    value: Float
): Color {
    val t = value.coerceIn(0f, 1f)
    val cold = Color(0xFF2563EB)
    val mid = Color(0xFF0F766E)
    val hot = Color(0xFFF59E0B)

    return if (t < 0.5f) {
        lerpColor(cold, mid, t * 2f)
    } else {
        lerpColor(mid, hot, (t - 0.5f) * 2f)
    }.copy(alpha = 0.9f)
}

private fun lerpColor(
    start: Color,
    end: Color,
    t: Float
): Color {
    val clamped = t.coerceIn(0f, 1f)

    return Color(
        red = start.red + (end.red - start.red) * clamped,
        green = start.green + (end.green - start.green) * clamped,
        blue = start.blue + (end.blue - start.blue) * clamped,
        alpha = start.alpha + (end.alpha - start.alpha) * clamped
    )
}
