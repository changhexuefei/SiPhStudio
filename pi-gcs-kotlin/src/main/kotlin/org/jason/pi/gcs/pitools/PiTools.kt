package org.jason.pi.gcs.pitools

import kotlinx.coroutines.delay
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.hexapod.PiAxis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 类似 PIPython pitools 的 Kotlin 工具类。
 *
 * 这一层不直接关心 SiPhTools / 耦光算法，
 * 只负责 PI 控制器常用运动工具：
 *
 * - startup
 * - setServo
 * - reference
 * - moveAndWait
 * - waitOnTarget
 * - stopAll
 * - queryTravelRange
 */
object PiTools {

    /**
     * 推荐新版 startup。
     *
     * 使用 PiStartupOptions 统一管理：
     * - 是否 STP
     * - 是否清错误
     * - Servo 模式
     * - Reference 模式
     * - 等待参数
     */
    suspend fun startup(
        device: GcsDevice,
        options: PiStartupOptions = PiStartupOptions.DefaultForSiPh
    ) {
        require(options.axes.isNotEmpty()) {
            "startup axes 不能为空"
        }

        if (options.stopBeforeStartup) {
            runCatching {
                device.stopAll()
            }.onFailure { error ->
                if (options.failFast) {
                    throw error
                }
            }

            delayIfNeeded(options.stopSettleDelayMs)
        }

        if (options.clearErrorBeforeStartup) {
            runCatching {
                device.qERR()
            }.onFailure { error ->
                if (options.failFast) {
                    throw error
                }
            }
        }

        when (options.servoMode) {
            PiStartupServoMode.Keep -> {
                // 不改变 Servo 状态
            }

            PiStartupServoMode.Enable -> {
                setServo(
                    device = device,
                    axes = options.axes,
                    enabled = true,
                    failFast = options.failFast
                )

                delayIfNeeded(options.servoSettleDelayMs)
            }

            PiStartupServoMode.Disable -> {
                setServo(
                    device = device,
                    axes = options.axes,
                    enabled = false,
                    failFast = options.failFast
                )
            }
        }

        referenceAxes(
            device = device,
            axes = options.effectiveReferenceAxes,
            waitAfterEachAxis = options.waitAfterEachReferenceAxis,
            waitOptions = options.waitOptions,
            failFast = options.failFast
        )

        if (options.waitOnTargetAfterStartup) {
            waitOnTarget(
                device = device,
                axes = options.axes,
                options = options.waitOptions
            )
        }
    }

    /**
     * 兼容旧版 startup 调用。
     *
     * 你原来调用：
     *
     * PiTools.startup(
     *     device = device,
     *     axes = PiAxis.HEXAPOD_AXES,
     *     enableServo = true,
     *     reference = false
     * )
     *
     * 仍然可以继续使用。
     */
    suspend fun startup(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        enableServo: Boolean = true,
        reference: Boolean = false
    ) {
        startup(
            device = device,
            options = PiStartupOptions(
                axes = axes,
                stopBeforeStartup = true,
                clearErrorBeforeStartup = true,
                servoMode = if (enableServo) {
                    PiStartupServoMode.Enable
                } else {
                    PiStartupServoMode.Keep
                },
                referenceMode = if (reference) {
                    PiStartupReferenceMode.ReferenceAll
                } else {
                    PiStartupReferenceMode.None
                },
                waitOptions = if (reference) {
                    PiWaitOptions.LongMove
                } else {
                    PiWaitOptions.Default
                }
            )
        )
    }

    /**
     * 设置多个轴 Servo。
     */
    suspend fun setServo(
        device: GcsDevice,
        axes: List<PiAxis>,
        enabled: Boolean,
        failFast: Boolean = true
    ) {
        require(axes.isNotEmpty()) {
            "setServo axes 不能为空"
        }

        axes.forEach { axis ->
            runCatching {
                if (enabled) {
                    device.servoOn(axis)
                } else {
                    device.servoOff(axis)
                }
            }.onFailure { error ->
                if (failFast) {
                    throw error
                }
            }
        }
    }

