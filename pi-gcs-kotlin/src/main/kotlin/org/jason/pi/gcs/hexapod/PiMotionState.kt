package org.jason.pi.gcs.hexapod

/** Observable logical safety state; it does not prove physical standstill. */
enum class PiMotionPhase {
    Disconnected, Ready, Moving, Stopping, Fault, RecoveryRequired
}

data class PiMotionState(
    val phase: PiMotionPhase = PiMotionPhase.Disconnected,
    val reason: String? = null
) {
    val canMove: Boolean get() = phase == PiMotionPhase.Ready
}
