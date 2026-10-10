package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.flow.StateFlow

/**
 * Mandatory application-facing command boundary for physical PI motion.
 *
 * Clients should not retain another writable reference to [port]. This layer
 * rejects unverified controller state even if a caller bypasses Compose.
 * It does NOT replace the port's own fault latch or hardware emergency stop.
 */
class PiMotionCommandGateway(
    private val port: PiHexapodPort,
    private val motionState: StateFlow<PiMotionState>
) {
    private fun requireReady() {
        check(motionState.value.phase == PiMotionPhase.Ready) {
            "PI movement denied: controller state is ${motionState.value.phase}"
        }
    }

    suspend fun moveTo(pose: PiHexapodPose, wait: Boolean = true) {
        requireReady()
        port.moveTo(pose, wait)
    }

    suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean = true) {
        requireReady()
        port.moveBy(delta, wait)
    }

    suspend fun moveToSafePose() {
        requireReady()
        port.moveToSafePose()
    }

    /** Stop always remains callable, including during a fault. */
    suspend fun stop() = port.stop()

    /** Recovery remains a separate, operator-controlled controller operation. */
}
