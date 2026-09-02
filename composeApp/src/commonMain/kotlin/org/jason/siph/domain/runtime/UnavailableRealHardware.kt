package org.jason.siph.domain.runtime

import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort

/**
 * Real 模式尚未注入具体设备驱动时使用的安全占位实现。
 * 任何连接或运动请求都会明确失败，而不是意外回退到 Demo 设备。
 */
class UnavailableRealPositioner : OpticalPositionerPort {
    private fun unavailable(): Nothing = error(
        "Real positioner adapter is not configured in the Koin hardware module"
    )

    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()
    override suspend fun startup(reference: Boolean) = unavailable()
    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) = unavailable()
    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) = unavailable()
    override suspend fun currentPose(): OpticalPose = unavailable()
    override suspend fun waitOnTarget(timeoutMs: Long) = unavailable()
    override suspend fun stop() = Unit
    override suspend fun moveToSafePose() = unavailable()
}

class UnavailableRealPowerMeter : OpticalPowerMeterPort {
    private fun unavailable(): Nothing = error(
        "Real optical power meter adapter is not configured in the Koin hardware module"
    )

    override suspend fun connect() = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable()

    override suspend fun setWavelengthNm(
        wavelengthNm: Double,
        channel: Int
    ) = unavailable()

    override suspend fun readPowerDbm(channel: Int): Double = unavailable()
}
