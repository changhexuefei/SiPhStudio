package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceIntegrityTest {
    @Test
    fun checksumIsRepeatableAndTamperingFailsClosed() {
        val dir = Files.createTempDirectory("pi-readonly-checksum")
        try {
            val evidence = dir.resolve("approval.json")
            val manifest = dir.resolve("integrity.json")
            PhysicalReadOnlyApprovalEvidence.export(null, evidence)
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            val originalManifest = Files.readAllBytes(manifest)
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest).isEmpty())
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            assertTrue(originalManifest.contentEquals(Files.readAllBytes(manifest)))

            // A byte-only change must be rejected even when JSON remains valid.
            Files.writeString(evidence, Files.readString(evidence) + " ")
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest)
                .contains("EVIDENCE_SHA256_MISMATCH"))

            // Checksum is not a signature: altered evidence and regenerated manifest
            // can still match. Review against a trusted source record is separate.
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest).isEmpty())

            Files.writeString(manifest, "{not-json")
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest)
                .contains("INVALID_INTEGRITY_MANIFEST"))
            Files.delete(manifest)
            assertEquals(listOf("MISSING_EVIDENCE_OR_MANIFEST"),
                PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest))
        } finally {
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun invalidApprovalEvidenceCannotBeFingerprintStamped() {
        val dir = Files.createTempDirectory("pi-invalid-approval")
        try {
            val evidence = dir.resolve("approval.json")
            Files.writeString(evidence, "{invalid")
            var rejected = false
            try {
                PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, dir.resolve("integrity.json"))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
}
