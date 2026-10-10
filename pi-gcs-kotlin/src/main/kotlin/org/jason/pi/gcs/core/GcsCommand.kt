package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

enum class GcsCommandKind {
    Command,
    Query
}

/**
 * Typed PI GCS command descriptor.
 *
 * Command construction is kept independent from transport I/O so it can be
 * unit-tested and reused by TCP, serial, USB or a future native PI backend.
 */
sealed interface GcsCommand {

    val text: String
    val kind: GcsCommandKind

    val isQuery: Boolean
        get() = kind == GcsCommandKind.Query

    val isCommand: Boolean
        get() = kind == GcsCommandKind.Command

    val shouldCheckError: Boolean
        get() = kind == GcsCommandKind.Command

    companion object {

        fun rawCommand(text: String): GcsCommand {
            return RawCommand(text.requireCommandText())
        }

        fun rawQuery(text: String): GcsCommand {
            return RawQuery(text.requireCommandText())
        }

        fun qIDN(): GcsCommand = RawQuery("*IDN?")

        fun qVER(): GcsCommand = RawQuery("VER?")

        fun qERR(): GcsCommand = RawQuery("ERR?")

        fun stopAll(): GcsCommand = RawCommand("STP")

        fun servo(
            axis: PiAxis,
            enabled: Boolean
        ): GcsCommand {
            return servo(linkedMapOf(axis to enabled))
        }

        /**
         * Creates one SVO command for all requested axes.
         *
         * Example: SVO X 1 Y 1 Z 1
         */
        fun servo(states: Map<PiAxis, Boolean>): GcsCommand {
            require(states.isNotEmpty()) { "SVO states 不能为空" }
            return RawCommand("SVO ${states.toAxisBooleanText()}")
        }

        fun servo(
            axes: List<PiAxis>,
            enabled: Boolean
        ): GcsCommand {
            val normalized = axes.requireAxes("SVO")
            return servo(normalized.associateWithTo(LinkedHashMap()) { enabled })
        }

        fun qServo(axis: PiAxis): GcsCommand {
            return RawQuery("SVO? ${axis.code}")
        }

        fun qServo(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("SVO?")
            return RawQuery("SVO? ${normalized.toAxisListText()}")
        }

        fun moveAbsolute(
            axis: PiAxis,
            target: Double
        ): GcsCommand {
            return moveAbsolute(linkedMapOf(axis to target))
        }

        fun moveAbsolute(targets: Map<PiAxis, Double>): GcsCommand {
            require(targets.isNotEmpty()) { "MOV targets 不能为空" }
            targets.requireFiniteValues("MOV")
            return RawCommand("MOV ${targets.toAxisValueText()}")
        }

        fun moveRelative(
            axis: PiAxis,
            delta: Double
        ): GcsCommand {
            return moveRelative(linkedMapOf(axis to delta))
        }

        fun moveRelative(deltas: Map<PiAxis, Double>): GcsCommand {
            deltas.requireFiniteValues("MVR")
            val nonZero = deltas.filterValues { it != 0.0 }
            require(nonZero.isNotEmpty()) {
                "MVR deltas 不能为空，或者所有 delta 都为 0"
            }
            return RawCommand("MVR ${nonZero.toAxisValueText()}")
        }

        fun qPosition(axis: PiAxis): GcsCommand {
            return RawQuery("POS? ${axis.code}")
        }

        fun qPosition(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("POS?")
            return RawQuery("POS? ${normalized.toAxisListText()}")
        }

        fun qOnTarget(axis: PiAxis): GcsCommand {
            return RawQuery("ONT? ${axis.code}")
        }

        fun qOnTarget(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("ONT?")
            return RawQuery("ONT? ${normalized.toAxisListText()}")
        }

        fun qTravelMin(axis: PiAxis): GcsCommand {
            return RawQuery("TMN? ${axis.code}")
        }

        fun qTravelMin(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("TMN?")
            return RawQuery("TMN? ${normalized.toAxisListText()}")
        }

        fun qTravelMax(axis: PiAxis): GcsCommand {
            return RawQuery("TMX? ${axis.code}")
        }

        fun qTravelMax(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("TMX?")
            return RawQuery("TMX? ${normalized.toAxisListText()}")
        }

        fun reference(axis: PiAxis): GcsCommand {
            return RawCommand("FRF ${axis.code}")
        }

        /**
         * Creates a multi-axis reference command. Keep using the single-axis
         * overload when a controller manual requires axis-by-axis referencing.
         */
        fun reference(axes: List<PiAxis>): GcsCommand {
            val normalized = axes.requireAxes("FRF")
            return RawCommand("FRF ${normalized.toAxisListText()}")
        }

        fun qAxes(): GcsCommand = RawQuery("SAI?")
    }
}

private data class RawCommand(
    override val text: String
) : GcsCommand {
    override val kind: GcsCommandKind = GcsCommandKind.Command
}

private data class RawQuery(
    override val text: String
) : GcsCommand {
    override val kind: GcsCommandKind = GcsCommandKind.Query
}

private fun String.requireCommandText(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "PI GCS command text must not be blank" }
    return normalized
}

private fun Boolean.toGcsInt(): Int = if (this) 1 else 0

internal fun Double.toGcsNumber(): String {
    require(isFinite()) { "PI GCS 数值必须是有限值，actual=$this" }
    return String.format(Locale.US, "%.9f", this)
}

internal fun List<PiAxis>.toAxisListText(): String {
    return joinToString(" ") { it.code }
}

internal fun Map<PiAxis, Double>.toAxisValueText(): String {
    return entries.joinToString(" ") { (axis, value) ->
        "${axis.code} ${value.toGcsNumber()}"
    }
}

private fun Map<PiAxis, Boolean>.toAxisBooleanText(): String {
    return entries.joinToString(" ") { (axis, enabled) ->
        "${axis.code} ${enabled.toGcsInt()}"
    }
}

private fun List<PiAxis>.requireAxes(command: String): List<PiAxis> {
    val normalized = distinct()
    require(normalized.isNotEmpty()) { "$command axes 不能为空" }
    return normalized
}

private fun Map<PiAxis, Double>.requireFiniteValues(command: String) {
    for ((axis, value) in this) {
        require(value.isFinite()) {
            "$command ${axis.code} 必须是有限值，actual=$value"
        }
    }
}