    /**
     * 对指定轴执行 Reference。
     *
     * 注意：
     * Hexapod 是否需要 / 允许 FRF，
     * 要根据你的 PI 控制器和 Hexapod 手册确认。
     */
    suspend fun referenceAxes(
        device: GcsDevice,
        axes: List<PiAxis>,
        waitAfterEachAxis: Boolean = true,
        waitOptions: PiWaitOptions = PiWaitOptions.LongMove,
        failFast: Boolean = true
    ) {
        if (axes.isEmpty()) {
            return
        }

        axes.forEach { axis ->
            runCatching {
                device.reference(axis)

                if (waitAfterEachAxis) {
                    waitOnTarget(
                        device = device,
                        axes = listOf(axis),
                        options = waitOptions
                    )
                }
            }.onFailure { error ->
                if (failFast) {
                    throw error
                }
            }
        }
    }

    /**
     * 移动并等待到位。
     *
     * targets 使用 GCS 命令单位：
     * - 如果 X/Y/Z 命令单位是 mm，这里就是 mm
     * - 如果 U/V/W 是 degree，这里就是 degree
     *
     * 业务层 um/deg 到 GCS 命令单位的转换，
     * 应该在 PiGcsHexapodPort / PiHexapodUnitConfig 中完成。
     */
    suspend fun moveAndWait(
        device: GcsDevice,
        targets: Map<PiAxis, Double>,
        waitOptions: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(targets.isNotEmpty()) {
            "moveAndWait targets 不能为空"
        }

        device.moveAbsolute(targets)

        waitOnTarget(
            device = device,
            axes = targets.keys.toList(),
            options = waitOptions
        )
    }

    /**
     * 等待所有指定轴到位。
     *
     * 推荐新版，使用 PiWaitOptions。
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        options: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(axes.isNotEmpty()) {
            "waitOnTarget axes 不能为空"
        }

        delayIfNeeded(options.preDelayMs)

        val startMs = nowMs()

        while (true) {
            val states = device.qONT(axes)

            if (states.values.all { it }) {
                delayIfNeeded(options.postDelayMs)
                return
            }

            val elapsedMs = nowMs() - startMs

            if (elapsedMs > options.timeoutMs) {
                throw PiGcsTimeoutException(
                    "等待 PI 到位超时: axes=${axes.toAxisText()}, " +
                            "states=${states.toStateText()}, " +
                            "timeoutMs=${options.timeoutMs}"
                )
            }

            delay(options.pollDelayMs)
        }
    }

    /**
     * 兼容旧版 waitOnTarget 调用。
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        timeoutMs: Long = 10_000L,
        pollDelayMs: Long = 100L,
        postDelayMs: Long = 0L
    ) {
        waitOnTarget(
            device = device,
            axes = axes,
            options = PiWaitOptions(
                timeoutMs = timeoutMs,
                pollDelayMs = pollDelayMs,
                postDelayMs = postDelayMs
            )
        )
    }

    /**
     * 停止所有运动。
     */
    suspend fun stopAll(
        device: GcsDevice,
        clearErrorAfterStop: Boolean = false
    ) {
        device.stopAll()

        if (clearErrorAfterStop) {
            runCatching {
                device.qERR()
            }
        }
    }

    /**
     * 查询行程范围。
     *
     * 返回值单位是 GCS 命令单位：
     * - X/Y/Z 可能是 mm
     * - U/V/W 通常是 degree
     *
     * 如果要转换成业务层 μm/deg，
     * 使用 PiHexapodUnitConfig.fromCommandTravelRanges(...)
     */
    suspend fun queryTravelRange(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): PiTravelRange {
        require(axes.isNotEmpty()) {
            "queryTravelRange axes 不能为空"
        }

        val minValues = device.qTMN(axes)
        val maxValues = device.qTMX(axes)

        return PiTravelRange.fromMinMax(
            minValues = minValues,
            maxValues = maxValues
        )
    }

    /**
     * 兼容旧代码：返回 Map<PiAxis, ClosedFloatingPointRange<Double>>。
     */
    suspend fun queryTravelRangeMap(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    private suspend fun delayIfNeeded(
        delayMs: Long
    ) {
        if (delayMs > 0L) {
            delay(delayMs)
        }
    }

    private fun nowMs(): Long {
        return System.currentTimeMillis()
    }

    private fun List<PiAxis>.toAxisText(): String {
        return joinToString(" ") { it.code }
    }

    private fun Map<PiAxis, Boolean>.toStateText(): String {
        return entries.joinToString(", ") { (axis, onTarget) ->
            "${axis.code}=$onTarget"
        }
    }
}