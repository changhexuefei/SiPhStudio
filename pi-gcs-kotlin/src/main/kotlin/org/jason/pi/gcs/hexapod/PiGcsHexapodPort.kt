package org.jason.pi.gcs.hexapod


import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.pitools.PiTools

/**
 * 基于 PI GCS 的六轴实现。
 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    private val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
) : PiHexapodPort {

    private var connected: Boolean = false

    override suspend fun connect() {
        device.connect()
        connected = true
    }

    override suspend fun disconnect() {
        close()
    }

    override suspend fun identify(): String {
        ensureConnected()
        return device.qIDN()
    }

    override suspend fun startup(
        reference: Boolean
    ) {
        ensureConnected()

        PiTools.startup(
            device = device,
            axes = axes,
            enableServo = true,
            reference = reference
        )
    }

    override suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean
    ) {
        ensureConnected()

        val commandValues = unitConfig.toCommandValues(pose)
            .filterKeys { it in axes }

        device.moveAbsolute(commandValues)

        if (wait) {
            waitOnTarget()
        }
    }

    override suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean
    ) {
        ensureConnected()

        val commandDeltas = unitConfig.toCommandDeltas(delta)
            .filterKeys { it in axes }

        device.moveRelative(commandDeltas)

        if (wait) {
            waitOnTarget()
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()

        val commandValues = device.qPOS(axes)
        return unitConfig.fromCommandValues(commandValues)
    }

    override suspend fun waitOnTarget(
        timeoutMs: Long
    ) {
        ensureConnected()

        PiTools.waitOnTarget(
            device = device,
            axes = axes,
            timeoutMs = timeoutMs,
            pollDelayMs = 100L
        )
    }

    override suspend fun stop() {
        ensureConnected()
        device.stopAll()
    }

    override suspend fun moveToSafePose() {
        moveTo(
            pose = safePose,
            wait = true
        )
    }

    /**
     * 查询命令单位下的行程范围。
     *
     * 注意：
     * 返回的是控制器命令单位，不是业务层 um。
     */
    suspend fun queryCommandTravelRange(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        ensureConnected()

        return PiTools.queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    override fun close() {
        connected = false
        device.close()
    }

    private fun ensureConnected() {
        check(connected) {
            "PI GCS Hexapod 尚未连接"
        }
    }
}
