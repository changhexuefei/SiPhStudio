package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualGcsSessionEvidenceTest {
    @Test
    fun rejectsQueryAfterDisconnectAndUnclosedSession() {
        val invalid = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "DISCONNECTED"),
            VirtualGcsEvent(3, 1, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(4, 2, "CONNECTED"),
            VirtualGcsEvent(5, 0, "SERVER_CLOSED")
        )
        val failures = VirtualGcsEvidenceContract.validate(invalid)
        assertTrue("QUERY_OUTSIDE_SESSION" in failures)
        assertTrue("UNCLOSED_CONNECTION" in failures)
    }

    @Test
    fun rejectsDuplicateConnectionAndEventsAfterServerShutdown() {
        val invalid = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "CONNECTED"),
            VirtualGcsEvent(3, 1, "DISCONNECTED"),
            VirtualGcsEvent(4, 0, "SERVER_CLOSED"),
            VirtualGcsEvent(5, 1, "QUERY", "*IDN?", "REPLIED")
        )
        val failures = VirtualGcsEvidenceContract.validate(invalid)
        assertTrue("DUPLICATE_CONNECTION" in failures)
        assertTrue("EVENT_AFTER_SERVER_CLOSED" in failures)
    }

    @Test
    fun twoRealTcpSessionsHaveCompleteOrderedEvidenceAfterServerClose() = runBlocking {
        val evidence = VirtualGcsDiagnosticEvidence()
        VirtualPiGcsTcpServer(diagnostics = evidence).use { server ->
            repeat(2) {
                val device = GcsDevice(GcsClient(
                    KtorTcpGcsTransport(server.host, server.localPort),
                    transactionTimeoutMs = 2_000
                ))
                try {
                    device.connect()
                    assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                } finally {
                    device.close()
                }
            }
        }
        val events = evidence.snapshot()
        assertTrue(VirtualGcsEvidenceContract.validate(events).isEmpty())
        assertEquals(2, events.count { it.type == "CONNECTED" })
        assertEquals(2, events.count { it.type == "DISCONNECTED" })
        assertEquals("SERVER_CLOSED", events.last().type)
        assertEquals(2, events.filter { it.type == "CONNECTED" }.map { it.connection }.distinct().size)
    }
}
