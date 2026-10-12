package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals

class PhysicalReadOnlyRecoverySamplingFailureTest {
    private fun report() = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    private fun resolution() = PhysicalReadOnlyRecoveryResolutionAudit.document(
        report(), "operator", "INVESTIGATE", "Preserve evidence"
    )

    @Test fun firstSamplingFailureBlocks() {
        var count = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            count++
            throw java.io.IOException("first sample unavailable")
        }
        assertEquals(1, count)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), result.currentReasons)
    }

    @Test fun secondSamplingFailureBlocksInsteadOfPropagating() {
        var count = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            if (++count == 1) report() else throw java.io.IOException("second sample unavailable")
        }
        assertEquals(2, count)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), result.currentReasons)
    }

    @Test fun stableSamplesStillAllowNonAuthorizingAuditMatch() {
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) { report() }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.currentDecision)
    }
}
