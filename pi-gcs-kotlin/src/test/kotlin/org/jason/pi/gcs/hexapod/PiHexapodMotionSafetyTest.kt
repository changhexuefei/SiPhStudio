package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiHexapodMotionSafetyTest {
    @Test
    fun disconnectDuringMoveLocksMotionAndAttemptsStop() = runBlocking {
        val transport = DisconnectOnMoveTransport()
        val hexapod = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
        hexapod.connect()

        assertFailsWith<IllegalStateException> {
            hexapod.moveTo(PiHexapodPose.ZERO.copy(xUm = 10.0), wait = false)
        }
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> {
            hexapod.moveBy(PiHexapodDelta(dxUm = 1.0), wait = false)
        }
        assertFailsWith<IllegalStateException> { hexapod.connect() }
        assertTrue(transport.writes.any { it.startsWith("MOV ") })
    }

    @Test
    fun explicitStopLocksSubsequentCommandsUntilRecovery() = runBlocking {
        val transport = DisconnectOnMoveTransport()
        val hexapod = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
        hexapod.connect()
        hexapod.stop()

        assertTrue("STP" in transport.writes)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> {
            hexapod.moveTo(PiHexapodPose.ZERO, wait = false)
        }
    }
}

private class DisconnectOnMoveTransport : GcsTransport {
    override var isOpen = false
        private set
    val writes = mutableListOf<String>()
    override suspend fun open() { isOpen = true }
    override suspend fun writeLine(command: String) {
        check(isOpen)
        writes += command
        if (command.startsWith("MOV ")) {
            isOpen = false
            error("Simulated network disconnect during MOV")
        }
    }
    override suspend fun readLine(): String {
        check(isOpen)
        return "0"
    }
    override fun close() { isOpen = false }
}
