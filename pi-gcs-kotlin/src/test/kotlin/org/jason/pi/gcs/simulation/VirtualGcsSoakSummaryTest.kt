package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualGcsSoakSummaryTest {
    @Test
    fun missingCycleFailsAndStillWritesAggregateVerdict() {
        val root = Files.createTempDirectory("virtual-gcs-soak-missing")
        try {
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 2))
            val summary = Files.readString(root.resolve("aggregate-summary.json"))
            assertTrue(summary.contains("\"result\":\"FAIL\""))
            assertTrue(summary.contains("cycle-01:MISSING_EVIDENCE"))
            assertTrue(summary.contains("cycle-02:MISSING_EVIDENCE"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidCycleSummaryCannotPassEvenWhenFilesExist() {
        val root = Files.createTempDirectory("virtual-gcs-soak-invalid")
        try {
            val dir = Files.createDirectories(root.resolve("cycle-01"))
            Files.writeString(dir.resolve("summary.json"), """{"schemaVersion":1,"source":"VIRTUAL_ONLY","scenario":"recovery-cycle-1","result":"FAIL","eventCount":1}""")
            Files.writeString(dir.resolve("trace.jsonl"), """{"schemaVersion":1}""" + "\n")
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1))
            assertTrue(Files.readString(root.resolve("aggregate-summary.json")).contains("INVALID_EVIDENCE"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
