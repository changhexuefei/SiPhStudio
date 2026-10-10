package org.jason.pi.gcs.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cancellation must retire the stream before a different response can be read. */
class GcsCancellationCleanupTest {
    @Test
    fun cancellingPendingReadClosesSessionAndFreshClientCanRead() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stalled = object : GcsTransport {
            override var isOpen = false
                private set
            var closed = false
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) {
                check(command == "*IDN?")
            }
            override suspend fun readLine(): String {
                pending.complete(Unit)
                gate.await()
                return "STALE,IDENTITY"
            }
            override fun close() { closed = true; isOpen = false }
        }
        val first = GcsClient(stalled)
        first.connect()
        val query = async { first.query("*IDN?") }
        withTimeout(2_000) { pending.await() }
        query.cancelAndJoin()
        assertTrue(stalled.closed, "Cancellation must close the partially-read transport")
        assertFalse(first.isOpen)
        val rejected = runCatching { first.query("*IDN?") }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        first.close()

        val clean = object : GcsTransport {
            override var isOpen = false
                private set
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) { check(command == "*IDN?") }
            override suspend fun readLine(): String = "PI,HEXAPOD,NEW,1.0"
            override fun close() { isOpen = false }
        }
        GcsClient(clean).use { next ->
            next.connect()
            assertEquals("PI,HEXAPOD,NEW,1.0", next.query("*IDN?"))
        }
    }
}
