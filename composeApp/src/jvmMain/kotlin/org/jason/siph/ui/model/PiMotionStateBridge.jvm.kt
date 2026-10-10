package org.jason.siph.ui.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiMotionPhase
import org.jason.pi.gcs.hexapod.PiMotionState

/**
 * JVM-only bridge from the real PI controller state to the common UI projection.
 *
 * The consuming application must collect this flow for its controller instance,
 * and apply each projection to its StateFlow/UI store. This does not instantiate
 * a device, connect, or issue any physical movement.
 */
fun PiGcsHexapodPort.motionUiStates(
    initial: PositionerUiState = PositionerUiState()
): Flow<PositionerUiState> = motionState.map { initial.withControllerMotionState(it) }

/** Physical-device paths always require a verified PI phase. */
fun PositionerUiState.withControllerMotionState(
    motion: PiMotionState
): PositionerUiState {
    val phase = when (motion.phase) {
        PiMotionPhase.Disconnected -> PiUiMotionPhase.Disconnected
        PiMotionPhase.Ready -> PiUiMotionPhase.Ready
        PiMotionPhase.Moving -> PiUiMotionPhase.Moving
        PiMotionPhase.Stopping -> PiUiMotionPhase.Stopping
        PiMotionPhase.Fault -> PiUiMotionPhase.Fault
        PiMotionPhase.RecoveryRequired -> PiUiMotionPhase.RecoveryRequired
    }
    return withPiMotionPhase(phase, motion.reason)
}

/**
 * Immutable UI store projection. The caller is responsible for collecting
 * controller state for the current live session, and cancelling on replacement.
 */
fun CouplingToolUiState.withControllerMotionState(
    motion: PiMotionState
): CouplingToolUiState = copy(
    positioner = positioner.withControllerMotionState(motion)
)
