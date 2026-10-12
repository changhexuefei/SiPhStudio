package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidencePublisherTest {
    private fun record() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner-ref",
        authorizationReference = "auth-ref",
        controllerModel = "C-887",
        controllerSerialReference = "serial-ref",
        firmwareVersion = "1.0",
        gcsManualRevision = "manual-rev",
        stageModel = "H-811",
        axisMappingConfirmed = true,
        endpointVerified = true,
        safetyProcedureReference = "safety-ref",
        interlocksVerified = true,
        emergencyStopVerified = true,
        operatorReference = "operator-ref",
        witnessReference = "witness-ref",
        approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    private fun withDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-atomic-publish-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun publishesOnlyCompleteValidatedBundle() = withDirectory { root ->
        val target = root.resolve("published")
        val source = record()
        PhysicalReadOnlyEvidencePublisher.publish(target, source)
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, source).isEmpty())
        Files.list(root).use { entries ->
            assertTrue(entries.map { it.fileName.toString() }.toList() == listOf("published"))
        }
    }

    @Test
    fun nullRecordLeavesNoDestinationOrStaging() = withDirectory { root ->
        val target = root.resolve("published")
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyEvidencePublisher.publish(target, null)
        }
        assertFalse(Files.exists(target))
        Files.list(root).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test
    fun existingDestinationIsPreservedWithoutStaging() = withDirectory { root ->
        val target = root.resolve("published")
        Files.createDirectory(target)
        Files.writeString(target.resolve("sentinel"), "do-not-replace")
        assertFailsWith<IllegalArgumentException> {
            PhysicalReadOnlyEvidencePublisher.publish(target, record())
        }
        assertTrue(Files.readString(target.resolve("sentinel")) == "do-not-replace")
        Files.list(root).use { assertTrue(it.count() == 1L) }
    }

    @Test
    fun invalidRecordStillProducesConsistentNoGoEvidence() = withDirectory { root ->
        val source = record().copy(interlocksVerified = false)
        val target = root.resolve("published")
        PhysicalReadOnlyEvidencePublisher.publish(target, source)
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, source).isEmpty())
        assertTrue(Files.readString(target.resolve("approval.json")).contains("NO_GO"))
    }
    @Test
    fun injectedFailureLeavesNoPublishedBundleOrStaging() = withDirectory { root ->
        val target = root.resolve("published")
        assertFailsWith<IllegalStateException> {
            PhysicalReadOnlyEvidencePublisher.publishWithPreMoveCheck(target, record()) {
                throw IllegalStateException("injected pre-publish failure")
            }
        }
        assertFalse(Files.exists(target))
        Files.list(root).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test
    fun injectedFailurePreservesOtherPublishedBundles() = withDirectory { root ->
        val original = root.resolve("original")
        PhysicalReadOnlyEvidencePublisher.publish(original, record())
        val next = root.resolve("next")
        assertFailsWith<IllegalStateException> {
            PhysicalReadOnlyEvidencePublisher.publishWithPreMoveCheck(next, record()) {
                throw IllegalStateException("injected")
            }
        }
        assertFalse(Files.exists(next))
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(original, record()).isEmpty())
        Files.list(root).use { assertTrue(it.count() == 1L) }
    }

}
