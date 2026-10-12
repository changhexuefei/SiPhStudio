package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryFailClosedExportTest {
    private val audit = PhysicalReadOnlyRecoveryAuditRevalidation
    private val exporter = PhysicalReadOnlyRecoveryDiagnosticsExport

    private fun result(
        status: PhysicalReadOnlyRecoveryAuditRevalidation.Status,
        decision: PhysicalReadOnlyEvidenceRecovery.Decision,
        reasons: List<String> = emptyList(),
        category: PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory =
            PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.NONE,
        sample: Int? = null
    ) = PhysicalReadOnlyRecoveryAuditRevalidation.Result(status, decision, reasons, category, sample)

    @Test fun rejectsBlockedStatusWithNonBlockedDecision() {
        assertFailsWith<IllegalArgumentException> {
            exporter.export(result(audit.Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR))
        }
    }

    @Test fun rejectsMatchingStatusWithBlockedDecision() {
        assertFailsWith<IllegalArgumentException> {
            exporter.export(result(audit.Status.MATCHES_CURRENT_SNAPSHOT,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK))
        }
    }

    @Test fun rejectsFailureCategoryWithMissingSampleIndex() {
        assertFailsWith<IllegalArgumentException> {
            exporter.export(result(audit.Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_SAMPLE_FAILED"), audit.FailureCategory.IO_ERROR))
        }
    }

    @Test fun rejectsSnapshotChangeWithSampleIndex() {
        assertFailsWith<IllegalArgumentException> {
            exporter.export(result(audit.Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"),
                audit.FailureCategory.SNAPSHOT_CHANGED, 1))
        }
    }

    @Test fun correctlyFormedFailureExportsUnchangedV1() {
        val dto = exporter.export(result(audit.Status.INSPECTION_BLOCKED,
            PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
            listOf("SNAPSHOT_SAMPLE_FAILED"), audit.FailureCategory.IO_ERROR, 2))
        assertEquals(1, dto.schemaVersion)
        assertEquals("IO_ERROR", dto.failureCategory)
        assertEquals(2, dto.failedSample)
        assertTrue(exporter.validate(dto).isEmpty())
    }
}
