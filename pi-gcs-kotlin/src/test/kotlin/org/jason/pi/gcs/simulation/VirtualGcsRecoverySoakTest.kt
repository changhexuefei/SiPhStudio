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

/**
 * Bounded recovery soak: each cycle owns a separate local-only virtual server.
 * Never contacts physical PI hardware or enables motion.
 */
class VirtualGcsRecoverySoakTest {
    @Test
    fun twentyTimeoutRecoveryCyclesKeepCompleteIndependentEvidence() = runBlocking {
        repeat(20) { cycle ->
            val journal = VirtualGcsDiagnosticEvidence()
            VirtualPiGcsTcpServer(
                fault = VirtualGcsFault.Timeout, diagnostics = journal
            ).use { server ->
                fun device() = GcsDevice(GcsClient(
                    KtorTcpGcsTransport(server.host, server.localPort),
                    transactionTimeoutMs = 350
                ))
                val timedOut = device()
                try {
                    timedOut.connect()
                    assertTrue(runCatching { timedOut.qIDN() }.isFailure, "cycle $cycle must time out")
                    assertFalse(timedOut.isOpen)
                } finally {
                    timedOut.close()
                }
                val recovered = device()
                try {
                    recovered.connect()
                    assertTrue(recovered.qIDN().contains("NOT-HARDWARE"))
                    val pose = recovered.qPOS(PiAxis.HEXAPOD_AXES)
                    assertEquals(6, pose.size)
                    assertTrue(pose.values.all { it == 0.0 })
                } finally {
                    recovered.close()
                }
            }
            val events = journal.snapshot()
            assertTrue(
                VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.Timeout).isEmpty(),
                "cycle $cycle has invalid evidence"
            )
            assertEquals(2, events.count { it.type == "CONNECTED" }, "cycle $cycle")
            assertEquals(2, events.count { it.type == "DISCONNECTED" }, "cycle $cycle")
            assertEquals(1, events.count { it.outcome == "FAULT_TIMEOUT" }, "cycle $cycle")
            assertEquals(2, events.count { it.outcome == "REPLIED" }, "cycle $cycle")
            assertEquals("SERVER_CLOSED", events.last().type)
        }
    }
}
