package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

/**
 * PI GCS 命令类型。
 */
enum class GcsCommandKind {

    /**
     * 无返回值命令。
     *
     * 例如：
     * - MOV X 1.0
     * - MVR X 0.1
     * - SVO X 1
     * - STP
     */
    Command,

    /**
     * 有返回值查询命令。
     *
     * 例如：
     * - *IDN?
     * - POS? X
     * - ONT? X
     * - ERR?
     */
    Query
}

/**
 * PI GCS 命令对象。
 *
 * 这个接口只负责生成 GCS 文本命令，不负责发送。
 *
 * 发送由 GcsClient / GcsDevice 负责。
 */
sealed interface GcsCommand {

    /**
     * GCS 原始命令文本。
     */
    val text: String

    /**
     * 命令类型。
     */
    val kind: GcsCommandKind

    /**
     * 是否是查询命令。
     */
    val isQuery: Boolean
        get() = kind == GcsCommandKind.Query

    /**
     * 是否是普通命令。
     */
    val isCommand: Boolean
        get() = kind == GcsCommandKind.Command

    /**
     * 普通命令执行后是否建议执行 ERR? 检查。
     */
    val shouldCheckError: Boolean
        get() = kind == GcsCommandKind.Command

    companion object {

        /**
         * 原始无返回值命令。
         *
         * 用于调试或临时支持尚未封装的 GCS 命令。
         */
        fun rawCommand(
            text: String
        ): GcsCommand {
            return RawCommand(text)
        }

        /**
         * 原始查询命令。
         */
        fun rawQuery(
            text: String
        ): GcsCommand {
            return RawQuery(text)
        }

        /**
         * 查询设备识别信息。
         *
         * GCS:
         * *IDN?
         */
        fun qIDN(): GcsCommand {
            return RawQuery("*IDN?")
        }

        /**
         * 查询版本信息。
         *
         * GCS:
         * VER?
         */
        fun qVER(): GcsCommand {
            return RawQuery("VER?")
        }

        /**
         * 查询错误码。
         *
         * GCS:
         * ERR?
         */
        fun qERR(): GcsCommand {
            return RawQuery("ERR?")
        }

        /**
         * 停止所有运动。
         *
         * GCS:
         * STP
         */
        fun stopAll(): GcsCommand {
            return RawCommand("STP")
        }

        /**
         * 打开 / 关闭单轴 Servo。
         *
         * GCS:
         * SVO X 1
         * SVO X 0
         */
        fun servo(
            axis: PiAxis,
            enabled: Boolean
        ): GcsCommand {
            return RawCommand(
                "SVO ${axis.code} ${enabled.toGcsInt()}"
            )
        }

        /**
         * 查询单轴 Servo 状态。
         *
         * GCS:
         * SVO? X
         */
        fun qServo(
            axis: PiAxis
        ): GcsCommand {
            return RawQuery(
                "SVO? ${axis.code}"
            )
        }

        /**
         * 绝对移动单轴。
         *
         * GCS:
         * MOV X 1.000000000
         */
        fun moveAbsolute(
            axis: PiAxis,
            target: Double
        ): GcsCommand {
            return RawCommand(
                "MOV ${axis.code} ${target.toGcsNumber()}"
            )
        }

        /**
         * 绝对移动多轴。
         *
         * GCS:
         * MOV X 1.000000000 Y 2.000000000 Z 3.000000000
         */
        fun moveAbsolute(
            targets: Map<PiAxis, Double>
        ): GcsCommand {
            require(targets.isNotEmpty()) {
                "MOV targets 不能为空"
            }

            return RawCommand(
                "MOV ${targets.toAxisValueText()}"
            )
        }

        /**
         * 相对移动单轴。
         *
         * GCS:
         * MVR X 0.001000000
         */
        fun moveRelative(
            axis: PiAxis,
            delta: Double
        ): GcsCommand {
            return RawCommand(
                "MVR ${axis.code} ${delta.toGcsNumber()}"
            )
        }

        /**
         * 相对移动多轴。
         *
         * GCS:
         * MVR X 0.001000000 Y -0.001000000
         */
        fun moveRelative(
            deltas: Map<PiAxis, Double>
        ): GcsCommand {
            val nonZero = deltas.filterValues { it != 0.0 }

            require(nonZero.isNotEmpty()) {
                "MVR deltas 不能为空，或者所有 delta 都为 0"
            }

            return RawCommand(
                "MVR ${nonZero.toAxisValueText()}"
            )
        }

        /**
         * 查询单轴位置。
         *
         * GCS:
         * POS? X
         */
        fun qPosition(
            axis: PiAxis
        ): GcsCommand {
            return RawQuery(
                "POS? ${axis.code}"
            )
        }

        /**
         * 查询多轴位置。
         *
         * GCS:
         * POS? X Y Z U V W
         */
        fun qPosition(
            axes: List<PiAxis>
        ): GcsCommand {
            require(axes.isNotEmpty()) {
                "POS? axes 不能为空"
            }

            return RawQuery(
                "POS? ${axes.toAxisListText()}"
            )
        }

        /**
         * 查询单轴是否到位。
         *
         * GCS:
         * ONT? X
         */
        fun qOnTarget(
            axis: PiAxis
        ): GcsCommand {
            return RawQuery(
                "ONT? ${axis.code}"
            )
        }

        /**
         * 查询多轴是否到位。
         *
         * GCS:
         * ONT? X Y Z U V W
         */
        fun qOnTarget(
            axes: List<PiAxis>
        ): GcsCommand {
            require(axes.isNotEmpty()) {
                "ONT? axes 不能为空"
            }

            return RawQuery(
                "ONT? ${axes.toAxisListText()}"
            )
        }

        /**
         * 查询单轴最小行程。
         *
         * GCS:
         * TMN? X
         */
        fun qTravelMin(
            axis: PiAxis
        ): GcsCommand {
            return RawQuery(
                "TMN? ${axis.code}"
            )
        }

        /**
         * 查询单轴最大行程。
         *
         * GCS:
         * TMX? X
         */
        fun qTravelMax(
            axis: PiAxis
        ): GcsCommand {
            return RawQuery(
                "TMX? ${axis.code}"
            )
        }

        /**
         * 查询多轴最小行程。
         *
         * GCS:
         * TMN? X Y Z U V W
         */
        fun qTravelMin(
            axes: List<PiAxis>
        ): GcsCommand {
            require(axes.isNotEmpty()) {
                "TMN? axes 不能为空"
            }

            return RawQuery(
                "TMN? ${axes.toAxisListText()}"
            )
        }

        /**
         * 查询多轴最大行程。
         *
         * GCS:
         * TMX? X Y Z U V W
         */
        fun qTravelMax(
            axes: List<PiAxis>
        ): GcsCommand {
            require(axes.isNotEmpty()) {
                "TMX? axes 不能为空"
            }

            return RawQuery(
                "TMX? ${axes.toAxisListText()}"
            )
        }

        /**
         * 单轴 reference。
         *
         * GCS:
         * FRF X
         *
         * 注意：
         * 六轴是否需要 FRF，要根据控制器型号和 PI 手册确认。
         */
        fun reference(
            axis: PiAxis
        ): GcsCommand {
            return RawCommand(
                "FRF ${axis.code}"
            )
        }

        /**
         * 查询轴列表。
         *
         * 常见 GCS:
         * SAI?
         *
         * 注意：
         * 不同控制器支持情况可能不同。
         * 如果不支持，可以在 GcsDevice 中 fallback 到 X/Y/Z/U/V/W。
         */
        fun qAxes(): GcsCommand {
            return RawQuery("SAI?")
        }
    }
}

