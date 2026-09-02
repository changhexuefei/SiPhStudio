package org.jason.siph.hardware.pi

import org.jason.pi.gcs.hexapod.PiHexapodDelta
import org.jason.pi.gcs.hexapod.PiHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort

/**
 * 将 JVM PI GCS 六轴端口适配到 Compose 公共业务接口。
 *
 * 两侧业务单位完全一致：
 * - X/Y/Z：um
 * - U/V/W：deg
 *
 * GCS 的 mm/um 转换只允许发生在 pi-gcs-kotlin 的 [PiHexapodPort] 实现内部。
 */
class PiOpticalPositionerAdapter(
    private val delegate: PiHexapodPort
) : OpticalPositionerPort {

    override suspend fun connect() = delegate.connect()

    override suspend fun disconnect() = delegate.disconnect()

    override suspend fun identify(): String = delegate.identify()

    override suspend fun startup(reference: Boolean) {
        delegate.startup(reference)
    }

    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) {
        delegate.moveTo(
            pose = pose.toPiPose(),
            wait = wait
        )
    }

    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) {
        delegate.moveBy(
            delta = delta.toPiDelta(),
            wait = wait
        )
    }

    override suspend fun currentPose(): OpticalPose {
        return delegate.currentPose().toOpticalPose()
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        delegate.waitOnTarget(timeoutMs)
    }

    override suspend fun stop() = delegate.stop()

    override suspend fun moveToSafePose() = delegate.moveToSafePose()
}

private fun OpticalPose.toPiPose(): PiHexapodPose = PiHexapodPose(
    xUm = xUm,
    yUm = yUm,
    zUm = zUm,
    uDeg = uDeg,
    vDeg = vDeg,
    wDeg = wDeg
)

private fun OpticalDelta.toPiDelta(): PiHexapodDelta = PiHexapodDelta(
    dxUm = dxUm,
    dyUm = dyUm,
    dzUm = dzUm,
    duDeg = duDeg,
    dvDeg = dvDeg,
    dwDeg = dwDeg
)

private fun PiHexapodPose.toOpticalPose(): OpticalPose = OpticalPose(
    xUm = xUm,
    yUm = yUm,
    zUm = zUm,
    uDeg = uDeg,
    vDeg = vDeg,
    wDeg = wDeg
)
