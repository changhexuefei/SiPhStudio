package org.jason.pi.gcs.simulation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises concurrent real loopback TCP sockets, never PI physical equipment. */
class VirtualGcsConcurrentSessionsTest {
    @Test
    fun concurrentReadOnlyClientsPreserveCompleteIndependentSessionEvidence() = runBlocking {
        val evidence = VirtualGcsDiagnosticEvidence()
        val clientCount = 6
        VirtualPiGcsTcpServer(diagnostics = evidence).use { server ->
            (1..clientCount).map {
                async(Dispatchers.IO) {
                    val device = GcsDevice(GcsClient(
                        KtorTcpGcsTransport(server.host, server.localPort),
                        transactionTimeoutMs = 3_000
                    ))
                    try {
                        device.connect()
                        assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                        val position = device.qPOS(PiAxis.HEXAPOD_AXES)
                        assertEquals(6, position.size)
                        assertTrue(position.values.all { it == 0.0 })
                    } finally {
                        device.close()
                    }
                }
            }.awaitAll()
        }
        val trace = evidence.snapshot()
        assertTrue(VirtualGcsEvidenceContract.validate(trace).isEmpty())
        assertEquals(clientCount, trace.count { it.type == "CONNECTED" })
        assertEquals(clientCount, trace.count { it.type == "DISCONNECTED" })
        assertEquals(clientCount, trace.mapNotNull { if (it.type == "CONNECTED") it.connection else null }.distinct().size)
        assertEquals(clientCount * 2, trace.count { it.type == "QUERY" && it.outcome == "REPLIED" })
        assertEquals("SERVER_CLOSED", trace.last().type)
    }

    @Test
    fun concurrentFaultInjectionOccursExactlyOnceAndOtherClientsReconnectNormally() = runBlocking {
        val evidence = VirtualGcsDiagnosticEvidence()
        val clientCount = 4
        val outcomes = VirtualPiGcsTcpServer(
            fault = VirtualGcsFault.Disconnect, diagnostics = evidence
        ).use { server ->
            (1..clientCount).map {
                async(Dispatchers.IO) {
                    val device = GcsDevice(GcsClient(
                        KtorTcpGcsTransport(server.host, server.localPort),
                        transactionTimeoutMs = 3_000
                    ))
                    try {
                        device.connect()
                        runCatching { device.qIDN().contains("NOT-HARDWARE") }
                            .getOrDefault(false)
                    } finally {
                        device.close()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, outcomes.count { !it }, "A one-shot disconnection must fail only one session")
        assertEquals(clientCount - 1, outcomes.count { it })
        val trace = evidence.snapshot()
        assertTrue(
            VirtualGcsEvidenceContract.validateScenario(trace, VirtualGcsFault.Disconnect).isEmpty(),
            "One injected disconnect and otherwise healthy sessions must have matching evidence"
        )
        assertEquals(1, trace.count { it.fault == "Disconnect" && it.outcome == "FAULT_DISCONNECT" })
        assertEquals(clientCount, trace.count { it.type == "DISCONNECTED" })
    }
}
