package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.TelemetryPill
import kotlin.math.absoluteValue

/** JVM/desktop 可选择的三维渲染后端。 */
internal enum class AerospaceSurfaceBackend(
    val label: String,
    val caption: String,
    val enabled: Boolean
) {
    SurfacePlot(
        label = "SURFACE PLOT",
        caption = "Cached mesh renderer with orbit, zoom and contours",
        enabled = true
    ),
    JavaFx3d(
        label = "JAVAFX",
        caption = "Persistent JavaFX scene with textured TriangleMesh",
        enabled = true
    ),

    /** 枚举名为兼容旧状态保留，实际实现已经替换为 GPU OpenGL JAR。 */
    Lwjgl(
        label = "GPU OPENGL",
        caption = "OpenGL 3.3 GPU renderer with lighting, picking and tooltips",
        enabled = true
    )
}

/**
 * 仅在 samples 改变时构建一次 [SurfaceMesh]，视图旋转和绘制交给各后端维护。
 */
@Composable
internal fun AerospacePowerSurface3d(
    samples: List<CouplingSampleUi>,
    selectedBackend: AerospaceSurfaceBackend,
    onSelectBackend: (AerospaceSurfaceBackend) -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = CouplingSurfaceRenderSpec
    val mesh = remember(samples) {
        buildAerospaceSurfaceMesh(samples)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SurfaceRendererControlBar(
            selectedBackend = selectedBackend,
            mesh = mesh,
            onSelectBackend = onSelectBackend,
            modifier = Modifier.fillMaxWidth()
        )

        when (selectedBackend) {
            AerospaceSurfaceBackend.SurfacePlot -> SurfacePlotPowerSurface3d(
                mesh = mesh,
                title = "POWER SURFACE",
                initialAzimuthDegrees = spec.initialAzimuthDegrees,
                initialElevationDegrees = spec.initialElevationDegrees,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            AerospaceSurfaceBackend.JavaFx3d -> JavaFxPowerSurface3d(
                mesh = mesh,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            AerospaceSurfaceBackend.Lwjgl -> LwjglPowerSurface3d(
                mesh = mesh,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
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
                    value = mesh?.let { "${it.rowCount}×${it.columnCount}" } ?: "NO DATA",
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

/**
 * 仅在样本集合变化时执行的网格构建。
 * 反距离加权插值不再放在 Canvas 每帧绘制路径中。
 */
internal fun buildAerospaceSurfaceMesh(
    samples: List<CouplingSampleUi>,
    resolution: Int = 30
): SurfaceMesh? {
    require(resolution >= 2) { "resolution must be >= 2" }
    if (samples.size < 3) return null

    val finiteSamples = samples.filter { sample ->
        sample.pose.xUm.isFinite() &&
            sample.pose.yUm.isFinite() &&
            sample.powerDbm.isFinite()
    }
    if (finiteSamples.size < 3) return null

    val xMin = finiteSamples.minOf { it.pose.xUm }
    val xMax = finiteSamples.maxOf { it.pose.xUm }
    val yMin = finiteSamples.minOf { it.pose.yUm }
    val yMax = finiteSamples.maxOf { it.pose.yUm }
    val pMin = finiteSamples.minOf { it.powerDbm }
    val pMax = finiteSamples.maxOf { it.powerDbm }

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
            val power = interpolatePower(finiteSamples, x, y)

            SurfacePoint(
                x = normalizeSurface(x, xMin, xMax) * 2.0 - 1.0,
                y = normalizeSurface(y, yMin, yMax) * 2.0 - 1.0,
                z = normalizeSurface(power, pMin, pMax),
                power = power
            )
        }
    }

    return SurfaceMesh(
        points = points,
        minPower = pMin,
        maxPower = pMax,
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax
    )
}

private fun interpolatePower(
    samples: List<CouplingSampleUi>,
    x: Double,
    y: Double
): Double {
    var weightedPower = 0.0
    var totalWeight = 0.0

    for (sample in samples) {
        val dx = sample.pose.xUm - x
        val dy = sample.pose.yUm - y
        val distanceSquared = dx * dx + dy * dy

        if (distanceSquared < 1e-12) return sample.powerDbm

        val weight = 1.0 / distanceSquared
        weightedPower += sample.powerDbm * weight
        totalWeight += weight
    }

    return weightedPower / totalWeight
}

private fun normalizeSurface(value: Double, min: Double, max: Double): Double {
    val span = max - min
    if (span.absoluteValue < 1e-12) return 0.5
    return ((value - min) / span).coerceIn(0.0, 1.0)
}

internal data class AerospaceSurfaceColorStop(
    val position: Float,
    val color: Color
)

/** 共享颜色表，显式处理 0 和 1，避免最低功率错误落入最后一个区间。 */
internal object AerospaceSurfaceColorScale {
    val stops: List<AerospaceSurfaceColorStop> = listOf(
        AerospaceSurfaceColorStop(0.00f, Color(0xFF071425)),
        AerospaceSurfaceColorStop(0.22f, Color(0xFF0B3C68)),
        AerospaceSurfaceColorStop(0.46f, Color(0xFF007E99)),
        AerospaceSurfaceColorStop(0.68f, Color(0xFF3C9DFF)),
        AerospaceSurfaceColorStop(0.86f, Color(0xFF39D98A)),
        AerospaceSurfaceColorStop(1.00f, Color(0xFFF5F7FA))
    )

    fun colorAt(powerRatio: Float): Color {
        val ratio = powerRatio.coerceIn(0f, 1f)
        if (ratio <= stops.first().position) return stops.first().color
        if (ratio >= stops.last().position) return stops.last().color

        val rightIndex = stops.indexOfFirst { ratio <= it.position }
        val left = stops[rightIndex - 1]
        val right = stops[rightIndex]
        val local = ((ratio - left.position) / (right.position - left.position))
            .coerceIn(0f, 1f)

        return Color(
            red = left.color.red + (right.color.red - left.color.red) * local,
            green = left.color.green + (right.color.green - left.color.green) * local,
            blue = left.color.blue + (right.color.blue - left.color.blue) * local,
            alpha = left.color.alpha + (right.color.alpha - left.color.alpha) * local
        )
    }
}

internal data class SurfaceMesh(
    val points: List<List<SurfacePoint>>,
    val minPower: Double,
    val maxPower: Double,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double
) {
    val rowCount: Int
        get() = points.size

    val columnCount: Int
        get() = points.firstOrNull()?.size ?: 0
}

internal data class SurfacePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val power: Double
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

@Composable
internal expect fun SurfacePlotPowerSurface3d(
    mesh: SurfaceMesh?,
    title: String,
    initialAzimuthDegrees: Float,
    initialElevationDegrees: Float,
    modifier: Modifier = Modifier
)
