package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyRecoveryAuditRevalidationTest {
    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-audit-recheck")
        try { block(root) } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } }
        }
    }

    private fun note(root: Path): PhysicalReadOnlyRecoveryResolutionAudit.Resolution {
        val original = PhysicalReadOnlyPublicationRecoveryReport.collect(root, "approved", null)
        return PhysicalReadOnlyRecoveryResolutionAudit.document(
            original, "operator-reference", "INVESTIGATE", "Inspect without changing files"
        )
    }

    @Test fun unchangedOrphanLockMatchesButNeverAuthorizes() = withRoot { root ->
        Files.writeString(root.resolve("approved.publish.lock"), "legacy lock")
        val resolution = note(root)
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.check(root, "approved", null, resolution)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.MATCHES_CURRENT_SNAPSHOT, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.currentDecision)
    }

    @Test fun changedStagingInvalidatesPriorResolution() = withRoot { root ->
        Files.writeString(root.resolve("approved.publish.lock"), "legacy lock")
        val resolution = note(root)
        Files.createDirectory(root.resolve(".pi-readonly-staging-aborted"))
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.check(root, "approved", null, resolution)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID, result.status)
        assertTrue("ORPHANED_STAGING_DIRECTORY" in result.currentReasons)
    }

    @Test fun removedLockInvalidatesPriorResolution() = withRoot { root ->
        val lock = root.resolve("approved.publish.lock")
        Files.writeString(lock, "legacy lock")
        val resolution = note(root)
        Files.delete(lock)
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.check(root, "approved", null, resolution)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID, result.status)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.NO_BUNDLE, result.currentDecision)
    }

    @Test fun invalidParentAlwaysBlocksInspection() = withRoot { root ->
        val resolution = note(root)
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.check(root.resolve("missing"), "approved", null, resolution)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED, result.status)
    }

    @Test fun tamperedOperatorDispositionFailsRevalidation() = withRoot { root ->
        Files.writeString(root.resolve("approved.publish.lock"), "legacy lock")
        val resolution = note(root).copy(disposition = "APPROVE")
        val result = PhysicalReadOnlyRecoveryAuditRevalidation.check(root, "approved", null, resolution)
        assertEquals(PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID, result.status)
    }
}
