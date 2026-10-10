package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiMotionCommandGatewayTest {
    @Test
    fun everyNonReadyPhaseRejectsCommandsWithoutCallingDriver() = runBlocking {
        val state = MutableStateFlow(PiMotionState())
        val fake = FakeMotionPort()
        val gateway = PiMotionCommandGateway(fake, state)
        for (phase in PiMotionPhase.entries.filter { it != PiMotionPhase.Ready }) {
            state.value = PiMotionState(phase)
            assertFailsWith<IllegalStateException> { gateway.moveTo(PiHexapodPose.ZERO) }
            assertFailsWith<IllegalStateException> { gateway.moveBy(PiHexapodDelta(dxUm = 1.0)) }
            assertFailsWith<IllegalStateException> { gateway.moveToSafePose() }
        }
        assertEquals(0, fake.moves)
        state.value = PiMotionState(PiMotionPhase.Ready)
        gateway.moveTo(PiHexapodPose.ZERO)
        assertEquals(1, fake.moves)
    }

    @Test
    fun stopIsAvailableEvenWhenRecoveryRequired() = runBlocking {
        val state = MutableStateFlow(PiMotionState(PiMotionPhase.RecoveryRequired))
        val fake = FakeMotionPort()
        PiMotionCommandGateway(fake, state).stop()
        assertEquals(1, fake.stops)
    }
}

private class FakeMotionPort : PiHexapodPort {
    var moves = 0
    var stops = 0
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun identify() = "fake"
    override suspend fun startup(reference: Boolean) = Unit
    override suspend fun moveTo(pose: PiHexapodPose, wait: Boolean) { moves++ }
    override suspend fun moveBy(delta: PiHexapodDelta, wait: Boolean) { moves++ }
    override suspend fun currentPose() = PiHexapodPose.ZERO
    override suspend fun waitOnTarget(timeoutMs: Long) = Unit
    override suspend fun stop() { stops++ }
    override suspend fun moveToSafePose() { moves++ }
    override fun close() = Unit
}
