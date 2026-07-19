package org.jason.siph.domain.inspection

import kotlinx.coroutines.flow.StateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.oo.DeviceDescriptor

interface CameraAcquisitionPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): CameraCapabilities
    suspend fun configure(config: CameraAcquisitionConfig)
    suspend fun capture(): CameraFrame
    suspend fun startStreaming()
    suspend fun stopStreaming()
}

data class CameraCapabilities(
    val maximumWidthPx: Int,
    val maximumHeightPx: Int,
    val minimumExposureUs: Double,
    val maximumExposureUs: Double,
    val supportsHardwareTrigger: Boolean,
    val supportsStreaming: Boolean
) {
    init {
        require(maximumWidthPx > 0 && maximumHeightPx > 0)
        require(minimumExposureUs.isFinite() && maximumExposureUs.isFinite())
        require(minimumExposureUs > 0.0 && maximumExposureUs >= minimumExposureUs)
    }
}

enum class CameraTriggerMode {
    Software,
    ExternalRising,
    ExternalFalling
}

data class CameraAcquisitionConfig(
    val widthPx: Int,
    val heightPx: Int,
    val exposureUs: Double,
    val gainDb: Double = 0.0,
    val triggerMode: CameraTriggerMode = CameraTriggerMode.Software
) {
    init {
        require(widthPx > 0 && heightPx > 0)
        require(exposureUs.isFinite() && exposureUs > 0.0)
        require(gainDb.isFinite())
    }
}

interface VisionFeatureDetector {
    val supportedKinds: Set<VisionFeatureKind>

    suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection
}

interface ZDisplacementSensorPort {
    val status: StateFlow<AutonomyCapabilityStatus>
    val descriptor: DeviceDescriptor

    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): ZDisplacementCapabilities
    suspend fun zero()
    suspend fun sample(): ZDisplacementSample
}

data class ZDisplacementCapabilities(
    val minimumDisplacementUm: Double,
    val maximumDisplacementUm: Double,
    val resolutionUm: Double,
    val maximumSampleRateHz: Double,
    val supportsHardwareZero: Boolean
) {
    init {
        require(minimumDisplacementUm.isFinite() && maximumDisplacementUm.isFinite())
        require(maximumDisplacementUm > minimumDisplacementUm)
        require(resolutionUm.isFinite() && resolutionUm > 0.0)
        require(maximumSampleRateHz.isFinite() && maximumSampleRateHz > 0.0)
    }
}

interface InspectionCalibrationRepository {
    suspend fun listCameraCalibrations(): List<CameraStageCalibration>
    suspend fun findCameraCalibration(id: String): CameraStageCalibration?
    suspend fun saveCameraCalibration(calibration: CameraStageCalibration)

    suspend fun listHeightProfiles(): List<ProbeHeightProfile>
    suspend fun findHeightProfile(id: String): ProbeHeightProfile?
    suspend fun saveHeightProfile(profile: ProbeHeightProfile)

    suspend fun listPivotCalibrations(): List<PivotCalibrationResult>
    suspend fun findPivotCalibration(id: String): PivotCalibrationResult?
    suspend fun savePivotCalibration(result: PivotCalibrationResult)

    suspend fun listTemperatureRecalibrations(limit: Int = 100): List<TemperatureRecalibrationResult>
    suspend fun findTemperatureRecalibration(runId: String): TemperatureRecalibrationResult?
    suspend fun saveTemperatureRecalibration(result: TemperatureRecalibrationResult)
}
