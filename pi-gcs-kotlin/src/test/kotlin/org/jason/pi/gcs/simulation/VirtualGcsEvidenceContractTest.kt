package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualGcsEvidenceContractTest {
    @Test
    fun writesRealCiEvidenceBundleWithVirtualOnlyPass() {
        val journal = VirtualGcsDiagnosticEvidence()
        journal.record(1, "CONNECTED")
        journal.record(1, "QUERY", "*IDN?", "REPLIED")
        journal.record(1, "DISCONNECTED")
        journal.record(0, "SERVER_CLOSED")
        val directory = Path.of("build", "ci", "virtual-gcs-evidence", "valid-readonly")
        assertTrue(VirtualGcsEvidenceContract.exportBundle(journal, directory, "valid-readonly"))
        val summary = Files.readString(directory.resolve("summary.json"))
        assertTrue(summary.contains("\"source\":\"VIRTUAL_ONLY\""))
        assertTrue(summary.contains("\"result\":\"PASS\""))
        assertEquals(4, Files.readAllLines(directory.resolve("trace.jsonl")).size)
    }

    @Test
    fun missingConnectionAndShutdownProduceMachineReadableFail() {
        val journal = VirtualGcsDiagnosticEvidence()
        journal.record(7, "QUERY", "POS? X Y Z U V W", "REPLIED")
        val directory = Path.of("build", "ci", "virtual-gcs-evidence", "invalid-lifecycle")
        assertFalse(VirtualGcsEvidenceContract.exportBundle(journal, directory, "invalid-lifecycle"))
        val summary = Files.readString(directory.resolve("summary.json"))
        assertTrue(summary.contains("\"result\":\"FAIL\""))
        assertTrue(summary.contains("QUERY_WITHOUT_CONNECTION"))
        assertTrue(summary.contains("MISSING_SERVER_CLOSED"))
    }

    @Test
    fun rejectsInvalidScenarioIdentifierAndNonContinuousSequence() {
        val events = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(3, 1, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(4, 0, "SERVER_CLOSED")
        )
        assertTrue("SEQUENCE_GAP" in VirtualGcsEvidenceContract.validate(events))
    }
}
