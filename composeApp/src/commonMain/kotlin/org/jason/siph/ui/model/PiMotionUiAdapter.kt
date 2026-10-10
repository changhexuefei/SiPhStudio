package org.jason.siph.ui.model

/**
 * Presentation-only boundary for PI motion state events.
 *
 * Keep transport/controller types out of commonMain; the JVM runtime must
 * forward its actual StateFlow transitions through this mapper.
 */
enum class PiUiMotionPhase {
    Disconnected, Ready, Moving, Stopping, Fault, RecoveryRequired
}

fun PositionerUiState.withPiMotionPhase(
    phase: PiUiMotionPhase,
    reason: String? = null
): PositionerUiState = copy(
    requiresVerifiedPiState = true,
    piMotionPhase = phase.name,
    isMoving = phase == PiUiMotionPhase.Moving || phase == PiUiMotionPhase.Stopping,
    errorMessage = if (phase == PiUiMotionPhase.Fault ||
        phase == PiUiMotionPhase.RecoveryRequired
    ) reason ?: "PI motion recovery required" else null
)
