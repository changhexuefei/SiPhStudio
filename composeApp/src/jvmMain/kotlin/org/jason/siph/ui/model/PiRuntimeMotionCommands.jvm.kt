package org.jason.siph.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodDelta
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiMotionCommandGateway
import org.jason.pi.gcs.hexapod.PiMotionPhase

/**
 * JVM application command boundary for the currently selected PI controller.
 *
 * Session replacement only changes routing and UI state. Hardware owners must
 * stop/close old sessions separately; never share write-capable port access.
 */
class PiRuntimeMotionCommands(scope: CoroutineScope) : AutoCloseable {
    private val lock = Any()
    private val uiBinding = PiRuntimeSessionBinding(scope)
    val uiState: StateFlow<PositionerUiState> get() = uiBinding.state
    private var closed = false
    private var motionInFlight = false
    private var stopInFlight = false
    private var activePort: PiGcsHexapodPort? = null
    private var gateway: PiMotionCommandGateway? = null

    fun bind(port: PiGcsHexapodPort?) {
        synchronized(lock) {
            check(!closed) { "PI command binding is closed" }
            check(!motionInFlight && !stopInFlight) { "Cannot replace PI session during motion or Stop" }
            // First invalidate the old command route; then swap the UI projection.
            gateway = null
            activePort = null
            uiBinding.bind(port)
            if (port != null) {
                activePort = port
                gateway = PiMotionCommandGateway(port, port.motionState)
            }
        }
    }

    private suspend fun dispatchMotion(block: suspend (PiMotionCommandGateway) -> Unit) {
        val selected = synchronized(lock) {
            check(!closed && activePort != null) { "No active PI controller session" }
            check(!motionInFlight && !stopInFlight) { "PI motion or Stop command already active" }
            check(activePort!!.motionState.value.phase == PiMotionPhase.Ready &&
                uiBinding.state.value.motionAllowed) { "PI controller not verified Ready" }
            motionInFlight = true
            checkNotNull(gateway)
        }
        try {
            block(selected)
        } finally {
            synchronized(lock) { motionInFlight = false }
        }
    }

    suspend fun moveTo(pose: PiHexapodPose, wait: Boolean = true) =
        dispatchMotion { it.moveTo(pose, wait) }

    suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean = true) =
        dispatchMotion { it.moveBy(delta, wait) }

    suspend fun moveToSafePose() = dispatchMotion { it.moveToSafePose() }

    /** Stop remains callable even when Ready permission is absent. */
    suspend fun stop() {
        val active = synchronized(lock) {
            check(!closed) { "PI command binding is closed" }
            check(!stopInFlight) { "PI Stop already in progress" }
            val selected = checkNotNull(gateway) { "No active PI controller session" }
            stopInFlight = true
            selected
        }
        try {
            active.stop()
        } finally {
            synchronized(lock) { stopInFlight = false }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            check(!motionInFlight && !stopInFlight) { "Cannot dispose PI command binding during motion or Stop" }
            closed = true
            gateway = null
            activePort = null
            uiBinding.close()
        }
    }
}
