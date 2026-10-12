package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyEvidenceRecovery
import org.jason.pi.gcs.simulation.PhysicalReadOnlyPublicationRecoveryReport
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRecoveryOfflineInspectorTest {
    private fun observed(staging: List<String> = emptyList()) =
        PhysicalReadOnlyPublicationRecoveryReport.Report(
            PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
            listOf("PUBLICATION_LOCK_PRESENT"), true, null, staging
        )

    @Test fun stableObservationPreservesOperatorGateWithoutAuditMatch() {
        var samples = 0
        val dto = PiRecoveryOfflineInspector.inspectSamples { samples++; observed() }
        assertEquals(2, samples)
        assertEquals("REQUIRE_OPERATOR", dto.decision)
        assertEquals("CHANGED_OR_INVALID", dto.status)
    }

    @Test fun changedStagingBetweenObservationsFailsClosed() {
        var samples = 0
        val dto = PiRecoveryOfflineInspector.inspectSamples {
            if (++samples == 1) observed() else observed(listOf(".pi-readonly-staging-new"))
        }
        assertEquals("INSPECTION_BLOCKED", dto.status)
        assertEquals("BLOCK", dto.decision)
        assertEquals("SNAPSHOT_CHANGED", dto.failureCategory)
        assertEquals(listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"), dto.reasonCodes)
    }

    @Test fun firstSampleExceptionReportsIndex() {
        val dto = PiRecoveryOfflineInspector.inspectSamples {
            throw java.io.IOException("private/path")
        }
        assertEquals(1, dto.failedSample)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), dto.reasonCodes)
        assertEquals("BLOCK", dto.decision)
    }

    @Test fun secondSampleExceptionReportsIndex() {
        var calls = 0
        val dto = PiRecoveryOfflineInspector.inspectSamples {
            if (++calls == 1) observed() else throw java.io.IOException("private/path")
        }
        assertEquals(2, dto.failedSample)
        assertEquals("INSPECTION_BLOCKED", dto.status)
        assertEquals("IO_ERROR", dto.failureCategory)
    }
}
