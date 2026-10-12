package org.jason.pi.gcs.simulation

import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals

class PhysicalReadOnlySharedSamplingEngineTest {
    private fun observed() = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    @Test fun stableReportsAreSampledExactlyTwice() {
        var calls = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            calls++
            observed()
        }
        assertEquals(2, calls)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID, result.status)
    }

    @Test fun firstSampleFailureNeverRetries() {
        var calls = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            calls++
            throw NoSuchFileException("private")
        }
        assertEquals(1, calls)
        assertEquals(1, result.failedSample)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.PATH_UNAVAILABLE, result.failureCategory)
    }

    @Test fun changedReportsBlockWithNoSampleErrorIndex() {
        var calls = 0
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector {
            if (++calls == 1) observed() else observed().copy(stagingNames = listOf("new-staging"))
        }
        assertEquals(2, calls)
        assertEquals(null, result.failedSample)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.SNAPSHOT_CHANGED, result.failureCategory)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.currentDecision)
    }
}
