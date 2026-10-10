package org.jason.pi.gcs.core

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Deterministic localhost fault fixtures. Only read-only GCS commands are accepted.
 * None of these tests communicates with a PI controller or issues motion commands.
 */
class GcsTcpFaultInjectionTest {
    @Test
    fun stalledIdentityTimesOutAndFreshSessionCanReconnect() = runBlocking {
        ServerSocket(0).use { listener ->
            val server = thread(name = "gcs-timeout-reconnect", isDaemon = true) {
                listener.accept().use { first ->
                    first.soTimeout = 3000
                    check(first.getInputStream().bufferedReader().readLine() == "*IDN?")
                    // Deliberately exceed the client's transaction timeout.
                    Thread.sleep(450)
                }
                listener.accept().use { second ->
                    second.soTimeout = 3000
                    check(second.getInputStream().bufferedReader().readLine() == "*IDN?")
                    second.getOutputStream().write("PI,HEXAPOD,RECOVERED,1.0\n".toByteArray())
                    second.getOutputStream().flush()
                }
            }
            val first = GcsDevice(
                GcsClient(
                    KtorTcpGcsTransport("127.0.0.1", listener.localPort),
                    transactionTimeoutMs = 150
                )
            )
            val second = GcsDevice(
                GcsClient(KtorTcpGcsTransport("127.0.0.1", listener.localPort))
            )
            try {
                first.connect()
                val failure = runCatching { first.qIDN() }.exceptionOrNull()
                assertTrue(failure != null, "Stalled IDN must not appear successful")
                first.close()
                second.connect()
                assertEquals("PI,HEXAPOD,RECOVERED,1.0", second.qIDN())
            } finally {
                first.close()
                second.close()
                server.join(3000)
            }
        }
    }

    @Test
    fun truncatedSixAxisResponseNeverBecomesValidPose() = runBlocking {
        ServerSocket(0).use { listener ->
            val server = thread(name = "gcs-truncated-pose", isDaemon = true) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    check(socket.getInputStream().bufferedReader().readLine() == "POS? X Y Z U V W")
                    // Missing Z/U/V/W: response framing must fail closed on EOF.
                    socket.getOutputStream().write("X=0.001\nY=0.002\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            val device = GcsDevice(GcsClient(KtorTcpGcsTransport("127.0.0.1", listener.localPort)))
            try {
                device.connect()
                val failure = runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.exceptionOrNull()
                assertTrue(failure != null, "Partial position response must fail")
                assertFalse(device.isOpen)
            } finally {
                device.close()
                server.join(3000)
            }
        }
    }
}
