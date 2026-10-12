package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryInspectionWithoutResolutionTest {
    private fun observed() = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    @Test fun stableSampleRequiresOperatorAndCannotBecomeAuditMatch() {
        var count = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            count++
            observed()
        }
        assertEquals(2, count)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.currentDecision)
    }

    @Test fun changedReportsBlockWithoutApproval() {
        var count = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            if (++count == 1) observed() else observed().copy(stagingNames = listOf("changed"))
        }
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.SNAPSHOT_CHANGED, result.failureCategory)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
    }

    @Test fun accessDeniedRetainsFirstSampleAndNoRawPath() {
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            throw AccessDeniedException("secret/device/path")
        }
        assertEquals(1, result.failedSample)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.ACCESS_DENIED, result.failureCategory)
        assertTrue(result.currentReasons.none { "secret" in it })
    }

    @Test fun secondSampleIOExceptionIsClassified() {
        var count = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            if (++count == 1) observed() else throw IOException("sensitive")
        }
        assertEquals(2, result.failedSample)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.IO_ERROR, result.failureCategory)
    }
}
