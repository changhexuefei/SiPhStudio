package org.jason.siph.domain.safety

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
        require(minimum <= maximum) {
            "minimum must be <= maximum, actual=$minimum..$maximum"
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

/**
 * 负责软限位检查和大范围转移路径规划。
 *
 * 小范围耦光扫描直接移动；超过阈值的横向或角度移动按以下顺序执行：
 * 当前点 -> 安全 Z -> 安全 Z 平面横移/转角 -> 目标 Z。
 */
class MotionSafetyPlanner(
    val config: MotionSafetyConfig
) {

    fun validate(pose: OpticalPose): List<MotionSafetyViolation> {
        if (!config.enabled) return emptyList()

        return buildList {
            addViolation("X", pose.xUm, config.xLimitUm)
            addViolation("Y", pose.yUm, config.yLimitUm)
            addViolation("Z", pose.zUm, config.zLimitUm)
            addViolation("U", pose.uDeg, config.uLimitDeg)
            addViolation("V", pose.vDeg, config.vLimitDeg)
            addViolation("W", pose.wDeg, config.wLimitDeg)
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
        if (!config.enabled || !config.protectedTransferEnabled) return false

        val linearDistanceUm = hypot(
            target.xUm - current.xUm,
            target.yUm - current.yUm
        )
        val maxAngleDeltaDeg = maxOf(
            abs(target.uDeg - current.uDeg),
            abs(target.vDeg - current.vDeg),
            abs(target.wDeg - current.wDeg)
        )

        return linearDistanceUm > config.protectedLinearThresholdUm ||
            maxAngleDeltaDeg > config.protectedAngleThresholdDeg
    }

    fun planMove(
        current: OpticalPose,
        target: OpticalPose
    ): List<OpticalPose> {
        requireValid(current)
        requireValid(target)

        if (!requiresProtectedTransfer(current, target)) {
            return listOf(target)
        }

        val clearanceAtCurrent = current.copy(zUm = config.clearanceZUm)
        val clearanceAtTarget = target.copy(zUm = config.clearanceZUm)

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
