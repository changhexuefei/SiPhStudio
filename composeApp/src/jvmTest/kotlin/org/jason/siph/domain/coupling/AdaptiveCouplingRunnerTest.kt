package org.jason.siph.domain.coupling

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPowerMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveCouplingRunnerTest {

    @Test
    fun coarseAndFineSearchConvergesNearDemoPeak() = runBlocking {
        val positioner = DemoOpticalPositioner(moveDelayMs = 0L)
        val powerMeter = DemoOpticalPowerMeter(
            poseProvider = { positioner.currentPose() }
        )
        positioner.connect()
        powerMeter.connect()

        val runner = AdaptiveCouplingRunner(
            positioner = positioner,
            powerMeter = powerMeter
        )

        val result = runner.run(
            initialPose = OpticalPose.ZERO,
            config = CouplingConfig(
                spiralPlane = CouplingSpiralPlane.XY,
                firstLightThresholdDbm = -15.0,
                targetPowerDbm = -8.5,
                spiralStepUm = 4.0,
                maxRadiusUm = 22.0,
                settleDelayMs = 0L,
                powerAverageCount = 1,
                powerAverageDelayMs = 0L,
                fineStepsUm = listOf(4.0, 2.0, 1.0, 0.5, 0.2),
                maxFinePassesPerStep = 8,
                enableIncidentAngleOptimization = false,
                maxTotalSamples = 1000
            )
        )

        assertEquals(CouplingResultStatus.Success, result.status)
        assertTrue(result.samples.size > 5)
        assertTrue(result.bestPowerDbm >= -8.5)
        assertTrue(kotlin.math.abs(result.bestPose.xUm - 12.0) <= 1.0)
        assertTrue(kotlin.math.abs(result.bestPose.yUm + 8.0) <= 1.0)
        assertTrue(kotlin.math.abs(result.bestPose.zUm - 2.5) <= 1.0)
    }

    @Test
    fun stopRequestReturnsStoppedResult() = runBlocking {
        val positioner = DemoOpticalPositioner(moveDelayMs = 0L)
        val powerMeter = DemoOpticalPowerMeter(
            poseProvider = { positioner.currentPose() }
        )
        positioner.connect()
        powerMeter.connect()

        val runner = AdaptiveCouplingRunner(
            positioner = positioner,
            powerMeter = powerMeter
        )

        var emittedSamples = 0
        val result = runner.run(
            initialPose = OpticalPose.ZERO,
            config = CouplingConfig(
                firstLightThresholdDbm = -5.0,
                targetPowerDbm = -4.0,
                spiralStepUm = 2.0,
                maxRadiusUm = 40.0,
                settleDelayMs = 0L,
                powerAverageCount = 1,
                powerAverageDelayMs = 0L,
                maxTotalSamples = 1000
            ),
            onSample = { emittedSamples += 1 },
            shouldStop = { emittedSamples >= 4 }
        )

        assertEquals(CouplingResultStatus.Stopped, result.status)
        assertEquals(4, result.samples.size)
    }
}
