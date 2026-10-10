package org.jason.siph.ui.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort

/**
 * Explicit physical PI connection owner for a supplied controller port.
 *
 * Never falls back to the demo reducer. Hardware selection/transport creation
 * belongs to the application composition root, not to this class.
 * A disconnect that encounters active movement/Stop is rejected by the command
 * binding before touching hardware. No implicit physical Stop is attempted.
 */
class PiPhysicalConnectionLifecycle(
    private val commands: PiRuntimeMotionCommands
) {
    private val mutex = Mutex()
    private var activePort: PiGcsHexapodPort? = null

    suspend fun connect(port: PiGcsHexapodPort) = mutex.withLock {
        check(activePort == null) { "PI physical controller already connected" }
        // Only expose a controller to the UI after the device connect succeeds.
        port.connect()
        try {
            commands.bind(port)
            activePort = port
        } catch (failure: Throwable) {
            // The transport was opened but the command/UI layer rejected ownership.
            // Fail closed: release the physical session rather than leak an
            // untracked, connected controller. Preserve the original failure.
            try {
                port.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    suspend fun disconnect() = mutex.withLock {
        val port = checkNotNull(activePort) { "No active PI physical controller" }
        // Refuse unsafe unbind before initiating a physical disconnect.
        commands.bind(null)
        activePort = null
        // An I/O failure must be propagated, never shown as success.
        port.disconnect()
    }
}
