package org.jason.siph.domain.inspection

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.oo.SimulatedOoEnvironment
import org.jason.siph.domain.oo.SimulatedTemperatureController
import org.jason.siph.domain.oo.TemperatureStabilityPolicy
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectionCalibrationTest {

    @Test
    fun pureKotlinDetectorsFindSyntheticFiberAndGrating() = runBlocking {
        var now = 1_000L
        val positioner = DemoOpticalPositioner()
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 }
        )
        val camera = SimulatedCameraAcquisitionPort(environment) { now++ }
        camera.connect()
        val frame = camera.capture()
        val detector = CompositeVisionFeatureDetector()

        val fiber = detector.detect(
            frame,
            VisionFeatureRequest(VisionFeatureKind.FiberTip, minimumConfidence = 0.3)
        )
        val grating = detector.detect(
            frame,
            VisionFeatureRequest(VisionFeatureKind.Grating, minimumConfidence = 0.3)
        )

        assertTrue(fiber.found, fiber.message)
        assertTrue(grating.found, grating.message)
        assertTrue(abs(requireNotNull(fiber.centerPx).x - 80.0) < 2.0)
        assertTrue(abs(requireNotNull(fiber.centerPx).y - 60.0) < 2.0)
    }

    @Test
    fun visualPreAlignmentMovesTargetToFiberTip() = runBlocking {
        var now = 2_000L
        val positioner = DemoOpticalPositioner()
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 }
        )
        val camera = SimulatedCameraAcquisitionPort(environment) { now++ }
        camera.connect()
        val repository = repositoryWithCameraCalibration(now++)
        val service = VisualPreAlignmentService(
            camera = camera,
            detector = CompositeVisionFeatureDetector(),
            positioner = positioner,
            repository = repository
        )

        val result = service.align(
            VisualPreAlignmentRequest(
                targetKind = VisionFeatureKind.Grating,
                cameraCalibrationId = CAMERA_CALIBRATION_ID,
                maximumIterations = 4,
                minimumConfidence = 0.3,
                alignmentToleranceUm = 1.5
            )
        )

        assertTrue(result.aligned, result.message)
        val pose = positioner.currentPose()
        assertTrue(abs(pose.xUm - 18.0) < 2.0, "x=${pose.xUm}")
        assertTrue(abs(pose.yUm + 12.0) < 2.0, "y=${pose.yUm}")
    }

    @Test
    fun probeHeightTrainingUsesZFeedbackAndReturnsSafe() = runBlocking {
        var now = 3_000L
        val positioner = DemoOpticalPositioner()
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 }
        )
        val sensor = SimulatedZDisplacementSensorPort(environment) { now++ }
        sensor.connect()
        val repository = InMemoryInspectionCalibrationRepository()
        val trainer = ProbeHeightTrainer(positioner, sensor, repository) { now++ }

        val profile = trainer.train(
            ProbeHeightTrainingRequest(
                id = "height-test",
                site = site(),
                approachDirectionSign = -1,
                searchStepUm = 4.0,
                fineStepUm = 0.5,
                safeClearanceUm = 30.0,
                samplesPerStep = 2
            )
        )

        assertTrue(profile.verified)
        assertTrue(abs(profile.measuredSurfaceZUm + 45.0) < 1.0)
        assertEquals(profile.safePose, positioner.currentPose())
    }

    @Test
    fun automaticPivotCalibrationEstimatesSyntheticPivot() = runBlocking {
        var now = 4_000L
        val positioner = DemoOpticalPositioner(initialPose = OpticalPose(18.0, -12.0, 0.0, 0.0, 0.0, 0.0))
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 }
        )
        val camera = SimulatedCameraAcquisitionPort(environment) { now++ }
        camera.connect()
        val repository = repositoryWithCameraCalibration(now++)
        val service = PivotCalibrationService(
            camera = camera,
            detector = CompositeVisionFeatureDetector(),
            positioner = positioner,
            repository = repository,
            nowEpochMs = { now++ }
        )

        val result = service.calibrate(
            PivotCalibrationRequest(
                id = "pivot-test",
                cameraCalibrationId = CAMERA_CALIBRATION_ID,
                featureKind = VisionFeatureKind.Grating,
                angularStepDeg = 0.5,
                samplesPerDirection = 2,
                maximumResidualUm = 8.0
            )
        )

        assertTrue(result.verified, result.message)
        assertTrue(abs(result.pivot.zUm + 92.0) < 12.0, "pivotZ=${result.pivot.zUm}")
    }

    @Test
    fun multiTemperatureCalibrationRunsToCompletion() = runBlocking {
        var now = 5_000L
        val positioner = DemoOpticalPositioner()
        val ooEnvironment = SimulatedOoEnvironment()
        val temperature = SimulatedTemperatureController(ooEnvironment)
        val inspectionEnvironment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { temperature.readSnapshot().processValueC }
        )
        val camera = SimulatedCameraAcquisitionPort(inspectionEnvironment) { now++ }
        val sensor = SimulatedZDisplacementSensorPort(inspectionEnvironment) { now++ }
        val repository = repositoryWithCameraCalibration(now++)
        val detector = CompositeVisionFeatureDetector()
        val preAlignment = VisualPreAlignmentService(camera, detector, positioner, repository)
        val heightTrainer = ProbeHeightTrainer(positioner, sensor, repository) { now++ }
        val pivot = PivotCalibrationService(camera, detector, positioner, repository) { now++ }
        val runner = DefaultInspectionCalibrationRunner(
            camera = camera,
            zSensor = sensor,
            temperatureController = temperature,
            positioner = positioner,
            preAlignment = preAlignment,
            heightTrainer = heightTrainer,
            pivotCalibration = pivot,
            repository = repository,
            nowEpochMs = { now++ }
        )

        val result = runner.run(
            InspectionCalibrationRunRequest(
                runId = "inspection-full",
                site = site(),
                targetKind = VisionFeatureKind.Grating,
                cameraCalibrationId = CAMERA_CALIBRATION_ID,
                policy = TemperatureRecalibrationPolicy(
                    temperaturesC = listOf(25.0, 45.0),
                    runProbeHeightTraining = true,
                    runPivotCalibrationAtFirstTemperature = true,
                    rerunPivotWhenOffsetExceedsUm = 1.0
                ),
                temperatureStability = TemperatureStabilityPolicy(
                    targetToleranceC = 0.2,
                    maximumSlopeCPerMinute = 1.0,
                    stableWindowMs = 0L,
                    timeoutMs = 100L,
                    pollIntervalMs = 1L
                ),
                approachDirectionSign = -1,
                manageConnections = true
            )
        )

        assertTrue(result.completed)
        assertEquals(2, result.points.size)
        assertTrue(result.points.all { it.passed && it.preAlignment.aligned })
        assertEquals(InspectionCalibrationStage.Completed, runner.state.value.stage)
        assertTrue(repository.listHeightProfiles().size >= 2)
        assertTrue(repository.listPivotCalibrations().isNotEmpty())
    }

    private fun repositoryWithCameraCalibration(now: Long) =
        InMemoryInspectionCalibrationRepository(
            initialCameraCalibrations = listOf(
                CameraStageCalibration(
                    id = CAMERA_CALIBRATION_ID,
                    cameraId = "sim-inspection-camera",
                    opticalCenterPx = VisionPointPx(80.0, 60.0),
                    micrometersPerPixelX = 1.0,
                    micrometersPerPixelY = 1.0,
                    calibratedAtEpochMs = now,
                    verified = true,
                    rmsErrorUm = 0.05
                )
            )
        )

    private fun site() = MeasurementSiteKey(
        waferId = "inspection-wafer",
        die = DieIndex(0, 0),
        subDieId = "sub",
        couplerId = "grating"
    )

    private companion object {
        const val CAMERA_CALIBRATION_ID = "camera-calibration-test"
    }
}
