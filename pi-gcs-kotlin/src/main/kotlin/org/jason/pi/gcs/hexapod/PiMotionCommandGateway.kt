package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-facing single-flight command boundary for a PI motion controller.
 *
 * Stop immediately latches the gateway before issuing STP; its request does not
 * wait for the motion mutex. Transport-level interruption is NOT guaranteed.
 * Underlying port access must not be shared with other write-capable callers.
 */
class PiMotionCommandGateway(
    private val port: PiHexapodPort,
    private val motionState: StateFlow<PiMotionState>
) {
    private val movementMutex = Mutex()
    @Volatile private var stopRequested = false

    private fun requireReady() {
        check(!stopRequested && motionState.value.phase == PiMotionPhase.Ready) {
            "PI movement denied: stop requested or controller state is ${motionState.value.phase}"
        }
    }

    private suspend fun move(block: suspend () -> Unit) {
        // Never queue additional movement behind another pending motion.
        check(movementMutex.tryLock()) { "PI movement denied: another motion command is running" }
        try {
            requireReady()
            block()
        } finally {
            movementMutex.unlock()
        }
    }

    suspend fun moveTo(pose: PiHexapodPose, wait: Boolean = true) =
        move { port.moveTo(pose, wait) }

    suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean = true) =
        move { port.moveBy(delta, wait) }

    suspend fun moveToSafePose() = move { port.moveToSafePose() }

    /** Stop stays callable while moving/faulted and invalidates new motion at entry. */
    suspend fun stop() {
        stopRequested = true
        port.stop()
    }

    /**
     * Does not silently reset the Stop latch. Create a fresh gateway only
     * after the underlying controller's explicit recovery verification.
     */
}
