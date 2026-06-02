package org.jason.pi.gcs.pitools

import org.jason.pi.gcs.core.PiGcsException
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.hexapod.PiHexapodDelta
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiHexapodUnitConfig

/**
 * 单个 PI 轴的行程范围。
 *
 * 注意：
 * 这里的单位由调用者决定。
 *
 * 如果来自 TMN?/TMX?：
 * - 通常是 GCS 命令单位
 *
 * 如果经过 PiHexapodUnitConfig 转换：
 * - X/Y/Z 可以是业务层 μm
 * - U/V/W 可以是 deg
 */
data class PiAxisTravelRange(
    val axis: PiAxis,
    val min: Double,
    val max: Double
) {

    init {
        require(min.isFinite()) {
            "PI 轴 ${axis.code} 最小行程不是有效数值: min=$min"
        }

        require(max.isFinite()) {
            "PI 轴 ${axis.code} 最大行程不是有效数值: max=$max"
        }

        require(min <= max) {
            "PI 轴 ${axis.code} 行程范围错误: min=$min, max=$max"
        }
    }

    val span: Double
        get() = max - min

    val center: Double
        get() = (min + max) / 2.0

    fun contains(
        value: Double,
        tolerance: Double = 0.0
    ): Boolean {
        require(value.isFinite()) {
            "PI 轴 ${axis.code} 目标值不是有效数值: value=$value"
        }

        require(tolerance >= 0.0 && tolerance.isFinite()) {
            "PI 轴 ${axis.code} 行程容差必须是非负有效数值: tolerance=$tolerance"
        }

        return value >= min - tolerance && value <= max + tolerance
    }

    fun clamp(
        value: Double
    ): Double {
        require(value.isFinite()) {
            "PI 轴 ${axis.code} 目标值不是有效数值: value=$value"
        }

        return value.coerceIn(min, max)
    }

    fun outOfRange(
        value: Double,
        tolerance: Double = 0.0
    ): PiAxisOutOfRange? {
        return if (contains(value, tolerance)) {
            null
        } else {
            PiAxisOutOfRange(
                axis = axis,
                value = value,
                min = min,
                max = max
            )
        }
    }

    fun toClosedRange(): ClosedFloatingPointRange<Double> {
        return min..max
    }
}

/**
 * PI 多轴行程范围。
 */
