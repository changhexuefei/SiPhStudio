package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhysicalReadOnlyPublicationRecoveryReportTest {
    private fun sandbox(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-offline-recovery-report")
        try { block(root) } finally {
            Files.walk(root).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun lockAndOrphanStagingProduceOperatorEvidence() = sandbox { root ->
        Files.writeString(root.resolve("approved.publish.lock"),
            "version=1\nrunId=3c785eab-2dad-4681-9e08-7ceeb6e3699d\npid=1234\ncreatedUtc=2026-10-12T00:00:00Z\n")
        val staging = Files.createDirectory(root.resolve(".pi-readonly-staging-interrupted"))
        val report = PhysicalReadOnlyPublicationRecoveryReport.collect(root, "approved", null)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, report.decision)
        assertTrue(report.lockPresent)
        assertEquals(1234L, assertNotNull(report.lock).pid)
        assertTrue("PUBLICATION_LOCK_PRESENT" in report.reasons)
        assertTrue("ORPHANED_STAGING_DIRECTORY" in report.reasons)
        assertEquals(listOf(staging.fileName.toString()), report.stagingNames)
        assertTrue(Files.exists(staging))
    }

    @Test fun legacyLockRemainsVisibleButUntrusted() = sandbox { root ->
        val lock = root.resolve("approved.publish.lock")
        Files.writeString(lock, "old partial lock")
        val report = PhysicalReadOnlyPublicationRecoveryReport.collect(root, "approved", null)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.REQUIRE_OPERATOR, report.decision)
        assertEquals(listOf("INVALID_LOCK_FORMAT"), assertNotNull(report.lock).warnings)
        assertTrue(Files.exists(lock))
    }

    @Test fun emptyDirectoryHasNoRecoveryArtifacts() = sandbox { root ->
        val report = PhysicalReadOnlyPublicationRecoveryReport.collect(root, "approved", null)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.NO_BUNDLE, report.decision)
        assertTrue(report.stagingNames.isEmpty())
        assertEquals(null, report.lock)
    }

    @Test fun unsafeNameDoesNotResolveOutsideParent() = sandbox { root ->
        val report = PhysicalReadOnlyPublicationRecoveryReport.collect(root, "../outside", null)
        assertEquals(PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK, report.decision)
        assertTrue("INVALID_DESTINATION_NAME" in report.reasons)
    }
}
