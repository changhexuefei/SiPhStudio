package org.jason.siph.domain.autonomy

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.coupling.CouplingConfig
import org.jason.siph.domain.coupling.CouplingResult
import org.jason.siph.domain.coupling.CouplingResultStatus
import org.jason.siph.domain.coupling.CouplingRunner
import org.jason.siph.domain.coupling.CouplingSample
import org.jason.siph.domain.coupling.CouplingStage
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutonomyFirstStageTest {

    @Test
    fun waferHierarchyResolvesCouplerByStableSiteKey() {
        val coupler = OpticalCouplerDefinition(
            id = "gc-01",
            name = "Input Grating",
            geometry = PhotonicCouplingGeometry.VerticalGrating,
            offsetXUm = 15.0,
            offsetYUm = -8.0
        )
        val wafer = testWafer(coupler)
        val key = testSite()

        assertEquals(coupler, wafer.findSite(key))
        assertNull(wafer.findSite(key.copy(couplerId = "missing")))
    }

    @Test
    fun stableReturnVerificationMeasuresPowerAndPositionRepeatability() = runBlocking {
        var now = 1_000L
        val positioner = RecordingPositioner(OpticalPose.ZERO)
        val powerMeter = FixedPowerMeter(-8.25)
        val verifier = OpticalAlignmentVerifier(
            positioner = positioner,
            powerMeter = powerMeter,
            nowEpochMs = { now++ }
        )

        val result = verifier.verify(
            bestPose = OpticalPose.ZERO,
            powerMeterChannel = 1,
            config = OpticalVerificationConfig(
                repeatCount = 4,
                readsPerRepeat = 2,
                settleDelayMs = 0L,
                excursion = OpticalDelta(dxUm = 1.0),
                maxPeakToPeakDb = 0.01,
                maxStandardDeviationDb = 0.01,
                maxReturnPositionErrorUm = 0.01
            )
        )

        assertTrue(result.passed)
        assertEquals(4, result.samples.size)
        assertEquals(0.0, result.peakToPeakDb, 1e-9)
        assertEquals(0.0, result.maximumPositionErrorUm, 1e-9)
        assertEquals(OpticalPose.ZERO, positioner.currentPose())
    }

    @Test
    fun driftPolicyEscalatesFromContinueToRealignAndStop() {
        var now = 100L
        val evaluator = DriftEvaluator { now++ }
        val baseline = DriftBaseline(
            id = "baseline-a",
            site = testSite(),
            referencePose = OpticalPose.ZERO,
            referencePowerDbm = -10.0,
            calibrationProfileId = "cal-a",
            createdAtEpochMs = 1L
        )
        val policy = DriftPolicy()

        val stable = evaluator.assess(
            baseline = baseline,
            currentPose = OpticalPose.ZERO,
            currentPowerDbm = -10.1,
            currentTemperatureC = null,
            policy = policy
        )
        val realign = evaluator.assess(
            baseline = baseline,
            currentPose = OpticalPose.ZERO,
            currentPowerDbm = -11.0,
            currentTemperatureC = null,
            policy = policy
        )
        val stop = evaluator.assess(
            baseline = baseline,
            currentPose = OpticalPose.ZERO,
            currentPowerDbm = -13.5,
            currentTemperatureC = null,
            policy = policy
        )

        assertEquals(DriftAction.Continue, stable.action)
        assertEquals(DriftAction.LocalRealign, realign.action)
        assertEquals(DriftAction.StopWorkflow, stop.action)
    }

    @Test
    fun workflowRetriesFailedCouplingReturnsToTrainingPositionAndPersistsRecord() = runBlocking {
        var now = 10_000L
        val repository = InMemoryAutonomyRepository()
        val site = testSite()
        val calibration = CalibrationProfile(
            id = "cal-a",
            name = "Fixture A",
            controllerIdentity = "PI-TEST",
            fixtureId = "fixture-a",
            geometry = PhotonicCouplingGeometry.VerticalGrating,
            measurementPose = OpticalPose.ZERO,
            createdAtEpochMs = 1L,
            verifiedAtEpochMs = 2L,
            verifiedBy = "test",
            verified = true
        )
        val trained = TrainedMeasurementPosition(
            id = "position-a",
            name = "GC position",
            site = site,
            pose = OpticalPose.ZERO,
            referencePowerDbm = -12.0,
            calibrationProfileId = calibration.id,
            trainedAtEpochMs = 3L,
            verifiedAtEpochMs = 4L,
            verified = true
        )
        repository.saveProfile(calibration)
        repository.activateProfile(calibration.id)
        repository.savePosition(trained)

        val positioner = RecordingPositioner(OpticalPose.ZERO)
        val powerMeter = FixedPowerMeter(-8.0)
        val coupling = FailOnceCouplingRunner { now++ }
        val runner = DefaultSiPhWorkflowRunner(
            positioner = positioner,
            powerMeter = powerMeter,
            couplingRunner = coupling,
            calibrationProfiles = repository,
            positions = repository,
            baselines = repository,
            checkpoints = repository,
            records = repository,
            verifier = OpticalAlignmentVerifier(positioner, powerMeter) { now++ },
            driftEvaluator = DriftEvaluator { now++ },
            runtimeModeProvider = { "Demo" },
            nowEpochMs = { now++ }
        )
        val recipe = SiPhWorkflowRecipe(
            id = "recipe-a",
            site = site,
            calibrationProfileId = calibration.id,
            trainedPositionId = trained.id,
            couplingConfig = CouplingConfig(
                settleDelayMs = 0L,
                powerAverageCount = 1,
                powerAverageDelayMs = 0L
            ),
            retryPolicy = WorkflowRetryPolicy(
                maxAttempts = 2,
                initialDelayMs = 0L,
                maximumDelayMs = 0L
            ),
            enableVerification = false,
            enableDriftAssessment = false
        )

        val result = runner.run(recipe = recipe, runId = "run-a")

        assertTrue(result.completed)
        assertEquals(2, coupling.calls)
        assertTrue(positioner.stopCount >= 1)
        assertEquals(OpticalPose.ZERO, positioner.currentPose())
        assertNull(repository.findCheckpoint("run-a"))
        assertNotNull(repository.findRecord(result.id))
        assertEquals(SiPhWorkflowStage.Completed, runner.state.value.stage)
    }

    private fun testSite() = MeasurementSiteKey(
        waferId = "wafer-a",
        die = DieIndex(column = 2, row = 3),
        subDieId = "sub-a",
        couplerId = "gc-01"
    )

    private fun testWafer(coupler: OpticalCouplerDefinition) = SiPhWaferDefinition(
        id = "wafer-a",
        diameterMm = 200.0,
        transform = WaferCoordinateTransform(
            originStageXUm = 0.0,
            originStageYUm = 0.0,
            diePitchXUm = 1_000.0,
            diePitchYUm = 1_000.0
        ),
        dies = listOf(
            SiPhDieDefinition(
                index = DieIndex(2, 3),
                subDies = listOf(
                    SiPhSubDieDefinition(
                        id = "sub-a",
                        name = "Sub A",
                        originOffsetXUm = 0.0,
                        originOffsetYUm = 0.0,
                        couplers = listOf(coupler)
                    )
                )
            )
        ),
        createdAtEpochMs = 1L
    )
}

