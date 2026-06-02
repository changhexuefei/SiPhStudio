package org.jason.siph.domain.positioner

/**
 * 光学定位器配置。
 *
 * 这里保存和 SiPhTools 业务相关的配置。
 */
data class OpticalPositionerConfig(
    /**
     * 安全位置。
     *
     * 探针台移动前，光学头必须先回到该位置。
     */
    val safePose: OpticalPose = OpticalPose.ZERO,

    /**
     * 默认初始耦光位置。
     */
    val defaultInitialPose: OpticalPose = OpticalPose.ZERO,

    /**
     * 虚拟枢轴点。
     */
    val virtualPivotPoint: VirtualPivotPoint = VirtualPivotPoint.Disabled,

    /**
     * 是否启用软件层枢轴补偿。
     *
     * 第一版可以先 false，只保存配置。
     * 等确认 PI 控制器坐标系和旋转中心后再启用。
     */
    val enableSoftwarePivotCompensation: Boolean = false,

    /**
     * 是否启用碰撞规避。
     */
    val enableCollisionAvoidance: Boolean = true
)