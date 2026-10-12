package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** In-memory parity for signed revalidation and unsigned desktop inspection. */
class PhysicalReadOnlyRecoverySamplingParityTest {
    private val api = PhysicalReadOnlyRecoveryAuditRevalidation

    private fun report(decision: PhysicalReadOnlyEvidenceRecovery.Decision =
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR) =
        PhysicalReadOnlyPublicationRecoveryReport.Report(
            decision, listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
        )

    private fun resolution() = PhysicalReadOnlyRecoveryResolutionAudit.document(
        report(), "operator-reference", "INVESTIGATE", "Read-only inspection"
    )

    @Test fun stableEvidenceHasDifferentAuditStatusButIdenticalDecision() {
        val signed = api.checkWithCollector(resolution()) { report() }
        val unsigned = api.inspectWithCollector { report() }
        assertEquals(api.Status.MATCHES_CURRENT_SNAPSHOT, signed.status)
        assertEquals(api.Status.CHANGED_OR_INVALID, unsigned.status)
        assertEquals(signed.currentDecision, unsigned.currentDecision)
        assertEquals(signed.currentReasons, unsigned.currentReasons)
        assertFalse(unsigned.status == api.Status.MATCHES_CURRENT_SNAPSHOT)
    }

    @Test fun stableBlockedEvidenceIsEquivalent() {
        val signed = api.checkWithCollector(resolution()) { report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK) }
        val unsigned = api.inspectWithCollector { report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK) }
        assertEquals(signed, unsigned)
        assertEquals(api.Status.INSPECTION_BLOCKED, signed.status)
    }

    @Test fun snapshotDifferenceIsEquivalent() {
        fun collector(): () -> PhysicalReadOnlyPublicationRecoveryReport.Report {
            var samples = 0
            return { if (++samples == 1) report() else report().copy(stagingNames = listOf("changed")) }
        }
        assertEquals(api.checkWithCollector(resolution(), collector()), api.inspectWithCollector(collector()))
    }

    @Test fun sampledExceptionsAreEquivalentForBothIndices() {
        val errors = listOf(
            { AccessDeniedException("private/path") },
            { NoSuchFileException("private/path") },
            { IOException("private/path") },
            { SecurityException("private/path") },
            { IllegalStateException("private/path") }
        )
        for (error in errors) for (failedAt in 1..2) {
            fun collector(): () -> PhysicalReadOnlyPublicationRecoveryReport.Report {
                var count = 0
                return {
                    if (++count == failedAt) throw error() else report()
                }
            }
            val signed = api.checkWithCollector(resolution(), collector())
            val unsigned = api.inspectWithCollector(collector())
            assertEquals(signed, unsigned)
            assertEquals(failedAt, signed.failedSample)
            assertEquals(api.Status.INSPECTION_BLOCKED, signed.status)
            assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, signed.currentDecision)
            assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), signed.currentReasons)
        }
    }

    @Test fun alteredOperatorNoteDoesNotAffectUnsignedSampling() {
        val invalid = resolution().copy(disposition = "APPROVE")
        val signed = api.checkWithCollector(invalid) { report() }
        val unsigned = api.inspectWithCollector { report() }
        assertEquals(api.Status.CHANGED_OR_INVALID, signed.status)
        assertEquals(api.Status.CHANGED_OR_INVALID, unsigned.status)
    }
}
