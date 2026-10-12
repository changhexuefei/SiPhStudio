package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryDiagnosticsExportTest {
    private fun result(
        status: PhysicalReadOnlyRecoveryAuditRevalidation.Status =
            PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED,
        decision: PhysicalReadOnlyEvidenceRecovery.Decision =
            PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
        category: PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory =
            PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.ACCESS_DENIED,
        sample: Int? = 2,
        reasons: List<String> = listOf("SNAPSHOT_SAMPLE_FAILED")
    ) = PhysicalReadOnlyRecoveryAuditRevalidation.Result(
        status, decision, reasons, category, sample
    )

    @Test fun blockedSampleExportsStableCodesWithoutExceptionDetails() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result())
        assertEquals(1, dto.schemaVersion)
        assertEquals("INSPECTION_BLOCKED", dto.status)
        assertEquals("BLOCK", dto.decision)
        assertEquals("ACCESS_DENIED", dto.failureCategory)
        assertEquals(2, dto.failedSample)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), dto.reasonCodes)
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
    }

    @Test fun changedSnapshotExportsDedicatedReason() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result(
            category = PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.SNAPSHOT_CHANGED,
            sample = null,
            reasons = listOf("SNAPSHOT_CHANGED_DURING_INSPECTION")
        ))
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
    }

    @Test fun cleanAuditMatchRemainsNonAuthorizing() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result(
            status = PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT,
            decision = PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
            category = PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.NONE,
            sample = null,
            reasons = listOf("PUBLICATION_LOCK_PRESENT")
        ))
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
        assertEquals("REQUIRE_OPERATOR", dto.decision)
    }

    @Test fun invalidSchemaAndEnumsAreRejected() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result())
        val errors = PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto.copy(
            schemaVersion = 9, status = "APPROVED", failureCategory = "OK"
        ))
        assertTrue("UNSUPPORTED_SCHEMA" in errors)
        assertTrue("INVALID_STATUS" in errors)
        assertTrue("INVALID_FAILURE_CATEGORY" in errors)
    }

    @Test fun malformedReasonAndSampleAreRejected() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result())
        val errors = PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto.copy(
            reasonCodes = listOf("private/path"), failedSample = 4
        ))
        assertTrue("INVALID_REASON_CODES" in errors)
        assertTrue("INVALID_SAMPLE" in errors)
    }

    @Test fun inconsistentBlockingDecisionIsRejected() {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(result())
        val errors = PhysicalReadOnlyRecoveryDiagnosticsExport.validate(
            dto.copy(decision = "VALIDATE_FOR_MANUAL_REVIEW")
        )
        assertTrue("INCONSISTENT_STATUS" in errors)
    }
}
