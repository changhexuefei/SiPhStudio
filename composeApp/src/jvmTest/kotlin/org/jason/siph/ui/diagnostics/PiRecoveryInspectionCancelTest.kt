package org.jason.siph.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiRecoveryInspectionCancelTest {
    @Test fun cancellationInvalidatesOldResultAndAllowsNewInspection() {
        val epochs = PiRecoveryInspectionEpoch()
        val first = epochs.begin()
        epochs.invalidate()
        assertFalse(epochs.isCurrent(first))
        assertFalse(epochs.complete(first))
        val second = epochs.begin()
        assertTrue(epochs.isCurrent(second))
        assertTrue(epochs.complete(second))
    }

    @Test fun cancellationMessageIsSafeAndNotApproval() {
        val view = PiRecoveryDiagnosticsPresentation.inputFailure("INSPECTION_CANCELLED")
        assertEquals("Inspection cancelled", view.headline)
        assertEquals("INSPECTION_CANCELLED", view.detail)
        assertTrue(view.guidance.contains("not changed"))
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.INFORMATIONAL, view.severity)
    }

    @Test fun arbitraryFailureTextCannotLeakIntoPresentation() {
        val view = PiRecoveryDiagnosticsPresentation.inputFailure("private/path/to/evidence")
        assertEquals("INSPECTION_FAILED", view.detail)
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED, view.severity)
    }
}
