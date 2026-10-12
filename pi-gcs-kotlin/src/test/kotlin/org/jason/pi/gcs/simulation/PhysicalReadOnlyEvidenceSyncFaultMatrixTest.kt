package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure filesystem tests; no PI controller or GCS transport is opened. */
class PhysicalReadOnlyEvidenceSyncFaultMatrixTest {
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
        val root = Files.createTempDirectory("pi-sync-fault-matrix")
        try { block(root) } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun eachForceFailureStopsPublicationAndCleansStaging() = withRoot { root ->
        val names = listOf("approval.json", "integrity.json", "bundle.json")
        for (failedName in names) {
            val target = root.resolve("published")
            val forced = mutableListOf<String>()
            var moved = false
            val failure = assertFailsWith<IOException> {
                PhysicalReadOnlyEvidencePublisher.publishWithSyncCheck(
                    target, record(),
                    sync = { staging ->
                        PhysicalReadOnlyEvidencePublisher.syncBundleFiles(staging) { file ->
                            forced += file.fileName.toString()
                            if (file.fileName.toString() == failedName) {
                                throw IOException("injected force failure: $failedName")
                            }
                        }
                    },
                    beforeMove = { moved = true }
                )
            }
            assertTrue(failure.message.orEmpty().contains(failedName))
            assertEquals(names.take(names.indexOf(failedName) + 1), forced)
            assertFalse(moved)
            assertFalse(Files.exists(target))
            Files.list(root).use { entries -> assertEquals(0L, entries.count()) }
        }
    }

    @Test fun successfulForceOrderPrecedesAtomicPublication() = withRoot { root ->
        val forced = mutableListOf<String>()
        val target = root.resolve("published")
        PhysicalReadOnlyEvidencePublisher.publishWithSyncCheck(
            target, record(),
            sync = { staging ->
                PhysicalReadOnlyEvidencePublisher.syncBundleFiles(staging) { file ->
                    forced += file.fileName.toString()
                    java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use {
                        it.force(true)
                    }
                }
            },
            beforeMove = {
                assertEquals(listOf("approval.json", "integrity.json", "bundle.json"), forced)
                assertFalse(Files.exists(target))
            }
        )
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(target, record()).isEmpty())
    }
}
