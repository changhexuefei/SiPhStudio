package org.jason.siph.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiMotionState

/**
 * Observable state for a single physical PI session. Construct a new store per
 * connected controller; close it when the device is replaced or UI disposed.
 */
class PiMotionUiStore(
    port: PiGcsHexapodPort,
    scope: CoroutineScope
) : AutoCloseable {
    private val _state = MutableStateFlow(
        PositionerUiState(requiresVerifiedPiState = true)
            .withControllerMotionState(port.motionState.value)
    )
    val state: StateFlow<PositionerUiState> = _state.asStateFlow()

    private val subscription: Job = scope.launch {
        port.motionState.collect { motion: PiMotionState ->
            _state.value = _state.value.withControllerMotionState(motion)
        }
    }

    override fun close() {
        subscription.cancel()
        _state.value = _state.value.copy(
            requiresVerifiedPiState = true,
            piMotionPhase = PiUiMotionPhase.Disconnected.name,
            connected = false
        )
    }
}
