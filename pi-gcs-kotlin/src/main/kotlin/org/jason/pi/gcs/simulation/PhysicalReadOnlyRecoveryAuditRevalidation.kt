package org.jason.pi.gcs.simulation

import java.nio.file.Path

/**
 * Recollects current offline recovery evidence before relying on an old
 * operator note. A match is informational only, never an approval or unlock.
 *
 * This is a point-in-time check, not an atomic filesystem snapshot; files may
 * change immediately after collection.
 */
object PhysicalReadOnlyRecoveryAuditRevalidation {
    enum class Status { MATCHES_CURRENT_SNAPSHOT, CHANGED_OR_INVALID, INSPECTION_BLOCKED }

    data class Result(
        val status: Status,
        val currentDecision: PhysicalReadOnlyEvidenceRecovery.Decision,
        val currentReasons: List<String>
    )

    fun check(
        parent: Path,
        publishedName: String,
        record: PhysicalReadOnlyApprovalRecord?,
        resolution: PhysicalReadOnlyRecoveryResolutionAudit.Resolution
    ): Result {
        return checkWithCollector(resolution) {
            PhysicalReadOnlyPublicationRecoveryReport.collect(parent, publishedName, record)
        }
    }

    /** Two samples catch observable changes, but cannot eliminate filesystem TOCTOU. */
    internal fun checkWithCollector(
        resolution: PhysicalReadOnlyRecoveryResolutionAudit.Resolution,
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): Result {
        val current = try {
            collect()
        } catch (_: Exception) {
            return Result(Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_SAMPLE_FAILED"))
        }
        val confirm = try {
            collect()
        } catch (_: Exception) {
            return Result(Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_SAMPLE_FAILED"))
        }
        if (current != confirm) {
            return Result(
                Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_CHANGED_DURING_INSPECTION")
            )
        }
        if (current.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK) {
            return Result(Status.INSPECTION_BLOCKED, current.decision, current.reasons)
        }
        val status = if (PhysicalReadOnlyRecoveryResolutionAudit.verify(resolution, current)) {
            Status.MATCHES_CURRENT_SNAPSHOT
        } else {
            Status.CHANGED_OR_INVALID
        }
        return Result(status, current.decision, current.reasons)
    }
}
