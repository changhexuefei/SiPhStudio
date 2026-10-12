package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Read-only, offline startup reconciliation. Never deletes evidence or
 * interprets a valid bundle as hardware permission or crash-durable approval.
 */
object PhysicalReadOnlyEvidenceRecovery {
    enum class Decision { NO_BUNDLE, VALIDATE_FOR_MANUAL_REVIEW, REQUIRE_OPERATOR, BLOCK }
    data class Assessment(val decision: Decision, val reasons: List<String>)

    fun inspect(
        parent: Path,
        publishedName: String,
        record: PhysicalReadOnlyApprovalRecord?
    ): Assessment {
        if (publishedName.isBlank() || publishedName == "." || publishedName == ".." ||
            publishedName.contains('/') || publishedName.contains('\\')) {
            return Assessment(Decision.BLOCK, listOf("INVALID_DESTINATION_NAME"))
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return Assessment(Decision.BLOCK, listOf("INVALID_PARENT_DIRECTORY"))
        }
        val entries = try {
            Files.list(parent).use { stream -> stream.map { it.fileName.toString() }.toList() }
        } catch (_: Exception) {
            return Assessment(Decision.BLOCK, listOf("PARENT_LIST_FAILED"))
        }
        val staging = entries.filter { it.startsWith(".pi-readonly-staging-") }
        val target = parent.resolve(publishedName)
        val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        if (staging.isNotEmpty()) {
            // Do not delete or guess which partially prepared staging directory to reuse.
            return Assessment(Decision.REQUIRE_OPERATOR, listOf("ORPHANED_STAGING_DIRECTORY"))
        }
        if (!exists) return Assessment(Decision.NO_BUNDLE, emptyList())
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return Assessment(Decision.BLOCK, listOf("INVALID_PUBLISHED_DIRECTORY"))
        }
        val errors = PhysicalReadOnlyEvidenceBundle.verify(target, record)
        return if (errors.isEmpty()) {
            // Consistency allows manual review only. No automatic resume or motion.
            Assessment(Decision.VALIDATE_FOR_MANUAL_REVIEW, emptyList())
        } else {
            Assessment(Decision.BLOCK, errors)
        }
    }
}
