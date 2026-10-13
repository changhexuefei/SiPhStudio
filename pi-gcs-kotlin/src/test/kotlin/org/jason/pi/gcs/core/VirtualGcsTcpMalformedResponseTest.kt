package org.jason.pi.gcs.core

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Real local Socket negative tests. This is NOT PIHexapodEmulator acceptance.
 * Only POS? read-only queries are allowed; no physical motion is permitted.
 */
class VirtualGcsTcpMalformedResponseTest {
    @Test
    fun malformedAxisValueMustNeverBecomeAValidSixAxisPose() = runBlocking {
        rejectMalformedPose(
            "X=0.001\nY=0.002\nZ=INVALID\nU=0.1\nV=0.2\nW=0.3\n"
        )
    }

    @Test
    fun duplicateAxisCannotReplaceMissingAxis() = runBlocking {
        rejectMalformedPose(
            "X=0.001\nY=0.002\nZ=0.003\nU=0.1\nV=0.2\nV=0.3\n"
        )
    }

    private suspend fun rejectMalformedPose(payload: String) {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 3000
            val failures = mutableListOf<Throwable>()
            val server = thread(name = "virtual-gcs-malformed", isDaemon = true) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val query = socket.getInputStream().bufferedReader().readLine()
                        check(query == "POS? X Y Z U V W") { "Unexpected or motion-capable command: $query" }
                        socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                    }
                } catch (error: Throwable) {
                    synchronized(failures) { failures.add(error) }
                }
            }
            val device = GcsDevice(GcsClient(KtorTcpGcsTransport("127.0.0.1", listener.localPort), transactionTimeoutMs = 1500))
            try {
                device.connect()
                val error = runCatching { device.qPOS(PiAxis.HEXAPOD_AXES) }.exceptionOrNull()
                assertTrue(error != null, "Malformed response must not become an accepted pose")
            } finally {
                device.close()
                server.join(3000)
            }
            assertTrue(!server.isAlive, "Loopback fixture must terminate")
            assertTrue(failures.isEmpty(), "Loopback fixture must only receive POS?: $failures")
        }
    }
}
