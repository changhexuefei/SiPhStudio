package org.jason.pi.gcs.simulation

import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiMotionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VirtualPiHexapodStateTest {
    @Test
    fun motionAdvancesOnlyOnExplicitTicksAcrossAllSixAxes() {
        val simulator = VirtualPiHexapodState()
        val target = PiHexapodPose(2_000.0, -1_000.0, 500.0, 10.0, -5.0, 1.0)
        assertEquals(PiMotionPhase.Disconnected, simulator.motion.phase)
        assertFailsWith<IllegalStateException> { simulator.moveTo(target) }
        simulator.connect()
        simulator.moveTo(target)
        assertEquals(PiHexapodPose.ZERO, simulator.pose)
        assertEquals(PiMotionPhase.Moving, simulator.motion.phase)
        simulator.tick(1.0)
        assertEquals(PiHexapodPose(1_000.0, -1_000.0, 500.0, 5.0, -5.0, 1.0), simulator.pose)
        simulator.tick(1.0)
        assertEquals(target, simulator.pose)
        assertEquals(PiMotionPhase.Ready, simulator.motion.phase)
        simulator.disconnect()
        assertEquals(PiMotionPhase.Disconnected, simulator.motion.phase)
    }

    @Test
    fun boundsAndNonFiniteTargetsFailWithoutIssuingMotion() {
        val simulator = VirtualPiHexapodState()
        simulator.connect()
        assertFailsWith<IllegalArgumentException> {
            simulator.moveTo(PiHexapodPose(20_000.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            simulator.moveTo(PiHexapodPose(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> { simulator.tick(Double.POSITIVE_INFINITY) }
        assertEquals(PiMotionPhase.Ready, simulator.motion.phase)
        assertEquals(PiHexapodPose.ZERO, simulator.pose)
    }

    @Test
    fun virtualStopLatchesRecoveryAndPreventsFurtherMotion() {
        val simulator = VirtualPiHexapodState()
        simulator.connect()
        simulator.moveTo(PiHexapodPose(3_000.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertFailsWith<IllegalStateException> { simulator.disconnect() }
        simulator.tick(0.5)
        simulator.stop()
        val stoppedAt = simulator.pose
        simulator.tick(10.0)
        assertEquals(stoppedAt, simulator.pose)
        assertFalse(simulator.motion.canMove)
        assertFailsWith<IllegalStateException> {
            simulator.moveTo(PiHexapodPose.ZERO)
        }
        assertFailsWith<IllegalArgumentException> { simulator.acknowledgeRecovery(false) }
        simulator.acknowledgeRecovery(true)
        assertEquals(PiMotionPhase.Ready, simulator.motion.phase)
    }

    @Test
    fun virtualFaultRequiresDisconnectRatherThanImplicitRecovery() {
        val simulator = VirtualPiHexapodState()
        simulator.connect()
        simulator.fault("Synthetic axis fault")
        assertEquals(PiMotionPhase.Fault, simulator.motion.phase)
        assertFailsWith<IllegalStateException> { simulator.acknowledgeRecovery(true) }
        assertFailsWith<IllegalStateException> { simulator.moveTo(PiHexapodPose.ZERO) }
        simulator.disconnect()
        assertEquals(PiMotionPhase.Disconnected, simulator.motion.phase)
    }
}
