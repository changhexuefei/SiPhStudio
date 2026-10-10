package org.jason.pi.gcs.hexapod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiHexapodUnitConfigTest {
    private val config = PiHexapodUnitConfig.MillimeterDegree

    @Test
    fun roundTripSixAxisPose() {
        val pose = PiHexapodPose(12.5, -25.0, 0.125, 1.0, -2.0, 3.5)
        val command = config.toCommandValues(pose)
        assertEquals(0.0125, command.getValue(PiAxis.X), 1e-12)
        assertEquals(-0.025, command.getValue(PiAxis.Y), 1e-12)
        val restored = config.fromCommandValues(command)
        assertEquals(pose.xUm, restored.xUm, 1e-9)
        assertEquals(pose.yUm, restored.yUm, 1e-9)
        assertEquals(pose.zUm, restored.zUm, 1e-9)
        assertEquals(pose.uDeg, restored.uDeg, 1e-9)
        assertEquals(pose.vDeg, restored.vDeg, 1e-9)
        assertEquals(pose.wDeg, restored.wDeg, 1e-9)
    }

    @Test
    fun rejectsNonFinitePositionOrDelta() {
        assertFailsWith<IllegalArgumentException> {
            config.toCommandValues(PiHexapodPose.ZERO.copy(xUm = Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            config.toCommandDeltas(PiHexapodDelta(dwDeg = Double.POSITIVE_INFINITY))
        }
        assertFailsWith<IllegalArgumentException> {
            config.commandToLinearUm(Double.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun rejectsInvalidTravelRanges() {
        assertFailsWith<IllegalArgumentException> {
            config.fromCommandTravelRanges(mapOf(PiAxis.X to (2.0..1.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            config.fromCommandTravelRanges(mapOf(PiAxis.Z to (0.0..Double.POSITIVE_INFINITY)))
        }
    }

    @Test
    fun convertsTravelRangeUnits() {
        val converted = config.fromCommandTravelRanges(mapOf(
            PiAxis.X to (-1.0..2.0),
            PiAxis.U to (-5.0..5.0)
        ))
        assertEquals(-1000.0, converted.getValue(PiAxis.X).start)
        assertEquals(2000.0, converted.getValue(PiAxis.X).endInclusive)
        assertEquals(-5.0, converted.getValue(PiAxis.U).start)
    }
}
