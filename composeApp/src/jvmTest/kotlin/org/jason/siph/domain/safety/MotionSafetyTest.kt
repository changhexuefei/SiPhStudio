package org.jason.siph.domain.safety

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.PivotAwareOpticalPositionerPort
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.domain.positioner.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MotionSafetyTest {

    private val config = MotionSafetyConfig(
        xLimitUm = AxisSoftLimit(-100.0, 100.0),
        yLimitUm = AxisSoftLimit(-100.0, 100.0),
        zLimitUm = AxisSoftLimit(-50.0, 50.0),
        uLimitDeg = AxisSoftLimit(-5.0, 5.0),
        vLimitDeg = AxisSoftLimit(-5.0, 5.0),
        wLimitDeg = AxisSoftLimit(-5.0, 5.0),
        protectedTransferEnabled = true,
        clearanceZUm = 20.0,
        protectedLinearThresholdUm = 10.0,
        protectedAngleThresholdDeg = 0.1
    )

    @Test
    fun smallCouplingMoveUsesDirectPath() {
        val planner = MotionSafetyPlanner(config)
        val current = OpticalPose.ZERO
        val target = current.copy(xUm = 3.0, yUm = -2.0, zUm = 1.0)

        assertEquals(listOf(target), planner.planMove(current, target))
    }

    @Test
    fun largeLateralMoveUsesClearancePath() {
        val planner = MotionSafetyPlanner(config)
        val current = OpticalPose.ZERO
        val target = OpticalPose(
            xUm = 30.0,
            yUm = -20.0,
            zUm = 2.0,
            uDeg = 0.0,
            vDeg = 0.0,
            wDeg = 0.0
        )

        assertEquals(
            listOf(
                current.copy(zUm = 20.0),
                target.copy(zUm = 20.0),
                target
            ),
            planner.planMove(current, target)
        )
    }

    @Test
    fun outOfRangeTargetIsRejectedBeforeDriverMove() = runBlocking {
        val raw = RecordingPositioner()
        raw.connect()
        val safe = SafetyCheckedOpticalPositioner(
            delegate = raw,
            planner = MotionSafetyPlanner(config)
        )

        assertFailsWith<MotionSafetyException> {
            safe.moveTo(OpticalPose.ZERO.copy(xUm = 101.0), wait = true)
        }
        assertEquals(emptyList(), raw.commandedPoses)
    }

    @Test
    fun wrapperExecutesProtectedWaypointsInOrder() = runBlocking {
        val raw = RecordingPositioner()
        raw.connect()
        val safe = SafetyCheckedOpticalPositioner(
            delegate = raw,
            planner = MotionSafetyPlanner(config)
        )
        val target = OpticalPose.ZERO.copy(xUm = 25.0, yUm = 5.0, zUm = 2.0)

        safe.moveTo(target, wait = true)

        assertEquals(
            listOf(
                OpticalPose.ZERO.copy(zUm = 20.0),
                target.copy(zUm = 20.0),
                target
            ),
            raw.commandedPoses
        )
        assertEquals(target, safe.currentPose())
    }

    private class RecordingPositioner : PivotAwareOpticalPositionerPort {
        var connected = false
        var pose = OpticalPose.ZERO
        val commandedPoses = mutableListOf<OpticalPose>()

        override suspend fun connect() {
            connected = true
        }

        override suspend fun disconnect() {
            connected = false
        }

        override suspend fun identify(): String = "Recording Positioner"

        override suspend fun startup(reference: Boolean) = Unit

        override suspend fun moveTo(pose: OpticalPose, wait: Boolean) {
            check(connected)
            commandedPoses += pose
            this.pose = pose
        }

        override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) {
            moveTo(pose + delta, wait)
        }

        override suspend fun moveByAroundPivot(
            delta: OpticalDelta,
            pivot: VirtualPivotPoint,
            wait: Boolean
        ) {
            moveBy(delta, wait)
        }

        override suspend fun currentPose(): OpticalPose {
            check(connected)
            return pose
        }

        override suspend fun waitOnTarget(timeoutMs: Long) = Unit

        override suspend fun stop() = Unit

        override suspend fun moveToSafePose() {
            moveTo(OpticalPose.ZERO, wait = true)
        }
    }
}
