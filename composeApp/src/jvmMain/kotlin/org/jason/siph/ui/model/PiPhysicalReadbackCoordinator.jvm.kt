package org.jason.siph.ui.model

/**
 * UI-side readback lease: only the newest request may publish its result.
 *
 * Call invalidate() whenever the Physical mode or selected PI session changes.
 * A null result means the caller must discard the obsolete completion.
 * Hardware queries are delegated to PiPhysicalReadbackProjection; this class
 * does not open sessions, send motion commands or infer Ready.
 */
class PiPhysicalReadbackCoordinator(
    private val query: suspend (CouplingToolUiState) -> CouplingToolUiState
) {
    private val lock = Any()
    private var generation = 0L
    private var closed = false

    fun invalidate() {
        synchronized(lock) {
            generation++
        }
    }

    suspend fun refresh(state: CouplingToolUiState): CouplingToolUiState? {
        val issued = synchronized(lock) {
            check(!closed) { "PI readback coordinator closed" }
            ++generation
        }
        val result = query(state)
        return synchronized(lock) {
            if (!closed && issued == generation) result else null
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            generation++
        }
    }
}
