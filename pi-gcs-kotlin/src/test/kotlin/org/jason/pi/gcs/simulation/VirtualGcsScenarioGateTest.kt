package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/** CI gate: observed socket failure and its diagnostic evidence must agree. */
class VirtualGcsScenarioGateTest {
    @Test
    fun truncatedPositionFailureMustMatchRecordedFault() = runBlocking {
        val journal = VirtualGcsDiagnosticEvidence()
        VirtualPiGcsTcpServer(fault = VirtualGcsFault.TruncatedPosition, diagnostics = journal).use { server ->
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort), transactionTimeoutMs = 1_500
            ))
            try {
                device.connect()
                assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                assertTrue(runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.isFailure,
                    "A truncated six-axis query must fail")
            } finally {
                device.close()
            }
        }
        assertTrue(
            VirtualGcsEvidenceContract.validateScenario(journal.snapshot(), VirtualGcsFault.TruncatedPosition).isEmpty(),
            "Truncated response must be backed by matching simulator evidence"
        )
    }

    @Test
    fun missingExpectedFaultCannotPassEvenWithValidLifecycle() {
        val evidence = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "QUERY", "*IDN?", "REPLIED"),
            VirtualGcsEvent(3, 1, "DISCONNECTED"),
            VirtualGcsEvent(4, 0, "SERVER_CLOSED")
        )
        assertTrue(VirtualGcsEvidenceContract.validate(evidence).isEmpty())
        assertTrue("EXPECTED_FAULT_NOT_OBSERVED" in VirtualGcsEvidenceContract.validateScenario(
            evidence, VirtualGcsFault.Disconnect
        ))
    }

    @Test
    fun wrongFaultLabelOrMultipleInjectionsAreRejected() {
        val events = listOf(
            VirtualGcsEvent(1, 1, "CONNECTED"),
            VirtualGcsEvent(2, 1, "QUERY", "POS? X Y Z U V W", "FAULT_REPLY", "MalformedPosition"),
            VirtualGcsEvent(3, 1, "QUERY", "POS? X Y Z U V W", "FAULT_REPLY", "TruncatedPosition"),
            VirtualGcsEvent(4, 1, "DISCONNECTED"),
            VirtualGcsEvent(5, 0, "SERVER_CLOSED")
        )
        val failures = VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.TruncatedPosition)
        assertTrue("FAULT_EVENT_COUNT_MISMATCH" in failures)
        assertTrue("UNEXPECTED_FAULT" in VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.None))
    }
}
