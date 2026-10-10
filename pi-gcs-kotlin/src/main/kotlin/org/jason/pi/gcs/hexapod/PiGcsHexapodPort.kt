package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Volatile private var recoveryRequired = false

    override suspend fun connect() {
        check(!recoveryRequired) { "PI motion recovery required; reconnect alone cannot unlock movement" }
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

        guardedMotion {
            device.moveAbsolute(commandValues)
            if (wait) waitForTarget()
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

        guardedMotion {
            device.moveRelative(commandDeltas)
            if (wait) waitForTarget()
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()
        return unitConfig.fromCommandValues(device.qPOS(axes))
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        ensureConnected()
        guardedMotion { waitForTarget(timeoutMs) }
    }

    override suspend fun stop() {
        recoveryRequired = true
        if (device.isOpen) {
            try { device.stopAll() } finally { device.close() }
        }
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

    private suspend fun waitForTarget(timeoutMs: Long = 10_000L) {
        PiTools.waitOnTarget(device, axes, timeoutMs, pollDelayMs = 100L)
    }

    private suspend fun guardedMotion(block: suspend () -> Unit) {
        ensureConnected()
        try {
            block()
        } catch (failure: Throwable) {
            recoveryRequired = true
            withContext(NonCancellable) {
                try { withTimeout(2_000L) { if (device.isOpen) device.stopAll() } }
                catch (_: Exception) { /* Lost communication cannot confirm physical stop. */ }
                finally { device.close() }
            }
            throw failure
        }
    }

    private fun ensureConnected() {
        check(!recoveryRequired) { "PI motion recovery required; motion locked" }
        check(device.isOpen) {
            "PI GCS Hexapod 尚未连接"
        }
    }
}
