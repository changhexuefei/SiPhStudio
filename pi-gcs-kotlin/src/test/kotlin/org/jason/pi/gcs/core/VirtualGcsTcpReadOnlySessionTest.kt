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
 * Real localhost TCP integration, NOT the official PIHexapodEmulator.
 * Simulator only accepts two explicitly allowlisted read-only GCS queries.
 */
class VirtualGcsTcpReadOnlySessionTest {
    @Test
    fun repeatedPositionReadbackAcrossIndependentTcpSessions() = runBlocking {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 5000
            val received = Collections.synchronizedList(mutableListOf<String>())
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val server = thread(name = "virtual-gcs-tcp-readonly", isDaemon = true) {
                try {
                    repeat(2) {
                        listener.accept().use { socket ->
                            socket.soTimeout = 2500
                            val reader = socket.getInputStream().bufferedReader()
                            val writer = socket.getOutputStream()
                            while (true) {
                                val command = try { reader.readLine() }
                                    catch (_: SocketTimeoutException) { break }
                                    ?: break
                                received.add(command)
                                val reply = when (command) {
                                    "*IDN?" -> "PI,HEXAPOD,VIRTUAL-GCS,1.0\n"
                                    "POS? X Y Z U V W" ->
                                        "X=0.001\nY=0.002\nZ=0.003\nU=0.1\nV=0.2\nW=0.3\n"
                                    else -> error("Rejected non-read-only command: $command")
                                }
                                writer.write(reply.toByteArray(Charsets.UTF_8))
                                writer.flush()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }
            try {
                repeat(2) {
                    val device = GcsDevice(GcsClient(KtorTcpGcsTransport("127.0.0.1", listener.localPort)))
                    try {
                        device.connect()
                        assertEquals("PI,HEXAPOD,VIRTUAL-GCS,1.0", device.qIDN())
                        repeat(3) {
                            val pose = device.qPOS(PiAxis.HEXAPOD_AXES)
                            assertEquals(6, pose.size)
                            assertEquals(0.001, pose[PiAxis.X])
                            assertEquals(0.002, pose[PiAxis.Y])
                            assertEquals(0.003, pose[PiAxis.Z])
                            assertEquals(0.1, pose[PiAxis.U])
                            assertEquals(0.2, pose[PiAxis.V])
                            assertEquals(0.3, pose[PiAxis.W])
                        }
                    } finally {
                        device.close()
                    }
                    assertFalse(device.isOpen)
                }
            } finally {
                server.join(5000)
            }
            assertFalse(server.isAlive, "Virtual TCP server must terminate without leaking a thread")
            assertTrue(errors.isEmpty(), "Virtual TCP server reported unexpected read or command: $errors")
            assertEquals((0 until 2).flatMap {
                listOf("*IDN?") + List(3) { "POS? X Y Z U V W" }
            }, received.toList())
        }
    }
}
