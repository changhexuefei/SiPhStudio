package org.jason.pi.gcs.simulation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Binds an operator's offline resolution notes to one observed recovery snapshot.
 * This is a checksum, not a signature; it never approves publication or PI motion.
 */
object PhysicalReadOnlyRecoveryResolutionAudit {
    data class Evidence(
        val decision: PhysicalReadOnlyEvidenceRecovery.Decision,
        val reasons: List<String>,
        val lockPresent: Boolean,
        val lockRunId: String?,
        val lockWarnings: List<String>,
        val stagingNames: List<String>
    )

    data class Resolution(
        val evidenceSha256: String,
        val operatorReference: String,
        val disposition: String,
        val note: String
    )

    fun snapshot(report: PhysicalReadOnlyPublicationRecoveryReport.Report): Evidence =
        Evidence(report.decision, report.reasons.toList(), report.lockPresent,
            report.lock?.runId, report.lock?.warnings?.toList() ?: emptyList(),
            report.stagingNames.toList())

    private fun canonical(evidence: Evidence): String {
        val fields = listOf(
            "decision" to evidence.decision.name,
            "lockPresent" to evidence.lockPresent.toString(),
            "lockRunId" to (evidence.lockRunId ?: "")
        )
        val collections = listOf(
            "reason" to evidence.reasons,
            "lockWarning" to evidence.lockWarnings,
            "staging" to evidence.stagingNames
        )
        val entries = fields + collections.flatMap { (key, values) ->
            values.mapIndexed { index, value -> "$key.$index" to value }
        }
        return entries.joinToString("") { (key, value) -> "$key:${value.length}:$value\n" }
    }

    fun digest(evidence: Evidence): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(evidence).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun document(
        report: PhysicalReadOnlyPublicationRecoveryReport.Report,
        operatorReference: String,
        disposition: String,
        note: String
    ): Resolution {
        require(operatorReference.isNotBlank()) { "Missing operator reference" }
        require(note.isNotBlank()) { "Missing resolution note" }
        require(disposition in setOf("INVESTIGATE", "QUARANTINE", "ESCALATE")) {
            "Only non-authorizing dispositions are supported"
        }
        return Resolution(digest(snapshot(report)), operatorReference, disposition, note)
    }

    fun verify(resolution: Resolution, report: PhysicalReadOnlyPublicationRecoveryReport.Report): Boolean =
        resolution.evidenceSha256 == digest(snapshot(report)) &&
            resolution.operatorReference.isNotBlank() &&
            resolution.note.isNotBlank() &&
            resolution.disposition in setOf("INVESTIGATE", "QUARANTINE", "ESCALATE")
}
