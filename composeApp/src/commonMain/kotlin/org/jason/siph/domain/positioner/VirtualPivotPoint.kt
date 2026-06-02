package org.jason.siph.domain.positioner

/**
 * 光学定位器坐标系。
 *
 * 注意：
 * 第一版建议先只使用 Positioner 坐标系。
 * 后续再扩展 Wafer / Chip / FiberTip 坐标系转换。
 */
enum class OpticalCoordinateFrame {
    /**
     * 光学定位器自身坐标系。
     *
     * 对 PI 六轴来说，就是 X/Y/Z/U/V/W 的位置坐标系。
     */
    Positioner,

    /**
     * 晶圆 / 探针台坐标系。
     */
    Wafer,

    /**
     * 芯片局部坐标系。
     */
    Chip,

    /**
     * 光纤端面 / Fiber tip 坐标系。
     */
    FiberTip
}

/**
 * 虚拟枢轴点。
 *
 * 用于定义 U/V/W 旋转时希望围绕哪个空间点转动。
 *
 * 线性单位：
 * - x/y/z: um
 *
 * 通常这个点可以理解为：
 * - grating coupler 上方的光斑中心
 * - edge coupler 的 fiber tip 附近
 * - fiber array 的参考 lane coupling point
 */
data class VirtualPivotPoint(
    val xUm: Double,
    val yUm: Double,
    val zUm: Double,
    val frame: OpticalCoordinateFrame = OpticalCoordinateFrame.Positioner,
    val enabled: Boolean = false,
    val name: String = "Default Pivot"
) {
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