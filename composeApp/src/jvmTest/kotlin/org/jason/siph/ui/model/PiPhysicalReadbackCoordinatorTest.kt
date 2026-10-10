package org.jason.siph.ui.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class PiPhysicalReadbackCoordinatorTest {
    @Test
    fun sessionInvalidationDiscardsInFlightReadback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val coordinator = PiPhysicalReadbackCoordinator { state ->
            entered.complete(Unit)
            resume.await()
            state.copy(positioner = state.positioner.copy(idn = "STALE-PI"))
        }
        val pending = async { coordinator.refresh(CouplingToolUiState()) }
        entered.await()
        coordinator.invalidate() // e.g., Physical -> Demo or disconnect
        resume.complete(Unit)
        assertNull(pending.await())
        coordinator.close()
        Unit
    }

    @Test
    fun newerRequestWinsAndCloseRevokesOldCompletion() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = PiPhysicalReadbackCoordinator { state ->
            calls++
            if (calls == 1) {
                firstEntered.complete(Unit)
                firstRelease.await()
            }
            state.copy(positioner = state.positioner.copy(idn = "PI-$calls"))
        }
        val first = async { coordinator.refresh(CouplingToolUiState()) }
        firstEntered.await()
        val second = coordinator.refresh(CouplingToolUiState())
        assertEquals("PI-2", second?.positioner?.idn)
        firstRelease.complete(Unit)
        assertNull(first.await())
        coordinator.close()
        assertFailsWith<IllegalStateException> { coordinator.refresh(CouplingToolUiState()) }
        Unit
    }
}