data class PiTravelRange(
    val ranges: Map<PiAxis, PiAxisTravelRange>
) {

    init {
        ranges.forEach { (axis, range) ->
            require(axis == range.axis) {
                "PiTravelRange key 和 value.axis 不一致: key=${axis.code}, value=${range.axis.code}"
            }
        }
    }

    val axes: List<PiAxis>
        get() = ranges.keys.toList()

    fun hasRangeFor(
        axis: PiAxis
    ): Boolean {
        return axis in ranges
    }

    fun isCompleteFor(
        requiredAxes: List<PiAxis>
    ): Boolean {
        return missingAxes(requiredAxes).isEmpty()
    }

    fun missingAxes(
        requiredAxes: List<PiAxis>
    ): List<PiAxis> {
        return requiredAxes.filterNot { axis ->
            hasRangeFor(axis)
        }
    }

    fun requireRangesFor(
        requiredAxes: List<PiAxis>,
        label: String = "PI travel range"
    ) {
        val missingAxes = missingAxes(requiredAxes)

        if (missingAxes.isNotEmpty()) {
            throw PiTravelRangeMissingAxesException(
                label = label,
                missingAxes = missingAxes
            )
        }
    }

    fun rangeOf(
        axis: PiAxis
    ): PiAxisTravelRange? {
        return ranges[axis]
    }

    fun requireRangeOf(
        axis: PiAxis
    ): PiAxisTravelRange {
        return ranges[axis]
            ?: error("缺少 PI 轴 ${axis.code} 的行程范围")
    }

    fun contains(
        axis: PiAxis,
        value: Double,
        tolerance: Double = 0.0
    ): Boolean {
        val range = ranges[axis] ?: return true
        return range.contains(
            value = value,
            tolerance = tolerance
        )
    }

    fun contains(
        pose: PiHexapodPose,
        tolerance: Double = 0.0
    ): Boolean {
        return isWithinRange(
            values = pose.toPiAxisValueMap(),
            tolerance = tolerance
        )
    }

    fun clamp(
        axis: PiAxis,
        value: Double
    ): Double {
        val range = ranges[axis] ?: return value
        return range.clamp(value)
    }

    fun clampPose(
        pose: PiHexapodPose
    ): PiHexapodPose {
        return PiHexapodPose(
            xUm = clamp(PiAxis.X, pose.xUm),
            yUm = clamp(PiAxis.Y, pose.yUm),
            zUm = clamp(PiAxis.Z, pose.zUm),
            uDeg = clamp(PiAxis.U, pose.uDeg),
            vDeg = clamp(PiAxis.V, pose.vDeg),
            wDeg = clamp(PiAxis.W, pose.wDeg)
        )
    }

    fun targetPoseAfterMoveBy(
        currentPose: PiHexapodPose,
        delta: PiHexapodDelta
    ): PiHexapodPose {
        return currentPose.copy(
            xUm = currentPose.xUm + delta.dxUm,
            yUm = currentPose.yUm + delta.dyUm,
            zUm = currentPose.zUm + delta.dzUm,
            uDeg = currentPose.uDeg + delta.duDeg,
            vDeg = currentPose.vDeg + delta.dvDeg,
            wDeg = currentPose.wDeg + delta.dwDeg
        )
    }

    fun toClosedRangeMap(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return ranges.mapValues { (_, range) ->
            range.toClosedRange()
        }
    }

    /**
     * 检查一个轴值 Map 是否全部在行程范围内。
     *
     * 注意：
     * values 和 ranges 必须使用同一种单位。
     */
    fun isWithinRange(
        values: Map<PiAxis, Double>,
        tolerance: Double = 0.0
    ): Boolean {
        return values.all { (axis, value) ->
            contains(
                axis = axis,
                value = value,
                tolerance = tolerance
            )
        }
    }

    fun isWithinRange(
        pose: PiHexapodPose,
        tolerance: Double = 0.0
    ): Boolean {
        return isWithinRange(
            values = pose.toPiAxisValueMap(),
            tolerance = tolerance
        )
    }

    /**
     * 返回所有越界轴。
     */
    fun outOfRangeAxes(
        values: Map<PiAxis, Double>,
        tolerance: Double = 0.0
    ): List<PiAxisOutOfRange> {
        return values.mapNotNull { (axis, value) ->
            val range = ranges[axis] ?: return@mapNotNull null
            range.outOfRange(
                value = value,
                tolerance = tolerance
            )
        }
    }

    fun outOfRangeAxes(
        pose: PiHexapodPose,
        tolerance: Double = 0.0
    ): List<PiAxisOutOfRange> {
        return outOfRangeAxes(
            values = pose.toPiAxisValueMap(),
            tolerance = tolerance
        )
    }

    /**
     * 真实硬件移动前使用这个方法做强校验。
     *
     * 注意：
     * values 和 ranges 必须使用同一种单位。
     */
    fun requireWithinRange(
        values: Map<PiAxis, Double>,
        tolerance: Double = 0.0,
        label: String = "PI target"
    ) {
        val outOfRangeAxes = outOfRangeAxes(
            values = values,
            tolerance = tolerance
        )

        if (outOfRangeAxes.isNotEmpty()) {
            throw PiTravelRangeException(
                label = label,
                outOfRangeAxes = outOfRangeAxes
            )
        }
    }

    fun requireWithinRange(
        pose: PiHexapodPose,
        tolerance: Double = 0.0,
        label: String = "PI target pose"
    ) {
        requireWithinRange(
            values = pose.toPiAxisValueMap(),
            tolerance = tolerance,
            label = label
        )
    }

    /**
     * 严格校验：指定轴必须全部有行程范围，且目标值必须在范围内。
     *
     * 真实硬件移动前优先使用这个方法。
     */
    fun requireWithinRangeStrict(
        values: Map<PiAxis, Double>,
        requiredAxes: List<PiAxis>,
        tolerance: Double = 0.0,
        label: String = "PI target"
    ) {
        val result = checkWithinRangeStrict(
            values = values,
            requiredAxes = requiredAxes,
            tolerance = tolerance,
            label = label
        )

        if (result.passed) {
            return
        }

        result.throwException()
    }

    fun requireWithinRangeStrict(
        pose: PiHexapodPose,
        requiredAxes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        tolerance: Double = 0.0,
        label: String = "PI target pose"
    ) {
        requireWithinRangeStrict(
            values = pose.toPiAxisValueMap(),
            requiredAxes = requiredAxes,
            tolerance = tolerance,
            label = label
        )
    }

    /**
     * 非抛异常的严格预检。
     *
     * 用于 UI 提示、日志记录、移动按钮 enable/disable 判断。
     */
    fun checkWithinRangeStrict(
        values: Map<PiAxis, Double>,
        requiredAxes: List<PiAxis>,
        tolerance: Double = 0.0,
        label: String = "PI target"
    ): PiTravelRangeCheckResult {
        require(tolerance >= 0.0 && tolerance.isFinite()) {
            "$label 行程容差必须是非负有效数值: tolerance=$tolerance"
        }

        val missingRangeAxes = missingAxes(requiredAxes)
        val missingTargetAxes = requiredAxes.filterNot { axis ->
            axis in values
        }
        val invalidTargetValues = requiredAxes.mapNotNull { axis ->
            val value = values[axis] ?: return@mapNotNull null

            if (value.isFinite()) {
                null
            } else {
                PiAxisInvalidTargetValue(
                    axis = axis,
                    value = value
                )
            }
        }
        val outOfRangeAxes = requiredAxes.mapNotNull { axis ->
            val range = ranges[axis] ?: return@mapNotNull null
            val value = values[axis] ?: return@mapNotNull null

            if (!value.isFinite()) {
                return@mapNotNull null
            }

            range.outOfRange(
                value = value,
                tolerance = tolerance
            )
        }

        return PiTravelRangeCheckResult(
            label = label,
            missingRangeAxes = missingRangeAxes,
            missingTargetAxes = missingTargetAxes,
            invalidTargetValues = invalidTargetValues,
            outOfRangeAxes = outOfRangeAxes
        )
    }

    fun checkWithinRangeStrict(
        pose: PiHexapodPose,
        requiredAxes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        tolerance: Double = 0.0,
        label: String = "PI target pose"
    ): PiTravelRangeCheckResult {
        return checkWithinRangeStrict(
            values = pose.toPiAxisValueMap(),
            requiredAxes = requiredAxes,
            tolerance = tolerance,
            label = label
        )
    }

    fun checkMoveWithinRange(
        currentPose: PiHexapodPose,
        delta: PiHexapodDelta,
        requiredAxes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        tolerance: Double = 0.0,
        label: String = "PI relative move target"
    ): PiTravelRangeCheckResult {
        return checkWithinRangeStrict(
            pose = targetPoseAfterMoveBy(
                currentPose = currentPose,
                delta = delta
            ),
            requiredAxes = requiredAxes,
            tolerance = tolerance,
            label = label
        )
    }

    fun requireMoveWithinRange(
        currentPose: PiHexapodPose,
        delta: PiHexapodDelta,
        requiredAxes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
        tolerance: Double = 0.0,
        label: String = "PI relative move target"
    ): PiHexapodPose {
        val targetPose = targetPoseAfterMoveBy(
            currentPose = currentPose,
            delta = delta
        )

        requireWithinRangeStrict(
            pose = targetPose,
            requiredAxes = requiredAxes,
            tolerance = tolerance,
            label = label
        )

        return targetPose
    }

    companion object {

        val Empty: PiTravelRange = PiTravelRange(
            ranges = emptyMap()
        )

        fun of(
            vararg ranges: PiAxisTravelRange
        ): PiTravelRange {
            return PiTravelRange(
                ranges = ranges.associateBy { it.axis }
            )
        }

        fun fromMinMax(
            minValues: Map<PiAxis, Double>,
            maxValues: Map<PiAxis, Double>
        ): PiTravelRange {
            val axes = (minValues.keys + maxValues.keys).toSet()

            val ranges = axes.associateWith { axis ->
                val min = minValues[axis]
                    ?: error("缺少 PI 轴 ${axis.code} 的最小行程")
                val max = maxValues[axis]
                    ?: error("缺少 PI 轴 ${axis.code} 的最大行程")

                PiAxisTravelRange(
                    axis = axis,
                    min = min,
                    max = max
                )
            }

            return PiTravelRange(ranges)
        }

        fun fromClosedRangeMap(
            ranges: Map<PiAxis, ClosedFloatingPointRange<Double>>
        ): PiTravelRange {
            return PiTravelRange(
                ranges = ranges.mapValues { (axis, range) ->
                    PiAxisTravelRange(
                        axis = axis,
                        min = range.start,
                        max = range.endInclusive
                    )
                }
            )
        }

        fun fromCommandRangeMap(
            commandRanges: Map<PiAxis, ClosedFloatingPointRange<Double>>,
            unitConfig: PiHexapodUnitConfig
        ): PiTravelRange {
            return fromClosedRangeMap(
                ranges = unitConfig.fromCommandTravelRanges(commandRanges)
            )
        }
    }
}

