package org.jason.siph.ui.model

import org.jason.siph.domain.positioner.OpticalPose
import kotlinx.coroutines.CancellationException

/**
 * Produces display-only readback from the owned physical PI session.
 * The latest controller safety projection is authoritative; successful reads
 * never synthesize Ready or grant motion permission.
 */
class PiPhysicalReadbackProjection(
    private val lifecycle: PiPhysicalConnectionLifecycle,
    private val commands: PiRuntimeMotionCommands
) {
    /**
     * UI-facing suspending refresh. A failed query invalidates previously
     * displayed identity and coordinates, without asserting disconnection or
     * physical standstill. Cancellation is propagated to the caller.
     */
    suspend fun refreshOrError(state: CouplingToolUiState): CouplingToolUiState =
        try {
            refresh(state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.copy(
                positioner = state.positioner.copy(
                    connected = false,
                    idn = null,
                    currentPose = OpticalPose.ZERO,
                    isMoving = false,
                    requiresVerifiedPiState = true,
                    piMotionPhase = PiUiMotionPhase.Disconnected.name,
                    errorMessage = "Physical PI readback is unverified"
                ),
                status = state.status.copy(
                    isError = true,
                    deviceText = "PI: physical readback unavailable | Laser: Demo | PowerMeter: Demo",
                    message = "Physical PI readback failed: " +
                        (failure.message ?: failure::class.simpleName.orEmpty())
                )
            )
        }

    suspend fun refresh(state: CouplingToolUiState): CouplingToolUiState {
        val identity = lifecycle.identify()
        val pose = lifecycle.readPose()
        val verified = commands.uiState.value
        return state.copy(
            positioner = verified.copy(
                idn = identity,
                currentPose = OpticalPose(
                    xUm = pose.xUm,
                    yUm = pose.yUm,
                    zUm = pose.zUm,
                    uDeg = pose.uDeg,
                    vDeg = pose.vDeg,
                    wDeg = pose.wDeg
                ),
                linearStepUm = state.positioner.linearStepUm,
                angleStepDeg = state.positioner.angleStepDeg
            ),
            status = state.status.copy(
                deviceText = "PI: $identity | Laser: Demo | PowerMeter: Demo",
                isError = false,
                message = "Physical PI identity and pose read successfully"
            )
        )
    }
}
