package org.jason.pi.gcs.simulation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * An audit snapshot binding a recovery decision to explicit operator disposition.
 * A digest detects accidental modification, NOT forgery; it is not a signature.
 * No disk mutation, lock removal or PI controller operations occur here.
 */
object PhysicalReadOnlyRecoveryResolutionEvidence {
    enum class Disposition { INVESTIGATE, REJECT, MANUAL_REVIEW_ONLY }

    data class Record(
        val schemaVersion: Int,
        val decision: String,
        val reasons: List<String>,
        val lockPresent: Boolean,
        val lockRunId: String?,
        val stagingNames: List<String>,
        val operatorReference: String,
        val caseReference: String,
        val disposition: Disposition,
        val recordedUtc: String,
        val sha256: String
    )

    fun capture(
        report: PhysicalReadOnlyPublicationRecoveryReport.Report,
        operatorReference: String,
        caseReference: String,
        disposition: Disposition,
        recordedUtc: Instant
    ): Record {
        require(operatorReference.isNotBlank() && caseReference.isNotBlank()) { "Operator and case references required" }
        require(operatorReference.length <= 256 && caseReference.length <= 256) { "Reference too long" }
        require(disposition != Disposition.MANUAL_REVIEW_ONLY ||
            report.decision == PhysicalReadOnlyEvidenceRecovery.Decision.VALIDATE_FOR_MANUAL_REVIEW) {
            "Only validated evidence can be marked for manual review"
        }
        val provisional = Record(
            schemaVersion = 1, decision = report.decision.name,
            reasons = report.reasons.toList(), lockPresent = report.lockPresent,
            lockRunId = report.lock?.runId, stagingNames = report.stagingNames.toList(),
            operatorReference = operatorReference, caseReference = caseReference,
            disposition = disposition, recordedUtc = recordedUtc.toString(), sha256 = ""
        )
        return provisional.copy(sha256 = digest(provisional))
    }

    fun validate(record: Record): List<String> {
        val problems = mutableListOf<String>()
        if (record.schemaVersion != 1) problems += "UNSUPPORTED_SCHEMA"
        if (record.operatorReference.isBlank() || record.caseReference.isBlank() ||
            record.operatorReference.length > 256 || record.caseReference.length > 256) problems += "INVALID_REFERENCES"
        if (runCatching { Instant.parse(record.recordedUtc) }.isFailure) problems += "INVALID_TIMESTAMP"
        if (record.disposition == Disposition.MANUAL_REVIEW_ONLY &&
            (record.decision != PhysicalReadOnlyEvidenceRecovery.Decision.VALIDATE_FOR_MANUAL_REVIEW.name ||
                record.lockPresent || record.stagingNames.isNotEmpty())) problems += "INVALID_DISPOSITION"
        if (record.sha256 != digest(record)) problems += "DIGEST_MISMATCH"
        return problems
    }

    private fun digest(record: Record): String {
        val fields = listOf(
            record.schemaVersion.toString(), record.decision,
            record.reasons.size.toString(), *record.reasons.toTypedArray(),
            record.lockPresent.toString(), record.lockRunId ?: "",
            record.stagingNames.size.toString(), *record.stagingNames.toTypedArray(),
            record.operatorReference, record.caseReference, record.disposition.name, record.recordedUtc
        )
        val bytes = buildString {
            for (field in fields) {
                append(field.toByteArray(StandardCharsets.UTF_8).size)
                append(':')
                append(field)
            }
        }.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