private class RecordingPositioner(
    initialPose: OpticalPose
) : OpticalPositionerPort {
    private var pose = initialPose
    var stopCount: Int = 0
        private set

    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = "PI-TEST-H811"
    override suspend fun startup(reference: Boolean) = Unit

    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) {
        this.pose = pose
    }

    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) {
        pose = pose.copy(
            xUm = pose.xUm + delta.dxUm,
            yUm = pose.yUm + delta.dyUm,
            zUm = pose.zUm + delta.dzUm,
            uDeg = pose.uDeg + delta.duDeg,
            vDeg = pose.vDeg + delta.dvDeg,
            wDeg = pose.wDeg + delta.dwDeg
        )
    }

    override suspend fun currentPose(): OpticalPose = pose
    override suspend fun waitOnTarget(timeoutMs: Long) = Unit

    override suspend fun stop() {
        stopCount += 1
    }

    override suspend fun moveToSafePose() {
        pose = OpticalPose.ZERO
    }
}

private class FixedPowerMeter(
    private val powerDbm: Double
) : OpticalPowerMeterPort {
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = "POWER-METER-TEST"
    override suspend fun setWavelengthNm(wavelengthNm: Double, channel: Int) = Unit
    override suspend fun readPowerDbm(channel: Int): Double = powerDbm
}

private class FailOnceCouplingRunner(
    private val now: () -> Long
) : CouplingRunner {
    var calls: Int = 0
        private set

    override suspend fun run(
        initialPose: OpticalPose,
        config: CouplingConfig,
        onSample: suspend (CouplingSample) -> Unit,
        shouldStop: suspend () -> Boolean
    ): CouplingResult {
        calls += 1
        if (calls == 1) {
            return CouplingResult(
                status = CouplingResultStatus.FirstLightNotFound,
                bestPose = initialPose,
                bestPowerDbm = -50.0,
                finalPose = initialPose,
                finalPowerDbm = -50.0,
                samples = emptyList(),
                message = "Injected first attempt failure",
                startedAtMs = now(),
                finishedAtMs = now()
            )
        }

        val sample = CouplingSample(
            index = 0,
            pose = initialPose,
            powerDbm = -8.0,
            stage = CouplingStage.Final,
            timestampMs = now()
        )
        onSample(sample)
        return CouplingResult(
            status = CouplingResultStatus.Success,
            bestPose = initialPose,
            bestPowerDbm = sample.powerDbm,
            finalPose = initialPose,
            finalPowerDbm = sample.powerDbm,
            samples = listOf(sample),
            message = "Success",
            startedAtMs = now(),
            finishedAtMs = now()
        )
    }
}
