package org.jason.pi.gcs.simulation

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidencePublicationLockTest {
    private fun record() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner", authorizationReference = "auth",
        controllerModel = "C-887", controllerSerialReference = "serial",
        firmwareVersion = "1", gcsManualRevision = "manual", stageModel = "H-811",
        axisMappingConfirmed = true, endpointVerified = true,
        safetyProcedureReference = "safety", interlocksVerified = true,
        emergencyStopVerified = true, operatorReference = "operator",
        witnessReference = "witness", approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-publication-lock")
        try { action(root) } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun simultaneousCooperatingPublisherIsRejected() = withRoot { root ->
        val target = root.resolve("published")
        val lock = root.resolve("published.publish.lock")
        PhysicalReadOnlyEvidencePublisher.withPublicationLock(target) {
            assertTrue(Files.isRegularFile(lock))
            assertFailsWith<FileAlreadyExistsException> {
                PhysicalReadOnlyEvidencePublisher.withPublicationLock(target) {
                    error("Second publisher must not enter its critical section")
                }
            }
            assertFalse(Files.exists(target))
        }
        assertFalse(Files.exists(lock))
    }

    @Test fun staleLockRequiresOperatorInsteadOfAutoRecovery() = withRoot { root ->
        val target = root.resolve("published")
        val lock = root.resolve("published.publish.lock")
        Files.createFile(lock)
        assertFailsWith<FileAlreadyExistsException> {
            PhysicalReadOnlyEvidencePublisher.publish(target, record())
        }
        assertTrue(Files.exists(lock))
        assertFalse(Files.exists(target))
    }

    @Test fun exceptionReleasesOwnedLockAndPreservesOriginalFailure() = withRoot { root ->
        val target = root.resolve("published")
        val error = assertFailsWith<IllegalStateException> {
            PhysicalReadOnlyEvidencePublisher.withPublicationLock(target) {
                throw IllegalStateException("injected publication failure")
            }
        }
        assertEquals("injected publication failure", error.message)
        assertFalse(Files.exists(root.resolve("published.publish.lock")))
    }

    @Test fun normalPublishProducesValidEvidenceAndNoResidualLock() = withRoot { root ->
        val target = root.resolve("published")
        PhysicalReadOnlyEvidencePublisher.publish(target, record())
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, record()).isEmpty())
        assertFalse(Files.exists(root.resolve("published.publish.lock")))
    }
}
