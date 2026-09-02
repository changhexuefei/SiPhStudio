package org.jason.pi.gcs.pitools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.core.PiReferenceCommand
import org.jason.pi.gcs.hexapod.PiAxis
import kotlin.time.TimeSource

/**
 * 类似 PIPython pitools 的 Kotlin 运动辅助层。
 *
 * 该层只负责控制器通用流程，不包含耦光算法和 Compose UI 状态。
 */
object PiTools {

    suspend fun startup(
        device: GcsDevice,
        options: PiStartupOptions = PiStartupOptions.DefaultForSiPh
    ) {
        require(options.axes.isNotEmpty()) { "startup axes 不能为空" }

        if (options.stopBeforeStartup) {
            runCatching { device.stopAll() }
                .onFailure { error -> error.rethrowIfNeeded(options.failFast) }
            delayIfNeeded(options.stopSettleDelayMs)
        }

        if (options.clearErrorBeforeStartup) {
            runCatching { device.qERR() }
                .onFailure { error -> error.rethrowIfNeeded(options.failFast) }
        }

        when (options.servoMode) {
            PiStartupServoMode.Keep -> Unit
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
            referenceCommand = options.referenceCommand,
            waitAfterEachAxis = options.waitAfterEachReferenceAxis,
            waitOptions = options.waitOptions,
            failFast = options.failFast
        )

        if (options.waitOnTargetAfterStartup) {
            waitOnTarget(device, options.axes, options.waitOptions)
        }
    }

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
                referenceCommand = PiReferenceCommand.FRF,
                waitOptions = if (reference) {
                    PiWaitOptions.LongMove
                } else {
                    PiWaitOptions.Default
                }
            )
        )
    }

    /** 一条 SVO 命令设置全部轴。 */
    suspend fun setServo(
        device: GcsDevice,
        axes: List<PiAxis>,
        enabled: Boolean,
        failFast: Boolean = true
    ) {
        require(axes.isNotEmpty()) { "setServo axes 不能为空" }

        runCatching {
            device.setServo(axes.associateWith { enabled })
        }.onFailure { error ->
            error.rethrowIfNeeded(failFast)
        }
    }

    suspend fun referenceAxes(
        device: GcsDevice,
        axes: List<PiAxis>,
        referenceCommand: PiReferenceCommand = PiReferenceCommand.FRF,
        waitAfterEachAxis: Boolean = true,
        waitOptions: PiWaitOptions = PiWaitOptions.LongMove,
        failFast: Boolean = true
    ) {
        if (axes.isEmpty()) return

        if (!waitAfterEachAxis) {
            runCatching {
                device.referenceAll(
                    axes = axes,
                    mode = referenceCommand
                )
            }.onFailure { error ->
                error.rethrowIfNeeded(failFast)
            }
            return
        }

        axes.forEach { axis ->
            runCatching {
                device.reference(
                    axis = axis,
                    mode = referenceCommand
                )
                waitOnTarget(
                    device = device,
                    axes = listOf(axis),
                    options = waitOptions
                )
            }.onFailure { error ->
                error.rethrowIfNeeded(failFast)
            }
        }
    }

    suspend fun moveAndWait(
        device: GcsDevice,
        targets: Map<PiAxis, Double>,
        waitOptions: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(targets.isNotEmpty()) { "moveAndWait targets 不能为空" }
        device.moveAbsolute(targets)
        waitOnTarget(
            device = device,
            axes = targets.keys.toList(),
            options = waitOptions
        )
    }

    /**
     * 等待指定轴到位。
     *
     * 协程被取消时默认在 [NonCancellable] 上下文中发送 STP，避免 UI 任务取消后
     * 控制器仍继续运动；超时时也可以按 [PiWaitOptions.stopOnTimeout] 自动停止。
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        options: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(axes.isNotEmpty()) { "waitOnTarget axes 不能为空" }

        try {
            delayIfNeeded(options.preDelayMs)
            val startedAt = TimeSource.Monotonic.markNow()

            while (true) {
                val states = device.qONT(axes)

                if (states.values.all { it }) {
                    delayIfNeeded(options.postDelayMs)
                    return
                }

                if (startedAt.elapsedNow().inWholeMilliseconds >= options.timeoutMs) {
                    if (options.stopOnTimeout) {
                        stopSafely(device)
                    }
                    throw PiGcsTimeoutException(
                        "等待 PI 到位超时: axes=${axes.toAxisText()}, " +
                            "states=${states.toStateText()}, " +
                            "timeoutMs=${options.timeoutMs}"
                    )
                }

                delay(options.pollDelayMs)
            }
        } catch (cancelled: CancellationException) {
            if (options.stopOnCancellation) {
                stopSafely(device)
            }
            throw cancelled
        }
    }

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

    suspend fun stopAll(
        device: GcsDevice,
        clearErrorAfterStop: Boolean = false
    ) {
        device.stopAll()
        if (clearErrorAfterStop) {
            runCatching { device.qERR() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
        }
    }

    suspend fun queryTravelRange(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): PiTravelRange {
        require(axes.isNotEmpty()) { "queryTravelRange axes 不能为空" }

        val minValues = device.qTMN(axes)
        val maxValues = device.qTMX(axes)

        return PiTravelRange.fromMinMax(
            minValues = minValues,
            maxValues = maxValues
        )
    }

    suspend fun queryTravelRangeMap(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return queryTravelRange(device, axes).toClosedRangeMap()
    }

    private suspend fun stopSafely(device: GcsDevice) {
        withContext(NonCancellable) {
            runCatching { device.stopAll() }
        }
    }

    private suspend fun delayIfNeeded(delayMs: Long) {
        if (delayMs > 0L) {
            delay(delayMs)
        }
    }

    private fun Throwable.rethrowIfNeeded(failFast: Boolean) {
        if (this is CancellationException || failFast) {
            throw this
        }
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
