package org.jason.pi.gcs.hexapod

import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.pitools.PiTools

/**
 * PI GCS based hexapod port.
 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
) : PiHexapodPort {

    private val axes: List<PiAxis> = axes.distinct().also {
        require(it.isNotEmpty()) { "PI hexapod axes must not be empty" }
    }

    override suspend fun connect() {
        device.connect()
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

        val commandValues = unitConfig
            .toCommandValues(pose)
            .filterKeys { it in axes }

        require(commandValues.isNotEmpty()) {
            "No configured PI axes are available for moveTo"
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

        val commandDeltas = unitConfig
            .toCommandDeltas(delta)
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

    /**
     * Returns the travel range in controller command units.
     */
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
