package org.jason.pi.gcs.pitools

import kotlinx.coroutines.delay
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.hexapod.PiAxis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
     * 使用 PiStartupOptions 统一管理
     */
    suspend fun startup(
        device: GcsDevice,
        options: PiStartupOptions = PiStartupOptions.DefaultForSiPh
    ) {
        require(options.axes.isNotEmpty()) { "startup axes 不能为空" }

        if (options.stopBeforeStartup) {
            runCatching {
                device.stopAll()
            }.onFailure { error ->
                if (options.failFast) throw error
            }
            delayIfNeeded(options.stopSettleDelayMs)
        }

        if (options.clearErrorBeforeStartup) {
            runCatching {
                device.qERR()
            }.onFailure { error ->
                if (options.failFast) throw error
            }
        }

        when (options.servoMode) {
            PiStartupServoMode.Keep -> {}
            PiStartupServoMode.Enable -> {
                // 🚀 优化：内部已升级为协程并发 Enable
                setServo(device, options.axes, enabled = true, failFast = options.failFast)
                delayIfNeeded(options.servoSettleDelayMs)
            }
            PiStartupServoMode.Disable -> {
                setServo(device, options.axes, enabled = false, failFast = options.failFast)
            }
        }

        // 🚀 优化：内部已升级为高效的批量 Reference
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

    /**
     * 兼容旧版 startup 调用
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
                servoMode = if (enableServo) PiStartupServoMode.Enable else PiStartupServoMode.Keep,
                referenceMode = if (reference) PiStartupReferenceMode.ReferenceAll else PiStartupReferenceMode.None,
                waitOptions = if (reference) PiWaitOptions.LongMove else PiWaitOptions.Default
            )
        )
    }

    /**
     * 🚀【重大优化】并行开启多个轴的 Servo
     * 利用 coroutineScope 结合 async 开启并行网络请求，
     * 避免串行循环下的多次 Mutex 锁排队和 RTT 累加。
     */
    suspend fun setServo(
        device: GcsDevice,
        axes: List<PiAxis>,
        enabled: Boolean,
        failFast: Boolean = true
    ) = coroutineScope {
        require(axes.isNotEmpty()) { "setServo axes 不能为空" }

        // 使用非阻塞并发处理所有轴
        axes.map { axis ->
            async {
                runCatching {
                    if (enabled) device.servoOn(axis) else device.servoOff(axis)
                }.onFailure { error ->
                    if (failFast) throw error
                }
            }
        }.awaitAll() // 等待所有轴的指令在网口全部就绪
    }

    /**
     * 对指定轴执行 Reference
     */
    suspend fun referenceAxes(
        device: GcsDevice,
        axes: List<PiAxis>,
        waitAfterEachAxis: Boolean = true,
        waitOptions: PiWaitOptions = PiWaitOptions.LongMove,
        failFast: Boolean = true
    ) {
        if (axes.isEmpty()) return

        // 💡 如果是六轴整体参考，推荐直接批量并发下发指令，而不是一个轴参考完再等下一个
        if (!waitAfterEachAxis) {
            coroutineScope {
                axes.map { axis ->
                    async {
                        runCatching { device.reference(axis) }.onFailure { if (failFast) throw it }
                    }
                }.awaitAll()
            }
            return
        }

        // 串行单轴参考路径（带单轴到位等待）
        axes.forEach { axis ->
            runCatching {
                device.reference(axis)
                waitOnTarget(device, listOf(axis), options = waitOptions)
            }.onFailure { error ->
                if (failFast) throw error
            }
        }
    }

    /**
     * 移动并等待到位
     */
    suspend fun moveAndWait(
        device: GcsDevice,
        targets: Map<PiAxis, Double>,
        waitOptions: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(targets.isNotEmpty()) { "moveAndWait targets 不能为空" }
        device.moveAbsolute(targets)
        waitOnTarget(device, axes = targets.keys.toList(), options = waitOptions)
    }

    /**
     * 🚀【高性能优化】等待所有指定轴到位
     * 结合我们先前升级的高性能多轴 qONT(axes) 批量拉取接口，
     * 循环体内完全避免了单个轴轮询的网络累加。
     */
    suspend fun waitOnTarget(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        options: PiWaitOptions = PiWaitOptions.Default
    ) {
        require(axes.isNotEmpty()) { "waitOnTarget axes 不能为空" }

        delayIfNeeded(options.preDelayMs)
        val startMs = nowMs()

        while (true) {
            // 💡 这里的 qONT 已经享受了 Ktor queryLines 的单报文多行优化
            val states = device.qONT(axes)

            // 优化：利用简单的局部标志位判断，减少不必要的对象生成
            var allOnTarget = true
            for (value in states.values) {
                if (!value) {
                    allOnTarget = false
                    break
                }
            }

            if (allOnTarget) {
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

            // 非阻塞挂起，平滑释放当前计算线程，不影响 Jetpack Compose 界面的 60帧/120帧 刷新
            delay(options.pollDelayMs)
        }
    }

    /**
     * 兼容旧版 waitOnTarget 调用
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

    suspend fun stopAll(device: GcsDevice, clearErrorAfterStop: Boolean = false) {
        device.stopAll()
        if (clearErrorAfterStop) {
            runCatching { device.qERR() }
        }
    }

    /**
     * 查询行程范围：通过批量 qTMN 和 qTMX 提升速度
     */
    suspend fun queryTravelRange(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): PiTravelRange {
        require(axes.isNotEmpty()) { "queryTravelRange axes 不能为空" }

        // 🚀 此处得益于前面的优化，两个网络请求包即可搞定全部轴边界查询
        val minValues = device.qTMN(axes)
        val maxValues = device.qTMX(axes)

        return PiTravelRange.fromMinMax(minValues = minValues, maxValues = maxValues)
    }

    suspend fun queryTravelRangeMap(
        device: GcsDevice,
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return queryTravelRange(device, axes).toClosedRangeMap()
    }

    private suspend fun delayIfNeeded(delayMs: Long) {
        if (delayMs > 0L) {
            delay(delayMs)
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun List<PiAxis>.toAxisText(): String = joinToString(" ") { it.code }

    private fun Map<PiAxis, Boolean>.toStateText(): String {
        return entries.joinToString(", ") { (axis, onTarget) -> "${axis.code}=$onTarget" }
    }
}