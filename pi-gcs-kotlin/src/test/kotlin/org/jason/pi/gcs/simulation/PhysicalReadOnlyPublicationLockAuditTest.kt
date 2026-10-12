package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyPublicationLockAuditTest {
    private fun withRoot(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("pi-lock-audit")
        try { test(root) } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun ownedLockContainsParseableDiagnosticMetadata() = withRoot { root ->
        val target = root.resolve("evidence")
        PhysicalReadOnlyEvidencePublisher.withPublicationLock(target) {
            val lock = root.resolve("evidence.publish.lock")
            val snapshot = PhysicalReadOnlyPublicationLockAudit.read(lock)
            assertTrue(snapshot.warnings.isEmpty())
            assertTrue(snapshot.runId != null)
            assertTrue(snapshot.pid != null && snapshot.pid > 0)
            assertTrue(snapshot.createdUtc != null)
            assertFalse(Files.exists(target))
        }
        assertFalse(Files.exists(root.resolve("evidence.publish.lock")))
    }

    @Test fun malformedOrLegacyLockNeverCountsAsApproval() = withRoot { root ->
        val lock = root.resolve("evidence.publish.lock")
        Files.writeString(lock, "old lock")
        assertEquals(listOf("INVALID_LOCK_FORMAT"), PhysicalReadOnlyPublicationLockAudit.read(lock).warnings)
        Files.writeString(lock, "version=99\nrunId=abc\n")
        assertEquals(listOf("UNKNOWN_LOCK_SCHEMA"), PhysicalReadOnlyPublicationLockAudit.read(lock).warnings)
        assertTrue(Files.exists(lock))
    }

    @Test fun oversizedLockIsRejectedWithoutDeletingIt() = withRoot { root ->
        val lock = root.resolve("evidence.publish.lock")
        Files.writeString(lock, "x".repeat(4097))
        assertEquals(listOf("LOCK_TOO_LARGE"), PhysicalReadOnlyPublicationLockAudit.read(lock).warnings)
        assertTrue(Files.exists(lock))
    }

    @Test fun missingLockIsFailClosed() = withRoot { root ->
        assertEquals(
            listOf("INVALID_LOCK_FILE"),
            PhysicalReadOnlyPublicationLockAudit.read(root.resolve("missing.publish.lock")).warnings
        )
    }
}
