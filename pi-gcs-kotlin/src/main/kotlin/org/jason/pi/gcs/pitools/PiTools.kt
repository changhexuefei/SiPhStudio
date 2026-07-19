package org.jason.pi.gcs.pitools

import kotlinx.coroutines.delay
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.hexapod.PiAxis
import kotlin.time.TimeSource

/**
 * Kotlin counterpart of the most frequently used PIPython pitools helpers.
 *
 * The controller connection is a serialized request/response stream. Axis
 * operations are therefore batched at protocol level where GCS supports it,
 * rather than launching coroutines that only queue behind the same mutex.
 */
object PiTools {

    suspend fun startup(
        device: GcsDevice,
        options: PiStartupOptions = PiStartupOptions.DefaultForSiPh
    ) {
        require(options.axes.isNotEmpty()) { "startup axes 不能为空" }

        if (options.stopBeforeStartup) {
            runStep(options.failFast) {
                device.stopAll()
            }
            delayIfNeeded(options.stopSettleDelayMs)
        }

        if (options.clearErrorBeforeStartup) {
            runStep(options.failFast) {
                device.qERR()
            }
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
                waitOptions = if (reference) {
                    PiWaitOptions.LongMove
                } else {
                    PiWaitOptions.Default
                }
            )
        )
    }

    /**
     * Sends one SVO command containing all axes.
     */
    suspend fun setServo(
        device: GcsDevice,
        axes: List<PiAxis>,
        enabled: Boolean,
        failFast: Boolean = true
    ) {
        val normalized = axes.distinct()
        require(normalized.isNotEmpty()) { "setServo axes 不能为空" }

        runStep(failFast) {
            device.setServo(normalized, enabled)
        }
    }

    /**
     * References the selected axes.
     *
     * When per-axis waiting is disabled, one multi-axis FRF command is used.
     * When it is enabled, each axis is referenced and verified independently so
     * a failure can be attributed to the correct axis.
     */
    suspend fun referenceAxes(
        device: GcsDevice,
        axes: List<PiAxis>,
        waitAfterEachAxis: Boolean = true,
        waitOptions: PiWaitOptions = PiWaitOptions.LongMove,
        failFast: Boolean = true
    ) {
        val normalized = axes.distinct()
        if (normalized.isEmpty()) return

        if (!waitAfterEachAxis) {
            runStep(failFast) {
                device.referenceAll(normalized)
            }
            return
        }

        for (axis in normalized) {
            runStep(failFast) {
                device.reference(axis)
                waitOnTarget(
                    device = device,
                    axes = listOf(axis),
                    options = waitOptions
                )
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
     * Waits until every requested axis reports ONT=1.
     *
     * A monotonic clock is used so an operating-system clock adjustment cannot
     * make the timeout expire too early or too late.
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        options: PiWaitOptions = PiWaitOptions.Default
    ) {
        val normalized = axes.distinct()
        require(normalized.isNotEmpty()) { "waitOnTarget axes 不能为空" }

        delayIfNeeded(options.preDelayMs)
        val started = TimeSource.Monotonic.markNow()

        while (true) {
            val states = device.qONT(normalized)
            if (normalized.all { axis -> states[axis] == true }) {
                delayIfNeeded(options.postDelayMs)
                return
            }

            if (started.elapsedNow().inWholeMilliseconds >= options.timeoutMs) {
                throw PiGcsTimeoutException(
                    "等待 PI 到位超时: axes=${normalized.toAxisText()}, " +
                        "states=${states.toStateText()}, " +
                        "timeoutMs=${options.timeoutMs}"
                )
            }

            delay(options.pollDelayMs)
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
        }
    }

    suspend fun queryTravelRange(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): PiTravelRange {
        val normalized = axes.distinct()
        require(normalized.isNotEmpty()) { "queryTravelRange axes 不能为空" }

        val minValues = device.qTMN(normalized)
        val maxValues = device.qTMX(normalized)

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

    private suspend fun runStep(
        failFast: Boolean,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (error: Throwable) {
            if (failFast) throw error
        }
    }

    private suspend fun delayIfNeeded(delayMs: Long) {
        if (delayMs > 0L) {
            delay(delayMs)
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
