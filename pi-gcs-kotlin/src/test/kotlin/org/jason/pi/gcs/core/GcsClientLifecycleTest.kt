package org.jason.pi.gcs.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GcsClientLifecycleTest {

    @Test
    fun disconnectWaitsForActiveQueryBeforeClosingTransport() = runBlocking {
        val transport = BlockingReadTransport()
        val client = GcsClient(transport)
        client.connect()

        val query = async {
            client.query("POS? X")
        }
        transport.readStarted.await()

        val disconnect = async {
            client.disconnect()
        }
        yield()

        assertFalse(disconnect.isCompleted)
        assertTrue(transport.isOpen)
        assertEquals(0, transport.closeCount)

        transport.response.complete("X=1.0")

        assertEquals("X=1.0", query.await())
        disconnect.await()
        assertFalse(transport.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun synchronousCloseRefusesToRaceActiveTransaction() = runBlocking {
        val transport = BlockingReadTransport()
        val client = GcsClient(transport)
        client.connect()

        val query = async {
            client.query("POS? X")
        }
        transport.readStarted.await()

        val failure = runCatching { client.close() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(transport.isOpen)

        transport.response.complete("X=2.0")
        assertEquals("X=2.0", query.await())
        client.disconnect()
    }
}

private class BlockingReadTransport : GcsTransport {
    val readStarted = CompletableDeferred<Unit>()
    val response = CompletableDeferred<String>()
    var closeCount: Int = 0
        private set

    override var isOpen: Boolean = false
        private set

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun writeLine(command: String) = Unit

    override suspend fun readLine(): String {
        readStarted.complete(Unit)
        return response.await()
    }

    override fun close() {
        closeCount += 1
        isOpen = false
    }
}
