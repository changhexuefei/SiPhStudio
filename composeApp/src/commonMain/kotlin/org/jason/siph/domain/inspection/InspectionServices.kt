package org.jason.siph.domain.inspection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.oo.DeviceBackendMode
import org.jason.siph.domain.oo.DeviceDescriptor
import org.jason.siph.domain.oo.DeviceVerificationState
import org.jason.siph.domain.oo.TemperatureControllerPort
import org.jason.siph.domain.oo.TemperatureStabilityPolicy
import org.jason.siph.domain.positioner.OpticalCoordinateFrame
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.positioner.VirtualPivotPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class VisualPreAlignmentService(
    private val camera: CameraAcquisitionPort,
    private val detector: VisionFeatureDetector,
    private val positioner: OpticalPositionerPort,
    private val repository: InspectionCalibrationRepository
) {
    suspend fun align(request: VisualPreAlignmentRequest): VisualPreAlignmentResult {
        val calibration = repository.findCameraCalibration(request.cameraCalibrationId)
            ?: error("Camera calibration not found: ${request.cameraCalibrationId}")
        require(calibration.verified) { "Camera calibration is not verified: ${calibration.id}" }
        require(request.targetKind in detector.supportedKinds)
        require(VisionFeatureKind.FiberTip in detector.supportedKinds)

        val iterations = mutableListOf<VisualPreAlignmentIteration>()
        var finalX = Double.POSITIVE_INFINITY
        var finalY = Double.POSITIVE_INFINITY
        var finalAngle = Double.POSITIVE_INFINITY

        repeat(request.maximumIterations) { index ->
            currentCoroutineContext().ensureActive()
            val frame = camera.capture()
            val fiber = detector.detect(
                frame,
                VisionFeatureRequest(
                    kind = VisionFeatureKind.FiberTip,
                    minimumConfidence = request.minimumConfidence
                )
            )
            check(fiber.found) { fiber.message }
            val target = detector.detect(
                frame,
                VisionFeatureRequest(
                    kind = request.targetKind,
                    regionOfInterest = request.regionOfInterest,
                    minimumConfidence = request.minimumConfidence
                )
            )
            check(target.found) { target.message }

            val desired = request.targetPixel ?: requireNotNull(fiber.centerPx)
            val actual = requireNotNull(target.centerPx)
            val (correctionX, correctionY) = calibration.pixelDeltaToStage(
                deltaX = actual.x - desired.x,
                deltaY = actual.y - desired.y
            )
            val angleError = target.angleDeg ?: 0.0
            val correctionW = (-angleError).coerceIn(
                -request.maximumRotationCorrectionDeg,
                request.maximumRotationCorrectionDeg
            )
            val correctionNorm = hypot(correctionX, correctionY)
            require(correctionNorm <= request.maximumCorrectionUm) {
                "Visual correction $correctionNorm um exceeds ${request.maximumCorrectionUm} um"
            }

            val before = positioner.currentPose()
            if (
                request.executeMotion &&
                (correctionNorm > request.alignmentToleranceUm || abs(angleError) > 0.05)
            ) {
                positioner.moveBy(
                    OpticalDelta(
                        dxUm = correctionX,
                        dyUm = correctionY,
                        dwDeg = correctionW
                    ),
                    wait = true
                )
            }
            val after = positioner.currentPose()
            iterations += VisualPreAlignmentIteration(
                index = index,
                fiberTip = fiber,
                target = target,
                correctionXUm = correctionX,
                correctionYUm = correctionY,
                correctionWDeg = correctionW,
                poseBefore = before,
                poseAfter = after,
                capturedAtEpochMs = frame.capturedAtEpochMs
            )
            finalX = correctionX
            finalY = correctionY
            finalAngle = angleError

            if (
                correctionNorm <= request.alignmentToleranceUm &&
                abs(angleError) <= 0.05
            ) {
                return VisualPreAlignmentResult(
                    aligned = true,
                    finalPose = after,
                    iterations = iterations,
                    finalOffsetXUm = finalX,
                    finalOffsetYUm = finalY,
                    finalAngleErrorDeg = finalAngle,
                    message = "Visual pre-alignment converged"
                )
            }
        }

        return VisualPreAlignmentResult(
            aligned = false,
            finalPose = positioner.currentPose(),
            iterations = iterations,
            finalOffsetXUm = finalX,
            finalOffsetYUm = finalY,
            finalAngleErrorDeg = finalAngle,
            message = "Visual pre-alignment did not converge within ${request.maximumIterations} iterations"
        )
    }
}

