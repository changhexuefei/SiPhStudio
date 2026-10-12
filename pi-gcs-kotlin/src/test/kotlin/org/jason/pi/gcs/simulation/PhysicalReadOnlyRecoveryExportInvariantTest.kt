package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end invariant test for shared sampler -> diagnostics DTO v1.
 * All cases are in-memory and have no PI hardware or filesystem side effects.
 */
class PhysicalReadOnlyRecoveryExportInvariantTest {
    private val sampler = PhysicalReadOnlyRecoveryAuditRevalidation
    private val codec = PhysicalReadOnlyRecoveryDiagnosticsExport

    private fun report(decision: PhysicalReadOnlyEvidenceRecovery.Decision =
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR
    ) = PhysicalReadOnlyPublicationRecoveryReport.Report(
        decision, listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    private fun valid(result: PhysicalReadOnlyRecoveryAuditRevalidation.Result):
        PhysicalReadOnlyRecoveryDiagnosticsExport.Dto {
        val dto = codec.export(result)
        assertEquals(1, dto.schemaVersion)
        assertEquals(emptyList(), codec.validate(dto))
        return dto
    }

    @Test fun allStableUnsignedDecisionsProduceValidV1WithoutVerifiedMatch() {
        for (decision in PhysicalReadOnlyEvidenceRecovery.Decision.entries) {
            val dto = valid(sampler.inspectWithCollector { report(decision) })
            assertEquals(decision.name, dto.decision)
            assertFalse(dto.status == "MATCHES_CURRENT_SNAPSHOT")
            assertEquals("NONE", dto.failureCategory)
            assertEquals(null, dto.failedSample)
        }
    }

    @Test fun changingSnapshotAlwaysExportsBlockedV1() {
        var calls = 0
        val dto = valid(sampler.inspectWithCollector {
            if (++calls == 1) report() else report().copy(stagingNames = listOf("new-stage"))
        })
        assertEquals(2, calls)
        assertEquals("INSPECTION_BLOCKED", dto.status)
        assertEquals("BLOCK", dto.decision)
        assertEquals("SNAPSHOT_CHANGED", dto.failureCategory)
        assertEquals(listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"), dto.reasonCodes)
        assertEquals(null, dto.failedSample)
    }

    @Test fun allFailureCategoriesAtBothSamplesProduceValidV1() {
        val failures = listOf(
            { AccessDeniedException("sensitive/path") as Exception } to "ACCESS_DENIED",
            { SecurityException("sensitive/path") as Exception } to "ACCESS_DENIED",
            { NoSuchFileException("sensitive/path") as Exception } to "PATH_UNAVAILABLE",
            { IOException("sensitive/path") as Exception } to "IO_ERROR",
            { IllegalStateException("sensitive/path") as Exception } to "OTHER_ERROR"
        )
        for ((failure, category) in failures) for (at in 1..2) {
            var calls = 0
            val dto = valid(sampler.inspectWithCollector {
                if (++calls == at) throw failure() else report()
            })
            assertEquals(at, calls)
            assertEquals("INSPECTION_BLOCKED", dto.status)
            assertEquals("BLOCK", dto.decision)
            assertEquals(category, dto.failureCategory)
            assertEquals(at, dto.failedSample)
            assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), dto.reasonCodes)
            assertFalse(dto.reasonCodes.any { "sensitive" in it })
        }
    }

    @Test fun signedStableNonblockingAuditMatchHasValidNonAuthorizingDto() {
        val original = report()
        val resolution = PhysicalReadOnlyRecoveryResolutionAudit.document(
            original, "operator-reference", "INVESTIGATE", "Read-only resolution note"
        )
        val dto = valid(sampler.checkWithCollector(resolution) { report() })
        assertEquals("MATCHES_CURRENT_SNAPSHOT", dto.status)
        assertEquals("REQUIRE_OPERATOR", dto.decision)
        assertEquals("NONE", dto.failureCategory)
    }

    @Test fun tamperedSignedResolutionRemainsInvalidButSchemaValid() {
        val resolution = PhysicalReadOnlyRecoveryResolutionAudit.document(
            report(), "operator-reference", "INVESTIGATE", "Read-only resolution note"
        ).copy(disposition = "APPROVE")
        val dto = valid(sampler.checkWithCollector(resolution) { report() })
        assertEquals("CHANGED_OR_INVALID", dto.status)
        assertEquals("REQUIRE_OPERATOR", dto.decision)
    }
}
