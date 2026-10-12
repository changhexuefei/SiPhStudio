package org.jason.siph.ui.diagnostics

import java.nio.file.Path
import org.jason.pi.gcs.simulation.PhysicalReadOnlyPublicationRecoveryReport
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryAuditRevalidation
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport

/**
 * JVM desktop adapter for shared, read-only recovery inspection.
 * An operator resolution is intentionally absent, so a match is never claimed.
 */
internal object PiRecoveryOfflineInspector {
    fun inspect(parent: Path, name: String): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto =
        export(PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithoutResolution(parent, name))

    internal fun inspectSamples(
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto =
        export(PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector(collect))

    private fun export(
        result: PhysicalReadOnlyRecoveryAuditRevalidation.Result
    ): PhysicalReadOnlyRecoveryDiagnosticsExport.Dto =
        PhysicalReadOnlyRecoveryDiagnosticsExport.export(result).also {
            require(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(it).isEmpty()) {
                "Invalid recovery diagnostics"
            }
        }
}
