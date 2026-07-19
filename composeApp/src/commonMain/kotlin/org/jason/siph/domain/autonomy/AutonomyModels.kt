package org.jason.siph.domain.autonomy

import kotlinx.serialization.Serializable
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.VirtualPivotPoint

/** 可接入能力的统一连接状态。 */
@Serializable
enum class AutonomyCapabilityState {
    NotConfigured,
    Disconnected,
    Connecting,
    Ready,
    Busy,
    Error
}

/** 自主硅光子系统中一个外部能力的状态快照。 */
@Serializable
data class AutonomyCapabilityStatus(
    val state: AutonomyCapabilityState = AutonomyCapabilityState.NotConfigured,
    val identity: String? = null,
    val detail: String = "Adapter is not configured",
    val errorMessage: String? = null
) {
    val configured: Boolean
        get() = state != AutonomyCapabilityState.NotConfigured

    val connected: Boolean
        get() = state == AutonomyCapabilityState.Ready || state == AutonomyCapabilityState.Busy

    val healthy: Boolean
        get() = state == AutonomyCapabilityState.Ready
}

@Serializable
enum class PhotonicCouplingGeometry {
    VerticalGrating,
    EdgeCoupling,
    FiberArray
}

@Serializable
data class VisionFrame(
    val frameId: String,
    val capturedAtEpochMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val sourceDescription: String
)

@Serializable
data class VisionTargetRequest(
    val geometry: PhotonicCouplingGeometry,
    val expectedFeature: String,
    val regionOfInterest: VisionRegion? = null
)

@Serializable
data class VisionRegion(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int
)

@Serializable
data class VisionAlignmentObservation(
    val frameId: String,
    val targetFound: Boolean,
    val confidence: Double,
    val offsetXUm: Double?,
    val offsetYUm: Double?,
    val angleDeg: Double?,
    val message: String
)

@Serializable
data class VisionCalibrationRequest(
    val profileName: String,
    val fixtureId: String,
    val geometry: PhotonicCouplingGeometry
)

@Serializable
data class VisionCalibrationResult(
    val calibrationId: String,
    val rmsErrorUm: Double,
    val verified: Boolean,
    val message: String
)

@Serializable
data class WaferSite(
    val dieColumn: Int,
    val dieRow: Int,
    val subDie: Int? = null,
    val label: String? = null
)

@Serializable
data class WaferMapDefinition(
    val mapId: String,
    val waferId: String,
    val sites: List<WaferSite>
)

@Serializable
data class WaferStageSnapshot(
    val loadedMapId: String? = null,
    val currentSite: WaferSite? = null,
    val stageXUm: Double? = null,
    val stageYUm: Double? = null,
    val contactState: String? = null
)

@Serializable
data class MeasurementPositionTrainingResult(
    val name: String,
    val site: WaferSite?,
    val stageXUm: Double,
    val stageYUm: Double,
    val probeHeightUm: Double?,
    val verified: Boolean
)

@Serializable
data class ProbeTrackingReference(
    val referenceId: String,
    val expectedGapUm: Double,
    val geometry: PhotonicCouplingGeometry
)

@Serializable
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
@Serializable
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
    val verified: Boolean = false,
    val schemaVersion: Int = 1
) {
    init {
        require(id.isNotBlank()) { "Calibration profile id must not be blank" }
        require(name.isNotBlank()) { "Calibration profile name must not be blank" }
        require(fixtureId.isNotBlank()) { "fixtureId must not be blank" }
        require(probeHeightUm == null || probeHeightUm.isFinite()) {
            "probeHeightUm must be finite when provided"
        }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }
}

class AutonomyCapabilityUnavailableException(
    capability: String
) : IllegalStateException("$capability adapter is not configured")
