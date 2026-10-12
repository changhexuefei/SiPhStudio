package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PhysicalReadOnlyRecoveryDiagnosticsCompatibilityTest {
    private fun dto() = PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
        1, "INSPECTION_BLOCKED", "BLOCK", listOf("SNAPSHOT_SAMPLE_FAILED"), "IO_ERROR", 2
    )

    @Test fun stableV1SampleFailureRemainsValid() {
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto()).isEmpty())
    }

    @Test fun stableV1SnapshotChangeRemainsValid() {
        val changed = dto().copy(
            reasonCodes = listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"),
            failureCategory = "SNAPSHOT_CHANGED", failedSample = null
        )
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(changed).isEmpty())
    }

    @Test fun missingSampleIndexOrReasonCannotBeTrusted() {
        for (broken in listOf(dto().copy(failedSample = null),
            dto().copy(reasonCodes = listOf("UNRELATED")))) {
            assertTrue("INCONSISTENT_SAMPLE_FAILURE" in PhysicalReadOnlyRecoveryDiagnosticsExport.validate(broken))
        }
    }

    @Test fun invalidSampleCategoryCombinationIsRejected() {
        val errors = PhysicalReadOnlyRecoveryDiagnosticsExport.validate(
            dto().copy(failureCategory = "NONE"))
        assertTrue("INCONSISTENT_SAMPLE_INDEX" in errors)
        assertTrue("MISSING_FAILURE_CATEGORY" in errors)
    }

    @Test fun inconsistentSnapshotChangeIsRejected() {
        val changed = dto().copy(
            failureCategory = "SNAPSHOT_CHANGED",
            reasonCodes = listOf("SNAPSHOT_CHANGED_DURING_INSPECTION")
        )
        assertTrue("INCONSISTENT_SNAPSHOT_CHANGE" in PhysicalReadOnlyRecoveryDiagnosticsExport.validate(changed))
    }

    @Test fun validOperatorReviewContractIsUnchanged() {
        val review = dto().copy(
            status = "CHANGED_OR_INVALID", decision = "REQUIRE_OPERATOR",
            reasonCodes = listOf("PUBLICATION_LOCK_PRESENT"),
            failureCategory = "NONE", failedSample = null
        )
        assertEquals(emptyList(), PhysicalReadOnlyRecoveryDiagnosticsExport.validate(review))
    }
}
