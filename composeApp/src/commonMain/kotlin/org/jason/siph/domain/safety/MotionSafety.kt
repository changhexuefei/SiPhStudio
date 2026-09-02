package org.jason.siph.domain.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jason.siph.domain.positioner.OpticalPose
import kotlin.math.abs
import kotlin.math.hypot

/** 单轴允许范围。 */
data class AxisSoftLimit(
    val minimum: Double,
    val maximum: Double
) {
    init {
        require(minimum.isFinite()) { "minimum must be finite" }
        require(maximum.isFinite()) { "maximum must be finite" }
        require(minimum < maximum) {
            "minimum must be < maximum, actual=$minimum..$maximum"
        }
    }

    operator fun contains(value: Double): Boolean =
        value.isFinite() && value >= minimum && value <= maximum
}

/**
 * 六轴光学定位平台的软件安全配置。
 *
 * [clearanceZUm] 是由设备安装和工艺定义的安全 Z 坐标，不假定 Z 正方向一定代表远离芯片。
 */
data class MotionSafetyConfig(
    val enabled: Boolean = true,
    val xLimitUm: AxisSoftLimit,
    val yLimitUm: AxisSoftLimit,
    val zLimitUm: AxisSoftLimit,
    val uLimitDeg: AxisSoftLimit,
    val vLimitDeg: AxisSoftLimit,
    val wLimitDeg: AxisSoftLimit,
    val protectedTransferEnabled: Boolean = true,
    val clearanceZUm: Double,
    val protectedLinearThresholdUm: Double = 15.0,
    val protectedAngleThresholdDeg: Double = 0.15
) {
    init {
        require(clearanceZUm.isFinite()) { "clearanceZUm must be finite" }
        require(clearanceZUm in zLimitUm) {
            "clearanceZUm=$clearanceZUm is outside Z soft limit $zLimitUm"
        }
        require(protectedLinearThresholdUm.isFinite() && protectedLinearThresholdUm >= 0.0) {
            "protectedLinearThresholdUm must be >= 0"
        }
        require(protectedAngleThresholdDeg.isFinite() && protectedAngleThresholdDeg >= 0.0) {
            "protectedAngleThresholdDeg must be >= 0"
        }
    }

    companion object {
        /** 仅用于离线 Demo。真实设备必须使用经过确认的机械范围。 */
        fun demoDefault(): MotionSafetyConfig = MotionSafetyConfig(
            xLimitUm = AxisSoftLimit(-100.0, 100.0),
            yLimitUm = AxisSoftLimit(-100.0, 100.0),
            zLimitUm = AxisSoftLimit(-50.0, 50.0),
            uLimitDeg = AxisSoftLimit(-5.0, 5.0),
            vLimitDeg = AxisSoftLimit(-5.0, 5.0),
            wLimitDeg = AxisSoftLimit(-5.0, 5.0),
            protectedTransferEnabled = true,
            clearanceZUm = 20.0,
            protectedLinearThresholdUm = 15.0,
            protectedAngleThresholdDeg = 0.15
        )
    }
}

data class MotionSafetyViolation(
    val axis: String,
    val value: Double,
    val limit: AxisSoftLimit
) {
    val message: String
        get() = "$axis=$value is outside ${limit.minimum}..${limit.maximum}"
}

class MotionSafetyException(
    val violations: List<MotionSafetyViolation>,
    message: String = violations.joinToString(
        prefix = "Motion rejected: ",
        separator = "; "
    ) { it.message }
) : IllegalArgumentException(message)

/** 在安全配置未加载或已被撤销时拒绝一切运动。 */
class MotionSafetyInterlockException(
    message: String = "Motion safety interlock is not ready"
) : IllegalStateException(message)

/**
 * 负责安全配置生命周期、软限位检查和大范围转移路径规划。
 *
 * 配置通过 [updateConfig] 原子替换。传入 null 会立即解除互锁就绪状态，之后所有运动规划都会失败。
 */
class MotionSafetyPlanner(
    initialConfig: MotionSafetyConfig?
) {
    private val _configuredConfig = MutableStateFlow(initialConfig)
    val configuredConfig: StateFlow<MotionSafetyConfig?> = _configuredConfig.asStateFlow()

    val isConfigured: Boolean
        get() = _configuredConfig.value != null

    val config: MotionSafetyConfig
        get() = requireConfigured()

    fun updateConfig(config: MotionSafetyConfig?) {
        _configuredConfig.value = config
    }

    fun requireConfigured(): MotionSafetyConfig {
        return _configuredConfig.value
            ?: throw MotionSafetyInterlockException(
                "Motion rejected: no validated soft limits and clearance Z are applied"
            )
    }

    fun validate(pose: OpticalPose): List<MotionSafetyViolation> {
        val currentConfig = requireConfigured()
        if (!currentConfig.enabled) return emptyList()

        return buildList {
            addViolation("X", pose.xUm, currentConfig.xLimitUm)
            addViolation("Y", pose.yUm, currentConfig.yLimitUm)
            addViolation("Z", pose.zUm, currentConfig.zLimitUm)
            addViolation("U", pose.uDeg, currentConfig.uLimitDeg)
            addViolation("V", pose.vDeg, currentConfig.vLimitDeg)
            addViolation("W", pose.wDeg, currentConfig.wLimitDeg)
        }
    }

    fun requireValid(pose: OpticalPose) {
        val violations = validate(pose)
        if (violations.isNotEmpty()) {
            throw MotionSafetyException(violations)
        }
    }

    fun requiresProtectedTransfer(
        current: OpticalPose,
        target: OpticalPose
    ): Boolean {
        val currentConfig = requireConfigured()
        if (!currentConfig.enabled || !currentConfig.protectedTransferEnabled) return false

        val linearDistanceUm = hypot(
            target.xUm - current.xUm,
            target.yUm - current.yUm
        )
        val maxAngleDeltaDeg = maxOf(
            abs(target.uDeg - current.uDeg),
            abs(target.vDeg - current.vDeg),
            abs(target.wDeg - current.wDeg)
        )

        return linearDistanceUm > currentConfig.protectedLinearThresholdUm ||
            maxAngleDeltaDeg > currentConfig.protectedAngleThresholdDeg
    }

    fun planMove(
        current: OpticalPose,
        target: OpticalPose
    ): List<OpticalPose> {
        val currentConfig = requireConfigured()
        requireValid(current)
        requireValid(target)

        if (!requiresProtectedTransfer(current, target)) {
            return listOf(target)
        }

        val clearanceAtCurrent = current.copy(zUm = currentConfig.clearanceZUm)
        val clearanceAtTarget = target.copy(zUm = currentConfig.clearanceZUm)

        return listOf(
            clearanceAtCurrent,
            clearanceAtTarget,
            target
        ).withoutConsecutiveDuplicates(current)
            .also { waypoints -> waypoints.forEach(::requireValid) }
    }

    private fun MutableList<MotionSafetyViolation>.addViolation(
        axis: String,
        value: Double,
        limit: AxisSoftLimit
    ) {
        if (value !in limit) {
            add(MotionSafetyViolation(axis, value, limit))
        }
    }
}

private fun List<OpticalPose>.withoutConsecutiveDuplicates(
    initial: OpticalPose
): List<OpticalPose> {
    val result = mutableListOf<OpticalPose>()
    var previous = initial

    for (pose in this) {
        if (pose != previous) {
            result += pose
            previous = pose
        }
    }

    return result
}
