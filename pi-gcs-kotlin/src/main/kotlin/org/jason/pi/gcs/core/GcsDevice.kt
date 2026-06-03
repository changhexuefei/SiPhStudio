package org.jason.pi.gcs.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jason.pi.gcs.hexapod.PiAxis

/**
 * PI GCS 命令封装。
 *
 * 类似 PIPython 里的 GCSDevice / GCSCommands 的最小 Kotlin 版本。
 */
/**
 * PI GCS 命令封装。
 * * 基于 Ktor Network 与 协程挂起机制优化的高并发、跨平台版本。
 */
class GcsDevice(
    private val client: GcsClient
) : AutoCloseable {

    suspend fun connect() {
        client.connect()
    }

    suspend fun qIDN(): String = client.query("*IDN?")

    suspend fun qVER(): String = client.query("VER?")

    suspend fun qERR(): Int = client.qERR()

    suspend fun stopAll() {
        client.command("STP")
    }

    suspend fun servoOn(axis: PiAxis) {
        client.command("SVO ${axis.code} 1")
    }

    suspend fun servoOff(axis: PiAxis) {
        client.command("SVO ${axis.code} 0")
    }

    // 优化：利用协程并发（launch/join）同时配置伺服，提高初始化速度
    suspend fun servoOnAll(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES) = coroutineScope {
        axes.map { axis -> launch { servoOn(axis) } }.joinAll()
    }

    suspend fun servoOffAll(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES) = coroutineScope {
        axes.map { axis -> launch { servoOff(axis) } }.joinAll()
    }

    suspend fun moveAbsolute(axis: PiAxis, target: Double) {
        client.command("MOV ${axis.code} ${target.toGcsNumber()}")
    }

    /**
     * 多轴绝对移动：符合原本设计的单行群发优化
     */
    suspend fun moveAbsolute(targets: Map<PiAxis, Double>) {
        if (targets.isEmpty()) return
        val body = targets.entries.joinToString(" ") { (axis, value) ->
            "${axis.code} ${value.toGcsNumber()}"
        }
        client.command("MOV $body")
    }

    suspend fun moveRelative(axis: PiAxis, delta: Double) {
        client.command("MVR ${axis.code} ${delta.toGcsNumber()}")
    }

    suspend fun moveRelative(deltas: Map<PiAxis, Double>) {
        val nonZero = deltas.filterValues { it != 0.0 }
        if (nonZero.isEmpty()) return
        val body = nonZero.entries.joinToString(" ") { (axis, value) ->
            "${axis.code} ${value.toGcsNumber()}"
        }
        client.command("MVR $body")
    }

    /**
     * 单轴查询保持原样
     */
    suspend fun qPOS(axis: PiAxis): Double {
        val response = client.query("POS? ${axis.code}")
        return parseAxisLineToDouble(response, axis)
    }

    /**
     * 🚀【重大优化】多轴位置查询（对齐 pi-tools 核心规范）
     * 一次性向 Ktor Channel 发送指令，连续读取多行，避免多次网络往返 (RTT)
     */
    suspend fun qPOS(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES): Map<PiAxis, Double> {
        if (axes.isEmpty()) return emptyMap()

        // 1. 组装批量查询命令，例如 "POS? X Y Z U V W"
        val cmd = "POS? " + axes.joinToString(" ") { it.code }

        // 2. 利用底层支持批量拉取的接口 (需要在 GcsClient 中实现 queryLines)
        val responses = client.queryLines(cmd, expectedLineCount = axes.size)

        // 3. 混合组装成结果 Map
        return parseMultipleAxisLinesToDouble(responses, axes)
    }

    suspend fun qONT(axis: PiAxis): Boolean {
        val response = client.query("ONT? ${axis.code}")
        return parseAxisLineToInt(response, axis) == 1
    }

    /**
     * 🚀【重大优化】多轴就位查询
     * 硅光寻光时需要高频轮询此状态，必须采用批量查询方式降低网口吞吐压力
     */
    suspend fun qONT(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES): Map<PiAxis, Boolean> {
        if (axes.isEmpty()) return emptyMap()
        val cmd = "ONT? " + axes.joinToString(" ") { it.code }
        val responses = client.queryLines(cmd, expectedLineCount = axes.size)

        return parseMultipleAxisLinesToInt(responses, axes).mapValues { it.value == 1 }
    }

    suspend fun qTMN(axis: PiAxis): Double {
        val response = client.query("TMN? ${axis.code}")
        return parseAxisLineToDouble(response, axis)
    }

    suspend fun qTMX(axis: PiAxis): Double {
        val response = client.query("TMX? ${axis.code}")
        return parseAxisLineToDouble(response, axis)
    }

    suspend fun qTMN(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES): Map<PiAxis, Double> {
        if (axes.isEmpty()) return emptyMap()
        val cmd = "TMN? " + axes.joinToString(" ") { it.code }
        val responses = client.queryLines(cmd, expectedLineCount = axes.size)
        return parseMultipleAxisLinesToDouble(responses, axes)
    }

    suspend fun qTMX(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES): Map<PiAxis, Double> {
        if (axes.isEmpty()) return emptyMap()
        val cmd = "TMX? " + axes.joinToString(" ") { it.code }
        val responses = client.queryLines(cmd, expectedLineCount = axes.size)
        return parseMultipleAxisLinesToDouble(responses, axes)
    }

    suspend fun reference(axis: PiAxis) {
        client.command("FRF ${axis.code}")
    }

    suspend fun referenceAll(axes: List<PiAxis> = PiAxis.HEXAPOD_AXES) = coroutineScope {
        axes.map { axis -> launch { reference(axis) } }.joinAll()
    }

    override fun close() {
        client.close()
    }


    /**
     * 解析单行响应数据 (支持 X=1.23, X 1.23 等)
     */
    private fun parseAxisLineToDouble(response: String, expectedAxis: PiAxis): Double {
        val tokens = response
            .replace("=", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.size < 2) {
            throw PiGcsParseException(response = response, message = "PI GCS 轴响应格式错误")
        }

        val actualAxis = tokens[0].trim()
        if (!actualAxis.equals(expectedAxis.code, ignoreCase = true)) {
            throw PiGcsParseException(
                response = response,
                message = "PI GCS 响应轴不匹配，expected=${expectedAxis.code}, actual=$actualAxis"
            )
        }

        return tokens[1].toDoubleOrNull()
            ?: throw PiGcsParseException(response = response, message = "无法解析 PI GCS 数值")
    }

    private fun parseAxisLineToInt(response: String, expectedAxis: PiAxis): Int {
        return parseAxisLineToDouble(response, expectedAxis).toInt()
    }

    /**
     * 🚀【新增】批量多行响应解析器
     * 自动适配控制器返回的无序或有序多行轴数据
     */
    private fun parseMultipleAxisLinesToDouble(
        responses: List<String>,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Double> {
        val result = mutableMapOf<PiAxis, Double>()

        responses.forEach { line ->
            val tokens = line.replace("=", " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size >= 2) {
                val axisCode = tokens[0].trim()
                val value = tokens[1].toDoubleOrNull() ?: 0.0

                // 在期望查询的轴列表中寻找匹配项
                val matchedAxis = expectedAxes.find { it.code.equals(axisCode, ignoreCase = true) }
                if (matchedAxis != null) {
                    result[matchedAxis] = value
                }
            }
        }
        return result
    }

    private fun parseMultipleAxisLinesToInt(
        responses: List<String>,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Int> {
        return parseMultipleAxisLinesToDouble(responses, expectedAxes).mapValues { it.value.toInt() }
    }
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