/**
 * 越界信息。
 */
data class PiAxisOutOfRange(
    val axis: PiAxis,
    val value: Double,
    val min: Double,
    val max: Double
) {
    val message: String
        get() = "PI 轴 ${axis.code} 目标值越界: value=$value, range=[$min, $max]"
}

data class PiAxisInvalidTargetValue(
    val axis: PiAxis,
    val value: Double
) {
    val message: String
        get() = "PI 轴 ${axis.code} 目标值不是有效数值: value=$value"
}

data class PiTravelRangeCheckResult(
    val label: String,
    val missingRangeAxes: List<PiAxis> = emptyList(),
    val missingTargetAxes: List<PiAxis> = emptyList(),
    val invalidTargetValues: List<PiAxisInvalidTargetValue> = emptyList(),
    val outOfRangeAxes: List<PiAxisOutOfRange> = emptyList()
) {

    val passed: Boolean
        get() = missingRangeAxes.isEmpty() &&
                missingTargetAxes.isEmpty() &&
                invalidTargetValues.isEmpty() &&
                outOfRangeAxes.isEmpty()

    val failed: Boolean
        get() = !passed

    val message: String
        get() {
            if (passed) {
                return "$label 在 PI 行程范围内"
            }

            return buildString {
                append(label)
                append(" PI 行程预检失败: ")
                append(
                    listOfNotNull(
                        missingRangeAxes.takeIf { it.isNotEmpty() }
                            ?.joinToString(
                                prefix = "missingRanges=",
                                separator = ", "
                            ) { it.code },
                        missingTargetAxes.takeIf { it.isNotEmpty() }
                            ?.joinToString(
                                prefix = "missingTargets=",
                                separator = ", "
                            ) { it.code },
                        invalidTargetValues.takeIf { it.isNotEmpty() }
                            ?.joinToString(
                                prefix = "invalidTargets=",
                                separator = "; "
                            ) { it.message },
                        outOfRangeAxes.takeIf { it.isNotEmpty() }
                            ?.joinToString(
                                prefix = "outOfRange=",
                                separator = "; "
                            ) { it.message }
                    ).joinToString("; ")
                )
            }
        }

    fun throwException() {
        when {
            missingRangeAxes.isNotEmpty() -> {
                throw PiTravelRangeMissingAxesException(
                    label = label,
                    missingAxes = missingRangeAxes
                )
            }

            missingTargetAxes.isNotEmpty() -> {
                throw PiTravelRangeMissingTargetAxesException(
                    label = label,
                    missingAxes = missingTargetAxes
                )
            }

            invalidTargetValues.isNotEmpty() -> {
                throw PiTravelRangeInvalidTargetValueException(
                    label = label,
                    invalidTargetValues = invalidTargetValues
                )
            }

            outOfRangeAxes.isNotEmpty() -> {
                throw PiTravelRangeException(
                    label = label,
                    outOfRangeAxes = outOfRangeAxes
                )
            }
        }
    }
}

