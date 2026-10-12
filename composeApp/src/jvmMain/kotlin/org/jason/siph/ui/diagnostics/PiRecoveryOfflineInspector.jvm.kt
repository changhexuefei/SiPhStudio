package org.jason.siph.ui.diagnostics

import java.nio.file.Path
import org.jason.pi.gcs.simulation.PhysicalReadOnlyEvidenceRecovery
import org.jason.pi.gcs.simulation.PhysicalReadOnlyPublicationRecoveryReport
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryAuditRevalidation
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport

/**
 * Read-only UI inspection with two samples; never manufactures an operator audit match.
 * Double sampling detects observed changes but cannot provide an atomic filesystem snapshot.
 */
internal object PiRecoveryOfflineInspector {
    fun inspect(parent: Path, name: String): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto =
        inspectSamples { PhysicalReadOnlyPublicationRecoveryReport.collect(parent, name, null) }

    internal fun inspectSamples(
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto {
        val first = try { collect() } catch (_: Exception) {
            return blocked("SNAPSHOT_SAMPLE_FAILED", "OTHER_ERROR", 1)
        }
        val second = try { collect() } catch (_: Exception) {
            return blocked("SNAPSHOT_SAMPLE_FAILED", "OTHER_ERROR", 2)
        }
        if (first != second) {
            return blocked("SNAPSHOT_CHANGED_DURING_INSPECTION", "SNAPSHOT_CHANGED", null)
        }
        val blocked = second.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.Result(
            status = if (blocked) PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED
                     else PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID,
            currentDecision = second.decision,
            currentReasons = second.reasons
        )
        return PhysicalReadOnlyRecoveryDiagnosticsExport.export(result).also {
            require(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(it).isEmpty()) { "Invalid diagnostics" }
        }
    }

    private fun blocked(reason: String, category: String, sample: Int?): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto {
        val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.Dto(
            schemaVersion = 1, status = "INSPECTION_BLOCKED", decision = "BLOCK",
            reasonCodes = listOf(reason), failureCategory = category, failedSample = sample
        )
        require(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty())
        return dto
    }
}
