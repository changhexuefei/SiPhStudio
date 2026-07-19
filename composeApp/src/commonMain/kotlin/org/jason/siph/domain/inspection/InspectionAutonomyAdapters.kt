package org.jason.siph.domain.inspection

import kotlinx.coroutines.flow.StateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.ProbeTrackingPort
import org.jason.siph.domain.autonomy.ProbeTrackingReference
import org.jason.siph.domain.autonomy.ProbeTrackingSample
import org.jason.siph.domain.autonomy.VisionAlignmentObservation
import org.jason.siph.domain.autonomy.VisionAlignmentPort
import org.jason.siph.domain.autonomy.VisionCalibrationRequest
import org.jason.siph.domain.autonomy.VisionCalibrationResult
import org.jason.siph.domain.autonomy.VisionFrame
import org.jason.siph.domain.autonomy.VisionRegion
import org.jason.siph.domain.autonomy.VisionTargetRequest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class InspectionVisionAlignmentAdapter(
    private val camera: CameraAcquisitionPort,
    private val detector: VisionFeatureDetector,
    private val calibrations: InspectionCalibrationRepository
) : VisionAlignmentPort {
    override val status: StateFlow<AutonomyCapabilityStatus> = camera.status
    private var lastFrame: CameraFrame? = null

    override suspend fun connect() = camera.connect()

    override suspend fun disconnect() {
        lastFrame = null
        camera.disconnect()
    }

    override suspend fun captureFrame(): VisionFrame {
        val frame = camera.capture().also { lastFrame = it }
        return VisionFrame(
            frameId = frame.frameId,
            capturedAtEpochMs = frame.capturedAtEpochMs,
            widthPx = frame.widthPx,
            heightPx = frame.heightPx,
            sourceDescription = frame.sourceDescription
        )
    }

    override suspend fun locateTarget(
        request: VisionTargetRequest
    ): VisionAlignmentObservation {
        val frame = lastFrame ?: camera.capture().also { lastFrame = it }
        val calibration = calibrations.listCameraCalibrations().firstOrNull { it.verified }
            ?: error("No verified camera-stage calibration is available")
        val kind = request.geometry.toFeatureKind()
        val detection = detector.detect(
            frame,
            VisionFeatureRequest(
                kind = kind,
                regionOfInterest = request.regionOfInterest?.toInspectionRegion(),
                minimumConfidence = 0.65
            )
        )
        val center = detection.centerPx
        val offset = center?.let {
            calibration.pixelDeltaToStagePublic(
                deltaX = it.x - calibration.opticalCenterPx.x,
                deltaY = it.y - calibration.opticalCenterPx.y
            )
        }
        return VisionAlignmentObservation(
            frameId = frame.frameId,
            targetFound = detection.found,
            confidence = detection.confidence,
            offsetXUm = offset?.first,
            offsetYUm = offset?.second,
            angleDeg = detection.angleDeg,
            message = detection.message
        )
    }

    override suspend fun calibrate(
        request: VisionCalibrationRequest
    ): VisionCalibrationResult {
        val calibration = calibrations.listCameraCalibrations().firstOrNull { it.verified }
        return if (calibration == null) {
            VisionCalibrationResult(
                calibrationId = "unavailable-${request.profileName}",
                rmsErrorUm = Double.MAX_VALUE,
                verified = false,
                message = "No verified camera-stage calibration is available"
            )
        } else {
            VisionCalibrationResult(
                calibrationId = calibration.id,
                rmsErrorUm = calibration.rmsErrorUm ?: 0.0,
                verified = calibration.verified,
                message = "Using verified camera-stage calibration for ${request.fixtureId}"
            )
        }
    }
}

class ZSensorProbeTrackingAdapter(
    private val sensor: ZDisplacementSensorPort
) : ProbeTrackingPort {
    override val status: StateFlow<AutonomyCapabilityStatus> = sensor.status
    private var reference: ProbeTrackingReference? = null
    private var tracking = false

    override suspend fun connect() = sensor.connect()

    override suspend fun disconnect() {
        tracking = false
        reference = null
        sensor.disconnect()
    }

    override suspend fun startTracking(reference: ProbeTrackingReference) {
        if (!sensor.status.value.connected) sensor.connect()
        this.reference = reference
        tracking = true
    }

    override suspend fun stopTracking() {
        tracking = false
        reference = null
    }

    override suspend fun snapshot(): ProbeTrackingSample {
        val activeReference = requireNotNull(reference) { "Probe tracking has not been started" }
        val sample = sensor.sample()
        val gap = sample.displacementUm
        return ProbeTrackingSample(
            timestampEpochMs = sample.timestampEpochMs,
            gapUm = gap.takeIf { sample.valid },
            lateralOffsetUm = null,
            verticalOffsetUm = (gap - activeReference.expectedGapUm).takeIf { sample.valid },
            confidence = sample.confidence,
            tracking = tracking && sample.valid,
            message = sample.message ?: if (sample.valid) {
                "Z displacement tracking is active"
            } else {
                "Z displacement sample is invalid"
            }
        )
    }
}

private fun PhotonicCouplingGeometry.toFeatureKind(): VisionFeatureKind = when (this) {
    PhotonicCouplingGeometry.VerticalGrating -> VisionFeatureKind.Grating
    PhotonicCouplingGeometry.EdgeCoupling -> VisionFeatureKind.Facet
    PhotonicCouplingGeometry.FiberArray -> VisionFeatureKind.Grating
}

private fun VisionRegion.toInspectionRegion() = VisionRectPx(
    left = leftPx,
    top = topPx,
    width = widthPx,
    height = heightPx
)

private fun CameraStageCalibration.pixelDeltaToStagePublic(
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