class ProbeHeightTrainer(
    private val positioner: OpticalPositionerPort,
    private val sensor: ZDisplacementSensorPort,
    private val repository: InspectionCalibrationRepository,
    private val nowEpochMs: () -> Long
) {
    suspend fun train(request: ProbeHeightTrainingRequest): ProbeHeightProfile {
        val startPose = positioner.currentPose()
        var sampleCount = 0
        var travelled = 0.0
        return try {
            val baseline = averageGap(request).also { sampleCount += request.samplesPerStep }
            var gap = baseline

            while (gap > request.approachGapUm) {
                currentCoroutineContext().ensureActive()
                val step = minOf(request.searchStepUm, gap - request.approachGapUm)
                moveApproach(request.approachDirectionSign, step)
                travelled += step
                require(travelled <= request.maximumTravelUm) {
                    "Probe height search exceeded ${request.maximumTravelUm} um"
                }
                gap = averageGap(request)
                sampleCount += request.samplesPerStep
            }

            while (gap > request.contactGapUm) {
                currentCoroutineContext().ensureActive()
                val step = minOf(request.fineStepUm, gap - request.contactGapUm)
                moveApproach(request.approachDirectionSign, step)
                travelled += step
                require(travelled <= request.maximumTravelUm) {
                    "Probe height fine search exceeded ${request.maximumTravelUm} um"
                }
                gap = averageGap(request)
                sampleCount += request.samplesPerStep
            }

            val contactPose = positioner.currentPose()
            val approachPose = contactPose.copy(
                zUm = contactPose.zUm - request.approachDirectionSign *
                    (request.approachGapUm - request.contactGapUm)
            )
            val surfaceZ = contactPose.zUm + request.approachDirectionSign * request.contactGapUm

            // The verified system safe pose owns the Z direction and clearance convention.
            // Never infer a safe direction from the training approach sign.
            positioner.moveToSafePose()
            val safePose = positioner.currentPose()
            val profile = ProbeHeightProfile(
                id = request.id,
                site = request.site,
                contactPose = contactPose,
                approachPose = approachPose,
                safePose = safePose,
                contactGapUm = request.contactGapUm,
                measuredSurfaceZUm = surfaceZ,
                sensorBaselineUm = baseline,
                trainedAtEpochMs = nowEpochMs(),
                verified = true,
                sampleCount = sampleCount,
                message = "Probe height trained with displacement feedback and returned to verified safe pose"
            )
            repository.saveHeightProfile(profile)
            profile
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { positioner.stop() }
                runCatching { positioner.moveToSafePose() }
                    .recoverCatching { positioner.moveTo(startPose, wait = true) }
            }
            throw error
        }
    }

    private suspend fun moveApproach(directionSign: Int, distanceUm: Double) {
        positioner.moveBy(
            OpticalDelta(dzUm = directionSign * distanceUm),
            wait = true
        )
    }

    private suspend fun averageGap(request: ProbeHeightTrainingRequest): Double {
        val samples = List(request.samplesPerStep) {
            sensor.sample().also { sample ->
                check(sample.valid && !sample.saturated) {
                    sample.message ?: "Invalid Z displacement sample"
                }
                check(sample.confidence >= request.minimumSensorConfidence) {
                    "Z sensor confidence ${sample.confidence} is below ${request.minimumSensorConfidence}"
                }
            }
        }
        return samples.map { abs(it.displacementUm) }.average()
    }
}

