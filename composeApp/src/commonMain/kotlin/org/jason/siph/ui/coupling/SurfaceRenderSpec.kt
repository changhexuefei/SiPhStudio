package org.jason.siph.ui.coupling

import androidx.compose.ui.graphics.Color
import org.jason.siph.ui.theme.AerospacePalette

/**
 * Surface Plot 与 GPU OpenGL 共享的三维曲面视觉和相机配置。
 *
 * 数据坐标：
 * - X/Y：真实位移，单位 um；
 * - Z：光功率，单位 dBm；
 * - worldX/worldY：归一化平面；
 * - worldZ：归一化高度。
 */
internal data class SurfaceRenderSpec(
    val xAxisLabel: String,
    val yAxisLabel: String,
    val zAxisLabel: String,
    val initialAzimuthDegrees: Float,
    val initialElevationDegrees: Float,
    val initialDistance: Float,
    val fieldOfViewDegrees: Float,
    val verticalScale: Float,
    val axisTickCount: Int,
    val contourLevelCount: Int,
    val multisampleCount: Int,
    val targetFramesPerSecond: Int,
    val backgroundColor: Color,
    val plotAreaColor: Color,
    val axisColor: Color,
    val gridColor: Color,
    val wireframeColor: Color,
    val textColor: Color,
    val peakColor: Color
) {
    init {
        require(initialDistance > 0f && initialDistance.isFinite())
        require(fieldOfViewDegrees in 1f..120f)
        require(verticalScale > 0f && verticalScale.isFinite())
        require(axisTickCount >= 2)
        require(contourLevelCount >= 2)
        require(multisampleCount >= 0)
        require(targetFramesPerSecond in 1..240)
    }
}

internal val CouplingSurfaceRenderSpec = SurfaceRenderSpec(
    xAxisLabel = "X (um)",
    yAxisLabel = "Y (um)",
    zAxisLabel = "Power (dBm)",
    initialAzimuthDegrees = -35.5f,
    initialElevationDegrees = 33.2f,
    initialDistance = 4.25f,
    fieldOfViewDegrees = 34f,
    verticalScale = 1.15f,
    axisTickCount = 5,
    contourLevelCount = 8,
    multisampleCount = 4,
    targetFramesPerSecond = 60,
    backgroundColor = AerospacePlotBackground,
    plotAreaColor = AerospacePlotBackground,
    axisColor = AerospacePalette.TextSecondary,
    gridColor = AerospacePlotGridMajor.copy(alpha = 0.78f),
    wireframeColor = Color.White.copy(alpha = 0.30f),
    textColor = AerospacePalette.TextSecondary,
    peakColor = AerospacePalette.Success
)

internal val SurfaceMesh.xRangeFloat: ClosedFloatingPointRange<Float>
    get() = xMin.toFloat()..xMax.toFloat()

internal val SurfaceMesh.yRangeFloat: ClosedFloatingPointRange<Float>
    get() = yMin.toFloat()..yMax.toFloat()

internal val SurfaceMesh.powerRangeFloat: ClosedFloatingPointRange<Float>
    get() = minPower.toFloat()..maxPower.toFloat()

internal fun SurfaceMesh.powerValues(): List<Float> =
    buildList(rowCount * columnCount) {
        points.forEach { row ->
            row.forEach { point -> add(point.power.toFloat()) }
        }
    }

internal fun SurfaceMesh.spatialAspectRatio(): Float {
    val xSpan = (xMax - xMin).toFloat()
    val ySpan = (yMax - yMin).toFloat()
    if (!xSpan.isFinite() || !ySpan.isFinite() || kotlin.math.abs(xSpan) < 1e-9f) {
        return 1f
    }
    return (ySpan / xSpan).coerceIn(0.25f, 4f)
}
