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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

internal enum class AerospaceSurfaceBackend(
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

@Composable
internal fun AerospacePowerSurface3d(
    samples: List<CouplingSampleUi>,
    selectedBackend: AerospaceSurfaceBackend,
    onSelectBackend: (AerospaceSurfaceBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    val mesh = remember(samples) { buildSurfaceMesh(samples) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AerospaceSurfaceBackend.entries.forEach { backend ->
                AerospaceSegmentButton(
                    selected = selectedBackend == backend,
                    label = backend.label,
                    enabled = backend.enabled,
                    onClick = { onSelectBackend(backend) }
                )
            }
            Text(
                text = selectedBackend.caption,
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            TelemetryPill(
                label = "MESH",
                value = mesh?.points?.size?.let { "${it}×$it" } ?: "NO DATA",
                tone = if (mesh != null) AerospacePalette.Success else AerospacePalette.Warning,
                active = mesh != null
            )
        }

        when (selectedBackend) {
            AerospaceSurfaceBackend.ComposeCanvas -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AerospaceSurfaceViewport(
                    title = "PRIMARY ORBIT",
                    mesh = mesh,
                    initialYaw = -0.62,
                    initialPitch = 0.58,
                    modifier = Modifier.weight(1f)
                )
                AerospaceSurfaceViewport(
                    title = "CROSS AXIS",
                    mesh = mesh,
                    initialYaw = 0.58,
                    initialPitch = 0.66,
                    modifier = Modifier.weight(1f)
                )
            }

            AerospaceSurfaceBackend.JavaFx3d -> JavaFxPowerSurface3d(
                mesh = mesh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            AerospaceSurfaceBackend.Lwjgl -> LwjglPowerSurface3d(
                mesh = mesh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun AerospaceSurfaceViewport(
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
        color = AerospacePlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 210.dp)
                .background(AerospacePlotBackground)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSurfaceGrid()
                if (mesh == null) {
                    drawSurfacePlaceholder()
                } else {
                    drawEngineeringSurface(
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

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clickable {
                        yaw = initialYaw
                        pitch = initialPitch
                    },
                shape = MaterialTheme.shapes.extraSmall,
                color = AerospacePalette.PanelRaised.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, AerospacePalette.BorderStrong)
            ) {
                Text(
                    text = "RESET VIEW",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
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
                x = normalizeSurface(x, xMin, xMax).toDouble() * 2.0 - 1.0,
                y = normalizeSurface(y, yMin, yMax).toDouble() * 2.0 - 1.0,
                z = normalizeSurface(power, pMin, pMax).toDouble(),
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

private fun DrawScope.drawEngineeringSurface(
    mesh: SurfaceMesh,
    yaw: Double,
    pitch: Double
) {
    val plotWidth = size.width - 80f
    val projected = mesh.points.map { row ->
        row.map { point -> projectSurfacePoint(point, yaw, pitch, plotWidth, size.height) }
    }
    val cells = mutableListOf<SurfaceCell>()

    for (rowIndex in 0 until projected.lastIndex) {
        for (columnIndex in 0 until projected[rowIndex].lastIndex) {
            val vertices = listOf(
                projected[rowIndex][columnIndex],
                projected[rowIndex][columnIndex + 1],
                projected[rowIndex + 1][columnIndex + 1],
                projected[rowIndex + 1][columnIndex]
            )
            val power = listOf(
                mesh.points[rowIndex][columnIndex].power,
                mesh.points[rowIndex][columnIndex + 1].power,
                mesh.points[rowIndex + 1][columnIndex + 1].power,
                mesh.points[rowIndex + 1][columnIndex].power
            ).average()

            cells += SurfaceCell(
                vertices = vertices,
                depth = vertices.map { it.depth }.average(),
                powerRatio = normalizeSurface(power, mesh.minPower, mesh.maxPower)
            )
        }
    }

    drawFloorProjection(projected)

    cells.sortedBy { it.depth }.forEach { cell ->
        val path = Path().apply {
            moveTo(cell.vertices.first().offset.x, cell.vertices.first().offset.y)
            cell.vertices.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
            close()
        }
        drawPath(path, surfaceColor(cell.powerRatio))
        drawPath(
            path = path,
            color = AerospacePalette.Void.copy(alpha = 0.46f),
            style = Stroke(width = 0.62f)
        )
    }

    drawPlotBox(projected)
    drawSurfaceColorBar(
        x = size.width - 48f,
        y = 34f,
        height = size.height - 100f
    )
    drawSurfacePeak(projected)
}

private fun DrawScope.drawSurfaceGrid() {
    drawRect(AerospacePlotBackground)
    val minor = 38.dp.toPx()
    var index = 0
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = if (index % 4 == 0) AerospacePlotGridMajor else AerospacePlotGridMinor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (index % 4 == 0) 0.7.dp.toPx() else 0.4.dp.toPx()
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
            strokeWidth = if (index % 4 == 0) 0.7.dp.toPx() else 0.4.dp.toPx()
        )
        y += minor
        index += 1
    }
}

private fun DrawScope.drawPlotBox(projected: List<List<ProjectedSurfacePoint>>) {
    val frameColor = AerospacePalette.BorderStrong
    val back = projected.first()
    val front = projected.last()
    val left = projected.map { it.first() }
    val right = projected.map { it.last() }

    listOf(back, front, left, right).forEach { line ->
        drawLine(
            color = frameColor,
            start = line.first().offset,
            end = line.last().offset,
            strokeWidth = 1.05f
        )
    }

    projected.first().indices.forEach { index ->
        if (index == 0 || index == projected.first().lastIndex || index % 5 == 0) {
            drawLine(
                AerospacePlotGridMajor,
                back[index].offset,
                front[index].offset,
                strokeWidth = 0.65f
            )
        }
    }

    projected.indices.forEach { index ->
        if (index == 0 || index == projected.lastIndex || index % 5 == 0) {
            drawLine(
                AerospacePlotGridMajor,
                projected[index].first().offset,
                projected[index].last().offset,
                strokeWidth = 0.65f
            )
        }
    }
}

private fun DrawScope.drawFloorProjection(projected: List<List<ProjectedSurfacePoint>>) {
    val floorRows = projected.map { row -> row.map { it.copy(offset = it.floorOffset) } }

    floorRows.forEachIndexed { index, row ->
        if (index % 2 == 0 || index == floorRows.lastIndex) {
            drawPolyline(
                points = row.map { it.offset },
                color = AerospacePalette.Accent.copy(alpha = 0.40f),
                strokeWidth = 0.9f
            )
        }
    }

    floorRows.first().indices.forEach { index ->
        if (index % 2 == 0 || index == floorRows.first().lastIndex) {
            drawPolyline(
                points = floorRows.map { it[index].offset },
                color = AerospacePalette.BorderStrong.copy(alpha = 0.46f),
                strokeWidth = 0.8f
            )
        }
    }
}

private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    points.zipWithNext().forEach { (start, end) ->
        drawLine(color, start, end, strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawSurfaceColorBar(
    x: Float,
    y: Float,
    height: Float
) {
    val width = 15f
    val steps = height.toInt().coerceAtLeast(1)

    for (index in 0..steps) {
        val ratio = 1f - index / steps.toFloat()
        drawLine(
            color = surfaceColor(ratio),
            start = Offset(x, y + index),
            end = Offset(x + width, y + index),
            strokeWidth = 1f
        )
    }

    drawRect(
        color = AerospacePalette.BorderStrong,
        topLeft = Offset(x, y),
        size = Size(width, height),
        style = Stroke(width = 1f)
    )
}

private fun DrawScope.drawSurfacePeak(projected: List<List<ProjectedSurfacePoint>>) {
    val peak = projected.flatten().maxByOrNull { it.powerRatio } ?: return
    drawCircle(
        color = AerospacePalette.Success.copy(alpha = 0.20f),
        radius = 13f,
        center = peak.offset
    )
    drawCircle(
        color = AerospacePalette.Success,
        radius = 7f,
        center = peak.offset,
        style = Stroke(width = 1.3f)
    )
    drawCircle(
        color = AerospacePalette.TextPrimary,
        radius = 3.8f,
        center = peak.offset
    )
}

private fun DrawScope.drawSurfacePlaceholder() {
    val center = Offset(size.width * 0.5f, size.height * 0.48f)
    val radiusX = size.width * 0.28f
    val radiusY = size.height * 0.16f

    drawLine(
        AerospacePlotGridMajor,
        Offset(size.width * 0.18f, size.height * 0.72f),
        Offset(size.width * 0.82f, size.height * 0.72f),
        strokeWidth = 1f
    )
    drawLine(
        AerospacePlotGridMajor,
        Offset(size.width * 0.18f, size.height * 0.72f),
        Offset(size.width * 0.5f, size.height * 0.20f),
        strokeWidth = 1f
    )
    drawLine(
        AerospacePlotGridMajor,
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
    0.86f to Color(0xFF39D98A),
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

    return Color(
        red = left.second.red + (right.second.red - left.second.red) * local,
        green = left.second.green + (right.second.green - left.second.green) * local,
        blue = left.second.blue + (right.second.blue - left.second.blue) * local,
        alpha = 1f
    )
}

private fun normalizeSurface(value: Double, min: Double, max: Double): Float {
    val span = max - min
    if (span.absoluteValue < 1e-9) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun ProjectedSurfacePoint.copy(offset: Offset): ProjectedSurfacePoint =
    ProjectedSurfacePoint(
        offset = offset,
        floorOffset = floorOffset,
        depth = depth,
        heightPixels = heightPixels,
        powerRatio = powerRatio
    )

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
