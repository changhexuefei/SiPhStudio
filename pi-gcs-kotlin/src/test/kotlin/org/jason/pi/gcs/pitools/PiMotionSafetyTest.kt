package org.jason.pi.gcs.pitools

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiGcsTimeoutException
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiMotionSafetyTest {
    @Test
    fun timeoutAttemptsStopAndInvalidatesSession() = runBlocking {
        val transport = NeverOnTargetTransport()
        val device = GcsDevice(GcsClient(transport))
        device.connect()

        assertFailsWith<PiGcsTimeoutException> {
            PiTools.moveAndWait(
                device,
                mapOf(PiAxis.X to 0.01),
                PiWaitOptions(timeoutMs = 20, pollDelayMs = 1)
            )
        }

        assertTrue(transport.writes.any { it.startsWith("MOV ") })
        assertTrue("STP" in transport.writes)
        assertFalse(device.isOpen)
        assertFailsWith<IllegalStateException> {
            device.moveAbsolute(PiAxis.X, 0.02)
        }
    }
}

private class NeverOnTargetTransport : GcsTransport {
    override var isOpen: Boolean = false
        private set

    val writes = mutableListOf<String>()
    private var lastQuery = ""

    override suspend fun open() { isOpen = true }

    override suspend fun writeLine(command: String) {
        check(isOpen)
        writes += command
        if (command.endsWith("?") || command.startsWith("ONT?")) lastQuery = command
    }

    override suspend fun readLine(): String {
        check(isOpen)
        return when (lastQuery) {
            "ERR?" -> "0"
            "ONT? X" -> "X=0"
            else -> error("Unexpected query: $lastQuery")
        }
    }

    override fun close() { isOpen = false }
}
