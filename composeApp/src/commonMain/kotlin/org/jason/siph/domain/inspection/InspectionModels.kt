package org.jason.siph.domain.inspection

import kotlinx.serialization.Serializable
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint

@Serializable
enum class CameraPixelFormat {
    Gray8
}

/** Runtime camera frame. Raw pixels are never embedded in calibration records. */
data class CameraFrame(
    val frameId: String,
    val capturedAtEpochMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val strideBytes: Int,
    val pixelFormat: CameraPixelFormat,
    val pixels: ByteArray,
    val exposureUs: Double,
    val gainDb: Double,
    val sourceDescription: String
) {
    init {
        require(frameId.isNotBlank())
        require(widthPx > 0 && heightPx > 0)
        require(strideBytes >= widthPx)
        require(pixelFormat == CameraPixelFormat.Gray8)
        require(pixels.size >= strideBytes * heightPx)
        require(exposureUs.isFinite() && exposureUs > 0.0)
        require(gainDb.isFinite())
        require(sourceDescription.isNotBlank())
    }

    fun grayAt(x: Int, y: Int): Int {
        require(x in 0 until widthPx && y in 0 until heightPx)
        return pixels[y * strideBytes + x].toInt() and 0xff
    }
}

@Serializable
data class VisionPointPx(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite())
    }
}

@Serializable
data class VisionRectPx(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(left >= 0 && top >= 0)
        require(width > 0 && height > 0)
    }

    val rightExclusive: Int get() = left + width
    val bottomExclusive: Int get() = top + height
}

@Serializable
enum class VisionFeatureKind {
    FiberTip,
    Grating,
    Facet
}

@Serializable
data class VisionFeatureRequest(
    val kind: VisionFeatureKind,
    val regionOfInterest: VisionRectPx? = null,
    val minimumConfidence: Double = 0.65
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
    }
}

@Serializable
data class VisionFeatureDetection(
    val kind: VisionFeatureKind,
    val found: Boolean,
    val confidence: Double,
    val centerPx: VisionPointPx? = null,
    val angleDeg: Double? = null,
    val widthPx: Double? = null,
    val heightPx: Double? = null,
    val scoreDetails: Map<String, Double> = emptyMap(),
    val message: String
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(angleDeg == null || angleDeg.isFinite())
        require(widthPx == null || widthPx.isFinite())
        require(heightPx == null || heightPx.isFinite())
        require(scoreDetails.values.all(Double::isFinite))
        require(message.isNotBlank())
        require(found == (centerPx != null)) {
            "A found feature must provide centerPx and a missing feature must not provide centerPx"
        }
    }
}

@Serializable
data class CameraStageCalibration(
    val id: String,
    val cameraId: String,
    val opticalCenterPx: VisionPointPx,
    val micrometersPerPixelX: Double,
    val micrometersPerPixelY: Double,
    val cameraToStageRotationDeg: Double = 0.0,
    val calibratedAtEpochMs: Long,
    val verified: Boolean = false,
    val rmsErrorUm: Double? = null
) {
    init {
        require(id.isNotBlank() && cameraId.isNotBlank())
        require(micrometersPerPixelX.isFinite() && micrometersPerPixelX > 0.0)
        require(micrometersPerPixelY.isFinite() && micrometersPerPixelY > 0.0)
        require(cameraToStageRotationDeg.isFinite())
        require(rmsErrorUm == null || (rmsErrorUm.isFinite() && rmsErrorUm >= 0.0))
    }
}

@Serializable
data class VisualPreAlignmentRequest(
    val targetKind: VisionFeatureKind,
    val cameraCalibrationId: String,
    val targetPixel: VisionPointPx? = null,
    val regionOfInterest: VisionRectPx? = null,
    val maximumIterations: Int = 3,
    val maximumCorrectionUm: Double = 250.0,
    val maximumRotationCorrectionDeg: Double = 1.0,
    val minimumConfidence: Double = 0.7,
    val executeMotion: Boolean = true
) {
    init {
        require(targetKind != VisionFeatureKind.FiberTip) {
            "Pre-alignment target must be a grating or facet"
        }
        require(cameraCalibrationId.isNotBlank())
        require(maximumIterations in 1..20)
        require(maximumCorrectionUm.isFinite() && maximumCorrectionUm > 0.0)
        require(maximumRotationCorrectionDeg.isFinite() && maximumRotationCorrectionDeg >= 0.0)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
    }
}

