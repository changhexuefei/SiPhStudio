package org.jason.pi.gcs.core

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Real localhost TCP framing tests, without a PI controller or vendor emulator.
 * The loopback server supports read-only commands exclusively.
 */
class GcsTcpLoopbackIntegrationTest {
    @Test
    fun fragmentedMultilinePoseAndIdentityOverRealSocket() = runBlocking {
        withReadOnlyServer { port, received ->
            val device = GcsDevice(GcsClient(KtorTcpGcsTransport("127.0.0.1", port)))
            try {
                device.connect()
                assertEquals("PI,HEXAPOD,LOOPBACK,1.0", device.qIDN())
                val pose = device.qPOS(PiAxis.HEXAPOD_AXES)
                assertEquals(0.001, pose[PiAxis.X])
                assertEquals(0.002, pose[PiAxis.Y])
                assertEquals(0.003, pose[PiAxis.Z])
                assertEquals(0.1, pose[PiAxis.U])
                assertEquals(0.2, pose[PiAxis.V])
                assertEquals(0.3, pose[PiAxis.W])
                assertEquals(listOf("*IDN?", "POS? X Y Z U V W"), received.toList())
            } finally {
                device.close()
            }
        }
    }

    @Test
    fun disconnectedSocketFailsClosedWithoutSendingMotion() = runBlocking {
        ServerSocket(0).use { listener ->
            val port = listener.localPort
            val server = thread(name = "pi-loopback-disconnect", isDaemon = true) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    socket.getInputStream().bufferedReader().readLine()
                    // Close without writing a response: EOF must not become a valid IDN.
                }
            }
            val device = GcsDevice(
                GcsClient(KtorTcpGcsTransport("127.0.0.1", port), transactionTimeoutMs = 2000)
            )
            try {
                device.connect()
                val failure = runCatching { device.qIDN() }.exceptionOrNull()
                assertTrue(failure != null, "EOF must fail rather than returning a device identity")
                assertFalse(device.isOpen)
            } finally {
                device.close()
                server.join(3000)
            }
        }
    }

    private suspend fun withReadOnlyServer(
        run: suspend (Int, MutableList<String>) -> Unit
    ) {
        ServerSocket(0).use { listener ->
            val received = Collections.synchronizedList(mutableListOf<String>())
            val server = thread(name = "pi-loopback-readonly", isDaemon = true) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream()
                    while (true) {
                        val command = try {
                            input.readLine()
                        } catch (_: SocketTimeoutException) {
                            break
                        } ?: break
                        received.add(command)
                        val response = when (command) {
                            "*IDN?" -> "PI,HEXAPOD,LOOPBACK,1.0\n"
                            "POS? X Y Z U V W" ->
                                "X=0.001\nY=0.002\nZ=0.003\nU=0.1\nV=0.2\nW=0.3\n"
                            else -> error("Unexpected (possibly write-capable) GCS command: $command")
                        }
                        // Split writes across real TCP packets, including within lines.
                        response.toByteArray(Charsets.UTF_8).forEach { byte ->
                            output.write(byte.toInt())
                        }
                        output.flush()
                    }
                }
            }
            try {
                run(listener.localPort, received)
            } finally {
                server.join(3000)
            }
        }
    }
}
