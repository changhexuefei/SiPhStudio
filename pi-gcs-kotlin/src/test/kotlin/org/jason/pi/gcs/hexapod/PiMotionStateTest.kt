package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PiMotionStateTest {
    @Test
    fun stopLatchesRecoveryAndRejectsReconnectWithoutConfirmation() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        assertEquals(PiMotionPhase.Disconnected, port.motionState.value.phase)
        port.connect()
        assertEquals(PiMotionPhase.Ready, port.motionState.value.phase)
        port.stop()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFailsWith<IllegalStateException> { port.connect() }
        assertFailsWith<IllegalArgumentException> { port.recover(operatorConfirmedSafe = false) }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
    }

    @Test
    fun recoveryRequiresReadbackOfPositionAndOnTarget() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        port.stop()
        transport.onTarget = false
        assertFailsWith<IllegalStateException> { port.recover(operatorConfirmedSafe = true) }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        transport.onTarget = true
        port.recover(operatorConfirmedSafe = true)
        assertEquals(PiMotionPhase.Ready, port.motionState.value.phase)
    }
}

private class SafetyTransport : GcsTransport {
    override var isOpen = false
        private set
    var onTarget = true
    private var last = ""
    override suspend fun open() { isOpen = true }
    override suspend fun writeLine(command: String) {
        check(isOpen)
        last = command
    }
    override suspend fun readLine(): String {
        check(isOpen)
        return when (last) {
            "ERR?" -> "0"
            "POS? X" -> "X=0"
            "ONT? X" -> if (onTarget) "X=1" else "X=0"
            else -> error("Unknown query: $last")
        }
    }
    override fun close() { isOpen = false }
}
