package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic changes between two filesystem observation points. */
class PhysicalReadOnlyRecoverySnapshotConsistencyTest {
    private fun report(
        staging: List<String> = emptyList(),
        lockPresent: Boolean = true
    ) = PhysicalReadOnlyPublicationRecoveryReport.Report(
        decision = PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        reasons = listOf("PUBLICATION_LOCK_PRESENT"),
        lockPresent = lockPresent,
        lock = null,
        stagingNames = staging
    )

    private fun resolution(current: PhysicalReadOnlyPublicationRecoveryReport.Report) =
        PhysicalReadOnlyRecoveryResolutionAudit.document(
            current, "operator", "INVESTIGATE", "Preserve offline evidence"
        )

    @Test fun changingStagingBetweenSamplesBlocksInspection() {
        val initial = report()
        val changed = report(staging = listOf(".pi-readonly-staging-new"))
        var observations = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution(initial)) {
            if (observations++ == 0) initial else changed
        }
        assertEquals(2, observations)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertTrue("SNAPSHOT_CHANGED_DURING_INSPECTION" in result.currentReasons)
    }

    @Test fun changingLockPresenceBetweenSamplesBlocksInspection() {
        val initial = report()
        var counter = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution(initial)) {
            if (counter++ == 0) initial else report(lockPresent = false)
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
    }

    @Test fun stableSnapshotCanMatchButStillRequiresOperator() {
        val observed = report()
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution(observed)) {
            observed
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.currentDecision)
    }
}
