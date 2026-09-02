package org.jason.pi.gcs.pitools

import org.jason.pi.gcs.core.PiReferenceCommand
import org.jason.pi.gcs.hexapod.PiAxis

/** PI 启动时的 Servo 处理方式。 */
enum class PiStartupServoMode {
    Keep,
    Enable,
    Disable
}

/** PI 启动时的 Reference 处理范围。 */
enum class PiStartupReferenceMode {
    None,
    ReferenceAll,
    ReferenceSelected
}

/**
 * PI startup 启动配置。
 *
 * [referenceMode] 决定哪些轴需要参考；[referenceCommand] 决定采用 FRF、FNL 还是 FPL。
 * 参考命令必须根据实际控制器、平台和机械安装方式配置，不能仅根据轴名猜测。
 */
data class PiStartupOptions(
    val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
    val stopBeforeStartup: Boolean = true,
    val clearErrorBeforeStartup: Boolean = true,
    val servoMode: PiStartupServoMode = PiStartupServoMode.Enable,
    val referenceMode: PiStartupReferenceMode = PiStartupReferenceMode.None,
    val referenceAxes: List<PiAxis> = emptyList(),
    val referenceCommand: PiReferenceCommand = PiReferenceCommand.FRF,
    val waitAfterEachReferenceAxis: Boolean = true,
    val waitOnTargetAfterStartup: Boolean = true,
    val waitOptions: PiWaitOptions = PiWaitOptions(),
    val stopSettleDelayMs: Long = 200L,
    val servoSettleDelayMs: Long = 100L,
    val failFast: Boolean = true
) {

    init {
        require(axes.isNotEmpty()) {
            "startup axes 不能为空"
        }
        require(axes.size == axes.distinct().size) {
            "startup axes 不能包含重复轴: $axes"
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
            require(referenceAxes.all { it in axes }) {
                "referenceAxes 必须是 startup axes 的子集"
            }
            require(referenceAxes.size == referenceAxes.distinct().size) {
                "referenceAxes 不能包含重复轴: $referenceAxes"
            }
        }
    }

    val effectiveReferenceAxes: List<PiAxis>
        get() = when (referenceMode) {
            PiStartupReferenceMode.None -> emptyList()
            PiStartupReferenceMode.ReferenceAll -> axes
            PiStartupReferenceMode.ReferenceSelected -> referenceAxes
        }

    val shouldReference: Boolean
        get() = effectiveReferenceAxes.isNotEmpty()

    companion object {

        /** 硅光耦光推荐默认配置：打开 Servo，但不自动执行 Reference。 */
        val DefaultForSiPh: PiStartupOptions = PiStartupOptions(
            axes = PiAxis.HEXAPOD_AXES,
            stopBeforeStartup = true,
            clearErrorBeforeStartup = true,
            servoMode = PiStartupServoMode.Enable,
            referenceMode = PiStartupReferenceMode.None,
            waitOnTargetAfterStartup = true,
            waitOptions = PiWaitOptions.Default
        )

        /** 明确使用 FRF 的完整参考配置。 */
        val ReferenceAllAxes: PiStartupOptions = PiStartupOptions(
            axes = PiAxis.HEXAPOD_AXES,
            stopBeforeStartup = true,
            clearErrorBeforeStartup = true,
            servoMode = PiStartupServoMode.Enable,
            referenceMode = PiStartupReferenceMode.ReferenceAll,
            referenceCommand = PiReferenceCommand.FRF,
            waitAfterEachReferenceAxis = true,
            waitOnTargetAfterStartup = true,
            waitOptions = PiWaitOptions.LongMove
        )

        /** 已经初始化好的仿真控制器或 Demo 环境。 */
        val Minimal: PiStartupOptions = PiStartupOptions(
            axes = PiAxis.HEXAPOD_AXES,
            stopBeforeStartup = false,
            clearErrorBeforeStartup = false,
            servoMode = PiStartupServoMode.Keep,
            referenceMode = PiStartupReferenceMode.None,
            waitOnTargetAfterStartup = false
        )
    }
}
