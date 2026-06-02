package org.jason.siph.domain.coupling

import org.jason.siph.domain.positioner.VirtualPivotPoint

/**
 * 耦光模式。
 */
enum class CouplingMode {

    /**
     * 垂直光栅耦合。
     *
     * 常见扫描平面：X/Y。
     */
    VerticalGratingCoupler,

    /**
     * 芯片级边缘耦合。
     *
     * 常见扫描平面：Y/Z。
     */
    HorizontalDieLevelEdgeCoupling,

    /**
     * 晶圆级边缘耦合。
     *
     * 常见扫描平面：Y/Z，需要更严格的碰撞规避。
     */
    WaferLevelEdgeCoupling
}

/**
 * 螺旋搜索平面。
 */
enum class CouplingSpiralPlane {
    XY,
    YZ,
    XZ
}

/**
 * 自动耦光配置。
 *
 * 这是 domain 层配置，给 CouplingRunner / SpiralCouplingRunner 使用。
 *
 * 注意：
 * - X/Y/Z 单位：um
 * - U/V/W 单位：deg
 * - 光功率单位：dBm
 */
data class CouplingConfig(

    /**
     * 耦光模式。
     */
    val mode: CouplingMode = CouplingMode.VerticalGratingCoupler,

    /**
     * 激光波长。
     */
    val wavelengthNm: Double = 1550.0,

    /**
     * 光功率计通道。
     */
    val powerMeterChannel: Int = 1,

    /**
     * 螺旋搜索平面。
     *
     * Grating Coupler 通常用 XY。
     * Edge Coupler 通常用 YZ。
     */
    val spiralPlane: CouplingSpiralPlane = CouplingSpiralPlane.XY,

    /**
     * First light 阈值。
     *
     * 超过这个功率，认为已经找到初始光。
     */
    val firstLightThresholdDbm: Double = -40.0,

    /**
     * 目标耦光功率。
     */
    val targetPowerDbm: Double = -10.0,

    /**
     * 螺旋搜索步长。
     */
    val spiralStepUm: Double = 2.0,

    /**
     * 螺旋搜索最大半径。
     */
    val maxRadiusUm: Double = 50.0,

    /**
     * 每次移动后等待稳定时间。
     */
    val settleDelayMs: Long = 50L,

    /**
     * 是否启用 XYZ 精细优化。
     */
    val enableFineXyz: Boolean = true,

    /**
     * XYZ 精细优化步长。
     */
    val fineStepsUm: List<Double> = listOf(
        2.0,
        1.0,
        0.5,
        0.2,
        0.1
    ),

    /**
     * 判断是否有明显改善的最小功率差。
     */
    val minImproveDb: Double = 0.02,

    /**
     * 是否启用入射角优化。
     */
    val enableIncidentAngleOptimization: Boolean = false,

    /**
     * U 轴角度优化步长。
     */
    val uStepDeg: Double = 0.02,

    /**
     * V 轴角度优化步长。
     */
    val vStepDeg: Double = 0.02,

    /**
     * W 轴角度优化步长。
     *
     * Fiber Array 时 W 很重要。
     */
    val wStepDeg: Double = 0.01,

    /**
     * U/V/W 最大角度搜索范围。
     */
    val maxAngleRangeDeg: Double = 0.2,

    /**
     * 虚拟枢轴点。
     *
     * 第一版可以先保存，不启用复杂补偿。
     */
    val virtualPivotPoint: VirtualPivotPoint = VirtualPivotPoint.Disabled,

    /**
     * 是否启用软件层虚拟枢轴补偿。
     *
     * 第一版建议 false。
     */
    val enableSoftwarePivotCompensation: Boolean = false,

    /**
     * 是否启用碰撞规避。
     */
    val enableCollisionAvoidance: Boolean = true
)