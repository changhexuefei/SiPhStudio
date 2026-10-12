package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Read-only operator evidence for interrupted offline bundle publication.
 * Diagnostic identifiers are untrusted; no auto-unlock, resume or PI motion.
 */
object PhysicalReadOnlyPublicationRecoveryReport {
    data class Report(
        val decision: PhysicalReadOnlyEvidenceRecovery.Decision,
        val reasons: List<String>,
        val lockPresent: Boolean,
        val lock: PhysicalReadOnlyPublicationLockAudit.Snapshot?,
        val stagingNames: List<String>
    )

    fun collect(parent: Path, publishedName: String, record: PhysicalReadOnlyApprovalRecord?): Report {
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(parent, publishedName, record)
        if (assessment.reasons.contains("INVALID_DESTINATION_NAME") ||
            assessment.reasons.contains("INVALID_PARENT_DIRECTORY") ||
            assessment.reasons.contains("PARENT_LIST_FAILED")) {
            return Report(assessment.decision, assessment.reasons, false, null, emptyList())
        }
        val staging = try {
            Files.list(parent).use { entries ->
                entries.map { it.fileName.toString() }
                    .filter { it.startsWith(".pi-readonly-staging-") }
                    .sorted().toList()
            }
        } catch (_: Exception) {
            return Report(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("PARENT_LIST_FAILED"), false, null, emptyList())
        }
        val lockPath = parent.resolve("$publishedName.publish.lock")
        val present = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
        val lockAudit = if (present) PhysicalReadOnlyPublicationLockAudit.read(lockPath) else null
        val reasons = (assessment.reasons +
            if (present) listOf("PUBLICATION_LOCK_PRESENT") else emptyList() +
            if (staging.isNotEmpty()) listOf("ORPHANED_STAGING_DIRECTORY") else emptyList()).distinct()
        val decision = if (present || staging.isNotEmpty()) {
            PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR
        } else assessment.decision
        return Report(decision, reasons, present, lockAudit, staging)
    }
}
