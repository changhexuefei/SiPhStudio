package org.jason.pi.gcs.core

import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Fault injection through actual localhost TCP sockets, without any PI equipment.
 * Only read-only GCS queries are accepted by the test peers.
 */
class GcsTcpFaultInjectionTest {
    @Test
    fun truncatedSixAxisResponseFailsInsteadOfPublishingPartialPose() = runBlocking {
        ServerSocket(0).use { listener ->
            val peer = thread(name = "pi-truncated-pose", isDaemon = true) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val request = socket.getInputStream().bufferedReader().readLine()
                    check(request == "POS? X Y Z U V W") { "Unexpected GCS command: $request" }
                    socket.getOutputStream().write("X=0.001\nY=0.002\n".toByteArray())
                    socket.getOutputStream().flush()
                    // EOF before the remaining four axes.
                }
            }
            val device = GcsDevice(
                GcsClient(KtorTcpGcsTransport("127.0.0.1", listener.localPort),
                    transactionTimeoutMs = 2_000)
            )
            try {
                device.connect()
                val failure = runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.exceptionOrNull()
                assertTrue(failure != null, "Truncated POS? must fail closed")
                assertFalse(device.isOpen)
            } finally {
                device.close()
                peer.join(3_000)
                assertFalse(peer.isAlive, "Loopback peer must exit")
            }
        }
    }

    @Test
    fun silentPeerTimesOutThenFreshSessionCanReconnect() = runBlocking {
        ServerSocket(0).use { listener ->
            val peer = thread(name = "pi-timeout-reconnect", isDaemon = true) {
                // First connection deliberately receives the query but never replies.
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val request = socket.getInputStream().bufferedReader().readLine()
                    check(request == "*IDN?") { "Unexpected first GCS command: $request" }
                    Thread.sleep(700)
                }
                // A fresh client connection can still query the same listener.
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val request = socket.getInputStream().bufferedReader().readLine()
                    check(request == "*IDN?") { "Unexpected second GCS command: $request" }
                    socket.getOutputStream().write("PI,HEXAPOD,RECOVERED,1.0\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            val first = GcsDevice(GcsClient(
                KtorTcpGcsTransport("127.0.0.1", listener.localPort),
                transactionTimeoutMs = 250
            ))
            try {
                first.connect()
                val failure = runCatching { first.qIDN() }.exceptionOrNull()
                assertTrue(failure != null, "Silent TCP peer must not return an ID")
            } finally {
                first.close()
            }
            val recovered = GcsDevice(GcsClient(
                KtorTcpGcsTransport("127.0.0.1", listener.localPort),
                transactionTimeoutMs = 2_000
            ))
            try {
                recovered.connect()
                assertEquals("PI,HEXAPOD,RECOVERED,1.0", recovered.qIDN())
            } finally {
                recovered.close()
                peer.join(3_000)
                assertFalse(peer.isAlive, "Recovery loopback peer must exit")
            }
        }
    }
}
