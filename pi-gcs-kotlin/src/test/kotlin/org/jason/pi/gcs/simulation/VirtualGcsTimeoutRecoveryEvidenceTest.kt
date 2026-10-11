package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real localhost TCP read timeout and clean recovery; no physical PI hardware. */
class VirtualGcsTimeoutRecoveryEvidenceTest {
    @Test
    fun timedOutReadRetiresOldSocketWhileFreshSessionAndEvidenceRecover() = runBlocking {
        val journal = VirtualGcsDiagnosticEvidence()
        VirtualPiGcsTcpServer(
            fault = VirtualGcsFault.Timeout, diagnostics = journal
        ).use { server ->
            fun newDevice() = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort),
                transactionTimeoutMs = 350
            ))
            val first = newDevice()
            try {
                first.connect()
                assertTrue(runCatching { first.qIDN() }.isFailure,
                    "A deliberately stalled GCS response must time out")
                assertFalse(first.isOpen, "Timeout must invalidate the client socket")
            } finally {
                first.close()
            }
            val recovered = newDevice()
            try {
                recovered.connect()
                assertTrue(recovered.qIDN().contains("NOT-HARDWARE"))
            } finally {
                recovered.close()
            }
        }
        val events = journal.snapshot()
        assertTrue(VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.Timeout).isEmpty(),
            "Timeout and recovery must retain a complete, matching event trace")
        val queries = events.filter { it.type == "QUERY" }
        assertEquals(1, queries.count { it.outcome == "FAULT_TIMEOUT" && it.fault == "Timeout" })
        assertEquals(1, queries.count { it.outcome == "REPLIED" && it.command == "*IDN?" })
        assertEquals(2, events.count { it.type == "DISCONNECTED" })
        assertEquals("SERVER_CLOSED", events.last().type)
    }

    @Test
    fun timeoutScenarioWithoutActualTimeoutCannotPassGate() {
        val fabricated = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(3, 1, "DISCONNECTED"),
            VirtualGcsEvent(4, 0, "SERVER_CLOSED")
        )
        val failures = VirtualGcsEvidenceContract.validateScenario(fabricated, VirtualGcsFault.Timeout)
        assertTrue("EXPECTED_FAULT_NOT_OBSERVED" in failures)
    }
}
