package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiMotionCommandGatewayConcurrencyTest {
    @Test
    fun secondMotionIsRejectedInsteadOfQueued() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val port = BlockingPort(entered, release)
        val gateway = PiMotionCommandGateway(
            port, MutableStateFlow(PiMotionState(PiMotionPhase.Ready))
        )
        val first = async { gateway.moveTo(PiHexapodPose.ZERO) }
        entered.await()
        assertFailsWith<IllegalStateException> {
            gateway.moveBy(PiHexapodDelta(dxUm = 1.0))
        }
        assertEquals(1, port.moves)
        release.complete(Unit)
        first.await()
    }

    @Test
    fun stopLatchesImmediatelyAndRejectsFurtherMotion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val port = BlockingPort(entered, release)
        val gateway = PiMotionCommandGateway(
            port, MutableStateFlow(PiMotionState(PiMotionPhase.Ready))
        )
        val first = async { gateway.moveTo(PiHexapodPose.ZERO) }
        entered.await()
        gateway.stop()
        assertEquals(1, port.stops)
        release.complete(Unit)
        first.await()
        assertFailsWith<IllegalStateException> {
            gateway.moveToSafePose()
        }
        assertEquals(1, port.moves)
    }
}

private class BlockingPort(
    private val entered: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>
) : PiHexapodPort {
    var moves = 0
    var stops = 0
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun identify() = "fake"
    override suspend fun startup(reference: Boolean) = Unit
    override suspend fun moveTo(pose: PiHexapodPose, wait: Boolean) {
        moves++
        entered.complete(Unit)
        release.await()
    }
    override suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean) { moves++ }
    override suspend fun currentPose() = PiHexapodPose.ZERO
    override suspend fun waitOnTarget(timeoutMs: Long) = Unit
    override suspend fun stop() { stops++ }
    override suspend fun moveToSafePose() { moves++ }
    override fun close() = Unit
}
