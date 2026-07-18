package org.jason.pi.gcs.pitools

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PiToolsTest {

    @Test
    fun waitOnTargetStopsControllerAfterTimeout() = runBlocking {
        val transport = NeverOnTargetTransport()
        val device = GcsDevice(GcsClient(transport))

        assertFailsWith<PiGcsTimeoutException> {
            PiTools.waitOnTarget(
                device = device,
                axes = listOf(PiAxis.X),
                options = PiWaitOptions(
                    timeoutMs = 3L,
                    pollDelayMs = 1L,
                    stopOnTimeout = true,
                    stopOnCancellation = true
                )
            )
        }

        assertTrue("STP" in transport.writes)
        assertTrue(transport.writes.containsSequence("STP", "ERR?"))
    }

    @Test
    fun waitOnTargetStopsControllerWhenCoroutineIsCancelled() = runBlocking {
        val transport = NeverOnTargetTransport()
        val device = GcsDevice(GcsClient(transport))

        val job = launch {
            PiTools.waitOnTarget(
                device = device,
                axes = listOf(PiAxis.X),
                options = PiWaitOptions(
                    timeoutMs = 60_000L,
                    pollDelayMs = 1L,
                    stopOnTimeout = true,
                    stopOnCancellation = true
                )
            )
        }

        delay(5L)
        job.cancelAndJoin()

        assertTrue("STP" in transport.writes)
        assertTrue(transport.writes.containsSequence("STP", "ERR?"))
    }
}

private class NeverOnTargetTransport : GcsTransport {

    val writes = mutableListOf<String>()

    private var lastWrite: String? = null

    override var isOpen: Boolean = true
        private set

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun writeLine(command: String) {
        writes += command
        lastWrite = command
    }

    override suspend fun readLine(): String {
        return when {
            lastWrite?.startsWith("ONT?") == true -> "X=0"
            lastWrite == "ERR?" -> "0"
            else -> error("Unexpected read after command=$lastWrite")
        }
    }

    override fun close() {
        isOpen = false
    }
}

private fun List<String>.containsSequence(
    first: String,
    second: String
): Boolean {
    return windowed(size = 2).any { it[0] == first && it[1] == second }
}
