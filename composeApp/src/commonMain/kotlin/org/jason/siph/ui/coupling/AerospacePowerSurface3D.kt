package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        caption = "Portable Compose renderer with interactive orbit controls",
        enabled = true
    ),
    JavaFx3d(
        label = "JAVAFX",
        caption = "Desktop JavaFX accelerated surface renderer",
        enabled = true
    ),
    Lwjgl(
        label = "LWJGL",
        caption = "OpenGL surface renderer for production visualization",
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SurfaceRendererControlBar(
            selectedBackend = selectedBackend,
            mesh = mesh,
            onSelectBackend = onSelectBackend,
            modifier = Modifier.fillMaxWidth()
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val stackViewports = this.maxWidth < 900.dp

            when (selectedBackend) {
                AerospaceSurfaceBackend.ComposeCanvas -> {
                    if (stackViewports) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AerospaceSurfaceViewport(
                                title = "PRIMARY ORBIT",
                                subtitle = "Perspective optimization surface",
                                mesh = mesh,
                                initialYaw = -0.62,
                                initialPitch = 0.58,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                            AerospaceSurfaceViewport(
                                title = "CROSS AXIS",
                                subtitle = "Orthogonal field inspection",
                                mesh = mesh,
                                initialYaw = 0.58,
                                initialPitch = 0.66,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AerospaceSurfaceViewport(
                                title = "PRIMARY ORBIT",
                                subtitle = "Perspective optimization surface",
                                mesh = mesh,
                                initialYaw = -0.62,
                                initialPitch = 0.58,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            AerospaceSurfaceViewport(
                                title = "CROSS AXIS",
                                subtitle = "Orthogonal field inspection",
                                mesh = mesh,
                                initialYaw = 0.58,
                                initialPitch = 0.66,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }

                AerospaceSurfaceBackend.JavaFx3d -> JavaFxPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier.fillMaxSize()
                )

                AerospaceSurfaceBackend.Lwjgl -> LwjglPowerSurface3d(
                    mesh = mesh,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SurfaceRendererControlBar(
    selectedBackend: AerospaceSurfaceBackend,
    mesh: SurfaceMesh?,
    onSelectBackend: (AerospaceSurfaceBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AerospacePlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AerospaceSurfaceBackend.entries.forEach { backend ->
                    AerospaceSegmentButton(
                        selected = selectedBackend == backend,
                        label = backend.label,
                        enabled = backend.enabled,
                        onClick = { onSelectBackend(backend) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "SURFACE RENDERER / ${selectedBackend.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AerospacePalette.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedBackend.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = AerospacePalette.TextMuted,
                        maxLines = 1
                    )
                }

                TelemetryPill(
                    label = "MESH",
                    value = mesh?.points?.size?.let { "${it}×$it" } ?: "NO DATA",
                    tone = if (mesh != null) AerospacePalette.Success else AerospacePalette.Warning,
                    active = mesh != null
                )

                TelemetryPill(
                    label = "POWER RANGE",
                    value = mesh?.let {
                        "${roundPlotValue(it.minPower)} · ${roundPlotValue(it.maxPower)} dBm"
                    } ?: "-- dBm",
                    tone = AerospacePalette.Accent,
                    active = mesh != null
                )
            }
        }
    }
}

@Composable
private fun AerospaceSurfaceViewport(
    title: String,
    subtitle: String,
    mesh: SurfaceMesh?,
    initialYaw: Double,
    initialPitch: Double,
    modifier: Modifier = Modifier
) {
    var yaw by remember(title) { mutableStateOf(initialYaw) }
    var pitch by remember(title) { mutableStateOf(initialPitch) }

    Surface(
        modifier = modifier.heightIn(min = 300.dp),
        shape = MaterialTheme.shapes.medium,
        color = AerospacePlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = if (mesh != null) {
                                AerospacePalette.AccentBright
                            } else {
                                AerospacePalette.TextMuted
                            },
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = AerospacePalette.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AerospacePalette.TextMuted
                    )
                }
                Text(
                    text = if (mesh == null) "AWAITING FIELD" else "LIVE MESH",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mesh == null) AerospacePalette.Warning else AerospacePalette.Success,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 220.dp)
                    .background(AerospacePlotBackground)
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
                                pitch = (pitch - dragAmount.y * 0.008)
                                    .coerceIn(0.24, 1.18)
                            }
                        }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "DRAG TO ORBIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.Accent,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "YAW ${roundAngle(yaw)}°  /  PITCH ${roundAngle(pitch)}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clickable {
                        yaw = initialYaw
                        pitch = initialPitch
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = AerospacePalette.PanelRaised,
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
}

private fun roundAngle(radians: Double): Double =
    kotlin.math.round(Math.toDegrees(radians) * 10.0) / 10.0

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
    val plotWidth = (size.width - 54f).coerceAtLeast(size.width * 0.72f)
    val projected = mesh.points.map { row ->
        row.map { point ->
            projectSurfacePoint(
                point = point,
                yaw = yaw,
                pitch = pitch,
                width = plotWidth,
                height = size.height
            )
        }
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
            color = AerospacePalette.Void.copy(alpha = 0.42f),
            style = Stroke(width = 0.58f)
        )
    }

    drawPlotBox(projected)
    drawSurfaceColorBar(
        x = size.width - 26f,
        y = 24f,
        height = (size.height - 58f).coerceAtLeast(64f)
    )
    drawSurfacePeak(projected)
}

private fun DrawScope.drawSurfaceGrid() {
    drawRect(AerospacePlotBackground)
    val minor = 36.dp.toPx()
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
    val back = projected.first()
    val front = projected.last()
    val left = projected.map { it.first() }
    val right = projected.map { it.last() }

    listOf(back, front, left, right).forEach { line ->
        drawLine(
            color = AerospacePalette.BorderStrong,
            start = line.first().offset,
            end = line.last().offset,
            strokeWidth = 1.05f
        )
    }

    projected.first().indices.forEach { index ->
        if (index == 0 || index == projected.first().lastIndex || index % 5 == 0) {
            drawLine(
                color = AerospacePlotGridMajor,
                start = back[index].offset,
                end = front[index].offset,
                strokeWidth = 0.65f
            )
        }
    }

    projected.indices.forEach { index ->
        if (index == 0 || index == projected.lastIndex || index % 5 == 0) {
            drawLine(
                color = AerospacePlotGridMajor,
                start = projected[index].first().offset,
                end = projected[index].last().offset,
                strokeWidth = 0.65f
            )
        }
    }
}

private fun DrawScope.drawFloorProjection(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val floorRows = projected.map { row ->
        row.map { it.copy(offset = it.floorOffset) }
    }

    floorRows.forEachIndexed { index, row ->
        if (index % 2 == 0 || index == floorRows.lastIndex) {
            drawPolyline(
                points = row.map { it.offset },
                color = AerospacePalette.Accent.copy(alpha = 0.34f),
                strokeWidth = 0.85f
            )
        }
    }

    floorRows.first().indices.forEach { index ->
        if (index % 2 == 0 || index == floorRows.first().lastIndex) {
            drawPolyline(
                points = floorRows.map { it[index].offset },
                color = AerospacePalette.BorderStrong.copy(alpha = 0.42f),
                strokeWidth = 0.78f
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
    val width = 12f
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

private fun DrawScope.drawSurfacePeak(
    projected: List<List<ProjectedSurfacePoint>>
) {
    val peak = projected.flatten().maxByOrNull { it.powerRatio } ?: return
    drawCircle(
        color = AerospacePalette.Success.copy(alpha = 0.18f),
        radius = 14f,
        center = peak.offset
    )
    drawCircle(
        color = AerospacePalette.Success,
        radius = 7f,
        center = peak.offset,
        style = Stroke(width = 1.4f)
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
    val z = point.z * 1.18
    val yawCos = cos(yaw)
    val yawSin = sin(yaw)
    val pitchCos = cos(pitch)
    val pitchSin = sin(pitch)
    val rotatedX = point.x * yawCos - point.y * yawSin
    val rotatedY = point.x * yawSin + point.y * yawCos
    val projectedY = rotatedY * pitchCos - z * pitchSin
    val depth = rotatedY * pitchSin + z * pitchCos
    val scale = minOf(width * 0.33, height * 0.38)
    val baseY = height * 0.72f
    val floorY = baseY + (rotatedY * pitchCos * scale).toFloat()

    return ProjectedSurfacePoint(
        offset = Offset(
            x = width * 0.50f + (rotatedX * scale).toFloat(),
            y = baseY + (projectedY * scale).toFloat()
        ),
        floorOffset = Offset(
            x = width * 0.50f + (rotatedX * scale).toFloat(),
            y = floorY
        ),
        depth = depth,
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
    val local = ((ratio - left.first) / (right.first - left.first))
        .coerceIn(0f, 1f)

    return Color(
        red = left.second.red + (right.second.red - left.second.red) * local,
        green = left.second.green + (right.second.green - left.second.green) * local,
        blue = left.second.blue + (right.second.blue - left.second.blue) * local,
        alpha = 1f
    )
}

private fun normalizeSurface(
    value: Double,
    min: Double,
    max: Double
): Float {
    val span = max - min
    if (span.absoluteValue < 1e-9) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun ProjectedSurfacePoint.copy(
    offset: Offset
): ProjectedSurfacePoint = ProjectedSurfacePoint(
    offset = offset,
    floorOffset = floorOffset,
    depth = depth,
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
