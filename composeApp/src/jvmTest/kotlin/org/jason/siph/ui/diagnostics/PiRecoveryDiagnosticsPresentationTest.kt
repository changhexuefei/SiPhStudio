package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiRecoveryDiagnosticsPresentationTest {
    private fun dto(decision: String, status: String = "CHANGED_OR_INVALID") =
        PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
            schemaVersion = 1,
            status = status,
            decision = decision,
            reasonCodes = emptyList(),
            failureCategory = "NONE",
            failedSample = null
        )

    @Test fun operatorReviewDoesNotSuggestAutomaticUnlock() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("REQUIRE_OPERATOR"))
        assertEquals("Operator review required", view.headline)
        assertTrue(view.guidance.contains("Do not remove locks"))
    }

    @Test fun blockedInspectionNeverSuggestsResume() {
        val view = PiRecoveryDiagnosticsPresentation.from(
            dto("BLOCK", "INSPECTION_BLOCKED")
        )
        assertEquals("Inspection blocked", view.headline)
        assertTrue(view.guidance.contains("Do not resume"))
    }

    @Test fun manualReviewIsNotPermission() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("VALIDATE_FOR_MANUAL_REVIEW"))
        assertTrue(view.guidance.contains("Manual review"))
        assertTrue(view.guidance.contains("separate approval"))
    }

    @Test fun malformedDtoFailsClosed() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("APPROVED"))
        assertEquals("Inspection blocked", view.headline)
        assertEquals("INVALID_DIAGNOSTIC_CONTRACT", view.detail)
    }

    @Test fun unexpectedInputFailureDoesNotLeakRawException() {
        val view = PiRecoveryDiagnosticsPresentation.inputFailure("private filesystem exception")
        assertEquals("INSPECTION_FAILED", view.detail)
        assertTrue(view.guidance.contains("operator review"))
    }
}
