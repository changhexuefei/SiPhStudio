package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

/** PI GCS 命令类型。 */
enum class GcsCommandKind {
    Command,
    Query
}

/**
 * PI GCS 命令描述。
 *
 * 除了命令文本之外，还携带响应行数。PI 控制器的多轴查询通常按轴逐行返回，
 * 因此不能把所有查询都当成单行响应处理。
 */
sealed interface GcsCommand {

    val text: String

    val kind: GcsCommandKind

    /** 查询命令预期返回的行数；普通命令固定为 0。 */
    val expectedResponseLines: Int
        get() = if (kind == GcsCommandKind.Query) 1 else 0

    val isQuery: Boolean
        get() = kind == GcsCommandKind.Query

    val isCommand: Boolean
        get() = kind == GcsCommandKind.Command

    /** 普通命令执行后是否执行 ERR? 检查。 */
    val shouldCheckError: Boolean
        get() = kind == GcsCommandKind.Command

    companion object {

        fun rawCommand(text: String): GcsCommand = RawCommand(text)

        fun rawQuery(
            text: String,
            expectedResponseLines: Int = 1
        ): GcsCommand = RawQuery(text, expectedResponseLines)

        fun qIDN(): GcsCommand = RawQuery("*IDN?")

        fun qVER(): GcsCommand = RawQuery("VER?")

        fun qERR(): GcsCommand = RawQuery("ERR?")

        fun stopAll(): GcsCommand = RawCommand("STP")

        fun servo(
            axis: PiAxis,
            enabled: Boolean
        ): GcsCommand = servo(linkedMapOf(axis to enabled))

        /** 一次发送多轴 Servo 状态。 */
        fun servo(states: Map<PiAxis, Boolean>): GcsCommand {
            require(states.isNotEmpty()) { "SVO states 不能为空" }
            return RawCommand("SVO ${states.toAxisBooleanText()}")
        }

        /** 动态轴版本，支持数字轴和 AXIS_1 等 GCS 3.0 轴名。 */
        fun servoIds(states: Map<PiAxisId, Boolean>): GcsCommand {
            require(states.isNotEmpty()) { "SVO states 不能为空" }
            return RawCommand("SVO ${states.toAxisIdBooleanText()}")
        }

        fun qServo(axis: PiAxis): GcsCommand = RawQuery("SVO? ${axis.code}")

        fun qServo(axes: List<PiAxis>): GcsCommand {
            require(axes.isNotEmpty()) { "SVO? axes 不能为空" }
            return RawQuery(
                text = "SVO? ${axes.toAxisListText()}",
                responseLines = axes.size
            )
        }

        fun qServoIds(axes: List<PiAxisId>): GcsCommand {
            require(axes.isNotEmpty()) { "SVO? axes 不能为空" }
            return RawQuery(
                text = "SVO? ${axes.toAxisIdListText()}",
                responseLines = axes.size
            )
        }

        fun moveAbsolute(
            axis: PiAxis,
            target: Double
        ): GcsCommand = moveAbsolute(linkedMapOf(axis to target))

        fun moveAbsolute(targets: Map<PiAxis, Double>): GcsCommand {
            require(targets.isNotEmpty()) { "MOV targets 不能为空" }
            return RawCommand("MOV ${targets.toAxisValueText()}")
        }

        fun moveAbsoluteIds(targets: Map<PiAxisId, Double>): GcsCommand {
            require(targets.isNotEmpty()) { "MOV targets 不能为空" }
            return RawCommand("MOV ${targets.toAxisIdValueText()}")
        }

        fun moveRelative(
            axis: PiAxis,
            delta: Double
        ): GcsCommand = moveRelative(linkedMapOf(axis to delta))

        fun moveRelative(deltas: Map<PiAxis, Double>): GcsCommand {
            val nonZero = deltas.filterValues { it != 0.0 }
            require(nonZero.isNotEmpty()) {
                "MVR deltas 不能为空，或者所有 delta 都为 0"
            }
            return RawCommand("MVR ${nonZero.toAxisValueText()}")
        }

        fun moveRelativeIds(deltas: Map<PiAxisId, Double>): GcsCommand {
            val nonZero = deltas.filterValues { it != 0.0 }
            require(nonZero.isNotEmpty()) {
                "MVR deltas 不能为空，或者所有 delta 都为 0"
            }
            return RawCommand("MVR ${nonZero.toAxisIdValueText()}")
        }

        fun qPosition(axis: PiAxis): GcsCommand = RawQuery("POS? ${axis.code}")

        fun qPosition(axes: List<PiAxis>): GcsCommand {
            require(axes.isNotEmpty()) { "POS? axes 不能为空" }
            return RawQuery(
                text = "POS? ${axes.toAxisListText()}",
                responseLines = axes.size
            )
        }

        fun qPositionIds(axes: List<PiAxisId>): GcsCommand {
            require(axes.isNotEmpty()) { "POS? axes 不能为空" }
            return RawQuery(
                text = "POS? ${axes.toAxisIdListText()}",
                responseLines = axes.size
            )
        }

        fun qOnTarget(axis: PiAxis): GcsCommand = RawQuery("ONT? ${axis.code}")

        fun qOnTarget(axes: List<PiAxis>): GcsCommand {
            require(axes.isNotEmpty()) { "ONT? axes 不能为空" }
            return RawQuery(
                text = "ONT? ${axes.toAxisListText()}",
                responseLines = axes.size
            )
        }

        fun qOnTargetIds(axes: List<PiAxisId>): GcsCommand {
            require(axes.isNotEmpty()) { "ONT? axes 不能为空" }
            return RawQuery(
                text = "ONT? ${axes.toAxisIdListText()}",
                responseLines = axes.size
            )
        }

        fun qTravelMin(axis: PiAxis): GcsCommand = RawQuery("TMN? ${axis.code}")

        fun qTravelMax(axis: PiAxis): GcsCommand = RawQuery("TMX? ${axis.code}")

        fun qTravelMin(axes: List<PiAxis>): GcsCommand {
            require(axes.isNotEmpty()) { "TMN? axes 不能为空" }
            return RawQuery(
                text = "TMN? ${axes.toAxisListText()}",
                responseLines = axes.size
            )
        }

        fun qTravelMax(axes: List<PiAxis>): GcsCommand {
            require(axes.isNotEmpty()) { "TMX? axes 不能为空" }
            return RawQuery(
                text = "TMX? ${axes.toAxisListText()}",
                responseLines = axes.size
            )
        }

        fun qTravelMinIds(axes: List<PiAxisId>): GcsCommand {
            require(axes.isNotEmpty()) { "TMN? axes 不能为空" }
            return RawQuery(
                text = "TMN? ${axes.toAxisIdListText()}",
                responseLines = axes.size
            )
        }

        fun qTravelMaxIds(axes: List<PiAxisId>): GcsCommand {
            require(axes.isNotEmpty()) { "TMX? axes 不能为空" }
            return RawQuery(
                text = "TMX? ${axes.toAxisIdListText()}",
                responseLines = axes.size
            )
        }

        fun reference(
            axis: PiAxis,
            mode: PiReferenceCommand = PiReferenceCommand.FRF
        ): GcsCommand {
            return RawCommand("${mode.gcsCode} ${axis.code}")
        }

        fun reference(
            axes: List<PiAxis>,
            mode: PiReferenceCommand = PiReferenceCommand.FRF
        ): GcsCommand {
            require(axes.isNotEmpty()) { "reference axes 不能为空" }
            return RawCommand("${mode.gcsCode} ${axes.toAxisListText()}")
        }

        fun referenceIds(
            axes: List<PiAxisId>,
            mode: PiReferenceCommand = PiReferenceCommand.FRF
        ): GcsCommand {
            require(axes.isNotEmpty()) { "reference axes 不能为空" }
            return RawCommand("${mode.gcsCode} ${axes.toAxisIdListText()}")
        }

        fun qAxes(): GcsCommand = RawQuery("SAI?")
    }
}

