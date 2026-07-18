package org.jason.pi.gcs.hexapod

import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.pitools.PiTools

/** 基于 PI GCS 的六轴定位器实现。 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    private val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
) : PiHexapodPort {

    init {
        require(axes.isNotEmpty()) { "PI Hexapod axes 不能为空" }
        require(axes.size == axes.distinct().size) {
            "PI Hexapod axes 不能重复: $axes"
        }
    }

    override suspend fun connect() {
        if (device.isOpen) return

        try {
            device.connect()
            // 用所有 PI 控制器都支持的 *IDN? 验证连接确实可收发，而不只是 TCP 已建立。
            device.qIDN()
        } catch (error: Throwable) {
            device.close()
            throw error
        }
    }

    override suspend fun disconnect() {
        close()
    }

    override suspend fun identify(): String {
        ensureConnected()
        return device.qIDN()
    }

    override suspend fun startup(reference: Boolean) {
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

        check(commandValues.isNotEmpty()) {
            "目标位姿没有可发送到控制器的轴: $pose"
        }

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
            .filterValues { it != 0.0 }

        if (commandDeltas.isEmpty()) return

        device.moveRelative(commandDeltas)

        if (wait) {
            waitOnTarget()
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()
        return unitConfig.fromCommandValues(device.qPOS(axes))
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
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

    /** 返回控制器命令单位下的行程范围。 */
    suspend fun queryCommandTravelRange(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        ensureConnected()
        return PiTools.queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    override fun close() {
        device.close()
    }

    private fun ensureConnected() {
        check(device.isOpen) {
            "PI GCS Hexapod 尚未连接"
        }
    }
}
