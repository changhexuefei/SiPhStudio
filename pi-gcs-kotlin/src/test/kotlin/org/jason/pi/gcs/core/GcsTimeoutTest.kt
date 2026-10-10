package org.jason.pi.gcs.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GcsTimeoutTest {
    @Test
    fun hungQueryIsCancelledAndConnectionInvalidated() = runBlocking {
        val transport = HangingGcsTransport()
        val client = GcsClient(transport, transactionTimeoutMs = 30L)
        client.connect()
        assertFailsWith<TimeoutCancellationException> { client.query("IDN?") }
        assertFalse(client.isOpen)
    }

    @Test
    fun hungErrorCheckInvalidatesConnection() = runBlocking {
        val transport = HangingGcsTransport()
        val client = GcsClient(transport, transactionTimeoutMs = 30L)
        client.connect()
        assertFailsWith<TimeoutCancellationException> { client.command("SVO X 1") }
        assertFalse(client.isOpen)
    }
}

private class HangingGcsTransport : GcsTransport {
    override var isOpen = false
        private set
    override suspend fun open() { isOpen = true }
    override suspend fun writeLine(command: String) { check(isOpen) }
    override suspend fun readLine(): String { awaitCancellation() }
    override fun close() { isOpen = false }
}
