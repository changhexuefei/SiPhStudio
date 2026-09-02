package org.jason.siph.domain.simulation

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.PivotAwareOpticalPositionerPort
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.domain.positioner.plus
import kotlin.math.sin

/**
 * 设备离线时使用的六轴定位器模拟器。
 *
 * 它不是一次性生成 UI 假数据，而是按真实接口逐次移动和读位置，因此可以完整验证
 * 协程取消、状态流、搜索路径和绘图刷新。
 */
class DemoOpticalPositioner(
    initialPose: OpticalPose = OpticalPose.ZERO,
    private val safePose: OpticalPose = OpticalPose.ZERO,
    private val moveDelayMs: Long = 4L
) : PivotAwareOpticalPositionerPort {

    private val mutex = Mutex()
    private var connected = false
    private var pose = initialPose
    private var stopRequested = false

    override suspend fun connect() {
        delay(30L)
        mutex.withLock {
            connected = true
            stopRequested = false
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            connected = false
            stopRequested = true
        }
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "Demo PI Hexapod Controller, X/Y/Z/U/V/W"
    }

    override suspend fun startup(reference: Boolean) {
        ensureConnected()
        if (reference) {
            moveTo(OpticalPose.ZERO, wait = true)
        }
    }

    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) {
        ensureConnected()
        validatePose(pose)

        mutex.withLock { stopRequested = false }
        if (wait && moveDelayMs > 0L) {
            delay(moveDelayMs)
        }

        mutex.withLock {
            if (!stopRequested) {
                this.pose = pose
            }
        }
    }

    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) {
        val target = mutex.withLock {
            ensureConnectedLocked()
            pose + delta
        }
        moveTo(target, wait)
    }

    override suspend fun moveByAroundPivot(
        delta: OpticalDelta,
        pivot: VirtualPivotPoint,
        wait: Boolean
    ) {
        ensureConnected()

        // Demo 环境保留角度变化，并加入极小的线性补偿，以便验证虚拟枢轴路径。
        val compensationScale = if (pivot.enabled) 0.01 else 0.0
        moveBy(
            delta.copy(
                dxUm = delta.dxUm - delta.dvDeg * compensationScale,
                dyUm = delta.dyUm + delta.duDeg * compensationScale,
                dzUm = delta.dzUm + delta.dwDeg * compensationScale
            ),
            wait = wait
        )
    }

    override suspend fun currentPose(): OpticalPose {
        return mutex.withLock {
            ensureConnectedLocked()
            pose
        }
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        ensureConnected()
        require(timeoutMs > 0L) { "timeoutMs 必须大于 0" }
    }

    override suspend fun stop() {
        mutex.withLock {
            stopRequested = true
        }
    }

    override suspend fun moveToSafePose() {
        moveTo(safePose, wait = true)
    }

    private suspend fun ensureConnected() {
        mutex.withLock { ensureConnectedLocked() }
    }

    private fun ensureConnectedLocked() {
        check(connected) { "Demo positioner 尚未连接" }
    }

    private fun validatePose(value: OpticalPose) {
        require(
            listOf(
                value.xUm,
                value.yUm,
                value.zUm,
                value.uDeg,
                value.vDeg,
                value.wDeg
            ).all { it.isFinite() }
        ) {
            "Positioner pose 必须全部为有限数: $value"
        }
    }
}

/**
 * 以六维高斯耦合面模拟功率计。
 *
 * 峰值位置故意不在原点，启动自动耦光后可以看到真实的螺旋粗扫和精调过程。
 */
class DemoOpticalPowerMeter(
    private val poseProvider: suspend () -> OpticalPose,
    private val optimumPose: OpticalPose = OpticalPose(
        xUm = 12.0,
        yUm = -8.0,
        zUm = 2.5,
        uDeg = 0.04,
        vDeg = -0.03,
        wDeg = 0.02
    ),
    private val peakPowerDbm: Double = -6.2,
    private val floorPowerDbm: Double = -75.0
) : OpticalPowerMeterPort {

    private var connected = false
    private var wavelengthNm = 1550.0
    private var readIndex = 0L

    override suspend fun connect() {
        delay(20L)
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "Demo Optical Power Meter"
    }

    override suspend fun setWavelengthNm(wavelengthNm: Double, channel: Int) {
        ensureConnected()
        require(wavelengthNm.isFinite() && wavelengthNm > 0.0) {
            "wavelengthNm 必须为正的有限数"
        }
        require(channel > 0) { "channel 必须大于 0" }
        this.wavelengthNm = wavelengthNm
    }

    override suspend fun readPowerDbm(channel: Int): Double {
        ensureConnected()
        require(channel > 0) { "channel 必须大于 0" }

        val pose = poseProvider()
        val normalizedDistance =
            squared(pose.xUm - optimumPose.xUm, 7.5) +
                squared(pose.yUm - optimumPose.yUm, 6.5) +
                squared(pose.zUm - optimumPose.zUm, 4.0) +
                squared(pose.uDeg - optimumPose.uDeg, 0.09) +
                squared(pose.vDeg - optimumPose.vDeg, 0.09) +
                squared(pose.wDeg - optimumPose.wDeg, 0.07)

        readIndex += 1L
        val deterministicNoise = 0.018 * sin(readIndex * 1.618 + wavelengthNm * 0.001)
        val power = peakPowerDbm - 4.343 * normalizedDistance + deterministicNoise
        return power.coerceAtLeast(floorPowerDbm)
    }

    private fun ensureConnected() {
        check(connected) { "Demo power meter 尚未连接" }
    }

    private fun squared(delta: Double, sigma: Double): Double {
        val normalized = delta / sigma
        return normalized * normalized
    }
}
