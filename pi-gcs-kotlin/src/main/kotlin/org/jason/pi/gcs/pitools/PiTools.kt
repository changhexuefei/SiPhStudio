package org.jason.pi.gcs.pitools

import org.jason.pi.gcs.core.GcsDevice

/**
 * 类似 PIPython pitools 的 Kotlin 工具类。
 *
 * 第一版只做硅光耦光必要能力：
 * - startup
 * - waitOnTarget
 * - queryTravelRange
 */
object PiTools {

    /**
     * 启动设备。
     *
     * 注意：
     * reference 默认 false。
     * 六轴是否需要 reference，需要根据你的 PI 控制器实际情况决定。
     */
    suspend fun startup(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        enableServo: Boolean = true,
        reference: Boolean = false
    ) {
        // 尝试先停止已有运动。
        runCatching {
            device.stopAll()
        }

        if (enableServo) {
            axes.forEach { axis ->
                device.servoOn(axis)
            }
        }

        if (reference) {
            axes.forEach { axis ->
                device.reference(axis)

                waitOnTarget(
                    device = device,
                    axes = listOf(axis),
                    timeout = 60.seconds
                )
            }
        }
    }

    /**
     * 等待所有指定轴到位。
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        timeout: Duration = 10.seconds,
        pollInterval: Duration = 100.milliseconds
    ) {
        val startMs = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds

        while (true) {
            val states = device.qONT(axes)

            if (states.values.all { it }) {
                return
            }

            val elapsedMs = System.currentTimeMillis() - startMs
            if (elapsedMs > timeoutMs) {
                throw PiGcsTimeoutException(
                    "等待 PI 到位超时: axes=$axes, states=$states, timeout=$timeout"
                )
            }

            delay(pollInterval)
        }
    }

    /**
     * 查询行程范围。
     */
    suspend fun queryTravelRange(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        val min = device.qTMN(axes)
        val max = device.qTMX(axes)

        return axes.associateWith { axis ->
            min.getValue(axis)..max.getValue(axis)
        }
    }
}