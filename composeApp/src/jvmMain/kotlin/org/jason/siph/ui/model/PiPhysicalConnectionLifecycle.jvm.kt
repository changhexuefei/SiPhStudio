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

    /** Read-only device identity; never use demo text as a physical ID. */
    suspend fun identify(): String = mutex.withLock {
        val port = checkNotNull(activePort) { "No active PI physical controller" }
        port.identify()
    }

    /** Read-only actual controller pose; no movement command is issued. */
    suspend fun readPose(): org.jason.pi.gcs.hexapod.PiHexapodPose = mutex.withLock {
        val port = checkNotNull(activePort) { "No active PI physical controller" }
        port.currentPose()
    }

    suspend fun disconnect() = mutex.withLock {
        val port = checkNotNull(activePort) { "No active PI physical controller" }
        // Refuse unsafe unbind before initiating a physical disconnect.
        commands.bind(null)
        // Retain ownership until physical disconnect completes. If transport
        // close fails, do not allow a new controller to connect; the operator
        // can retry disconnect without re-enabling motion in the UI.
        port.disconnect()
        activePort = null
    }
}
