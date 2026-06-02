package org.jason.pi.gcs.pitools

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * PI 启动时的 Servo 处理方式。
 */
enum class PiStartupServoMode {

    /**
     * 不改变 Servo 状态。
     */
    Keep,

    /**
     * 启动时打开 Servo。
     */
    Enable,

    /**
     * 启动时关闭 Servo。
     *
     * 一般不建议在自动耦光流程中使用。
     */
    Disable
}

/**
 * PI 启动时的 Reference 处理方式。
 */
enum class PiStartupReferenceMode {

    /**
     * 不执行 Reference。
     *
     * 对已经完成初始化的 Hexapod，通常使用这个。
     */
    None,

    /**
     * 对所有指定轴执行 Reference。
     *
     * 注意：
     * 六轴是否需要执行 FRF，需要根据实际 PI 控制器 / Hexapod 手册确认。
     */
    ReferenceAll,

    /**
     * 只对 referenceAxes 中指定的轴执行 Reference。
     */
    ReferenceSelected
}



/**
 * PI startup 启动配置。
 *
 * 这个类对应 PIPython pitools.startup() 的 Kotlin 简化版配置。
 *
 * 第一版建议用于：
 * - PI Hexapod 连接后初始化
 * - Servo 打开
 * - 可选 Reference
 * - 等待到位
 */
data class PiStartupOptions(

    /**
     * 需要参与 startup 的轴。
     *
     * Hexapod 默认：
     * X / Y / Z / U / V / W
     */
    val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,

    /**
     * 启动前是否先执行 STP。
     *
     * 推荐 true，避免控制器上一次残留运动影响当前流程。
     */
    val stopBeforeStartup: Boolean = true,

    /**
     * 启动前是否读取一次 ERR?。
     *
     * 注意：
     * ERR? 通常会读取并清除当前错误。
     * 第一版可以设置为 true，方便清理历史错误。
     */
    val clearErrorBeforeStartup: Boolean = true,

    /**
     * Servo 处理方式。
     */
    val servoMode: PiStartupServoMode = PiStartupServoMode.Enable,

    /**
     * Reference 处理方式。
     */
    val referenceMode: PiStartupReferenceMode = PiStartupReferenceMode.None,

    /**
     * 当 referenceMode = ReferenceSelected 时，
     * 只对这些轴执行 Reference。
     */
    val referenceAxes: List<PiAxis> = emptyList(),

    /**
     * 每个轴执行 Reference 后是否等待到位。
     */
    val waitAfterEachReferenceAxis: Boolean = true,

    /**
     * startup 结束后是否等待所有轴到位。
     */
    val waitOnTargetAfterStartup: Boolean = true,

    /**
     * 等待到位配置。
     */
    val waitOptions: PiWaitOptions = PiWaitOptions(),

    /**
     * 执行 STP 后额外等待时间。
     */
    val stopSettleDelayMs: Long = 200L,

    /**
     * Servo 打开后额外等待时间。
     */
    val servoSettleDelayMs: Long = 100L,

    /**
     * 是否在 startup 过程中遇到错误时立即失败。
     *
     * 推荐 true。
     */
    val failFast: Boolean = true
) {

    init {
        require(axes.isNotEmpty()) {
            "startup axes 不能为空"
        }

        require(stopSettleDelayMs >= 0L) {
            "stopSettleDelayMs 不能小于 0，当前值: $stopSettleDelayMs"
        }

        require(servoSettleDelayMs >= 0L) {
            "servoSettleDelayMs 不能小于 0，当前值: $servoSettleDelayMs"
        }

        if (referenceMode == PiStartupReferenceMode.ReferenceSelected) {
            require(referenceAxes.isNotEmpty()) {
                "referenceMode 为 ReferenceSelected 时，referenceAxes 不能为空"
            }
        }
    }

    /**
     * 实际需要执行 Reference 的轴。
     */
    val effectiveReferenceAxes: List<PiAxis>
        get() {
            return when (referenceMode) {
                PiStartupReferenceMode.None -> {
                    emptyList()
                }

                PiStartupReferenceMode.ReferenceAll -> {
                    axes
                }

                PiStartupReferenceMode.ReferenceSelected -> {
                    referenceAxes
                }
            }
        }

    /**
     * 是否需要执行 Reference。
     */
    val shouldReference: Boolean
        get() = effectiveReferenceAxes.isNotEmpty()

    companion object {

        /**
         * 硅光耦光推荐默认配置。
         *
         * 说明：
         * - 默认不 Reference
         * - 默认打开 Servo
         * - 默认 startup 后等待到位
         */
        val DefaultForSiPh: PiStartupOptions =
            PiStartupOptions(
                axes = PiAxis.HEXAPOD_AXES,
                stopBeforeStartup = true,
                clearErrorBeforeStartup = true,
                servoMode = PiStartupServoMode.Enable,
                referenceMode = PiStartupReferenceMode.None,
                waitOnTargetAfterStartup = true,
                waitOptions = PiWaitOptions(
                    timeoutMs = 10_000L,
                    pollDelayMs = 100L,
                    postDelayMs = 0L
                )
            )

        /**
         * 完整 Reference 配置。
         *
         * 注意：
         * 只有在确认控制器和 Hexapod 允许 / 需要 Reference 时再使用。
         */
        val ReferenceAllAxes: PiStartupOptions =
            PiStartupOptions(
                axes = PiAxis.HEXAPOD_AXES,
                stopBeforeStartup = true,
                clearErrorBeforeStartup = true,
                servoMode = PiStartupServoMode.Enable,
                referenceMode = PiStartupReferenceMode.ReferenceAll,
                waitAfterEachReferenceAxis = true,
                waitOnTargetAfterStartup = true,
                waitOptions = PiWaitOptions(
                    timeoutMs = 60_000L,
                    pollDelayMs = 200L,
                    postDelayMs = 200L
                )
            )

        /**
         * 最小启动配置。
         *
         * 用于已经初始化好的仿真控制器或 Demo 环境。
         */
        val Minimal: PiStartupOptions =
            PiStartupOptions(
                axes = PiAxis.HEXAPOD_AXES,
                stopBeforeStartup = false,
                clearErrorBeforeStartup = false,
                servoMode = PiStartupServoMode.Keep,
                referenceMode = PiStartupReferenceMode.None,
                waitOnTargetAfterStartup = false
            )
    }
}