class PivotCalibrationService(
    private val camera: CameraAcquisitionPort,
    private val detector: VisionFeatureDetector,
    private val positioner: OpticalPositionerPort,
    private val repository: InspectionCalibrationRepository,
    private val nowEpochMs: () -> Long
) {
    suspend fun calibrate(request: PivotCalibrationRequest): PivotCalibrationResult {
        val calibration = repository.findCameraCalibration(request.cameraCalibrationId)
            ?: error("Camera calibration not found: ${request.cameraCalibrationId}")
        require(calibration.verified) { "Camera calibration is not verified" }
        require(request.featureKind in detector.supportedKinds)
        val basePose = positioner.currentPose()
        val baseline = detectCenter(request.featureKind)
        val samples = mutableListOf<PivotCalibrationSample>()

        try {
            listOf("U", "V").forEach { axis ->
                listOf(-1.0, 1.0).forEach { direction ->
                    for (multiplier in 1..request.samplesPerDirection) {
                        currentCoroutineContext().ensureActive()
                        val angle = direction * request.angularStepDeg * multiplier
                        val targetPose = when (axis) {
                            "U" -> basePose.copy(uDeg = basePose.uDeg + angle)
                            else -> basePose.copy(vDeg = basePose.vDeg + angle)
                        }
                        if (request.executeMotion) positioner.moveTo(targetPose, wait = true)
                        val center = detectCenter(request.featureKind)
                        val (shiftX, shiftY) = calibration.pixelDeltaToStage(
                            deltaX = center.x - baseline.x,
                            deltaY = center.y - baseline.y
                        )
                        samples += PivotCalibrationSample(
                            axis = axis,
                            commandedAngleDeg = angle,
                            featureCenterPx = center,
                            observedShiftXUm = shiftX,
                            observedShiftYUm = shiftY,
                            pose = positioner.currentPose(),
                            timestampEpochMs = nowEpochMs()
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { positioner.moveTo(basePose, wait = true) }
            }
        }

        val observations = samples.mapNotNull { sample ->
            val sine = sin(sample.commandedAngleDeg * PI / 180.0)
            if (abs(sine) <= 1e-9) {
                null
            } else {
                PivotObservation(
                    sine = sine,
                    displacementUm = when (sample.axis) {
                        "U" -> -sample.observedShiftYUm
                        else -> sample.observedShiftXUm
                    }
                )
            }
        }
        require(observations.isNotEmpty()) { "Pivot calibration produced no valid samples" }

        // Fit displacement = distance * sin(angle) through the origin. This avoids
        // amplifying sub-pixel quantization by dividing every small-angle sample separately.
        val denominator = observations.sumOf { it.sine * it.sine }
        require(denominator > 1e-12) { "Pivot calibration angular span is too small" }
        val meanDistance = observations.sumOf { it.sine * it.displacementUm } / denominator
        val rmsResidual = sqrt(
            observations.sumOf {
                val residual = it.displacementUm - meanDistance * it.sine
                residual * residual
            } / observations.size
        )
        val verified = rmsResidual <= request.maximumResidualUm
        val result = PivotCalibrationResult(
            id = request.id,
            pivot = VirtualPivotPoint(
                xUm = basePose.xUm,
                yUm = basePose.yUm,
                zUm = basePose.zUm - meanDistance,
                frame = OpticalCoordinateFrame.Positioner,
                enabled = verified,
                name = "Auto Pivot ${request.id}"
            ),
            rmsResidualUm = rmsResidual,
            samples = samples,
            calibratedAtEpochMs = nowEpochMs(),
            verified = verified,
            message = if (verified) {
                "Pivot calibration verified by least-squares image-displacement fit"
            } else {
                "Pivot image residual $rmsResidual um exceeds ${request.maximumResidualUm} um"
            }
        )
        repository.savePivotCalibration(result)
        return result
    }

    private suspend fun detectCenter(kind: VisionFeatureKind): VisionPointPx {
        val frame = camera.capture()
        val detection = detector.detect(
            frame,
            VisionFeatureRequest(kind = kind, minimumConfidence = 0.65)
        )
        check(detection.found) { detection.message }
        return requireNotNull(detection.centerPx)
    }

    private data class PivotObservation(
        val sine: Double,
        val displacementUm: Double
    )
}

data class InspectionCalibrationRunRequest(
    val runId: String,
    val site: MeasurementSiteKey,
    val targetKind: VisionFeatureKind,
    val cameraCalibrationId: String,
    val policy: TemperatureRecalibrationPolicy,
    val temperatureStability: TemperatureStabilityPolicy = TemperatureStabilityPolicy(),
    val approachDirectionSign: Int,
    val manageConnections: Boolean = true
) {
    init {
        require(runId.isNotBlank())
        require(targetKind != VisionFeatureKind.FiberTip)
        require(cameraCalibrationId.isNotBlank())
        require(approachDirectionSign == -1 || approachDirectionSign == 1)
    }
}

interface InspectionCalibrationRunner {
    val state: StateFlow<InspectionCalibrationState>
    suspend fun run(request: InspectionCalibrationRunRequest): TemperatureRecalibrationResult
    suspend fun requestStop()
}

class DefaultInspectionCalibrationRunner(
    private val camera: CameraAcquisitionPort,
    private val zSensor: ZDisplacementSensorPort,
    private val temperatureController: TemperatureControllerPort,
    private val positioner: OpticalPositionerPort,
    private val preAlignment: VisualPreAlignmentService,
    private val heightTrainer: ProbeHeightTrainer,
    private val pivotCalibration: PivotCalibrationService,
    private val repository: InspectionCalibrationRepository,
    private val nowEpochMs: () -> Long
) : InspectionCalibrationRunner {
    private val runMutex = Mutex()
    private val mutableState = MutableStateFlow(InspectionCalibrationState())
    override val state: StateFlow<InspectionCalibrationState> = mutableState.asStateFlow()
    private val stopRequested = MutableStateFlow(false)

    override suspend fun run(
        request: InspectionCalibrationRunRequest
    ): TemperatureRecalibrationResult = runMutex.withLock {
        stopRequested.value = false
        val startedAt = nowEpochMs()
        var result = TemperatureRecalibrationResult(
            runId = request.runId,
            site = request.site,
            policy = request.policy,
            points = emptyList(),
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = startedAt,
            completed = false
        )
        mutableState.value = InspectionCalibrationState(
            runId = request.runId,
            stage = InspectionCalibrationStage.ConnectDevices,
            running = true,
            totalTemperatureCount = request.policy.temperaturesC.size,
            message = "Phase-three inspection calibration started"
        )
        repository.saveTemperatureRecalibration(result)

        try {
            if (request.manageConnections) connectDevices()
            requireUsable(camera.descriptor)
            requireUsable(zSensor.descriptor)
            requireUsable(temperatureController.descriptor)

            request.policy.temperaturesC.forEachIndexed { index, temperatureC ->
                ensureRunning()
                setStage(
                    InspectionCalibrationStage.StabilizeTemperature,
                    "Stabilizing inspection system at $temperatureC C",
                    temperatureC
                )
                temperatureController.setSetpointC(temperatureC)
                temperatureController.startControl()
                val stability = temperatureController.waitUntilStable(request.temperatureStability)
                check(stability.stable) { stability.message }
                check(!stability.finalSnapshot.alarmActive) {
                    "Temperature controller alarm is active"
                }

                val pointStartedAt = nowEpochMs()
                setStage(
                    InspectionCalibrationStage.VisualPreAlignment,
                    "Running visual pre-alignment at $temperatureC C",
                    temperatureC
                )
                val alignment = preAlignment.align(
                    VisualPreAlignmentRequest(
                        targetKind = request.targetKind,
                        cameraCalibrationId = request.cameraCalibrationId,
                        maximumCorrectionUm = request.policy.maximumPreAlignmentOffsetUm
                    )
                )
                check(alignment.aligned) { alignment.message }

                val height = if (request.policy.runProbeHeightTraining) {
                    setStage(
                        InspectionCalibrationStage.ProbeHeightTraining,
                        "Training probe height at $temperatureC C",
                        temperatureC
                    )
                    heightTrainer.train(
                        ProbeHeightTrainingRequest(
                            id = "height-${request.runId}-$index",
                            site = request.site,
                            approachDirectionSign = request.approachDirectionSign
                        )
                    )
                } else {
                    null
                }

                val alignmentOffset = hypot(alignment.finalOffsetXUm, alignment.finalOffsetYUm)
                val shouldCalibratePivot =
                    (index == 0 && request.policy.runPivotCalibrationAtFirstTemperature) ||
                        alignmentOffset >= request.policy.rerunPivotWhenOffsetExceedsUm
                val pivot = if (shouldCalibratePivot) {
                    setStage(
                        InspectionCalibrationStage.PivotCalibration,
                        "Calibrating virtual pivot at $temperatureC C",
                        temperatureC
                    )
                    pivotCalibration.calibrate(
                        PivotCalibrationRequest(
                            id = "pivot-${request.runId}-$index",
                            cameraCalibrationId = request.cameraCalibrationId,
                            featureKind = request.targetKind
                        )
                    ).also { check(it.verified) { it.message } }
                } else {
                    null
                }

                val point = TemperatureCalibrationPoint(
                    temperatureC = temperatureC,
                    preAlignment = alignment,
                    heightProfile = height,
                    pivotCalibration = pivot,
                    startedAtEpochMs = pointStartedAt,
                    finishedAtEpochMs = nowEpochMs(),
                    passed = true
                )
                result = result.copy(
                    points = result.points + point,
                    finishedAtEpochMs = nowEpochMs()
                )
                setStage(
                    InspectionCalibrationStage.PersistResult,
                    "Persisting calibration at $temperatureC C",
                    temperatureC
                )
                repository.saveTemperatureRecalibration(result)
                mutableState.update {
                    it.copy(completedTemperatureCount = index + 1)
                }
            }

            setStage(
                InspectionCalibrationStage.ReturnSafeState,
                "Returning inspection hardware to safe state"
            )
            safeShutdown()
            result = result.copy(
                completed = true,
                finishedAtEpochMs = nowEpochMs()
            )
            repository.saveTemperatureRecalibration(result)
            mutableState.update {
                it.copy(
                    stage = InspectionCalibrationStage.Completed,
                    running = false,
                    message = "Phase-three inspection calibration completed"
                )
            }
            result
        } catch (cancelled: CancellationException) {
            safeShutdown()
            result = result.copy(
                completed = false,
                finishedAtEpochMs = nowEpochMs(),
                failureMessage = cancelled.message
            )
            repository.saveTemperatureRecalibration(result)
            mutableState.update {
                it.copy(
                    stage = InspectionCalibrationStage.Stopped,
                    running = false,
                    stopRequested = true,
                    message = cancelled.message ?: "Inspection calibration stopped"
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            safeShutdown()
            result = result.copy(
                completed = false,
                finishedAtEpochMs = nowEpochMs(),
                failureMessage = error.message ?: error::class.simpleName
            )
            repository.saveTemperatureRecalibration(result)
            mutableState.update {
                it.copy(
                    stage = InspectionCalibrationStage.Failed,
                    running = false,
                    errorMessage = error.message ?: error::class.simpleName,
                    message = "Inspection calibration failed"
                )
            }
            throw error
        } finally {
            if (request.manageConnections) disconnectDevices()
        }
    }

    override suspend fun requestStop() {
        stopRequested.value = true
        mutableState.update {
            it.copy(stopRequested = true, message = "Inspection stop requested")
        }
        safeShutdown()
    }

    private suspend fun connectDevices() {
        setStage(
            InspectionCalibrationStage.ConnectDevices,
            "Connecting camera, Z sensor and temperature controller"
        )
        positioner.connect()
        positioner.startup(reference = false)
        camera.connect()
        zSensor.connect()
        temperatureController.connect()
    }

    private suspend fun disconnectDevices() {
        withContext(NonCancellable) {
            runCatching { camera.disconnect() }
            runCatching { zSensor.disconnect() }
            runCatching { temperatureController.disconnect() }
            runCatching { positioner.disconnect() }
        }
    }

    private suspend fun safeShutdown() {
        withContext(NonCancellable) {
            runCatching { positioner.stop() }
            runCatching { positioner.moveToSafePose() }
            runCatching { temperatureController.stopControl() }
            runCatching { camera.stopStreaming() }
        }
    }

    private suspend fun setStage(
        stage: InspectionCalibrationStage,
        message: String,
        temperatureC: Double? = mutableState.value.currentTemperatureC
    ) {
        ensureRunning()
        mutableState.update {
            it.copy(stage = stage, message = message, currentTemperatureC = temperatureC)
        }
    }

    private fun ensureRunning() {
        if (stopRequested.value) {
            throw CancellationException("Inspection calibration stop requested")
        }
    }

    private fun requireUsable(descriptor: DeviceDescriptor) {
        if (
            descriptor.backendMode == DeviceBackendMode.Real &&
            descriptor.verificationState != DeviceVerificationState.HardwareVerified
        ) {
            error("${descriptor.model} is not hardware-verified")
        }
    }
}

private fun CameraStageCalibration.pixelDeltaToStage(
    deltaX: Double,
    deltaY: Double
): Pair<Double, Double> {
    val localX = deltaX * micrometersPerPixelX
    val localY = deltaY * micrometersPerPixelY
    val radians = cameraToStageRotationDeg * PI / 180.0
    val cosine = cos(radians)
    val sine = sin(radians)
    return Pair(
        localX * cosine - localY * sine,
        localX * sine + localY * cosine
    )
}
