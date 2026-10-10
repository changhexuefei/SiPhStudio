package org.jason.siph.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiRuntimeModeGuardTest {
    @Test
    fun physicalModeRevokesDemoReadyAndReportsUnwiredHardware() {
        val demo = CouplingToolUiState(
            positioner = PositionerUiState(connected = true)
        )
        assertTrue(demo.positioner.motionAllowed)
        val physical = demo.rejectUnwiredPhysicalAction()
        assertFalse(physical.positioner.motionAllowed)
        assertFalse(physical.positioner.connected)
        assertTrue(physical.positioner.requiresVerifiedPiState)
        assertEquals(PiUiMotionPhase.Disconnected.name, physical.positioner.piMotionPhase)
        assertTrue(physical.status.isError)
        assertTrue(physical.status.message.contains("denied"))
    }
}
