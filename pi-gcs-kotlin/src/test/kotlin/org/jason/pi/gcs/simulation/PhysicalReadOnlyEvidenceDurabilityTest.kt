package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceDurabilityTest {
    @Test
    fun regularFileProbeDoesNotClaimPowerLossSafety() {
        val path = Files.createTempFile("pi-durability", ".json")
        try {
            Files.writeString(path, "{\"schemaVersion\":1}")
            val before = Files.readAllBytes(path)
            val report = PhysicalReadOnlyEvidenceDurability.probeFileSync(path)
            assertTrue(report.fileSync != PhysicalReadOnlyEvidenceDurability.FileSync.NOT_ATTEMPTED)
            assertTrue(before.contentEquals(Files.readAllBytes(path)))
            assertEquals(PhysicalReadOnlyEvidenceDurability.DirectorySync.NOT_VERIFIED, report.directorySync)
            assertEquals(PhysicalReadOnlyEvidenceDurability.PowerLossDurability.NOT_VERIFIED, report.powerLossDurability)
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun missingFileDoesNotAttemptSync() {
        val dir = Files.createTempDirectory("pi-durability-missing")
        try {
            val report = PhysicalReadOnlyEvidenceDurability.probeFileSync(dir.resolve("missing.json"))
            assertEquals(PhysicalReadOnlyEvidenceDurability.FileSync.NOT_ATTEMPTED, report.fileSync)
            assertEquals("INVALID_FILE", report.failureType)
        } finally { Files.deleteIfExists(dir) }
    }

    @Test
    fun directoryIsNotMistakenForRegularFile() {
        val dir = Files.createTempDirectory("pi-durability-directory")
        try {
            assertEquals(PhysicalReadOnlyEvidenceDurability.FileSync.NOT_ATTEMPTED,
                PhysicalReadOnlyEvidenceDurability.probeFileSync(dir).fileSync)
        } finally { Files.deleteIfExists(dir) }
    }
}
