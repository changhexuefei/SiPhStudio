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
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

@Composable
internal fun AerospaceCouplingPlotPanelV2(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CouplingPlotViewModeV2.Planar) }
    var rendererBackend by remember { mutableStateOf(AerospaceSurfaceBackend.SurfacePlot) }
    val summary = remember(samples) { buildPlotSummaryV2(samples) }

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CouplingPlotViewModeV2.entries.forEach { mode ->
                AerospaceSegmentButton(
                    selected = viewMode == mode,
                    label = mode.label,
                    onClick = { viewMode = mode },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        PlotMetricsStripV2(summary)

        when (viewMode) {
            CouplingPlotViewModeV2.Planar -> PlanarTelemetryViewV2(
                samples = samples,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            CouplingPlotViewModeV2.Surface3d -> LargePowerSurface3d(
                samples = samples,
                selectedBackend = rendererBackend,
                onSelectBackend = { rendererBackend = it },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

private enum class CouplingPlotViewModeV2(val label: String) {
    Planar("2D ANALYTICS"),
    Surface3d("3D SURFACE")
}

private data class PlotSummaryV2(
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
private fun PlotMetricsStripV2(summary: PlotSummaryV2) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricTile("SAMPLES", summary.sampleCount.toString(), Modifier.weight(1f))
        MetricTile("CURRENT", formatPlotDbm(summary.currentPowerDbm), Modifier.weight(1f))
        MetricTile(
            label = "PEAK",
            value = formatPlotDbm(summary.peakPowerDbm),
            modifier = Modifier.weight(1f),
            accent = AerospacePalette.Success,
            emphasized = summary.peakPowerDbm != null
        )
        MetricTile(
            label = "DYNAMIC RANGE",
            value = summary.dynamicRangeDb?.let { "${roundPlotValue(it)} dB" } ?: "-- dB",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlanarTelemetryViewV2(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AerospacePlotFrame(
            eyebrow = "SEQUENCE TREND",
            title = "POWER VS SAMPLE",
            caption = "Optical power progression through the active search",
            modifier = Modifier.fillMaxWidth().weight(0.9f)
        ) {
            if (samples.isEmpty()) {
                AerospaceEmptyPlotState(
                    title = "NO TREND DATA",
                    caption = "Start a coupling sequence to populate the power timeline."
                )
            } else {
                AerospacePowerTrend(samples, Modifier.fillMaxSize())
            }
        }

        AerospacePlotFrame(
            eyebrow = "SPATIAL FIELD",
            title = "XY POWER MAP",
            caption = "Scan path, current position, peak position and power contours",
            modifier = Modifier.fillMaxWidth().weight(1.1f)
        ) {
            AerospaceHeatMap(samples, Modifier.fillMaxSize())
        }
    }
}

private enum class LargeSurfaceView(
    val label: String,
    val subtitle: String,
    val initialYaw: Double,
    val initialPitch: Double
) {
    Primary("PRIMARY ORBIT", "Perspective optimization surface", -0.62, 0.58),
    CrossAxis("CROSS AXIS", "Orthogonal field inspection", 0.58, 0.66)
}

@Composable
private fun LargePowerSurface3d(
    samples: List<CouplingSampleUi>,
    selectedBackend: AerospaceSurfaceBackend,
    onSelectBackend: (AerospaceSurfaceBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    val mesh = remember(samples) { buildLargeSurfaceMesh(samples) }
    var selectedView by remember { mutableStateOf(LargeSurfaceView.Primary) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LargeRendererControlBar(
            selectedBackend = selectedBackend,
            selectedView = selectedView,
            mesh = mesh,
            onSelectBackend = onSelectBackend,
            onSelectView = { selectedView = it },
            modifier = Modifier.fillMaxWidth()
        )

        when (selectedBackend) {
            AerospaceSurfaceBackend.SurfacePlot -> SurfacePlotPowerSurface3d(
                mesh = mesh,
                title = selectedView.label,
                initialAzimuthDegrees = (selectedView.initialYaw * 180.0 / kotlin.math.PI).toFloat(),
                initialElevationDegrees = (selectedView.initialPitch * 180.0 / kotlin.math.PI).toFloat(),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 430.dp)
            )

            AerospaceSurfaceBackend.JavaFx3d -> JavaFxPowerSurface3d(
                mesh = mesh,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 430.dp)
            )

            AerospaceSurfaceBackend.Lwjgl -> LwjglPowerSurface3d(
                mesh = mesh,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 430.dp)
            )
        }
    }
}

@Composable
private fun LargeRendererControlBar(
    selectedBackend: AerospaceSurfaceBackend,
    selectedView: LargeSurfaceView,
    mesh: SurfaceMesh?,
    onSelectBackend: (AerospaceSurfaceBackend) -> Unit,
    onSelectView: (LargeSurfaceView) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(9.dp)
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

            if (selectedBackend == AerospaceSurfaceBackend.SurfacePlot) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LargeSurfaceView.entries.forEach { view ->
                        AerospaceSegmentButton(
                            selected = selectedView == view,
                            label = view.label,
                            onClick = { onSelectView(view) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeSurfaceViewport(
    view: LargeSurfaceView,
    mesh: SurfaceMesh?,
    modifier: Modifier = Modifier
) {
    var yaw by remember(view) { mutableStateOf(view.initialYaw) }
    var pitch by remember(view) { mutableStateOf(view.initialPitch) }
    var zoom by remember(view) { mutableStateOf(1f) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AerospacePlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            if (mesh != null) AerospacePalette.AccentBright else AerospacePalette.TextMuted,
                            androidx.compose.foundation.shape.CircleShape
                        )
                        .padding(4.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        view.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = AerospacePalette.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        view.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AerospacePalette.TextMuted
                    )
                }
                Text(
                    if (mesh == null) "AWAITING FIELD" else "AUTO-FIT LIVE MESH",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mesh == null) AerospacePalette.Warning else AerospacePalette.Success,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 330.dp)
                    .background(AerospacePlotBackground)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLargeSurfaceGrid()
                    if (mesh == null) {
                        drawLargeSurfacePlaceholder()
                    } else {
                        drawLargeEngineeringSurface(mesh, yaw, pitch, zoom)
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(view) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            yaw += dragAmount.x * 0.008
                            pitch = (pitch - dragAmount.y * 0.008).coerceIn(0.18, 1.28)
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "DRAG TO ORBIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.Accent,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "YAW ${roundAngleV2(yaw)}° / PITCH ${roundAngleV2(pitch)}° / ZOOM ${(zoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                SurfaceAction("−", zoom > 0.78f) {
                    zoom = (zoom - 0.1f).coerceAtLeast(0.75f)
                }
                SurfaceAction("+", zoom < 1.5f) {
                    zoom = (zoom + 0.1f).coerceAtMost(1.5f)
                }
                SurfaceAction("FIT / RESET", true) {
                    yaw = view.initialYaw
                    pitch = view.initialPitch
                    zoom = 1f
                }
            }
        }
    }
}

@Composable
private fun SurfaceAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(
            1.dp,
            if (enabled) AerospacePalette.BorderStrong else AerospacePalette.Border
        )
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) AerospacePalette.TextSecondary else AerospacePalette.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun buildLargeSurfaceMesh(
    samples: List<CouplingSampleUi>,
    resolution: Int = 30
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
    ) return null

    val points = List(resolution) { yIndex ->
        List(resolution) { xIndex ->
            val x = xMin + (xMax - xMin) * xIndex / (resolution - 1)
            val y = yMin + (yMax - yMin) * yIndex / (resolution - 1)
            val power = interpolateLargePower(samples, x, y)
            SurfacePoint(
                x = normalizeLargeSurface(x, xMin, xMax).toDouble() * 2.0 - 1.0,
                y = normalizeLargeSurface(y, yMin, yMax).toDouble() * 2.0 - 1.0,
                z = normalizeLargeSurface(power, pMin, pMax).toDouble(),
                power = power
            )
        }
    }
    return SurfaceMesh(points, pMin, pMax, xMin, xMax, yMin, yMax)
}

private fun interpolateLargePower(samples: List<CouplingSampleUi>, x: Double, y: Double): Double {
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

private fun DrawScope.drawLargeEngineeringSurface(
    mesh: SurfaceMesh,
    yaw: Double,
    pitch: Double,
    zoom: Float
) {
    val projected = projectLargeSurface(mesh, yaw, pitch, size.width, size.height, zoom)
    val cells = mutableListOf<LargeSurfaceCell>()

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
            cells += LargeSurfaceCell(
                vertices,
                vertices.map { it.depth }.average(),
                normalizeLargeSurface(power, mesh.minPower, mesh.maxPower)
            )
        }
    }

    drawLargeFloorProjection(projected)
    cells.sortedBy { it.depth }.forEach { cell ->
        val path = Path().apply {
            moveTo(cell.vertices.first().offset.x, cell.vertices.first().offset.y)
            cell.vertices.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
            close()
        }
        drawPath(path, largeSurfaceColor(cell.powerRatio))
        drawPath(path, AerospacePalette.Void.copy(alpha = 0.40f), style = Stroke(0.58f))
    }
    drawLargePlotBox(projected)
    drawLargeSurfaceColorBar(
        x = size.width - 42f,
        y = 26f,
        height = (size.height - 72f).coerceAtLeast(80f)
    )
    drawLargeSurfacePeak(projected)
}

private fun projectLargeSurface(
    mesh: SurfaceMesh,
    yaw: Double,
    pitch: Double,
    canvasWidth: Float,
    canvasHeight: Float,
    zoom: Float
): List<List<LargeProjectedSurfacePoint>> {
    val yawCos = cos(yaw)
    val yawSin = sin(yaw)
    val pitchCos = cos(pitch)
    val pitchSin = sin(pitch)

    val raw = mesh.points.map { row ->
        row.map { point ->
            val z = point.z * 1.35
            val rotatedX = point.x * yawCos - point.y * yawSin
            val rotatedY = point.x * yawSin + point.y * yawCos
            LargeRawProjection(
                x = rotatedX,
                surfaceY = rotatedY * pitchCos - z * pitchSin,
                floorY = rotatedY * pitchCos,
                depth = rotatedY * pitchSin + z * pitchCos,
                powerRatio = point.z.toFloat()
            )
        }
    }

    val flat = raw.flatten()
    val minX = flat.minOf { it.x }
    val maxX = flat.maxOf { it.x }
    val minY = flat.minOf { min(it.surfaceY, it.floorY) }
    val maxY = flat.maxOf { maxOf(it.surfaceY, it.floorY) }
    val spanX = (maxX - minX).coerceAtLeast(1e-6)
    val spanY = (maxY - minY).coerceAtLeast(1e-6)
    val left = 30f
    val right = 78f
    val top = 24f
    val bottom = 26f
    val availableWidth = (canvasWidth - left - right).coerceAtLeast(80f)
    val availableHeight = (canvasHeight - top - bottom).coerceAtLeast(80f)
    val fitScale = min(
        availableWidth / spanX.toFloat(),
        availableHeight / spanY.toFloat()
    ) * 0.90f * zoom
    val rawCenterX = (minX + maxX) * 0.5
    val rawCenterY = (minY + maxY) * 0.5
    val targetCenterX = left + availableWidth * 0.5f
    val targetCenterY = top + availableHeight * 0.5f

    return raw.map { row ->
        row.map { point ->
            LargeProjectedSurfacePoint(
                offset = Offset(
                    targetCenterX + ((point.x - rawCenterX) * fitScale).toFloat(),
                    targetCenterY + ((point.surfaceY - rawCenterY) * fitScale).toFloat()
                ),
                floorOffset = Offset(
                    targetCenterX + ((point.x - rawCenterX) * fitScale).toFloat(),
                    targetCenterY + ((point.floorY - rawCenterY) * fitScale).toFloat()
                ),
                depth = point.depth,
                powerRatio = point.powerRatio
            )
        }
    }
}

private fun DrawScope.drawLargeSurfaceGrid() {
    drawRect(AerospacePlotBackground)
    val step = 42.dp.toPx()
    var index = 0
    var x = 0f
    while (x <= size.width) {
        drawLine(
            if (index % 4 == 0) AerospacePlotGridMajor else AerospacePlotGridMinor,
            Offset(x, 0f), Offset(x, size.height),
            strokeWidth = if (index % 4 == 0) 0.8.dp.toPx() else 0.4.dp.toPx()
        )
        x += step
        index++
    }
    index = 0
    var y = 0f
    while (y <= size.height) {
        drawLine(
            if (index % 4 == 0) AerospacePlotGridMajor else AerospacePlotGridMinor,
            Offset(0f, y), Offset(size.width, y),
            strokeWidth = if (index % 4 == 0) 0.8.dp.toPx() else 0.4.dp.toPx()
        )
        y += step
        index++
    }
}

private fun DrawScope.drawLargePlotBox(projected: List<List<LargeProjectedSurfacePoint>>) {
    val edges = listOf(
        projected.first(),
        projected.last(),
        projected.map { it.first() },
        projected.map { it.last() }
    )
    edges.forEach {
        drawLine(
            AerospacePalette.BorderStrong,
            it.first().offset,
            it.last().offset,
            strokeWidth = 1.1f
        )
    }
}

private fun DrawScope.drawLargeFloorProjection(projected: List<List<LargeProjectedSurfacePoint>>) {
    projected.forEachIndexed { index, row ->
        if (index % 3 == 0 || index == projected.lastIndex) {
            drawLargePolyline(row.map { it.floorOffset }, AerospacePalette.Accent.copy(alpha = 0.34f), 0.9f)
        }
    }
    projected.first().indices.forEach { index ->
        if (index % 3 == 0 || index == projected.first().lastIndex) {
            drawLargePolyline(
                projected.map { it[index].floorOffset },
                AerospacePalette.BorderStrong.copy(alpha = 0.42f),
                0.8f
            )
        }
    }
}

private fun DrawScope.drawLargePolyline(points: List<Offset>, color: Color, strokeWidth: Float) {
    points.zipWithNext().forEach { (start, end) -> drawLine(color, start, end, strokeWidth) }
}

private fun DrawScope.drawLargeSurfaceColorBar(x: Float, y: Float, height: Float) {
    val width = 16f
    val steps = height.toInt().coerceAtLeast(1)
    for (index in 0..steps) {
        val ratio = 1f - index / steps.toFloat()
        drawLine(
            largeSurfaceColor(ratio),
            Offset(x, y + index),
            Offset(x + width, y + index),
            1f
        )
    }
    drawRect(
        AerospacePalette.BorderStrong,
        Offset(x, y),
        Size(width, height),
        style = Stroke(1f)
    )
}

private fun DrawScope.drawLargeSurfacePeak(projected: List<List<LargeProjectedSurfacePoint>>) {
    val peak = projected.flatten().maxByOrNull { it.powerRatio } ?: return
    drawCircle(AerospacePalette.Success.copy(alpha = 0.18f), 17f, peak.offset)
    drawCircle(AerospacePalette.Success, 9f, peak.offset, style = Stroke(1.6f))
    drawCircle(AerospacePalette.TextPrimary, 4.5f, peak.offset)
}

private fun DrawScope.drawLargeSurfacePlaceholder() {
    val center = Offset(size.width * 0.5f, size.height * 0.48f)
    val radiusX = size.width * 0.32f
    val radiusY = size.height * 0.22f
    drawOval(
        AerospacePalette.Accent.copy(alpha = 0.38f),
        Offset(center.x - radiusX, center.y - radiusY),
        Size(radiusX * 2f, radiusY * 2f),
        style = Stroke(1.4f)
    )
}

private val LargeSurfaceColorStops = listOf(
    0.00f to Color(0xFF071425),
    0.22f to Color(0xFF0B3C68),
    0.46f to Color(0xFF007E99),
    0.68f to Color(0xFF3C9DFF),
    0.86f to Color(0xFF39D98A),
    1.00f to Color(0xFFF5F7FA)
)

private fun largeSurfaceColor(powerRatio: Float): Color {
    val ratio = powerRatio.coerceIn(0f, 1f)
    val rightIndex = LargeSurfaceColorStops.indexOfFirst { ratio <= it.first }
        .takeIf { it > 0 } ?: LargeSurfaceColorStops.lastIndex
    val left = LargeSurfaceColorStops[rightIndex - 1]
    val right = LargeSurfaceColorStops[rightIndex]
    val local = ((ratio - left.first) / (right.first - left.first)).coerceIn(0f, 1f)
    return Color(
        red = left.second.red + (right.second.red - left.second.red) * local,
        green = left.second.green + (right.second.green - left.second.green) * local,
        blue = left.second.blue + (right.second.blue - left.second.blue) * local,
        alpha = 1f
    )
}

private fun normalizeLargeSurface(value: Double, min: Double, max: Double): Float {
    val span = max - min
    if (span.absoluteValue < 1e-9) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun buildPlotSummaryV2(samples: List<CouplingSampleUi>): PlotSummaryV2 {
    val minPower = samples.minOfOrNull { it.powerDbm }
    val maxPower = samples.maxOfOrNull { it.powerDbm }
    return PlotSummaryV2(
        sampleCount = samples.size,
        peakPowerDbm = maxPower,
        currentPowerDbm = samples.lastOrNull()?.powerDbm,
        dynamicRangeDb = if (minPower != null && maxPower != null) maxPower - minPower else null
    )
}

private fun roundAngleV2(radians: Double): Double =
    round(radians * 180.0 / kotlin.math.PI * 10.0) / 10.0

private data class LargeRawProjection(
    val x: Double,
    val surfaceY: Double,
    val floorY: Double,
    val depth: Double,
    val powerRatio: Float
)

private data class LargeProjectedSurfacePoint(
    val offset: Offset,
    val floorOffset: Offset,
    val depth: Double,
    val powerRatio: Float
)

private data class LargeSurfaceCell(
    val vertices: List<LargeProjectedSurfacePoint>,
    val depth: Double,
    val powerRatio: Float
)
