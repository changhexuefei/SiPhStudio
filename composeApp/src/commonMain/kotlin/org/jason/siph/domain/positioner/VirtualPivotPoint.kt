package org.jason.siph.domain.positioner

import kotlinx.serialization.Serializable

/** 光学定位器坐标系。 */
@Serializable
enum class OpticalCoordinateFrame {
    Positioner,
    Wafer,
    Chip,
    FiberTip
}

/**
 * 虚拟枢轴点。
 *
 * 线性单位：x/y/z 为 um。
 */
@Serializable
data class VirtualPivotPoint(
    val xUm: Double,
    val yUm: Double,
    val zUm: Double,
    val frame: OpticalCoordinateFrame = OpticalCoordinateFrame.Positioner,
    val enabled: Boolean = false,
    val name: String = "Default Pivot"
) {
    init {
        require(xUm.isFinite()) { "pivot xUm must be finite" }
        require(yUm.isFinite()) { "pivot yUm must be finite" }
        require(zUm.isFinite()) { "pivot zUm must be finite" }
        require(name.isNotBlank()) { "pivot name must not be blank" }
    }

    companion object {
        val Disabled = VirtualPivotPoint(
            xUm = 0.0,
            yUm = 0.0,
            zUm = 0.0,
            frame = OpticalCoordinateFrame.Positioner,
            enabled = false,
            name = "Disabled"
        )
    }
}
