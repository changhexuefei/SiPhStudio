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

/**
 * Physical mode accepts only local presentation/configuration edits.
 * Device commands and derived hardware-state mutations fail closed.
 */
fun CouplingToolUiState.reduceUnwiredPhysicalAction(
    action: CouplingToolAction
): CouplingToolUiState {
    val updated = when (action) {
        is CouplingToolAction.SelectPage -> copy(selectedPage = action.page)
        is CouplingToolAction.UpdateLinearStep ->
            copy(positioner = positioner.copy(linearStepUm = action.valueUm))
        is CouplingToolAction.UpdateAngleStep ->
            copy(positioner = positioner.copy(angleStepDeg = action.valueDeg))
        is CouplingToolAction.UpdateCouplingConfig ->
            copy(coupling = coupling.copy(config = action.config))
        is CouplingToolAction.UpdateVirtualPivot ->
            copy(coupling = coupling.copy(config = coupling.config.copy(
                virtualPivotPoint = action.pivot
            )))
        else -> return rejectUnwiredPhysicalAction()
    }
    // Preserve a fail-closed projection regardless of stale UI state supplied.
    return updated.copy(
        positioner = updated.positioner.copy(
            connected = false,
            requiresVerifiedPiState = true,
            piMotionPhase = PiUiMotionPhase.Disconnected.name,
            isMoving = false
        )
    )
}
