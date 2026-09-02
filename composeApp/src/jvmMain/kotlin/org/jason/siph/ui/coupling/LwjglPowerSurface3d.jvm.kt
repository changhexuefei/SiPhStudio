package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
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
import com.jason.measure.uikit.surfaceplot.opengl.GpuMeshes
import com.jason.measure.uikit.surfaceplot.opengl.GpuProjectionMode
import com.jason.measure.uikit.surfaceplot.opengl.GpuScene
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePlot
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePlotConfig
import com.jason.measure.uikit.surfaceplot.opengl.GpuSurfacePoint
import com.jason.measure.uikit.surfaceplot.opengl.GpuTextLabel
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
    active: Boolean,
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

    Box(modifier = modifier.background(AerospacePlotBackground)) {
        GpuSurfacePlot(
            scene = scene,
            modifier = Modifier.fillMaxSize(),
            config = config,
            active = active,
            onPointHovered = {},
            tooltipText = { point -> gpuTooltipText(scene, point) }
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
    val aspect = mesh?.spatialAspectRatio() ?: 1f
    val xRange = mesh?.xRangeFloat ?: 0f..1f
    val yRange = mesh?.yRangeFloat ?: 0f..1f
    val zRange = mesh?.powerRangeFloat ?: 0f..1f

    val layers = buildList {
        add(
            GpuMeshLayer(
                id = SCIENTIFIC_GRID_LAYER_ID,
                mesh = GpuMeshes.scientificGrid(
                    xHalfExtent = 1f,
                    yHalfExtent = aspect,
                    zHeight = spec.verticalScale,
                    tickCount = spec.axisTickCount,
                    lineWidth = 0.0045f,
                    color = spec.gridColor.toGpuColor()
                ),
                material = GpuMaterial(
                    opacity = 1f,
                    ambient = 1f,
                    diffuse = 0f,
                    specular = 0f,
                    doubleSided = true,
                    castsShadow = false,
                    receivesShadow = false
                ),
                visible = true,
                pickable = false
            )
        )
        add(
            GpuMeshLayer(
                id = AXES_LAYER_ID,
                mesh = buildGpuAxesMesh(aspect, spec),
                material = GpuMaterial(
                    opacity = 1f,
                    ambient = 1f,
                    diffuse = 0f,
                    specular = 0f,
                    doubleSided = true,
                    castsShadow = false,
                    receivesShadow = false
                ),
                visible = true,
                pickable = false
            )
        )
        if (mesh != null) {
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

            add(
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
    }

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
        labels = buildGpuAxisLabels(
            xRange = xRange,
            yRange = yRange,
            zRange = zRange,
            aspect = aspect,
            spec = spec
        )
    )
}

private fun buildGpuAxesMesh(aspect: Float, spec: SurfaceRenderSpec) =
    GpuMeshes.merge(
        listOf(
            GpuMeshes.box(
                Vec3(-1f, -aspect - AXIS_HALF_WIDTH, -AXIS_HALF_WIDTH),
                Vec3(1f, -aspect + AXIS_HALF_WIDTH, AXIS_HALF_WIDTH),
                spec.axisColor.toGpuColor()
            ),
            GpuMeshes.box(
                Vec3(-1f - AXIS_HALF_WIDTH, -aspect, -AXIS_HALF_WIDTH),
                Vec3(-1f + AXIS_HALF_WIDTH, aspect, AXIS_HALF_WIDTH),
                spec.axisColor.toGpuColor()
            ),
            GpuMeshes.box(
                Vec3(-1f - AXIS_HALF_WIDTH, -aspect - AXIS_HALF_WIDTH, -AXIS_HALF_WIDTH),
                Vec3(-1f + AXIS_HALF_WIDTH, -aspect + AXIS_HALF_WIDTH, spec.verticalScale),
                spec.axisColor.toGpuColor()
            )
        )
    )

private fun buildGpuAxisLabels(
    xRange: ClosedFloatingPointRange<Float>,
    yRange: ClosedFloatingPointRange<Float>,
    zRange: ClosedFloatingPointRange<Float>,
    aspect: Float,
    spec: SurfaceRenderSpec
): List<GpuTextLabel> = buildList {
    val color = spec.textColor.toGpuColor()
    fun value(range: ClosedFloatingPointRange<Float>, fraction: Float): Float =
        range.start + (range.endInclusive - range.start) * fraction

    repeat(spec.axisTickCount) { tick ->
        val fraction = tick / (spec.axisTickCount - 1f)
        val worldX = -1f + 2f * fraction
        val worldY = -aspect + 2f * aspect * fraction
        val worldZ = spec.verticalScale * fraction

        add(GpuTextLabel(formatGpuAxisTick(value(xRange, fraction)), Vec3(worldX, -aspect, 0f), color, -10f, 12f, 0.48f))
        if (tick > 0) {
            add(GpuTextLabel(formatGpuAxisTick(value(yRange, fraction)), Vec3(-1f, worldY, 0f), color, -34f, 2f, 0.48f))
        }
        add(GpuTextLabel(formatGpuAxisTick(value(zRange, fraction)), Vec3(-1f, -aspect, worldZ), color, -44f, -5f, 0.48f))
    }

    add(GpuTextLabel(spec.xAxisLabel, Vec3(1f, -aspect, 0f), color, 16f, 18f, 0.58f))
    add(GpuTextLabel(spec.yAxisLabel, Vec3(-1f, aspect, 0f), color, -22f, -26f, 0.58f))
    add(GpuTextLabel(spec.zAxisLabel, Vec3(-1f, -aspect, spec.verticalScale), color, -22f, -30f, 0.58f))
}

private fun formatGpuAxisTick(value: Float): String {
    val rounded = kotlin.math.round(value * 10f) / 10f
    return if (kotlin.math.abs(rounded - kotlin.math.round(rounded)) < 0.01f) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun gpuTooltipText(
    scene: GpuScene,
    point: GpuSurfacePoint
): String {
    val dataPosition = scene.layers
        .firstOrNull { it.id == point.layerId }
        ?.mesh
        ?.vertices
        ?.getOrNull(point.vertexIndex)
        ?.dataPosition
        ?: point.position

    return buildString {
        append("X = ")
        append(formatGpuValue(dataPosition.x))
        append(" um\nY = ")
        append(formatGpuValue(dataPosition.y))
        append(" um\nPower = ")
        append(formatGpuValue(dataPosition.z))
        append(" dBm")
    }
}

private fun formatGpuValue(value: Float): String =
    String.format(Locale.US, "%.4f", value)

private fun androidx.compose.ui.graphics.Color.toGpuColor(): GpuColor =
    GpuColor(red, green, blue, alpha)

private const val POWER_SURFACE_LAYER_ID = "coupling-power-surface"
private const val SCIENTIFIC_GRID_LAYER_ID = "coupling-scientific-grid"
private const val AXES_LAYER_ID = "coupling-axes"
private const val AXIS_HALF_WIDTH = 0.006f
