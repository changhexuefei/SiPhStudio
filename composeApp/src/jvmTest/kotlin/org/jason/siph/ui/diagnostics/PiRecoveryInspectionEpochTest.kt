package org.jason.siph.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiRecoveryInspectionEpochTest {
    @Test fun oldCompletionCannotReplaceNewerRequest() {
        val epochs = PiRecoveryInspectionEpoch()
        val first = epochs.begin()
        val second = epochs.begin()
        assertFalse(epochs.isCurrent(first))
        assertFalse(epochs.complete(first))
        assertTrue(epochs.isCurrent(second))
        assertTrue(epochs.complete(second))
    }

    @Test fun changingInputInvalidatesInFlightInspection() {
        val epochs = PiRecoveryInspectionEpoch()
        val running = epochs.begin()
        epochs.invalidate()
        assertFalse(epochs.isCurrent(running))
        assertFalse(epochs.complete(running))
        val next = epochs.begin()
        assertTrue(epochs.isCurrent(next))
    }

    @Test fun leavingPanelDoesNotLetLateCompletionUpdateState() {
        val epochs = PiRecoveryInspectionEpoch()
        val running = epochs.begin()
        epochs.invalidate()
        assertFalse(epochs.complete(running))
    }

    @Test fun repeatedRefreshUsesDistinctGenerations() {
        val epochs = PiRecoveryInspectionEpoch()
        val first = epochs.begin()
        assertTrue(epochs.complete(first))
        val next = epochs.begin()
        assertFalse(epochs.complete(first))
        assertTrue(epochs.complete(next))
    }
}
