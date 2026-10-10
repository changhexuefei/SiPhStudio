package org.jason.siph.ui.model

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
}