/**
 * 原始无返回值命令。
 */
private data class RawCommand(
    override val text: String
) : GcsCommand {

    override val kind: GcsCommandKind =
        GcsCommandKind.Command
}

/**
 * 原始查询命令。
 */
private data class RawQuery(
    override val text: String
) : GcsCommand {

    override val kind: GcsCommandKind =
        GcsCommandKind.Query
}

/**
 * Boolean 转 GCS 0/1。
 */
private fun Boolean.toGcsInt(): Int {
    return if (this) 1 else 0
}

/**
 * Double 转 GCS 数字字符串。
 *
 * 使用 Locale.US，避免中文系统 / 欧洲系统下小数点变成逗号。
 */
internal fun Double.toGcsNumber(): String {
    return String.format(
        Locale.US,
        "%.9f",
        this
    )
}

/**
 * 多轴列表转 GCS 文本。
 *
 * 例如：
 * X Y Z U V W
 */
internal fun List<PiAxis>.toAxisListText(): String {
    return joinToString(" ") { axis ->
        axis.code
    }
}

/**
 * 多轴目标值转 GCS 文本。
 *
 * 例如：
 * X 1.000000000 Y 2.000000000
 */
internal fun Map<PiAxis, Double>.toAxisValueText(): String {
    return entries.joinToString(" ") { (axis, value) ->
        "${axis.code} ${value.toGcsNumber()}"
    }
}