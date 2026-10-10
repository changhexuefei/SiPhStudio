package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        assertEquals("Stop requested; physical stop unverified", port.motionState.value.reason)
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
    fun cancellingPendingOnTargetQueryKeepsMotionLocked() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        val entered = CompletableDeferred<Unit>()
        transport.onTargetReadStarted = entered
        transport.pauseOnTarget = CompletableDeferred()
        val task = launch { port.waitOnTarget(9_000L) }
        entered.await()
        task.cancel()
        task.join()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
    }

    @Test
    fun poseReadbackFailureLocksMovementAndDisconnects() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        transport.failPositionRead = true
        assertFailsWith<IllegalStateException> { port.currentPose() }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun identificationFailureLatchesRecovery() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        transport.failIdentification = true
        assertFailsWith<IllegalStateException> { port.identify() }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun travelQueryFailureLatchesRecovery() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X))
        port.connect()
        transport.failTravelRead = true
        assertFailsWith<IllegalStateException> { port.queryCommandTravelRange() }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
    }

    @Test
    fun startupFailureLocksMotionAndClosesSession() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        transport.failStop = true
        assertFailsWith<IllegalStateException> { port.startup(reference = false) }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun failedConnectLatchesRecoveryAndClosesTransport() = runBlocking {
        val transport = SafetyTransport()
        transport.failOpen = true
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        assertFailsWith<IllegalStateException> { port.connect() }
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun stopDuringPendingConnectNeverRestoresReady() = runBlocking {
        val transport = SafetyTransport()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.openStarted = entered
        transport.pauseOpen = release
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        val connecting = async { runCatching { port.connect() } }
        entered.await()
        val stopping = async { runCatching { port.stop() } }
        while (port.motionState.value.phase != PiMotionPhase.Stopping &&
            port.motionState.value.phase != PiMotionPhase.RecoveryRequired) yield()
        release.complete(Unit)
        connecting.await()
        stopping.await()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
    }

    @Test
    fun disconnectDuringPendingConnectNeverPublishesReady() = runBlocking {
        val transport = SafetyTransport()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.openStarted = entered
        transport.pauseOpen = release
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        val connecting = async { runCatching { port.connect() } }
        entered.await()
        port.disconnect()
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        release.complete(Unit)
        assertEquals(true, connecting.await().isFailure)
        assertFalse(transport.isOpen)
        assertFalse(port.motionState.value.canMove)
        assertFailsWith<IllegalStateException> { port.connect() }
    }

    @Test
    fun idleDisconnectPublishesDisconnected() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        port.disconnect()
        assertEquals(PiMotionPhase.Disconnected, port.motionState.value.phase)
        assertFalse(transport.isOpen)
    }

    @Test
    fun disconnectDuringPendingRecoveryCannotReauthorizeMotion() = runBlocking {
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
        val restoring = async { runCatching { port.recover(operatorConfirmedSafe = true) } }
        entered.await()
        port.disconnect()
        release.complete(Unit)
        assertEquals(true, restoring.await().isFailure)
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        assertFalse(transport.isOpen)
    }

    @Test
    fun overlappingRecoveryIsRejectedWithoutInvalidatingFirstVerification() = runBlocking {
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
        val first = async { runCatching { port.recover(operatorConfirmedSafe = true) } }
        entered.await()
        assertFailsWith<IllegalStateException> { port.recover(operatorConfirmedSafe = true) }
        assertFalse(port.motionState.value.canMove)
        release.complete(Unit)
        assertEquals(true, first.await().isSuccess)
        assertEquals(PiMotionPhase.Ready, port.motionState.value.phase)
        port.disconnect()
    }

    @Test
    fun overlappingStopDoesNotFinishAnActiveStopEarly() = runBlocking {
        val transport = SafetyTransport()
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.recoveryReadStarted = entered
        transport.pauseRecovery = release
        val query = async { runCatching { port.currentPose() } }
        entered.await()
        val first = async { runCatching { port.stop() } }
        while (port.motionState.value.phase != PiMotionPhase.Stopping) yield()
        port.stop()
        assertEquals(PiMotionPhase.Stopping, port.motionState.value.phase)
        assertFalse(port.motionState.value.canMove)
        release.complete(Unit)
        query.await()
        assertEquals(true, first.await().isSuccess)
        assertEquals(PiMotionPhase.RecoveryRequired, port.motionState.value.phase)
        assertFalse(transport.isOpen)
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
    var failOpen = false
    var openStarted: CompletableDeferred<Unit>? = null
    var pauseOpen: CompletableDeferred<Unit>? = null
    var controllerError = 0
    var maxX = 10.0
    var failPositionRead = false
    var failIdentification = false
    var failTravelRead = false
    var pauseOnTarget: CompletableDeferred<Unit>? = null
    var onTargetReadStarted: CompletableDeferred<Unit>? = null
    var pauseRecovery: CompletableDeferred<Unit>? = null
    var recoveryReadStarted: CompletableDeferred<Unit>? = null
    private var last = ""
    override suspend fun open() {
        openStarted?.complete(Unit)
        pauseOpen?.await()
        if (failOpen) error("Simulated connect failure")
        isOpen = true
    }
    override suspend fun writeLine(command: String) {
        check(isOpen)
        if (failStop && command == "STP") error("Simulated STP failure")
        last = command
    }
    override suspend fun readLine(): String {
        check(isOpen)
        return when (last) {
            "ERR?" -> controllerError.toString()
            "TMN? X" -> {
                if (failTravelRead) error("Simulated travel range query failure")
                "X=-10"
            }
            "*IDN?" -> {
                if (failIdentification) error("Simulated identification failure")
                "Simulated PI device"
            }
            "TMX? X" -> "X=$maxX"
            "POS? X" -> {
                if (failPositionRead) error("Simulated position read failure")
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
