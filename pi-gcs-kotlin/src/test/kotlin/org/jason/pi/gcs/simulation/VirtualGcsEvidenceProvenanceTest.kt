package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualGcsEvidenceProvenanceTest {
    @Test
    fun manifestBindsExactEvidenceBytesRunAndRevision() {
        val dir = Files.createTempDirectory("gcs-provenance")
        try {
            Files.writeString(dir.resolve("summary.json"), "{\"result\":\"PASS\"}\n")
            Files.writeString(dir.resolve("trace.jsonl"), "{\"sequence\":1}\n")
            VirtualGcsEvidenceProvenance.write(dir, "run-123", "abcdef1234")
            assertTrue(VirtualGcsEvidenceProvenance.matches(dir, "run-123", "abcdef1234"))
            assertFalse(VirtualGcsEvidenceProvenance.matches(dir, "run-456", "abcdef1234"))
            assertFalse(VirtualGcsEvidenceProvenance.matches(dir, "run-123", "different123"))
            Files.writeString(dir.resolve("trace.jsonl"), "{\"sequence\":2}\n")
            assertFalse(VirtualGcsEvidenceProvenance.matches(dir, "run-123", "abcdef1234"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidRunIdentifiersAndMissingManifestFailClosed() {
        val dir = Files.createTempDirectory("gcs-provenance-negative")
        try {
            Files.writeString(dir.resolve("summary.json"), "{}")
            Files.writeString(dir.resolve("trace.jsonl"), "{}\n")
            assertFalse(VirtualGcsEvidenceProvenance.matches(dir, "run-1", "abc"))
            assertTrue(runCatching {
                VirtualGcsEvidenceProvenance.write(dir, "../outside", "abc")
            }.isFailure)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
