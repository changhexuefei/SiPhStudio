package org.jason.siph.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jason.siph.domain.coupling.CouplingConfig
import org.jason.siph.domain.coupling.CouplingResult
import org.jason.siph.domain.coupling.CouplingResultStatus
import org.jason.siph.domain.coupling.CouplingRunner
import org.jason.siph.domain.coupling.CouplingSample
import org.jason.siph.domain.coupling.CouplingStage
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPowerMeter
import org.jason.siph.ui.model.CouplingStartMode
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolRunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CouplingToolStoreStartModeTest {

    @Test
    fun currentPreviousAndSafeStartModesResolveExpectedPoses() = runBlocking {
        val positioner = DemoOpticalPositioner(moveDelayMs = 0L)
        val powerMeter = DemoOpticalPowerMeter(
            poseProvider = { positioner.currentPose() }
        )
        val runner = RecordingRunner()
        val store = CouplingToolStore(
            scope = CoroutineScope(coroutineContext),
            positioner = positioner,
            powerMeter = powerMeter,
            runner = runner,
            nowMs = { 0L }
        )

        store.dispatch(CouplingToolAction.ConnectPositioner)
        waitUntil { store.state.value.positioner.connected }

        val firstStart = OpticalPose(
            xUm = 3.0,
            yUm = 4.0,
            zUm = 1.0,
            uDeg = 0.01,
            vDeg = 0.02,
            wDeg = 0.03
        )
        positioner.moveTo(firstStart, wait = true)

        store.dispatch(CouplingToolAction.StartCoupling)
        waitForRun(store, expectedRunCount = 1, runner = runner)

        assertEquals(firstStart, runner.initialPoses[0])
        assertEquals(firstStart, store.state.value.coupling.previousRunStartPose)

        positioner.moveTo(
            firstStart.copy(xUm = 50.0, yUm = 60.0),
            wait = true
        )
        store.dispatch(
            CouplingToolAction.UpdateCouplingConfig(
                store.state.value.coupling.config.copy(
                    startMode = CouplingStartMode.PreviousRunStart
                )
            )
        )
        store.dispatch(CouplingToolAction.StartCoupling)
        waitForRun(store, expectedRunCount = 2, runner = runner)

        assertEquals(firstStart, runner.initialPoses[1])

        store.dispatch(CouplingToolAction.SaveBestPose)
        val safePose = store.state.value.positioner.safePose
        assertNotNull(safePose)

        positioner.moveTo(
            firstStart.copy(xUm = 80.0, yUm = 90.0),
            wait = true
        )
        store.dispatch(
            CouplingToolAction.UpdateCouplingConfig(
                store.state.value.coupling.config.copy(
                    startMode = CouplingStartMode.SafePose
                )
            )
        )
        store.dispatch(CouplingToolAction.StartCoupling)
        waitForRun(store, expectedRunCount = 3, runner = runner)

        assertEquals(safePose, runner.initialPoses[2])
    }

    @Test
    fun previousRunStartIsUnavailableBeforeFirstRun() = runBlocking {
        val positioner = DemoOpticalPositioner(moveDelayMs = 0L)
        val powerMeter = DemoOpticalPowerMeter(
            poseProvider = { positioner.currentPose() }
        )
        val runner = RecordingRunner()
        val store = CouplingToolStore(
            scope = CoroutineScope(coroutineContext),
            positioner = positioner,
            powerMeter = powerMeter,
            runner = runner,
            nowMs = { 0L }
        )

        store.dispatch(CouplingToolAction.ConnectPositioner)
        waitUntil { store.state.value.positioner.connected }
        store.dispatch(
            CouplingToolAction.UpdateCouplingConfig(
                store.state.value.coupling.config.copy(
                    startMode = CouplingStartMode.PreviousRunStart
                )
            )
        )

        assertEquals(false, store.state.value.canStartCoupling)
        assertNotNull(store.state.value.coupling.errorMessage)
    }

    private suspend fun waitForRun(
        store: CouplingToolStore,
        expectedRunCount: Int,
        runner: RecordingRunner
    ) {
        waitUntil {
            runner.initialPoses.size >= expectedRunCount &&
                store.state.value.runState == CouplingToolRunState.Completed &&
                !store.state.value.coupling.isRunning
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(2_000L) {
            while (!condition()) {
                delay(5L)
            }
        }
    }

    private class RecordingRunner : CouplingRunner {
        val initialPoses = mutableListOf<OpticalPose>()

        override suspend fun run(
            initialPose: OpticalPose,
            config: CouplingConfig,
            onSample: suspend (CouplingSample) -> Unit,
            shouldStop: suspend () -> Boolean
        ): CouplingResult {
            initialPoses += initialPose

            val sample = CouplingSample(
                index = 0,
                pose = initialPose,
                powerDbm = -12.0,
                stage = CouplingStage.Initial,
                timestampMs = 0L
            )
            onSample(sample)

            val bestPose = initialPose.copy(xUm = initialPose.xUm + 5.0)
            return CouplingResult(
                status = CouplingResultStatus.TargetNotReached,
                bestPose = bestPose,
                bestPowerDbm = -9.0,
                finalPose = bestPose,
                finalPowerDbm = -9.0,
                samples = listOf(sample),
                message = "test run completed"
            )
        }
    }
}
