package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryResolutionAuditTest {
    private fun report(
        reasons: List<String> = listOf("PUBLICATION_LOCK_PRESENT"),
        staging: List<String> = listOf(".pi-readonly-staging-abandoned")
    ) = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        reasons,
        true,
        PhysicalReadOnlyPublicationLockAudit.Snapshot(
            "3c785eab-2dad-4681-9e08-7ceeb6e3699d", 123L,
            java.time.Instant.parse("2026-10-12T00:00:00Z"), emptyList()
        ),
        staging
    )

    @Test fun unchangedSnapshotVerifiesOperatorRecord() {
        val observed = report()
        val record = PhysicalReadOnlyRecoveryResolutionAudit.document(
            observed, "operator-1", "INVESTIGATE", "Inspect interrupted publication"
        )
        assertEquals(64, record.evidenceSha256.length)
        assertTrue(PhysicalReadOnlyRecoveryResolutionAudit.verify(record, observed))
        assertEquals(record.evidenceSha256,
            PhysicalReadOnlyRecoveryResolutionAudit.digest(
                PhysicalReadOnlyRecoveryResolutionAudit.snapshot(observed)
            ))
    }

    @Test fun changingRecoveryEvidenceInvalidatesResolution() {
        val original = report()
        val record = PhysicalReadOnlyRecoveryResolutionAudit.document(
            original, "operator-1", "QUARANTINE", "Preserve files"
        )
        assertFalse(PhysicalReadOnlyRecoveryResolutionAudit.verify(
            record, report(staging = listOf(".pi-readonly-staging-different"))
        ))
        assertFalse(PhysicalReadOnlyRecoveryResolutionAudit.verify(
            record, report(reasons = listOf("ORPHANED_STAGING_DIRECTORY"))
        ))
    }

    @Test fun missingOperatorAndAuthorizingDispositionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyRecoveryResolutionAudit.document(report(), " ", "INVESTIGATE", "note")
        }
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyRecoveryResolutionAudit.document(report(), "operator", "APPROVE", "note")
        }
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyRecoveryResolutionAudit.document(report(), "operator", "ESCALATE", " ")
        }
    }
}
