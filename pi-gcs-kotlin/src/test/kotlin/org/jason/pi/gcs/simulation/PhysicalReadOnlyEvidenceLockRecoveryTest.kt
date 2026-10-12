package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceLockRecoveryTest {
    private fun record() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner", authorizationReference = "auth",
        controllerModel = "C-887", controllerSerialReference = "serial",
        firmwareVersion = "1", gcsManualRevision = "manual",
        stageModel = "H-811", axisMappingConfirmed = true, endpointVerified = true,
        safetyProcedureReference = "safety", interlocksVerified = true,
        emergencyStopVerified = true, operatorReference = "operator",
        witnessReference = "witness", approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-lock-recovery")
        try { block(root) } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun orphanedLockWithoutBundleRequiresOperator() = withRoot { root ->
        val lock = root.resolve("approved.publish.lock")
        Files.createFile(lock)
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.decision)
        assertEquals(listOf("PUBLICATION_LOCK_PRESENT"), result.reasons)
        assertTrue(Files.exists(lock))
    }

    @Test fun validPublishedBundleWithLockCannotBeAccepted() = withRoot { root ->
        val target = root.resolve("approved")
        PhysicalReadOnlyEvidencePublisher.publish(target, record())
        val lock = root.resolve("approved.publish.lock")
        Files.createFile(lock)
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.decision)
        assertTrue("PUBLICATION_LOCK_PRESENT" in result.reasons)
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, record()).isEmpty())
        assertTrue(Files.exists(lock))
    }

    @Test fun simultaneousOrphanedLockAndStagingPreservesBothReasons() = withRoot { root ->
        val lock = root.resolve("approved.publish.lock")
        val staging = root.resolve(".pi-readonly-staging-interrupted")
        Files.createFile(lock)
        Files.createDirectory(staging)
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.decision)
        assertEquals(
            listOf("ORPHANED_STAGING_DIRECTORY", "PUBLICATION_LOCK_PRESENT"), result.reasons
        )
        assertTrue(Files.exists(lock) && Files.exists(staging))
    }

    @Test fun unrelatedLockDoesNotPreventValidBundleReview() = withRoot { root ->
        PhysicalReadOnlyEvidencePublisher.publish(root.resolve("approved"), record())
        Files.createFile(root.resolve("different.publish.lock"))
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.VALIDATE_FOR_MANUAL_REVIEW, result.decision)
    }
}
