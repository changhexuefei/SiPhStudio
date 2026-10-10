package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.transport.GcsTransport
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiMotionPhase
import org.jason.pi.gcs.hexapod.PiMotionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PiMotionUiBridgeTest {
    @Test
    fun realControllerMustFailClosedWithoutReadyState() {
        val initial = PositionerUiState(connected = true, requiresVerifiedPiState = true)
        assertFalse(initial.motionAllowed)
        for (phase in PiMotionPhase.entries.filter { it != PiMotionPhase.Ready }) {
            val state = initial.withControllerMotionState(PiMotionState(phase))
            assertFalse(state.motionAllowed, "Unexpected motion permission: $phase")
            assertFalse(state.couplingAllowed)
        }
        assertTrue(initial.withControllerMotionState(PiMotionState(PiMotionPhase.Ready)).motionAllowed)
    }

    @Test
    fun faultReasonIsProjectedAndBlocksMovement() {
        val state = PositionerUiState(connected = true)
            .withControllerMotionState(PiMotionState(PiMotionPhase.RecoveryRequired, "Stop unverified"))
        assertEquals("Stop unverified", state.errorMessage)
        assertTrue(state.recoveryLocked)
        assertFalse(state.motionAllowed)
    }
    @Test
    fun disposedStoreStaysDisconnectedAfterLaterControllerTransitions() = runBlocking {
        val transport = object : GcsTransport {
            override val isOpen = false
            override suspend fun open() = Unit
            override suspend fun writeLine(command: String) = Unit
            override suspend fun readLine(): String = error("No device attached")
            override fun close() = Unit
        }
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
        val store = PiMotionUiStore(port, this)
        store.close()
        port.stop()
        yield()
        assertEquals(PiUiMotionPhase.Disconnected.name, store.state.value.piMotionPhase)
        assertFalse(store.state.value.motionAllowed)
        assertFalse(store.state.value.connected)
        store.close()
    }

}
