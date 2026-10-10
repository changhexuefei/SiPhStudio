package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
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
    fun stopDuringRecoveryKeepsFaultLatch() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        port.stop()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.recoveryReadStarted = entered
        transport.pauseRecovery = release
        val recovery = async {
            runCatching { port.recover(operatorConfirmedSafe = true) }
        }
        entered.await()
        val stopping = async { runCatching { port.stop() } }
        // stop() latches before waiting on the busy GCS transaction.
        while (port.motionState.value.phase != PiMotionPhase.Stopping) yield()
        release.complete(Unit)
        assertEquals(true, recovery.await().isFailure)
        stopping.await()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
    }

    @Test
    fun recoveryIsRejectedDuringInFlightStop() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.recoveryReadStarted = waiting
        transport.pauseRecovery = release
        val read = async { runCatching { port.currentPose() } }
        waiting.await()
        val stopping = async { runCatching { port.stop() } }
        while (port.motionState.value.phase != PiMotionPhase.Stopping) yield()
        assertFailsWith<IllegalStateException> { port.recover(operatorConfirmedSafe = true) }
        release.complete(Unit)
        read.await()
        stopping.await()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
    }

    @Test
    fun motionCompletionAfterStopDoesNotRestoreReady() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onTargetReadStarted = entered
        transport.pauseOnTarget = release
        val moving = async { runCatching { port.waitOnTarget(9_000L) } }
        entered.await()
        val stopping = async { runCatching { port.stop() } }
        while (port.motionState.value.phase != PiMotionPhase.Stopping) yield()
        release.complete(Unit)
        moving.await()
        stopping.await()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertEquals("Stop requested; physical stop unverified", port.motionState.value.reason)
        assertFalse(port.motionState.value.canMove)
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
    var pauseOnTarget: CompletableDeferred<Unit>? = null
    var onTargetReadStarted: CompletableDeferred<Unit>? = null
    var pauseRecovery: CompletableDeferred<Unit>? = null
    var recoveryReadStarted: CompletableDeferred<Unit>? = null
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
            "POS? X" -> {
                recoveryReadStarted?.complete(Unit)
                pauseRecovery?.await()
                "X=0"
            }
            "ONT? X" -> {
                onTargetReadStarted?.complete(Unit)
                pauseOnTarget?.await()
                if (onTarget) "X=1" else "X=0"
            }
            else -> error("Unknown query: $last")
        }
    }
    override fun close() { isOpen = false }
}
