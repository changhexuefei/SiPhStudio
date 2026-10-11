package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualGcsSoakIntegrityTest {
    @Test
    fun tamperedQueryAfterDisconnectCannotPassAggregate() {
        val root = Files.createTempDirectory("virtual-gcs-integrity")
        try {
            val dir = Files.createDirectories(root.resolve("cycle-01"))
            val journal = VirtualGcsDiagnosticEvidence()
            journal.record(1, "CONNECTED")
            journal.record(1, "QUERY", "*IDN?", "FAULT_TIMEOUT", "Timeout")
            journal.record(1, "DISCONNECTED")
            journal.record(2, "CONNECTED")
            journal.record(2, "QUERY", "*IDN?", "REPLIED")
            journal.record(2, "QUERY", "POS? X Y Z U V W", "REPLIED")
            journal.record(2, "DISCONNECTED")
            journal.record(0, "SERVER_CLOSED")
            assertTrue(VirtualGcsEvidenceContract.exportBundle(journal, dir, "recovery-cycle-1"))
            assertTrue(VirtualGcsSoakSummary.aggregate(root, 1))
            // Keep valid JSON and the same event count while corrupting the lifecycle order.
            val trace = dir.resolve("trace.jsonl")
            Files.writeString(trace, Files.readString(trace).replace(
                "\"connection\":2,\"type\":\"QUERY\"",
                "\"connection\":1,\"type\":\"QUERY\""
            ))
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1))
            assertTrue(Files.readString(root.resolve("aggregate-summary.json")).contains("INVALID_EVIDENCE"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun forgedPassStringInMalformedJsonIsRejected() {
        val root = Files.createTempDirectory("virtual-gcs-json")
        try {
            val dir = Files.createDirectories(root.resolve("cycle-01"))
            Files.writeString(dir.resolve("summary.json"), """{"schemaVersion":1,"result":"PASS",broken}""")
            Files.writeString(dir.resolve("trace.jsonl"), """{"schemaVersion":1}""" + "\n")
            assertFalse(VirtualGcsSoakSummary.aggregate(root, 1))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
