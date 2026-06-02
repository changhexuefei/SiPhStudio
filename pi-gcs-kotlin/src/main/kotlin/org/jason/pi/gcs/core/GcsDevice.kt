package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

/**
 * PI GCS 命令封装。
 *
 * 类似 PIPython 里的 GCSDevice / GCSCommands 的最小 Kotlin 版本。
 */
class GcsDevice(
    private val client: GcsClient
) : AutoCloseable {

    suspend fun connect() {
        client.connect()
    }

    suspend fun qIDN(): String {
        return client.query("*IDN?")
    }

    suspend fun qVER(): String {
        return client.query("VER?")
    }

    suspend fun qERR(): Int {
        return client.qERR()
    }

    suspend fun stopAll() {
        client.command("STP")
    }

    suspend fun servoOn(axis: PiAxis) {
        client.command("SVO ${axis.code} 1")
    }

    suspend fun servoOff(axis: PiAxis) {
        client.command("SVO ${axis.code} 0")
    }

    suspend fun servoOnAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            servoOn(axis)
        }
    }

    suspend fun servoOffAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            servoOff(axis)
        }
    }

    /**
     * 单轴绝对移动。
     */
    suspend fun moveAbsolute(
        axis: PiAxis,
        target: Double
    ) {
        client.command(
            "MOV ${axis.code} ${target.toGcsNumber()}"
        )
    }

    /**
     * 多轴绝对移动。
     *
     * 对六轴来说，尽量用一次 MOV 下发多个轴，
     * 比逐个轴移动更适合保持同步。
     */
    suspend fun moveAbsolute(
        targets: Map<PiAxis, Double>
    ) {
        if (targets.isEmpty()) return

        val body = targets.entries.joinToString(" ") { (axis, value) ->
            "${axis.code} ${value.toGcsNumber()}"
        }

        client.command("MOV $body")
    }

    /**
     * 单轴相对移动。
     */
    suspend fun moveRelative(
        axis: PiAxis,
        delta: Double
    ) {
        client.command(
            "MVR ${axis.code} ${delta.toGcsNumber()}"
        )
    }

    /**
     * 多轴相对移动。
     */
    suspend fun moveRelative(
        deltas: Map<PiAxis, Double>
    ) {
        val nonZero = deltas.filterValues { it != 0.0 }
        if (nonZero.isEmpty()) return

        val body = nonZero.entries.joinToString(" ") { (axis, value) ->
            "${axis.code} ${value.toGcsNumber()}"
        }

        client.command("MVR $body")
    }

    suspend fun qPOS(
        axis: PiAxis
    ): Double {
        val response = client.query("POS? ${axis.code}")
        return parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    /**
     * 查询多个轴位置。
     *
     * 为了兼容不同控制器响应格式，这里先逐轴查询。
     * 后续如果确认控制器多轴响应格式稳定，可以优化成一次 POS? 多轴查询。
     */
    suspend fun qPOS(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        return axes.associateWith { axis ->
            qPOS(axis)
        }
    }

    suspend fun qONT(
        axis: PiAxis
    ): Boolean {
        val response = client.query("ONT? ${axis.code}")
        return parseAxisInt(
            response = response,
            expectedAxis = axis
        ) == 1
    }

    suspend fun qONT(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Boolean> {
        return axes.associateWith { axis ->
            qONT(axis)
        }
    }

    suspend fun qTMN(
        axis: PiAxis
    ): Double {
        val response = client.query("TMN? ${axis.code}")
        return parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qTMX(
        axis: PiAxis
    ): Double {
        val response = client.query("TMX? ${axis.code}")
        return parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qTMN(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        return axes.associateWith { axis ->
            qTMN(axis)
        }
    }

    suspend fun qTMX(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        return axes.associateWith { axis ->
            qTMX(axis)
        }
    }

    /**
     * FRF 参考。
     *
     * 不是所有控制器 / 六轴都需要这样做。
     * 实际是否使用，要根据 PI 控制器手册确认。
     */
    suspend fun reference(axis: PiAxis) {
        client.command("FRF ${axis.code}")
    }

    suspend fun referenceAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            reference(axis)
        }
    }

    override fun close() {
        client.close()
    }
}

private fun Double.toGcsNumber(): String {
    return String.format(Locale.US, "%.9f", this)
}

/**
 * 解析类似：
 * X=1.234
 * X 1.234
 * X\t1.234
 */
private fun parseAxisDouble(
    response: String,
    expectedAxis: PiAxis
): Double {
    val tokens = response
        .replace("=", " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (tokens.size < 2) {
        throw PiGcsParseException(
            response = response,
            message = "PI GCS 轴响应格式错误"
        )
    }

    val actualAxis = tokens[0].trim()
    if (!actualAxis.equals(expectedAxis.code, ignoreCase = true)) {
        throw PiGcsParseException(
            response = response,
            message = "PI GCS 响应轴不匹配，expected=${expectedAxis.code}, actual=$actualAxis"
        )
    }

    return tokens[1].toDoubleOrNull()
        ?: throw PiGcsParseException(
            response = response,
            message = "无法解析 PI GCS 数值"
        )
}

private fun parseAxisInt(
    response: String,
    expectedAxis: PiAxis
): Int {
    return parseAxisDouble(
        response = response,
        expectedAxis = expectedAxis
    ).toInt()
}