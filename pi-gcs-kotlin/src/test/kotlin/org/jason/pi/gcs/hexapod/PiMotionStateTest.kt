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
    @Test
    fun stopFailureStillLocksRecoveryAndClosesSession() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        transport.failStop = true
        assertFailsWith<IllegalStateException> { port.stop() }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun recoveryRejectsControllerErrorAndOutOfRangePosition() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        port.stop()
        transport.controllerError = 5
        assertFailsWith<IllegalStateException> { port.recover(operatorConfirmedSafe = true) }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        transport.controllerError = 0
        transport.maxX = -1.0
        assertFailsWith<IllegalStateException> { port.recover(operatorConfirmedSafe = true) }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        transport.maxX = 10.0
        port.recover(operatorConfirmedSafe = true)
        assertEquals(PiMotionPhase.Ready, port.motionState.value.phase)
    }

}

private class SafetyTransport : GcsTransport {
    override var isOpen = false
        private set
    var onTarget = true
    var failStop = false
    var controllerError = 0
    var maxX = 10.0
    private var last = ""
    override suspend fun open() { isOpen = true }
    override suspend fun writeLine(command: String) {
        check(isOpen)
        if (failStop && command == "STP") error("Simulated STP failure")
        last = command
    }
    override suspend fun readLine(): String {
        check(isOpen)
        return when (last) {
            "ERR?" -> controllerError.toString()
            "TMN? X" -> "X=-10"
            "TMX? X" -> "X=$maxX"
            "POS? X" -> "X=0"
            "ONT? X" -> if (onTarget) "X=1" else "X=0"
            else -> error("Unknown query: $last")
        }
    }
    override fun close() { isOpen = false }
}
