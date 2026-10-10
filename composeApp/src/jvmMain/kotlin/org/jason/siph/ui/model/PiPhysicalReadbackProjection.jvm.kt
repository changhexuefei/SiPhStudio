package org.jason.siph.ui.model

import org.jason.siph.domain.positioner.OpticalPose

/**
 * Produces display-only readback from the owned physical PI session.
 * The latest controller safety projection is authoritative; successful reads
 * never synthesize Ready or grant motion permission.
 */
class PiPhysicalReadbackProjection(
    private val lifecycle: PiPhysicalConnectionLifecycle,
    private val commands: PiRuntimeMotionCommands
) {
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
