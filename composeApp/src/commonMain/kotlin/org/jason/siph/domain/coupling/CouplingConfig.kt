package org.jason.siph.domain.coupling

import kotlinx.serialization.Serializable
import org.jason.siph.domain.positioner.VirtualPivotPoint

/** 耦光模式。 */
@Serializable
enum class CouplingMode {
    VerticalGratingCoupler,
    HorizontalDieLevelEdgeCoupling,
    WaferLevelEdgeCoupling
}

/** 螺旋搜索平面。 */
@Serializable
enum class CouplingSpiralPlane {
    XY,
    YZ,
    XZ
}

/**
 * 自动耦光配置。
 *
 * 单位：X/Y/Z 为 um，U/V/W 为 deg，光功率为 dBm。
 */
@Serializable
data class CouplingConfig(
    val mode: CouplingMode = CouplingMode.VerticalGratingCoupler,
    val wavelengthNm: Double = 1550.0,
    val powerMeterChannel: Int = 1,
    val spiralPlane: CouplingSpiralPlane = CouplingSpiralPlane.XY,
    val firstLightThresholdDbm: Double = -40.0,
    val targetPowerDbm: Double = -10.0,
    val spiralStepUm: Double = 2.0,
    val maxRadiusUm: Double = 50.0,
    val settleDelayMs: Long = 50L,
    val powerAverageCount: Int = 3,
    val powerAverageDelayMs: Long = 5L,
    val enableFineXyz: Boolean = true,
    val fineStepsUm: List<Double> = listOf(2.0, 1.0, 0.5, 0.2, 0.1),
    val minImproveDb: Double = 0.02,
    val maxFinePassesPerStep: Int = 12,
    val enableIncidentAngleOptimization: Boolean = false,
    val uStepDeg: Double = 0.02,
    val vStepDeg: Double = 0.02,
    val wStepDeg: Double = 0.01,
    val maxAngleRangeDeg: Double = 0.2,
    val virtualPivotPoint: VirtualPivotPoint = VirtualPivotPoint.Disabled,
    val enableSoftwarePivotCompensation: Boolean = false,
    val enableCollisionAvoidance: Boolean = true,
    val maxTotalSamples: Int = 2500,
    val stopWhenTargetReached: Boolean = true
) {
    init {
        require(wavelengthNm.isFinite() && wavelengthNm > 0.0) {
            "wavelengthNm 必须为正的有限数，当前值: $wavelengthNm"
        }
        require(powerMeterChannel > 0) {
            "powerMeterChannel 必须大于 0，当前值: $powerMeterChannel"
        }
        require(firstLightThresholdDbm.isFinite()) {
            "firstLightThresholdDbm 必须是有限数"
        }
        require(targetPowerDbm.isFinite()) {
            "targetPowerDbm 必须是有限数"
        }
        require(targetPowerDbm >= firstLightThresholdDbm) {
            "targetPowerDbm 不能低于 firstLightThresholdDbm"
        }
        require(spiralStepUm.isFinite() && spiralStepUm > 0.0) {
            "spiralStepUm 必须大于 0"
        }
        require(maxRadiusUm.isFinite() && maxRadiusUm > 0.0) {
            "maxRadiusUm 必须大于 0"
        }
        require(settleDelayMs >= 0L) {
            "settleDelayMs 不能小于 0"
        }
        require(powerAverageCount in 1..100) {
            "powerAverageCount 必须在 1..100，当前值: $powerAverageCount"
        }
        require(powerAverageDelayMs >= 0L) {
            "powerAverageDelayMs 不能小于 0"
        }
        require(fineStepsUm.isNotEmpty()) {
            "fineStepsUm 不能为空"
        }
        require(fineStepsUm.all { it.isFinite() && it > 0.0 }) {
            "fineStepsUm 必须全部为正的有限数: $fineStepsUm"
        }
        require(minImproveDb.isFinite() && minImproveDb >= 0.0) {
            "minImproveDb 不能小于 0"
        }
        require(maxFinePassesPerStep > 0) {
            "maxFinePassesPerStep 必须大于 0"
        }
        require(uStepDeg.isFinite() && uStepDeg > 0.0) {
            "uStepDeg 必须大于 0"
        }
        require(vStepDeg.isFinite() && vStepDeg > 0.0) {
            "vStepDeg 必须大于 0"
        }
        require(wStepDeg.isFinite() && wStepDeg > 0.0) {
            "wStepDeg 必须大于 0"
        }
        require(maxAngleRangeDeg.isFinite() && maxAngleRangeDeg >= 0.0) {
            "maxAngleRangeDeg 不能小于 0"
        }
        require(maxTotalSamples > 0) {
            "maxTotalSamples 必须大于 0"
        }
    }
}
