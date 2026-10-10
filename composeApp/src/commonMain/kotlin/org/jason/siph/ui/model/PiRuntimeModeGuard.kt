package org.jason.siph.ui.model

/** Explicit application mode; physical PI commands must not use demo reducers. */
enum class PiRuntimeMode { Demo, Physical }

/**
 * Until physical controller lifecycle is wired to Compose, fail closed rather
 * than reporting simulated movement, connection or Stop as hardware success.
 */
fun CouplingToolUiState.rejectUnwiredPhysicalAction(): CouplingToolUiState = copy(
    positioner = positioner.copy(
        connected = false,
        requiresVerifiedPiState = true,
        piMotionPhase = PiUiMotionPhase.Disconnected.name,
        isMoving = false
    ),
    status = status.copy(
        isError = true,
        deviceText = "PI: Physical mode - not connected | Laser: Demo | PowerMeter: Demo",
        message = "Physical PI runtime is not connected; action denied"
    )
)
