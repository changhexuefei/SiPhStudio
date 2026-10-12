package org.jason.pi.gcs.simulation

import java.nio.file.Path
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/**
 * Recollects current offline recovery evidence before relying on an old
 * operator note. A match is informational only, never an approval or unlock.
 *
 * This is a point-in-time check, not an atomic filesystem snapshot; files may
 * change immediately after collection.
 */
object PhysicalReadOnlyRecoveryAuditRevalidation {
    enum class Status { MATCHES_CURRENT_SNAPSHOT, CHANGED_OR_INVALID, INSPECTION_BLOCKED }

    enum class FailureCategory { NONE, ACCESS_DENIED, PATH_UNAVAILABLE, IO_ERROR, OTHER_ERROR, SNAPSHOT_CHANGED }

    data class Result(
        val status: Status,
        val currentDecision: PhysicalReadOnlyEvidenceRecovery.Decision,
        val currentReasons: List<String>,
        val failureCategory: FailureCategory = FailureCategory.NONE,
        val failedSample: Int? = null
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

    /**
     * Two-sample read-only inspection without a signed operator resolution.
     * This API never reports MATCHES_CURRENT_SNAPSHOT or authorizes recovery.
     */
    fun inspectWithoutResolution(
        parent: Path,
        publishedName: String,
        record: PhysicalReadOnlyApprovalRecord? = null
    ): Result = inspectWithCollector {
        PhysicalReadOnlyPublicationRecoveryReport.collect(parent, publishedName, record)
    }

    /** The same two-sample collection and failure handling serve both consumers. */
    private data class Sampled(
        val report: PhysicalReadOnlyPublicationRecoveryReport.Report? = null,
        val failure: Result? = null
    )

    private fun sample(
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): Sampled {
        val first = try { collect() } catch (error: Exception) {
            return Sampled(failure = samplingFailure(error, 1))
        }
        val second = try { collect() } catch (error: Exception) {
            return Sampled(failure = samplingFailure(error, 2))
        }
        if (first != second) {
            return Sampled(failure = Result(
                Status.INSPECTION_BLOCKED,
                PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
                listOf("SNAPSHOT_CHANGED_DURING_INSPECTION"),
                FailureCategory.SNAPSHOT_CHANGED
            ))
        }
        return Sampled(report = second)
    }

    /** Public sampling seam for JVM consumers and deterministic contract tests. */
    fun inspectWithCollector(
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): Result {
        val sampled = sample(collect)
        sampled.failure?.let { return it }
        val report = requireNotNull(sampled.report)
        return Result(
            if (report.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK) {
                Status.INSPECTION_BLOCKED
            } else {
                Status.CHANGED_OR_INVALID
            },
            report.decision,
            report.reasons
        )
    }

    /** Two samples detect observed changes, not all filesystem TOCTOU races. */
    internal fun checkWithCollector(
        resolution: PhysicalReadOnlyRecoveryResolutionAudit.Resolution,
        collect: () -> PhysicalReadOnlyPublicationRecoveryReport.Report
    ): Result {
        val sampled = sample(collect)
        sampled.failure?.let { return it }
        val report = requireNotNull(sampled.report)
        if (report.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK) {
            return Result(Status.INSPECTION_BLOCKED, report.decision, report.reasons)
        }
        val status = if (PhysicalReadOnlyRecoveryResolutionAudit.verify(resolution, report)) {
            Status.MATCHES_CURRENT_SNAPSHOT
        } else {
            Status.CHANGED_OR_INVALID
        }
        return Result(status, report.decision, report.reasons)
    }

    private fun samplingFailure(error: Exception, sample: Int): Result {
        val category = when (error) {
            is AccessDeniedException, is SecurityException -> FailureCategory.ACCESS_DENIED
            is NoSuchFileException -> FailureCategory.PATH_UNAVAILABLE
            is IOException -> FailureCategory.IO_ERROR
            else -> FailureCategory.OTHER_ERROR
        }
        return Result(Status.INSPECTION_BLOCKED,
            PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
            listOf("SNAPSHOT_SAMPLE_FAILED"),
            category, sample)
    }
}
