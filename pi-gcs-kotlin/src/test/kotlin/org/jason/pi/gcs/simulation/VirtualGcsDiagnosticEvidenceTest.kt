package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualGcsDiagnosticEvidenceTest {
    @Test
    fun recordsFaultAndConnectionSequenceAndExportsVersionedJsonLines() = runBlocking {
        val evidence = VirtualGcsDiagnosticEvidence()
        VirtualPiGcsTcpServer(
            fault = VirtualGcsFault.TruncatedPosition, diagnostics = evidence
        ).use { server ->
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort), transactionTimeoutMs = 1_000
            ))
            try {
                device.connect()
                assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                assertTrue(runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.isFailure)
            } finally {
                device.close()
            }
        }
        val events = evidence.snapshot()
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
        assertTrue(events.any { it.type == "CONNECTED" })
        assertTrue(events.any { it.command == "*IDN?" && it.outcome == "REPLIED" })
        assertTrue(events.any { it.command == "POS? X Y Z U V W" &&
            it.outcome == "FAULT_REPLY" && it.fault == "TruncatedPosition" })
        assertTrue(events.any { it.type == "SERVER_CLOSED" })
        val destination = Files.createTempFile("virtual-pi-diagnostic-", ".jsonl")
        try {
            evidence.exportJsonLines(destination)
            val exported = Files.readString(destination)
            assertTrue(exported.contains("\"schemaVersion\":1"))
            assertTrue(exported.contains("\"fault\":\"TruncatedPosition\""))
            assertEquals(events.size, exported.lineSequence().count { it.isNotBlank() })
            assertTrue(exported.lines().filter { it.isNotBlank() }.all { it.startsWith("{") && it.endsWith("}") })
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
