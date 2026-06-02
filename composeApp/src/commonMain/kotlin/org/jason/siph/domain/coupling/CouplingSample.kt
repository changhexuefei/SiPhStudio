package org.jason.siph.domain.coupling

import org.jason.siph.domain.positioner.OpticalPose

/**
 * 耦光阶段。
 */
enum class CouplingStage {

    /**
     * 初始点读数。
     */
    Initial,

    /**
     * 螺旋找光阶段。
     */
    SpiralFirstLight,

    /**
     * X/Y/Z 精调阶段。
     */
    FineXyz,

    /**
     * U 角度优化。
     */
    OptimizeU,

    /**
     * V 角度优化。
     */
    OptimizeV,

    /**
     * W 角度优化。
     */
    OptimizeW,

    /**
     * 最终点。
     */
    Final
}

/**
 * 耦光过程中的一个采样点。
 *
 * 每移动到一个位置，就读取一次光功率，形成一个 CouplingSample。
 */
data class CouplingSample(
    val index: Int,
    val pose: OpticalPose,
    val powerDbm: Double,
    val stage: CouplingStage,

    /**
     * 时间戳。
     *
     * commonMain 不直接依赖 System.currentTimeMillis。
     * 由 Runner 注入 timeProvider 后填入。
     */
    val timestampMs: Long = 0L
)