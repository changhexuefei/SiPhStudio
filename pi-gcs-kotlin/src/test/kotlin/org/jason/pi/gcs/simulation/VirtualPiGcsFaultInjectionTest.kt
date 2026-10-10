package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real loopback sockets; no real PI equipment or motion commands. */
class VirtualPiGcsFaultInjectionTest {
    @Test
    fun malformedPositionFailsAndDoesNotPublishSixAxisPose() = runBlocking {
        VirtualPiGcsTcpServer(fault = VirtualGcsFault.MalformedPosition).use { server ->
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
    }

    @Test
    fun truncatedPositionClosesSessionInsteadOfAcceptingPartialPose() = runBlocking {
        VirtualPiGcsTcpServer(fault = VirtualGcsFault.TruncatedPosition).use { server ->
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort), transactionTimeoutMs = 1_000
            ))
            try {
                device.connect()
                assertTrue(runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.isFailure)
                assertFalse(device.isOpen)
            } finally {
                device.close()
            }
        }
    }

    @Test
    fun injectedDisconnectFailsFirstQueryAndNextFreshSessionRecovers() = runBlocking {
        VirtualPiGcsTcpServer(fault = VirtualGcsFault.Disconnect).use { server ->
            fun client() = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort), transactionTimeoutMs = 1_000
            ))
            val first = client()
            try {
                first.connect()
                assertTrue(runCatching { first.qIDN() }.isFailure)
                assertFalse(first.isOpen)
            } finally {
                first.close()
            }
            val second = client()
            try {
                second.connect()
                assertTrue(second.qIDN().contains("NOT-HARDWARE"))
            } finally {
                second.close()
            }
        }
    }
}
