package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceRecoveryTest {
    private fun record() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner-ref", authorizationReference = "auth-ref",
        controllerModel = "C-887", controllerSerialReference = "serial-ref",
        firmwareVersion = "1.0", gcsManualRevision = "manual-rev",
        stageModel = "H-811", axisMappingConfirmed = true, endpointVerified = true,
        safetyProcedureReference = "safety-ref", interlocksVerified = true,
        emergencyStopVerified = true, operatorReference = "operator-ref",
        witnessReference = "witness-ref", approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    private fun withRoot(test: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("pi-recovery")
        try { test(root) } finally {
            Files.walk(root).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun missingBundleDoesNotResume() = withRoot { root ->
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.NO_BUNDLE,
            PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record()).decision)
    }

    @Test fun validBundleRequiresManualReview() = withRoot { root ->
        PhysicalReadOnlyEvidencePublisher.publish(root.resolve("approved"), record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.VALIDATE_FOR_MANUAL_REVIEW,
            PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record()).decision)
    }

    @Test fun orphanedStagingRequiresOperatorAndIsPreserved() = withRoot { root ->
        val orphan = Files.createDirectory(root.resolve(".pi-readonly-staging-old"))
        Files.writeString(orphan.resolve("incomplete"), "failure")
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, result.decision)
        assertTrue(Files.exists(orphan.resolve("incomplete")))
    }

    @Test fun tamperedBundleBlocksAcceptance() = withRoot { root ->
        val target = root.resolve("approved")
        PhysicalReadOnlyEvidencePublisher.publish(target, record())
        Files.writeString(target.resolve("approval.json"), "invalid")
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
            PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record()).decision)
    }

    @Test fun missingRecordBlocksAcceptance() = withRoot { root ->
        PhysicalReadOnlyEvidencePublisher.publish(root.resolve("approved"), record())
        val result = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", null)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, result.decision)
        assertTrue("MISSING_APPROVAL_RECORD" in result.reasons)
    }

    @Test fun invalidNameIsRejected() = withRoot { root ->
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK,
            PhysicalReadOnlyEvidenceRecovery.inspect(root, "../escape", record()).decision)
    }
}
