package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jason.measure.uikit.surfaceplot.SurfaceColorStop
import com.jason.measure.uikit.surfaceplot.SurfaceGrid
import com.jason.measure.uikit.surfaceplot.opengl.GpuAxes
import com.jason.measure.uikit.surfaceplot.opengl.GpuColor
import com.jason.measure.uikit.surfaceplot.opengl.GpuLight
import com.jason.measure.uikit.surfaceplot.opengl.GpuMaterial
import com.jason.measure.uikit.surfaceplot.opengl.GpuMeshLayer
import com.jason.measure.uikit.surfaceplot.opengl.GpuProjectionMode
import com.jason.measure.uikit.surfaceplot.opengl.GpuScene
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePlot
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePlotConfig
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePoint
import com.jason.measure.uikit.surfaceplot.opengl.Vec3
import com.jason.measure.uikit.surfaceplot.opengl.toGpuTriangleMesh
import java.util.Locale

/**
 * 使用 surface-plot-opengl JAR 的 OpenGL 3.3 GPU 后端。
 *
 * 网格、法线、颜色和索引只在 [mesh] 变化时构建；旋转、缩放、拾取和 GPU 资源
 * 生命周期由 JAR 内部的 GpuSurfacePlot/GpuOpenGlCanvas 管理。
 */
@Composable
internal actual fun LwjglPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier
) {
    val spec = CouplingSurfaceRenderSpec
    val scene = remember(mesh, spec) {
        buildGpuSurfaceScene(mesh, spec)
    }
    val config = remember(spec) {
        GpuSurfacePlotConfig(
            initialAzimuthDegrees = spec.initialAzimuthDegrees,
            initialElevationDegrees = spec.initialElevationDegrees,
            initialDistance = spec.initialDistance,
            target = Vec3(0f, 0f, spec.verticalScale * 0.42f),
            fieldOfViewDegrees = spec.fieldOfViewDegrees,
            projectionMode = GpuProjectionMode.Perspective,
            orthographicScale = 2.4f,
            multisampleCount = spec.multisampleCount,
            targetFramesPerSecond = spec.targetFramesPerSecond
        )
    }

    Box(modifier = modifier) {
        GpuSurfacePlot(
            scene = scene,
            modifier = Modifier.fillMaxSize(),
            config = config,
            onPointHovered = {},
            tooltipText = ::gpuTooltipText
        )

        if (mesh == null) {
            AerospaceEmptyPlotState(
                title = "NO GPU SURFACE DATA",
                caption = "At least three samples spanning X, Y and power are required.",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

internal fun buildGpuSurfaceScene(
    mesh: SurfaceMesh?,
    spec: SurfaceRenderSpec = CouplingSurfaceRenderSpec
): GpuScene {
    val layers = if (mesh == null) {
        emptyList()
    } else {
        val grid = SurfaceGrid(
            columns = mesh.columnCount,
            rows = mesh.rowCount,
            values = mesh.powerValues(),
            xRange = mesh.xRangeFloat,
            yRange = mesh.yRangeFloat
        )
        val palette = AerospaceSurfaceColorScale.stops.map { stop ->
            SurfaceColorStop(stop.position, stop.color)
        }
        val gpuMesh = grid.toGpuTriangleMesh(
            zScale = spec.verticalScale,
            zRange = mesh.powerRangeFloat,
            palette = palette,
            opacityProvider = { 1f }
        )

        listOf(
            GpuMeshLayer(
                id = POWER_SURFACE_LAYER_ID,
                mesh = gpuMesh,
                material = GpuMaterial(
                    opacity = 1f,
                    ambient = 0.36f,
                    diffuse = 0.82f,
                    specular = 0.34f,
                    shininess = 42f,
                    doubleSided = true,
                    castsShadow = false,
                    receivesShadow = false,
                    wireframeColor = spec.wireframeColor.toGpuColor(),
                    wireframeWidth = 0.72f,
                    radialDepthFade = 0.08f
                ),
                visible = true,
                pickable = true
            )
        )
    }

    val aspect = mesh?.spatialAspectRatio() ?: 1f
    val xRange = mesh?.xRangeFloat ?: 0f..1f
    val yRange = mesh?.yRangeFloat ?: 0f..1f
    val zRange = mesh?.powerRangeFloat ?: 0f..1f

    return GpuScene(
        layers = layers,
        lights = listOf(
            GpuLight(
                position = Vec3(-3.2f, -2.4f, 4.6f),
                color = GpuColor(0.82f, 0.91f, 1f, 1f),
                intensity = 1.05f
            ),
            GpuLight(
                position = Vec3(3.0f, 2.0f, 2.8f),
                color = GpuColor(0.42f, 0.72f, 1f, 1f),
                intensity = 0.48f
            )
        ),
        backgroundColor = spec.backgroundColor.toGpuColor(),
        shadowsEnabled = false,
        axes = GpuAxes(
            xRange = xRange,
            yRange = yRange,
            zRange = zRange,
            worldXRange = -1f..1f,
            worldYRange = -aspect..aspect,
            worldZRange = 0f..spec.verticalScale,
            tickCount = spec.axisTickCount,
            xLabel = spec.xAxisLabel,
            yLabel = spec.yAxisLabel,
            zLabel = spec.zAxisLabel
        ),
        labels = emptyList()
    )
}

private fun gpuTooltipText(point: GpuSurfacePoint): String {
    val value = point.position
    return buildString {
        append("X = ")
        append(formatGpuValue(value.x))
        append(" um\nY = ")
        append(formatGpuValue(value.y))
        append(" um\nPower = ")
        append(formatGpuValue(value.z))
        append(" dBm")
    }
}

private fun formatGpuValue(value: Float): String =
    String.format(Locale.US, "%.4f", value)

private fun androidx.compose.ui.graphics.Color.toGpuColor(): GpuColor =
    GpuColor(red, green, blue, alpha)

private const val POWER_SURFACE_LAYER_ID = "coupling-power-surface"