@Serializable
data class VisualPreAlignmentIteration(
    val index: Int,
    val fiberTip: VisionFeatureDetection,
    val target: VisionFeatureDetection,
    val correctionXUm: Double,
    val correctionYUm: Double,
    val correctionWDeg: Double,
    val poseBefore: OpticalPose,
    val poseAfter: OpticalPose,
    val capturedAtEpochMs: Long
) {
    init {
        require(index >= 0)
        require(correctionXUm.isFinite() && correctionYUm.isFinite() && correctionWDeg.isFinite())
    }
}

@Serializable
data class VisualPreAlignmentResult(
    val aligned: Boolean,
    val finalPose: OpticalPose,
    val iterations: List<VisualPreAlignmentIteration>,
    val finalOffsetXUm: Double,
    val finalOffsetYUm: Double,
    val finalAngleErrorDeg: Double,
    val message: String
) {
    init {
        require(finalOffsetXUm.isFinite() && finalOffsetYUm.isFinite())
        require(finalAngleErrorDeg.isFinite())
        require(message.isNotBlank())
    }
}

@Serializable
data class ZDisplacementSample(
    val timestampEpochMs: Long,
    val displacementUm: Double,
    val confidence: Double,
    val valid: Boolean,
    val saturated: Boolean = false,
    val message: String? = null
) {
    init {
        require(displacementUm.isFinite())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

@Serializable
data class ProbeHeightTrainingRequest(
    val id: String,
    val site: MeasurementSiteKey,
    val approachDirectionSign: Int,
    val searchStepUm: Double = 2.0,
    val fineStepUm: Double = 0.25,
    val maximumTravelUm: Double = 200.0,
    val contactGapUm: Double = 8.0,
    val approachGapUm: Double = 25.0,
    val safeClearanceUm: Double = 150.0,
    val samplesPerStep: Int = 3,
    val minimumSensorConfidence: Double = 0.75
) {
    init {
        require(id.isNotBlank())
        require(approachDirectionSign == -1 || approachDirectionSign == 1)
        require(searchStepUm.isFinite() && searchStepUm > 0.0)
        require(fineStepUm.isFinite() && fineStepUm > 0.0 && fineStepUm <= searchStepUm)
        require(maximumTravelUm.isFinite() && maximumTravelUm > 0.0)
        require(contactGapUm.isFinite() && contactGapUm >= 0.0)
        require(approachGapUm.isFinite() && approachGapUm > contactGapUm)
        require(safeClearanceUm.isFinite() && safeClearanceUm > approachGapUm)
        require(samplesPerStep in 1..100)
        require(minimumSensorConfidence.isFinite() && minimumSensorConfidence in 0.0..1.0)
    }
}

@Serializable
data class ProbeHeightProfile(
    val id: String,
    val site: MeasurementSiteKey,
    val contactPose: OpticalPose,
    val approachPose: OpticalPose,
    val safePose: OpticalPose,
    val contactGapUm: Double,
    val measuredSurfaceZUm: Double,
    val sensorBaselineUm: Double,
    val trainedAtEpochMs: Long,
    val verified: Boolean,
    val sampleCount: Int,
    val message: String
) {
    init {
        require(id.isNotBlank())
        require(contactGapUm.isFinite() && measuredSurfaceZUm.isFinite() && sensorBaselineUm.isFinite())
        require(sampleCount > 0)
        require(message.isNotBlank())
    }
}

@Serializable
data class PivotCalibrationRequest(
    val id: String,
    val cameraCalibrationId: String,
    val featureKind: VisionFeatureKind = VisionFeatureKind.FiberTip,
    val angularStepDeg: Double = 0.08,
    val samplesPerDirection: Int = 2,
    val maximumResidualUm: Double = 1.5,
    val executeMotion: Boolean = true
) {
    init {
        require(id.isNotBlank() && cameraCalibrationId.isNotBlank())
        require(angularStepDeg.isFinite() && angularStepDeg > 0.0 && angularStepDeg <= 2.0)
        require(samplesPerDirection in 1..20)
        require(maximumResidualUm.isFinite() && maximumResidualUm > 0.0)
    }
}

@Serializable
data class PivotCalibrationSample(
    val axis: String,
    val commandedAngleDeg: Double,
    val featureCenterPx: VisionPointPx,
    val observedShiftXUm: Double,
    val observedShiftYUm: Double,
    val pose: OpticalPose,
    val timestampEpochMs: Long
) {
    init {
        require(axis == "U" || axis == "V")
        require(commandedAngleDeg.isFinite())
        require(observedShiftXUm.isFinite() && observedShiftYUm.isFinite())
    }
}

@Serializable
data class PivotCalibrationResult(
    val id: String,
    val pivot: VirtualPivotPoint,
    val rmsResidualUm: Double,
    val samples: List<PivotCalibrationSample>,
    val calibratedAtEpochMs: Long,
    val verified: Boolean,
    val message: String
) {
    init {
        require(id.isNotBlank())
        require(rmsResidualUm.isFinite() && rmsResidualUm >= 0.0)
        require(samples.isNotEmpty())
        require(message.isNotBlank())
    }
}

@Serializable
data class TemperatureRecalibrationPolicy(
    val temperaturesC: List<Double>,
    val runProbeHeightTraining: Boolean = true,
    val runPivotCalibrationAtFirstTemperature: Boolean = true,
    val rerunPivotWhenOffsetExceedsUm: Double = 1.5,
    val maximumPreAlignmentOffsetUm: Double = 100.0,
    val stopOnFailure: Boolean = true
) {
    init {
        require(temperaturesC.isNotEmpty())
        require(temperaturesC.all(Double::isFinite))
        require(rerunPivotWhenOffsetExceedsUm.isFinite() && rerunPivotWhenOffsetExceedsUm >= 0.0)
        require(maximumPreAlignmentOffsetUm.isFinite() && maximumPreAlignmentOffsetUm > 0.0)
    }
}

@Serializable
data class TemperatureCalibrationPoint(
    val temperatureC: Double,
    val preAlignment: VisualPreAlignmentResult,
    val heightProfile: ProbeHeightProfile?,
    val pivotCalibration: PivotCalibrationResult?,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val passed: Boolean,
    val messages: List<String> = emptyList()
)

@Serializable
data class TemperatureRecalibrationResult(
    val runId: String,
    val site: MeasurementSiteKey,
    val policy: TemperatureRecalibrationPolicy,
    val points: List<TemperatureCalibrationPoint>,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val completed: Boolean,
    val failureMessage: String? = null
) {
    init {
        require(runId.isNotBlank())
    }
}

@Serializable
enum class InspectionCalibrationStage {
    Idle,
    ConnectDevices,
    CaptureReference,
    DetectFiberTip,
    DetectTarget,
    VisualPreAlignment,
    ProbeHeightTraining,
    PivotCalibration,
    StabilizeTemperature,
    PersistResult,
    ReturnSafeState,
    Completed,
    Stopped,
    Failed
}

@Serializable
data class InspectionCalibrationState(
    val runId: String? = null,
    val stage: InspectionCalibrationStage = InspectionCalibrationStage.Idle,
    val running: Boolean = false,
    val stopRequested: Boolean = false,
    val currentTemperatureC: Double? = null,
    val completedTemperatureCount: Int = 0,
    val totalTemperatureCount: Int = 0,
    val message: String = "Inspection calibration is idle",
    val errorMessage: String? = null
)
