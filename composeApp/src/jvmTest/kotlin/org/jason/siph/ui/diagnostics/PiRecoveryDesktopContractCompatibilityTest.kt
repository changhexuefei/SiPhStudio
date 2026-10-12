package org.jason.siph.ui.diagnostics

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import org.jason.pi.gcs.simulation.PhysicalReadOnlyEvidenceRecovery
import org.jason.pi.gcs.simulation.PhysicalReadOnlyPublicationRecoveryReport
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Consumer contract: pi-gcs-kotlin DTO v1 -> desktop inspection, display and
 * sanitized support copy. No filesystem or hardware is involved in these tests.
 */
class PiRecoveryDesktopContractCompatibilityTest {
    private fun observed() = PhysicalReadOnlyPublicationRecoveryReport.Report(
        PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR,
        listOf("PUBLICATION_LOCK_PRESENT"), true, null, emptyList()
    )

    @Test fun stableOperatorGateFlowsThroughAllDesktopConsumers() {
        val dto = PiRecoveryOfflineInspector.inspectSamples { observed() }
        assertEquals(emptyList(), PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto))
        assertEquals("CHANGED_OR_INVALID", dto.status)
        val view = PiRecoveryDiagnosticsPresentation.from(dto)
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.OPERATOR_ACTION, view.severity)
        val copy = PiRecoveryDiagnosticCopy.from(dto)
        assertContains(copy, "DECISION=REQUIRE_OPERATOR")
        assertContains(copy, "READ_ONLY=true")
        assertFalse(copy.contains("APPROVED"))
    }

    @Test fun firstSampleAccessDeniedRemainsBlockedAcrossBoundaries() {
        val dto = PiRecoveryOfflineInspector.inspectSamples {
            throw AccessDeniedException("C:/private/device/evidence")
        }
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
        assertEquals("BLOCK", dto.decision)
        assertEquals(1, dto.failedSample)
        assertEquals("ACCESS_DENIED", dto.failureCategory)
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED,
            PiRecoveryDiagnosticsPresentation.from(dto).severity)
        val copy = PiRecoveryDiagnosticCopy.from(dto)
        assertContains(copy, "FAILURE_CATEGORY=ACCESS_DENIED")
        assertFalse(copy.contains("private"))
    }

    @Test fun secondSampleMissingPathRemainsBlockedAcrossBoundaries() {
        var calls = 0
        val dto = PiRecoveryOfflineInspector.inspectSamples {
            if (++calls == 1) observed() else throw NoSuchFileException("secret/path")
        }
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
        assertEquals(2, dto.failedSample)
        assertContains(PiRecoveryDiagnosticCopy.from(dto), "FAILED_SAMPLE=2")
        assertEquals("PATH_UNAVAILABLE", dto.failureCategory)
    }

    @Test fun ioErrorAndChangingSamplesStillMatchV1Contract() {
        val io = PiRecoveryOfflineInspector.inspectSamples { throw IOException("secret") }
        var calls = 0
        val changed = PiRecoveryOfflineInspector.inspectSamples {
            if (++calls == 1) observed() else observed().copy(stagingNames = listOf("stage"))
        }
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(io).isEmpty())
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(changed).isEmpty())
        assertEquals("IO_ERROR", io.failureCategory)
        assertEquals("SNAPSHOT_CHANGED", changed.failureCategory)
        assertEquals(null, changed.failedSample)
    }

    @Test fun malformedSamplingDtoIsRejectedByBothPresentationAndCopy() {
        val bad = PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
            1, "INSPECTION_BLOCKED", "BLOCK", listOf("SNAPSHOT_SAMPLE_FAILED"),
            "ACCESS_DENIED", null
        )
        assertTrue(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(bad).isNotEmpty())
        val view = PiRecoveryDiagnosticsPresentation.from(bad)
        assertEquals("INVALID_DIAGNOSTIC_CONTRACT", view.detail)
        assertContains(PiRecoveryDiagnosticCopy.from(bad), "REASON=INVALID_DIAGNOSTIC_CONTRACT")
    }

    @Test fun unexpectedFutureSchemaFailsClosed() {
        val future = PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
            2, "INSPECTION_BLOCKED", "BLOCK", listOf("SNAPSHOT_SAMPLE_FAILED"),
            "IO_ERROR", 1
        )
        assertContains(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(future), "UNSUPPORTED_SCHEMA")
        assertEquals(PiRecoveryDiagnosticsPresentation.Severity.BLOCKED,
            PiRecoveryDiagnosticsPresentation.from(future).severity)
        assertFalse(PiRecoveryDiagnosticCopy.from(future).contains("IO_ERROR"))
    }
}
