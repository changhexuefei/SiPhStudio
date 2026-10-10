package org.jason.pi.gcs.core

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Cancellation at a real localhost TCP read boundary. No PI hardware and no motion commands.
 */
class GcsTcpCancellationIntegrationTest {
    @Test
    fun cancelPendingIdentityReadRetiresSocketAndFreshSessionWorks() = runBlocking {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 5_000
            val firstRequestReceived = CountDownLatch(1)
            val server = thread(name = "pi-cancel-tcp-peer", isDaemon = true) {
                // Keep first socket alive with no response until client cancellation closes it.
                listener.accept().use { first ->
                    first.soTimeout = 4_000
                    val query = first.getInputStream().bufferedReader().readLine()
                    check(query == "*IDN?") { "Unexpected command: $query" }
                    firstRequestReceived.countDown()
                    val stream = first.getInputStream()
                    try {
                        val result = stream.read()
                        check(result == -1) { "Expected EOF after query cancellation" }
                    } catch (e: SocketTimeoutException) {
                        error("Cancelled GCS query did not close TCP connection")
                    }
                }
                listener.accept().use { second ->
                    second.soTimeout = 4_000
                    val query = second.getInputStream().bufferedReader().readLine()
                    check(query == "*IDN?") { "Unexpected reconnect command: $query" }
                    second.getOutputStream().write("PI,HEXAPOD,AFTER-CANCEL,1.0\n".toByteArray())
                    second.getOutputStream().flush()
                }
            }
            val transport = KtorTcpGcsTransport("127.0.0.1", listener.localPort)
            val first = GcsClient(transport, transactionTimeoutMs = 4_000)
            try {
                first.connect()
                val pending = async { first.query("*IDN?") }
                assertTrue(withContext(Dispatchers.IO) {
                    firstRequestReceived.await(3, TimeUnit.SECONDS)
                }, "Server must receive the pending IDN query")
                pending.cancelAndJoin()
                assertFalse(first.isOpen, "Cancelled read must retire the TCP socket")
                assertTrue(runCatching { first.query("*IDN?") }.isFailure,
                    "An invalidated socket must not accept another query")
            } finally {
                first.close()
            }
            val next = GcsClient(
                KtorTcpGcsTransport("127.0.0.1", listener.localPort),
                transactionTimeoutMs = 4_000
            )
            try {
                next.connect()
                assertEquals("PI,HEXAPOD,AFTER-CANCEL,1.0",
                    withTimeout(4_000) { next.query("*IDN?") })
            } finally {
                next.close()
                server.join(5_000)
                assertFalse(server.isAlive, "Loopback server must terminate and release both sockets")
            }
        }
    }
}
