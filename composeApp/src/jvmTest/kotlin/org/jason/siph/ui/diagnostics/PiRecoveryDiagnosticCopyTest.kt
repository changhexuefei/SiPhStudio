package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class PiRecoveryDiagnosticCopyTest {
    private fun dto() = PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
        1, "INSPECTION_BLOCKED", "BLOCK", listOf("SNAPSHOT_SAMPLE_FAILED"),
        "ACCESS_DENIED", 2
    )

    @Test fun supportCopyContainsOnlyStableDiagnosticCodes() {
        val copy = PiRecoveryDiagnosticCopy.from(dto())
        assertContains(copy, "PI_OFFLINE_RECOVERY_DIAGNOSTIC_V1")
        assertContains(copy, "DECISION=BLOCK")
        assertContains(copy, "FAILURE_CATEGORY=ACCESS_DENIED")
        assertContains(copy, "FAILED_SAMPLE=2")
        assertFalse(copy.contains("C:\\"))
    }

    @Test fun invalidDtoFailsClosed() {
        val copy = PiRecoveryDiagnosticCopy.from(dto().copy(status = "APPROVED"))
        assertContains(copy, "REASON=INVALID_DIAGNOSTIC_CONTRACT")
        assertContains(copy, "DECISION=BLOCK")
    }

    @Test fun arbitraryErrorMessageCannotLeakToClipboard() {
        val copy = PiRecoveryDiagnosticCopy.failure("secret/path/token")
        assertContains(copy, "REASON=INSPECTION_FAILED")
        assertFalse(copy.contains("secret"))
    }

    @Test fun cancellationCanBeCopiedButIsNotPermission() {
        val copy = PiRecoveryDiagnosticCopy.failure("INSPECTION_CANCELLED")
        assertContains(copy, "REASON=INSPECTION_CANCELLED")
        assertContains(copy, "READ_ONLY=true")
    }
}
