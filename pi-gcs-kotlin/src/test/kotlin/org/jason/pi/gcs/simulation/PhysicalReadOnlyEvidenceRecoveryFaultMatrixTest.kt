package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Offline fault matrix: no PI device, TCP connection, or mechanical motion. */
class PhysicalReadOnlyEvidenceRecoveryFaultMatrixTest {
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

    private fun sandbox(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-recovery-matrix")
        try { test(root) } finally {
            Files.walk(root).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun invalidDestinationNamesFailClosed() = sandbox { root ->
        for (name in listOf("", " ", ".", "..", "../outside", "nested/child", "nested\\child")) {
            val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(root, name, record())
            assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, assessment.decision, name)
        }
    }

    @Test fun ordinaryFileAtDestinationFailsClosed() = sandbox { root ->
        Files.writeString(root.resolve("approved"), "not a bundle")
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, assessment.decision)
        assertTrue("INVALID_PUBLISHED_DIRECTORY" in assessment.reasons)
    }

    @Test fun missingManifestWithNoOrphanBlocks() = sandbox { root ->
        val target = root.resolve("approved")
        PhysicalReadOnlyEvidencePublisher.publish(target, record())
        Files.delete(target.resolve("bundle.json"))
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, assessment.decision)
        assertTrue("MISSING_BUNDLE_FILE" in assessment.reasons)
    }

    @Test fun existingBundlePlusOrphanRequiresOperatorAndPreservesBoth() = sandbox { root ->
        val target = root.resolve("approved")
        PhysicalReadOnlyEvidencePublisher.publish(target, record())
        val orphan = Files.createDirectory(root.resolve(".pi-readonly-staging-interrupted"))
        Files.writeString(orphan.resolve("evidence.partial"), "partial")
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(root, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, assessment.decision)
        assertTrue(Files.exists(orphan.resolve("evidence.partial")))
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, record()).isEmpty())
    }

    @Test fun mismatchedFirmwareBlocksReconciliation() = sandbox { root ->
        val source = record()
        PhysicalReadOnlyEvidencePublisher.publish(root.resolve("approved"), source)
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(
            root, "approved", source.copy(firmwareVersion = "changed")
        )
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, assessment.decision)
        assertTrue("RECORD_METADATA_MISMATCH" in assessment.reasons)
    }

    @Test fun ordinaryParentFileFailsClosed() = sandbox { root ->
        val parent = root.resolve("not-a-directory")
        Files.writeString(parent, "file")
        val assessment = PhysicalReadOnlyEvidenceRecovery.inspect(parent, "approved", record())
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, assessment.decision)
        assertTrue("INVALID_PARENT_DIRECTORY" in assessment.reasons)
    }
}
