package org.jason.pi.gcs.pitools

import org.jason.pi.gcs.hexapod.LinearCommandUnit
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.hexapod.PiHexapodDelta
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiHexapodUnitConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiTravelRangeTest {

    @Test
    fun strictCheckFailsWhenRequiredAxisRangeIsMissing() {
        val travelRange = PiTravelRange.of(
            PiAxisTravelRange(
                axis = PiAxis.X,
                min = -10.0,
                max = 10.0
            )
        )

        val exception = assertFailsWith<PiTravelRangeMissingAxesException> {
            travelRange.requireWithinRangeStrict(
                pose = PiHexapodPose.ZERO,
                requiredAxes = listOf(PiAxis.X, PiAxis.Y)
            )
        }

        assertEquals(
            expected = listOf(PiAxis.Y),
            actual = exception.missingAxes
        )
    }

    @Test
    fun strictCheckFailsWhenPoseIsOutOfRange() {
        val travelRange = fullTravelRange()

        val exception = assertFailsWith<PiTravelRangeException> {
            travelRange.requireWithinRangeStrict(
                pose = PiHexapodPose.ZERO.copy(
                    xUm = 11.0
                )
            )
        }

        assertEquals(
            expected = listOf(PiAxis.X),
            actual = exception.outOfRangeAxes.map { it.axis }
        )
    }

    @Test
    fun relativeMoveCheckReturnsTargetPose() {
        val travelRange = fullTravelRange()

        val targetPose = travelRange.requireMoveWithinRange(
            currentPose = PiHexapodPose.ZERO,
            delta = PiHexapodDelta(
                dxUm = 3.0,
                dyUm = -2.0,
                duDeg = 0.5
            )
        )

        assertEquals(3.0, targetPose.xUm)
        assertEquals(-2.0, targetPose.yUm)
        assertEquals(0.5, targetPose.uDeg)
    }

    @Test
    fun strictCheckResultReportsMissingTargetAxesWithoutThrowing() {
        val travelRange = fullTravelRange()

        val result = travelRange.checkWithinRangeStrict(
            values = mapOf(
                PiAxis.X to 0.0
            ),
            requiredAxes = listOf(PiAxis.X, PiAxis.Y)
        )

        assertFalse(result.passed)
        assertEquals(
            expected = listOf(PiAxis.Y),
            actual = result.missingTargetAxes
        )
    }

    @Test
    fun strictCheckResultReportsInvalidTargetValuesWithoutThrowing() {
        val travelRange = fullTravelRange()

        val result = travelRange.checkWithinRangeStrict(
            values = mapOf(
                PiAxis.X to Double.NaN
            ),
            requiredAxes = listOf(PiAxis.X)
        )

        assertFalse(result.passed)
        assertEquals(
            expected = listOf(PiAxis.X),
            actual = result.invalidTargetValues.map { it.axis }
        )
    }

    @Test
    fun strictCheckResultPassesForValidPose() {
        val travelRange = fullTravelRange()

        val result = travelRange.checkWithinRangeStrict(
            pose = PiHexapodPose.ZERO
        )

        assertTrue(result.passed)
        assertTrue(result.message.contains("在 PI 行程范围内"))
    }

    @Test
    fun commandRangesCanBeConvertedToBusinessUnits() {
        val travelRange = PiTravelRange.fromCommandRangeMap(
            commandRanges = mapOf(
                PiAxis.X to -1.0..1.0,
                PiAxis.U to -2.0..2.0
            ),
            unitConfig = PiHexapodUnitConfig(
                linearCommandUnit = LinearCommandUnit.Millimeter
            )
        )

        assertEquals(-1000.0, travelRange.requireRangeOf(PiAxis.X).min)
        assertEquals(1000.0, travelRange.requireRangeOf(PiAxis.X).max)
        assertEquals(-2.0, travelRange.requireRangeOf(PiAxis.U).min)
        assertEquals(2.0, travelRange.requireRangeOf(PiAxis.U).max)
    }

    private fun fullTravelRange(): PiTravelRange {
        return PiTravelRange.of(
            PiAxisTravelRange(PiAxis.X, -10.0, 10.0),
            PiAxisTravelRange(PiAxis.Y, -10.0, 10.0),
            PiAxisTravelRange(PiAxis.Z, -10.0, 10.0),
            PiAxisTravelRange(PiAxis.U, -1.0, 1.0),
            PiAxisTravelRange(PiAxis.V, -1.0, 1.0),
            PiAxisTravelRange(PiAxis.W, -1.0, 1.0)
        )
    }
}
