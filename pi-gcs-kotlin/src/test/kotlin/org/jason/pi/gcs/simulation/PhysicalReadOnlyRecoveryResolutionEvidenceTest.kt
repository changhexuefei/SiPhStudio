package org.jason.pi.gcs.simulation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryResolutionEvidenceTest {
    private fun report(
        decision: PhysicalReadOnlyEvidenceRecovery.Decision,
        locked: Boolean = false
    ) = PhysicalReadOnlyPublicationRecoveryReport.Report(
        decision = decision,
        reasons = if (locked) listOf("PUBLICATION_LOCK_PRESENT") else emptyList(),
        lockPresent = locked, lock = null,
        stagingNames = if (locked) listOf(".pi-readonly-staging-test") else emptyList()
    )

    private val now = Instant.parse("2026-10-12T00:00:00Z")

    @Test fun rejectedInterruptedPublicationIsAuditable() {
        val entry = PhysicalReadOnlyRecoveryResolutionEvidence.capture(
            report(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, true),
            "operator-ref", "case-123", PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.INVESTIGATE, now
        )
        assertTrue(PhysicalReadOnlyRecoveryResolutionEvidence.validate(entry).isEmpty())
        assertEquals(64, entry.sha256.length)
        assertEquals("REQUIRE_OPERATOR", entry.decision)
    }

    @Test fun changingReasonOrDispositionInvalidatesDigest() {
        val entry = PhysicalReadOnlyRecoveryResolutionEvidence.capture(
            report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK),
            "operator", "case", PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.REJECT, now
        )
        assertTrue("DIGEST_MISMATCH" in PhysicalReadOnlyRecoveryResolutionEvidence.validate(
            entry.copy(reasons = listOf("changed"))))
        assertTrue("DIGEST_MISMATCH" in PhysicalReadOnlyRecoveryResolutionEvidence.validate(
            entry.copy(disposition = PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.INVESTIGATE)))
    }

    @Test fun lockedEvidenceCannotBeMarkedAsReviewable() {
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyRecoveryResolutionEvidence.capture(
                report(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, true),
                "operator", "case", PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.MANUAL_REVIEW_ONLY, now
            )
        }
    }

    @Test fun validatedEvidenceCanOnlyBeFlaggedForManualReview() {
        val entry = PhysicalReadOnlyRecoveryResolutionEvidence.capture(
            report(PhysicalReadOnlyEvidenceRecovery.Decision.VALIDATE_FOR_MANUAL_REVIEW),
            "operator", "case", PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.MANUAL_REVIEW_ONLY, now
        )
        assertTrue(PhysicalReadOnlyRecoveryResolutionEvidence.validate(entry).isEmpty())
    }

    @Test fun missingOperatorOrCaseIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyRecoveryResolutionEvidence.capture(
                report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK),
                "", "case", PhysicalReadOnlyRecoveryResolutionEvidence.Disposition.REJECT, now
            )
        }
    }
}