class PiTravelRangeException(
    val label: String,
    val outOfRangeAxes: List<PiAxisOutOfRange>
) : PiGcsException(
    message = buildString {
        append(label)
        append(" 超出 PI 行程范围: ")
        append(outOfRangeAxes.joinToString("; ") { it.message })
    }
)

class PiTravelRangeMissingAxesException(
    val label: String,
    val missingAxes: List<PiAxis>
) : PiGcsException(
    message = buildString {
        append(label)
        append(" 缺少 PI 行程范围: axes=")
        append(missingAxes.joinToString(", ") { it.code })
    }
)

class PiTravelRangeMissingTargetAxesException(
    val label: String,
    val missingAxes: List<PiAxis>
) : PiGcsException(
    message = buildString {
        append(label)
        append(" 缺少 PI 目标值: axes=")
        append(missingAxes.joinToString(", ") { it.code })
    }
)

class PiTravelRangeInvalidTargetValueException(
    val label: String,
    val invalidTargetValues: List<PiAxisInvalidTargetValue>
) : PiGcsException(
    message = buildString {
        append(label)
        append(" 包含无效 PI 目标值: ")
        append(invalidTargetValues.joinToString("; ") { it.message })
    }
)

fun PiHexapodPose.toPiAxisValueMap(): Map<PiAxis, Double> {
    return mapOf(
        PiAxis.X to xUm,
        PiAxis.Y to yUm,
        PiAxis.Z to zUm,
        PiAxis.U to uDeg,
        PiAxis.V to vDeg,
        PiAxis.W to wDeg
    )
}
