package org.jason.siph.ui.diagnostics

import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport

/**
 * Pure, deterministic operator-facing copy for read-only diagnostic snapshots.
 * Presentation is not authorization or permission to resume operations.
 */
internal object PiRecoveryDiagnosticsPresentation {
    enum class Severity { BLOCKED, OPERATOR_ACTION, REVIEW_REQUIRED, INFORMATIONAL }

    data class View(
        val headline: String,
        val guidance: String,
        val detail: String,
        val severity: Severity = Severity.BLOCKED
    )

    fun from(dto: PhysicalReadOnlyRecoveryDiagnosticsExport.Dto): View {
        val errors = PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto)
        if (errors.isNotEmpty()) return View(
            "Inspection blocked",
            "Diagnostic data is invalid. Preserve evidence and request operator review.",
            "INVALID_DIAGNOSTIC_CONTRACT", Severity.BLOCKED
        )
        val guidance = when (dto.decision) {
            "BLOCK" -> "Do not resume. Preserve evidence and investigate the inspection failure."
            "REQUIRE_OPERATOR" -> "Operator investigation is required. Do not remove locks automatically."
            "VALIDATE_FOR_MANUAL_REVIEW" -> "Manual review is still required before any separate approval."
            "NO_BUNDLE" -> "No published evidence bundle was found. Do not infer recovery approval."
            else -> "Do not resume. Request operator review."
        }
        val headline = when (dto.decision) {
            "BLOCK" -> "Inspection blocked"
            "REQUIRE_OPERATOR" -> "Operator review required"
            "VALIDATE_FOR_MANUAL_REVIEW" -> "Manual evidence review"
            "NO_BUNDLE" -> "No evidence bundle"
            else -> "Inspection blocked"
        }
        val detail = buildList {
            add("Audit: ${dto.status}")
            if (dto.reasonCodes.isNotEmpty()) add("Reasons: ${dto.reasonCodes.joinToString(", ")}")
            if (dto.failureCategory != "NONE") add("Failure: ${dto.failureCategory}")
            dto.failedSample?.let { add("Sample: $it") }
        }.joinToString(" | ")
        val severity = when (dto.decision) {
            "BLOCK" -> Severity.BLOCKED
            "REQUIRE_OPERATOR" -> Severity.OPERATOR_ACTION
            "VALIDATE_FOR_MANUAL_REVIEW" -> Severity.REVIEW_REQUIRED
            else -> Severity.INFORMATIONAL
        }
        return View(headline, guidance, detail, severity)
    }

    fun inputFailure(code: String?): View {
        val safe = when (code) {
            "PARENT_REQUIRED", "NAME_REQUIRED", "INVALID_NAME", "INVALID_PATH", "INVALID_PARENT" -> code
            else -> "INSPECTION_FAILED"
        }
        return View("Inspection blocked", "Correct the input or request operator review.", safe)
    }
}
