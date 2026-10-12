package org.jason.siph.ui.diagnostics

/**
 * Tiny deterministic state gate for async read-only diagnostics.
 * Cancelling a coroutine does not necessarily stop blocking filesystem I/O;
 * a stale completion must not change the current UI state.
 */
internal class PiRecoveryInspectionEpoch {
    private var generation: Long = 0
    private var active: Long? = null

    fun begin(): Long {
        val id = ++generation
        active = id
        return id
    }

    fun invalidate() {
        generation++
        active = null
    }

    fun isCurrent(id: Long): Boolean = active == id

    fun complete(id: Long): Boolean {
        if (active != id) return false
        active = null
        return true
    }
}
