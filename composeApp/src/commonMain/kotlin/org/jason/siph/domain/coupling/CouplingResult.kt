package org.jason.siph.domain.coupling

import org.jason.siph.domain.positioner.OpticalPose

/**
 * 耦光结果状态。
 */
enum class CouplingResultStatus {

    /**
     * 达到目标功率。
     */
    Success,

    /**
     * 找到了 first light，但没有达到目标功率。
     */
    TargetNotReached,

    /**
     * 没有找到 first light。
     */
    FirstLightNotFound,

    /**
     * 用户停止。
     */
    Stopped,

    /**
     * 执行异常。
     */
    Failed
}

/**
 * 自动耦光结果。
 */
data class CouplingResult(
    val status: CouplingResultStatus,
    val bestPose: OpticalPose,
    val bestPowerDbm: Double,
    val finalPose: OpticalPose,
    val finalPowerDbm: Double,
    val samples: List<CouplingSample>,
    val message: String? = null,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L
) {
    val success: Boolean
        get() = status == CouplingResultStatus.Success

    companion object {

        fun stopped(
            bestPose: OpticalPose,
            bestPowerDbm: Double,
            samples: List<CouplingSample>,
            message: String = "耦光已停止",
            startedAtMs: Long = 0L,
            finishedAtMs: Long = 0L
        ): CouplingResult {
            return CouplingResult(
                status = CouplingResultStatus.Stopped,
                bestPose = bestPose,
                bestPowerDbm = bestPowerDbm,
                finalPose = bestPose,
                finalPowerDbm = bestPowerDbm,
                samples = samples,
                message = message,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs
            )
        }

        fun failed(
            initialPose: OpticalPose,
            samples: List<CouplingSample>,
            message: String,
            startedAtMs: Long = 0L,
            finishedAtMs: Long = 0L
        ): CouplingResult {
            return CouplingResult(
                status = CouplingResultStatus.Failed,
                bestPose = initialPose,
                bestPowerDbm = Double.NEGATIVE_INFINITY,
                finalPose = initialPose,
                finalPowerDbm = Double.NEGATIVE_INFINITY,
                samples = samples,
                message = message,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs
            )
        }
    }
}