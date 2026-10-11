package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fail closed for stale or incomplete CI evidence inventories. */
class VirtualGcsManifestValidationTest {
    @Test
    fun staleExtraCycleDirectoryIsReportedExplicitly() {
        val root = Files.createTempDirectory("gcs-extra-cycle")
        try {
            Files.createDirectories(root.resolve("cycle-02"))
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1))
            val report = Files.readString(root.resolve("aggregate-summary.json"))
            assertTrue(report.contains("cycle-02:UNEXPECTED_ENTRY"))
            assertTrue(report.contains("cycle-01:MISSING_EVIDENCE"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun unrecognizedFileIsRejectedBeforeOtherwiseValidEvidence() {
        val root = Files.createTempDirectory("gcs-extra-file")
        try {
            val cycle = Files.createDirectories(root.resolve("cycle-01"))
            Files.writeString(cycle.resolve("summary.json"), "{}")
            Files.writeString(cycle.resolve("trace.jsonl"), "{}\n")
            Files.writeString(cycle.resolve("stale.json"), "{}")
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1))
            assertTrue(Files.readString(root.resolve("aggregate-summary.json")).contains("cycle-01:UNEXPECTED_FILE"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun missingProvenanceManifestGetsSpecificFailureReason() {
        val root = Files.createTempDirectory("gcs-missing-manifest")
        try {
            val cycle = Files.createDirectories(root.resolve("cycle-01"))
            Files.writeString(cycle.resolve("summary.json"), "{}")
            Files.writeString(cycle.resolve("trace.jsonl"), "{}\n")
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1, "run-1", "commit-1"))
            assertTrue(Files.readString(root.resolve("aggregate-summary.json")).contains("cycle-01:MISSING_MANIFEST"))
        } finally { root.toFile().deleteRecursively() }
    }
}
