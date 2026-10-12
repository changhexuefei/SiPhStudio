package org.jason.pi.gcs.simulation

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceAtomicMoveSafetyTest {
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

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-atomic-fault")
        try { block(root) } finally {
            Files.walk(root).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun unsupportedAtomicMoveNeverFallsBack() = withRoot { root ->
        val target = root.resolve("published")
        var moveCalls = 0
        assertFailsWith<AtomicMoveNotSupportedException> {
            PhysicalReadOnlyEvidencePublisher.publishWithMoveCheck(
                target, record(), move = { from, to ->
                    moveCalls++
                    throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected")
                }
            )
        }
        assertEquals(1, moveCalls)
        assertFalse(Files.exists(target))
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun newlyOccupiedDestinationIsNotOverwritten() = withRoot { root ->
        val target = root.resolve("published")
        var moved = false
        assertFailsWith<FileAlreadyExistsException> {
            PhysicalReadOnlyEvidencePublisher.publishWithMoveCheck(
                target, record(),
                beforeMove = {
                    Files.createDirectory(target)
                    Files.writeString(target.resolve("sentinel"), "unchanged")
                },
                move = { _, _ -> moved = true }
            )
        }
        assertFalse(moved)
        assertEquals("unchanged", Files.readString(target.resolve("sentinel")))
        Files.list(root).use { assertEquals(1L, it.count()) }
    }

    @Test fun injectedIoFailureDoesNotLeavePartialDestination() = withRoot { root ->
        val target = root.resolve("published")
        assertFailsWith<java.io.IOException> {
            PhysicalReadOnlyEvidencePublisher.publishWithMoveCheck(
                target, record(), move = { _, _ -> throw java.io.IOException("injected move failure") }
            )
        }
        assertFalse(Files.exists(target))
        Files.list(root).use { assertEquals(0L, it.count()) }
    }
}
