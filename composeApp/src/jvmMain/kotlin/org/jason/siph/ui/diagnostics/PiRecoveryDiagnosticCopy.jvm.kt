package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport

/**
 * Explicit allowlist of fields included in support copy.
 * No evidence paths, hostnames, raw exception messages, lock IDs or operator identities.
 * The text is information only, never a recovery authorization.
 */
internal object PiRecoveryDiagnosticCopy {
    fun from(dto: PhysicalReadOnlyRecoveryDiagnosticsExport.Dto): String {
        if (PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isNotEmpty()) {
            return "PI_OFFLINE_RECOVERY_DIAGNOSTIC_V1\nSTATUS=INSPECTION_BLOCKED\nDECISION=BLOCK\nREASON=INVALID_DIAGNOSTIC_CONTRACT\nREAD_ONLY=true"
        }
        return listOf(
            "PI_OFFLINE_RECOVERY_DIAGNOSTIC_V1",
            "STATUS=${dto.status}",
            "DECISION=${dto.decision}",
            "REASONS=${dto.reasonCodes.joinToString(",")}",
            "FAILURE_CATEGORY=${dto.failureCategory}",
            "FAILED_SAMPLE=${dto.failedSample?.toString() ?: "NONE"}",
            "READ_ONLY=true"
        ).joinToString("\n")
    }

    fun failure(code: String?): String {
        // Reuse the allowlisted presentation code rather than arbitrary exception text.
        val safe = PiRecoveryDiagnosticsPresentation.inputFailure(code)
        return listOf(
            "PI_OFFLINE_RECOVERY_DIAGNOSTIC_V1",
            "STATUS=INSPECTION_BLOCKED",
            "DECISION=BLOCK",
            "REASON=${safe.detail}",
            "READ_ONLY=true"
        ).joinToString("\n")
    }
}