private data class RawCommand(
    override val text: String
) : GcsCommand {
    init {
        require(text.isNotBlank()) { "GCS command 不能为空" }
    }

    override val kind: GcsCommandKind = GcsCommandKind.Command
}

private data class RawQuery(
    override val text: String,
    private val responseLines: Int = 1
) : GcsCommand {
    init {
        require(text.isNotBlank()) { "GCS query 不能为空" }
        require(responseLines > 0) {
            "responseLines 必须大于 0，当前值: $responseLines"
        }
    }

    override val kind: GcsCommandKind = GcsCommandKind.Query

    override val expectedResponseLines: Int
        get() = responseLines
}

private fun Boolean.toGcsInt(): Int = if (this) 1 else 0

/** 使用固定 Locale，避免不同系统区域设置改变小数点。 */
internal fun Double.toGcsNumber(): String {
    require(isFinite()) { "GCS 数值必须是有限数，当前值: $this" }
    return String.format(Locale.US, "%.9f", this)
}

internal fun List<PiAxis>.toAxisListText(): String {
    require(isNotEmpty()) { "axes 不能为空" }
    require(size == distinct().size) { "axes 不能包含重复轴: $this" }
    return joinToString(" ") { it.code }
}

internal fun List<PiAxisId>.toAxisIdListText(): String {
    require(isNotEmpty()) { "axes 不能为空" }
    require(size == distinct().size) { "axes 不能包含重复轴: $this" }
    return joinToString(" ") { it.value }
}

internal fun Map<PiAxis, Double>.toAxisValueText(): String {
    return entries
        .sortedBy { it.key.ordinal }
        .joinToString(" ") { (axis, value) ->
            "${axis.code} ${value.toGcsNumber()}"
        }
}

internal fun Map<PiAxisId, Double>.toAxisIdValueText(): String {
    return entries.joinToString(" ") { (axis, value) ->
        "${axis.value} ${value.toGcsNumber()}"
    }
}

private fun Map<PiAxis, Boolean>.toAxisBooleanText(): String {
    return entries
        .sortedBy { it.key.ordinal }
        .joinToString(" ") { (axis, enabled) ->
            "${axis.code} ${enabled.toGcsInt()}"
        }
}

private fun Map<PiAxisId, Boolean>.toAxisIdBooleanText(): String {
    return entries.joinToString(" ") { (axis, enabled) ->
        "${axis.value} ${enabled.toGcsInt()}"
    }
}
