package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals

class PhysicalReadOnlyRecoveryInspectionDiagnosticsTest {
    private fun observed() = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    private fun resolution() = PhysicalReadOnlyRecoveryResolutionAudit.document(
        observed(), "operator", "INVESTIGATE", "Preserve evidence"
    )

    @Test fun accessDeniedOnFirstSampleIsCategorized() {
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            throw AccessDeniedException("private-evidence-path")
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.ACCESS_DENIED, result.failureCategory)
        assertEquals(1, result.failedSample)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), result.currentReasons)
    }

    @Test fun missingPathOnSecondSampleIsCategorized() {
        var samples = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            if (++samples == 1) observed() else throw NoSuchFileException("missing")
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.PATH_UNAVAILABLE, result.failureCategory)
        assertEquals(2, result.failedSample)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
    }

    @Test fun genericIoFailureIsCategorizedWithoutLeakingExceptionMessage() {
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            throw IOException("private message")
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.IO_ERROR, result.failureCategory)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), result.currentReasons)
    }

    @Test fun changedSnapshotsHaveDedicatedCategory() {
        var samples = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) {
            if (++samples == 1) observed() else observed().copy(stagingNames = listOf("stage-new"))
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.SNAPSHOT_CHANGED, result.failureCategory)
        assertEquals(null, result.failedSample)
    }

    @Test fun stableObservationsKeepDefaultDiagnosticFields() {
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.checkWithCollector(resolution()) { observed() }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT, result.status)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.NONE, result.failureCategory)
        assertEquals(null, result.failedSample)
    }
}
