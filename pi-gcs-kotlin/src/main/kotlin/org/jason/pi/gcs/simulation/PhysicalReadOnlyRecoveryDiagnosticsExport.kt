package org.jason.pi.gcs.simulation

/**
 * Versioned, JSON-compatible, offline diagnostics for UI and operations consumers.
 * This DTO has no authority to unlock, approve, resume or control PI hardware.
 */
object PhysicalReadOnlyRecoveryDiagnosticsExport {
    data class Dto(
        val schemaVersion: Int,
        val status: String,
        val decision: String,
        val reasonCodes: List<String>,
        val failureCategory: String,
        val failedSample: Int?
    )

    fun export(result: PhysicalReadOnlyRecoveryAuditRevalidation.Result): Dto {
        val reasons = result.currentReasons.toList()
        require(reasons.size <= 100 && reasons.all { it.length in 1..128 && it.matches(Regex("[A-Z0-9_]+")) }) {
            "Invalid diagnostic reason codes"
        }
        require(result.failedSample == null || result.failedSample in 1..2) {
            "Invalid sample index"
        }
        return Dto(1, result.status.name, result.currentDecision.name,
            reasons, result.failureCategory.name, result.failedSample)
    }

    fun validate(dto: Dto): List<String> {
        val errors = mutableListOf<String>()
        if (dto.schemaVersion != 1) errors += "UNSUPPORTED_SCHEMA"
        if (dto.status !in PhysicalReadOnlyRecoveryAuditRevalidation.Status.entries.map { it.name }) {
            errors += "INVALID_STATUS"
        }
        if (dto.decision !in PhysicalReadOnlyEvidenceRecovery.Decision.entries.map { it.name }) {
            errors += "INVALID_DECISION"
        }
        if (dto.failureCategory !in PhysicalReadOnlyRecoveryAuditRevalidation.FailureCategory.entries.map { it.name }) {
            errors += "INVALID_FAILURE_CATEGORY"
        }
        if (dto.reasonCodes.size > 100 || dto.reasonCodes.any {
                it.length !in 1..128 || !it.matches(Regex("[A-Z0-9_]+"))
            }) errors += "INVALID_REASON_CODES"
        if (dto.failedSample != null && dto.failedSample !in 1..2) errors += "INVALID_SAMPLE"
        if (dto.status == "MATCHES_CURRENT_SNAPSHOT" && dto.decision == "BLOCK") errors += "INCONSISTENT_STATUS"
        if (dto.status == "INSPECTION_BLOCKED" && dto.decision != "BLOCK") errors += "INCONSISTENT_STATUS"
        if (dto.failureCategory == "SNAPSHOT_CHANGED" &&
            "SNAPSHOT_CHANGED_DURING_INSPECTION" !in dto.reasonCodes) errors += "INCONSISTENT_FAILURE"
        return errors.distinct()
    }
}
