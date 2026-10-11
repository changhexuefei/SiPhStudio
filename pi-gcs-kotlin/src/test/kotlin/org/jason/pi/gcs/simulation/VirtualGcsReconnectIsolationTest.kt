package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Read-only localhost regression: timed-out connections must never pollute fresh sessions. */
class VirtualGcsReconnectIsolationTest {
    @Test
    fun firstTimeoutThenThreeFreshSessionsKeepIndependentResponsesAndEvidence() = runBlocking {
        val journal = VirtualGcsDiagnosticEvidence()
        VirtualPiGcsTcpServer(
            fault = VirtualGcsFault.Timeout, diagnostics = journal
        ).use { server ->
            fun newDevice() = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort), transactionTimeoutMs = 1_000
            ))
            val stale = newDevice()
            try {
                stale.connect()
                assertTrue(runCatching { stale.qIDN() }.isFailure)
                assertFalse(stale.isOpen)
                assertTrue(runCatching { stale.qIDN() }.isFailure,
                    "Retired connection must not accept later queries")
            } finally {
                stale.close()
            }
            repeat(3) {
                val fresh = newDevice()
                try {
                    fresh.connect()
                    assertTrue(fresh.qIDN().startsWith("VIRTUAL,"))
                    val pose = fresh.qPOS(PiAxis.HEXAPOD_AXES)
                    assertEquals(6, pose.size)
                    assertTrue(pose.values.all { it == 0.0 })
                    assertTrue(fresh.qIDN().contains("NOT-HARDWARE"),
                        "Repeated responses must belong to the active session")
                } finally {
                    fresh.close()
                }
            }
        }
        val events = journal.snapshot()
        assertTrue(VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.Timeout).isEmpty())
        val sessions = events.filter { it.type == "CONNECTED" }.map { it.connection }
        assertEquals(4, sessions.size)
        assertEquals(4, sessions.distinct().size)
        assertEquals(4, events.count { it.type == "DISCONNECTED" })
        assertEquals(1, events.count { it.outcome == "FAULT_TIMEOUT" })
        assertEquals(9, events.count { it.type == "QUERY" && it.outcome == "REPLIED" })
        val failedId = events.single { it.outcome == "FAULT_TIMEOUT" }.connection
        assertEquals(0, events.count { it.connection == failedId && it.outcome == "REPLIED" },
            "Timed-out socket must never emit a successful late response")
        assertEquals("SERVER_CLOSED", events.last().type)
    }

    @Test
    fun fabricatedLateQueryOnDisconnectedSessionFailsEvidenceGate() {
        val trace = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "QUERY", "*IDN?", "FAULT_TIMEOUT", "Timeout"),
            VirtualGcsEvent(3, 1, "DISCONNECTED"),
            VirtualGcsEvent(4, 2, "CONNECTED"),
            VirtualGcsEvent(5, 1, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(6, 2, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(7, 2, "DISCONNECTED"),
            VirtualGcsEvent(8, 0, "SERVER_CLOSED")
        )
        assertTrue("QUERY_OUTSIDE_SESSION" in VirtualGcsEvidenceContract.validateScenario(
            trace, VirtualGcsFault.Timeout
        ))
    }
}
