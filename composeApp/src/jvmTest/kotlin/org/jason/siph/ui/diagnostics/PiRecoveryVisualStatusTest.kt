package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRecoveryVisualStatusTest {
    private fun dto(decision: String, status: String = "CHANGED_OR_INVALID") =
        PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(1, status, decision, emptyList(), "NONE", null)

    @Test fun blockedIsCritical() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("BLOCK", "INSPECTION_BLOCKED"))
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED, view.severity)
    }

    @Test fun orphanedPublicationRequiresOperator() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("REQUIRE_OPERATOR"))
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.OPERATOR_ACTION, view.severity)
    }

    @Test fun manualReviewIsNotSuccess() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("VALIDATE_FOR_MANUAL_REVIEW"))
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.REVIEW_REQUIRED, view.severity)
    }

    @Test fun noBundleIsOnlyInformational() {
        val view = PiRecoveryDiagnosticsPresentation.from(dto("NO_BUNDLE"))
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.INFORMATIONAL, view.severity)
    }

    @Test fun malformedDataAndInputRemainBlocked() {
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED,
            PiRecoveryDiagnosticsPresentation.from(dto("APPROVED")).severity)
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED,
            PiRecoveryDiagnosticsPresentation.inputFailure("INVALID_PATH").severity)
    }
}
