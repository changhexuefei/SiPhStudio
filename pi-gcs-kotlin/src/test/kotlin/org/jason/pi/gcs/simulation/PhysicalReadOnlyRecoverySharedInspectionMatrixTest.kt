package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks down the shared inspection boundary used by both core diagnostics
 * and the Compose Desktop consumer; exercises no disk or PI controller.
 */
class PhysicalReadOnlyRecoverySharedInspectionMatrixTest {
    private val api = PhysicalReadOnlyRecoveryAuditRevalidation
    private fun report(
        decision: PhysicalReadOnlyEvidenceRecovery.Decision =
            PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR
    ) = PhysicalReadOnlyPublicationRecoveryReport.Report(
        decision, listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    @Test fun unchangedBlockedEvidenceCannotBecomeApproved() {
        var calls = 0
        val result = api.inspectWithCollector {
            calls++
            report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK)
        }
        assertEquals(2, calls)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.NONE, result.failureCategory)
        assertEquals(null, result.failedSample)
    }

    @Test fun mismatchIsBlockedEvenWhenBothReportsAreNonBlocking() {
        var calls = 0
        val result = api.inspectWithCollector {
            if (++calls == 1) report() else report().copy(reasons = listOf("CHANGED"))
        }
        assertEquals(2, calls)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.SNAPSHOT_CHANGED, result.failureCategory)
        assertEquals(listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"), result.currentReasons)
    }

    @Test fun allExceptionTypesAreClassifiedAndNeverLeakPaths() {
        val failures = listOf(
            AccessDeniedException("private/path") to
                PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.ACCESS_DENIED,
            SecurityException("private/path") to
                PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.ACCESS_DENIED,
            NoSuchFileException("private/path") to
                PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.PATH_UNAVAILABLE,
            IOException("private/path") to
                PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.IO_ERROR,
            IllegalStateException("private/path") to
                PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.OTHER_ERROR
        )
        for ((error, expected) in failures) {
            var calls = 0
            val result = api.inspectWithCollector { calls++; throw error }
            assertEquals(1, calls)
            assertEquals(expected, result.failureCategory)
            assertEquals(1, result.failedSample)
            assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
            assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), result.currentReasons)
            assertTrue(result.currentReasons.none { "private" in it })
            assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(
                PhysicalReadOnlyRecoveryDiagnosticsExport.export(result)
            ).isEmpty())
        }
    }

    @Test fun secondSampleFailureIsNeverSuppressed() {
        var calls = 0
        val result = api.inspectWithCollector {
            if (++calls == 1) report() else throw NoSuchFileException("sensitive")
        }
        assertEquals(2, calls)
        assertEquals(2, result.failedSample)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.PATH_UNAVAILABLE, result.failureCategory)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
    }

    @Test fun stableUnsignedSampleNeverReportsVerifiedMatch() {
        for (decision in PhysicalReadOnlyEvidenceRecovery.Decision.entries) {
            val result = api.inspectWithCollector { report(decision) }
            assertTrue(result.status != PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT)
            assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(
                PhysicalReadOnlyRecoveryDiagnosticsExport.export(result)
            ).isEmpty())
        }
    }
}
