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
    private var activePort: PiGcsHexapodPort? = null
    private var gateway: PiMotionCommandGateway? = null

    fun bind(port: PiGcsHexapodPort?) {
        synchronized(lock) {
            check(!closed) { "PI command binding is closed" }
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

    private fun readyGateway(): PiMotionCommandGateway = synchronized(lock) {
        check(!closed && activePort != null) { "No active PI controller session" }
        check(activePort!!.motionState.value.phase == PiMotionPhase.Ready &&
            uiBinding.state.value.motionAllowed) { "PI controller not verified Ready" }
        checkNotNull(gateway) { "No active PI motion command gateway" }
    }

    suspend fun moveTo(pose: PiHexapodPose, wait: Boolean = true) =
        readyGateway().moveTo(pose, wait)

    suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean = true) =
        readyGateway().moveBy(delta, wait)

    suspend fun moveToSafePose() = readyGateway().moveToSafePose()

    /** Stop remains callable even when Ready permission is absent. */
    suspend fun stop() {
        val active = synchronized(lock) {
            check(!closed) { "PI command binding is closed" }
            checkNotNull(gateway) { "No active PI controller session" }
        }
        active.stop()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            gateway = null
            activePort = null
            uiBinding.close()
        }
    }
}
