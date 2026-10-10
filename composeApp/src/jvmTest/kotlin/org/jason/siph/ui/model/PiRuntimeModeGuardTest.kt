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
        assertTrue(physical.status.message?.contains("denied") == true)
    }
    @Test
    fun physicalModeAllowsPageAndStepEditsButNeverDemoMovement() {
        val disconnected = CouplingToolUiState().rejectUnwiredPhysicalAction()
        val configured = disconnected
            .reduceUnwiredPhysicalAction(CouplingToolAction.UpdateLinearStep(2.5))
            .reduceUnwiredPhysicalAction(CouplingToolAction.UpdateAngleStep(0.02))
        assertEquals(2.5, configured.positioner.linearStepUm)
        assertEquals(0.02, configured.positioner.angleStepDeg)
        assertFalse(configured.positioner.motionAllowed)
        val denied = configured.reduceUnwiredPhysicalAction(CouplingToolAction.MoveSafe)
        assertTrue(denied.status.isError)
        assertFalse(denied.positioner.motionAllowed)
        assertFalse(denied.positioner.connected)
    }

}
