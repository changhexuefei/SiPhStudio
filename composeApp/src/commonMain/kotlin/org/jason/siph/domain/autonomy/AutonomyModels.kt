package org.jason.siph.domain.autonomy

import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint

/** 可接入能力的统一连接状态。 */
enum class AutonomyCapabilityState {
    NotConfigured,
    Disconnected,
    Connecting,
    Ready,
    Busy,
    Error
}

/** 自主硅光子系统中一个外部能力的状态快照。 */
data class AutonomyCapabilityStatus(
    val state: AutonomyCapabilityState = AutonomyCapabilityState.NotConfigured,
    val identity: String? = null,
    val detail: String = "Adapter is not configured",
    val errorMessage: String? = null
) {
    val configured: Boolean
        get() = state != AutonomyCapabilityState.NotConfigured

    val connected: Boolean
        get() = state == AutonomyCapabilityState.Ready ||
            state == AutonomyCapabilityState.Busy

    val healthy: Boolean
        get() = state == AutonomyCapabilityState.Ready
}

enum class PhotonicCouplingGeometry {
    VerticalGrating,
    EdgeCoupling,
    FiberArray
}

data class VisionFrame(
    val frameId: String,
    val capturedAtEpochMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val sourceDescription: String
)

data class VisionTargetRequest(
    val geometry: PhotonicCouplingGeometry,
    val expectedFeature: String,
    val regionOfInterest: VisionRegion? = null
)

data class VisionRegion(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int
)

data class VisionAlignmentObservation(
    val frameId: String,
    val targetFound: Boolean,
    val confidence: Double,
    val offsetXUm: Double?,
    val offsetYUm: Double?,
    val angleDeg: Double?,
    val message: String
)

data class VisionCalibrationRequest(
    val profileName: String,
    val fixtureId: String,
    val geometry: PhotonicCouplingGeometry
)

data class VisionCalibrationResult(
    val calibrationId: String,
    val rmsErrorUm: Double,
    val verified: Boolean,
    val message: String
)

data class WaferSite(
    val dieColumn: Int,
    val dieRow: Int,
    val subDie: Int? = null,
    val label: String? = null
)

data class WaferMapDefinition(
    val mapId: String,
    val waferId: String,
    val sites: List<WaferSite>
)

data class WaferStageSnapshot(
    val loadedMapId: String? = null,
    val currentSite: WaferSite? = null,
    val stageXUm: Double? = null,
    val stageYUm: Double? = null,
    val contactState: String? = null
)

data class MeasurementPositionTrainingResult(
    val name: String,
    val site: WaferSite?,
    val stageXUm: Double,
    val stageYUm: Double,
    val probeHeightUm: Double?,
    val verified: Boolean
)

data class ProbeTrackingReference(
    val referenceId: String,
    val expectedGapUm: Double,
    val geometry: PhotonicCouplingGeometry
)

data class ProbeTrackingSample(
    val timestampEpochMs: Long,
    val gapUm: Double?,
    val lateralOffsetUm: Double?,
    val verticalOffsetUm: Double?,
    val confidence: Double,
    val tracking: Boolean,
    val message: String
)

/**
 * 与具体控制器、夹具和校准数据绑定的自主作业配置。
 * 未验证的配置不能被自动工作流视为运动许可。
 */
data class CalibrationProfile(
    val id: String,
    val name: String,
    val controllerIdentity: String? = null,
    val fixtureId: String,
    val geometry: PhotonicCouplingGeometry,
    val measurementPose: OpticalPose? = null,
    val virtualPivot: VirtualPivotPoint = VirtualPivotPoint.Disabled,
    val probeHeightUm: Double? = null,
    val visionCalibrationId: String? = null,
    val waferMapId: String? = null,
    val createdAtEpochMs: Long,
    val verifiedAtEpochMs: Long? = null,
    val verifiedBy: String? = null,
    val verified: Boolean = false
)

class AutonomyCapabilityUnavailableException(
    capability: String
) : IllegalStateException("$capability adapter is not configured")
