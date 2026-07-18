package org.jason.siph.domain.autonomy

import kotlinx.coroutines.flow.StateFlow

/** 机器视觉、特征识别和视觉标定的厂商无关接口。 */
interface VisionAlignmentPort {
    val status: StateFlow<AutonomyCapabilityStatus>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun captureFrame(): VisionFrame

    suspend fun locateTarget(
        request: VisionTargetRequest
    ): VisionAlignmentObservation

    suspend fun calibrate(
        request: VisionCalibrationRequest
    ): VisionCalibrationResult
}
