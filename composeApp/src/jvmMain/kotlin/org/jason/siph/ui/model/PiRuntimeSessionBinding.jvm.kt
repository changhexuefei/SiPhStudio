package org.jason.siph.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiMotionPhase
import org.jason.pi.gcs.hexapod.PiMotionState

/**
 * JVM-side binding for the current PI controller session.
 *
 * Changing the binding only manages UI subscriptions. The caller remains
 * responsible for stopping/closing the former physical controller safely.
 * No hardware commands are issued by this class.
 */
class PiRuntimeSessionBinding(private val scope: CoroutineScope) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var subscription: Job? = null
    private val _state = MutableStateFlow(disconnected())
    val state: StateFlow<PositionerUiState> = _state.asStateFlow()

    /** A null port explicitly unbinds the current controller. */
    fun bind(port: PiGcsHexapodPort?) {
        synchronized(lock) {
            check(!closed) { "PI runtime UI binding is closed" }
            generation++
            subscription?.cancel()
            subscription = null
            // Deny motion while the controller session is changing.
            _state.value = disconnected()
            if (port != null) {
                val currentGeneration = generation
                _state.value = projected(port.motionState.value)
                subscription = scope.launch {
                    port.motionState.collect { motion ->
                        synchronized(lock) {
                            if (!closed && generation == currentGeneration) {
                                _state.value = projected(motion)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            subscription?.cancel()
            subscription = null
            _state.value = disconnected()
        }
    }

    private fun projected(motion: PiMotionState): PositionerUiState =
        PositionerUiState(
            connected = motion.phase != PiMotionPhase.Disconnected,
            requiresVerifiedPiState = true
        ).withControllerMotionState(motion)

    private fun disconnected(): PositionerUiState =
        PositionerUiState(requiresVerifiedPiState = true)
            .withControllerMotionState(PiMotionState(PiMotionPhase.Disconnected))
}
