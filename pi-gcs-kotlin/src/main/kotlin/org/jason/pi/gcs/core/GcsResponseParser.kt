package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

object GcsResponseParser {

    private const val NUMBER_PATTERN: String =
        """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"""

    private val plainNumberRegex = Regex(
        pattern = """^\s*($NUMBER_PATTERN)\s*$"""
    )

    /**
     * 支持传统 X/Y/Z 轴、数字轴和 GCS 3.0 的 AXIS_1 类轴名。
     */
    private val axisValueRegex = Regex(
        pattern = """([A-Za-z0-9_][A-Za-z0-9_.-]*)\s*(?:=|:|\s)\s*($NUMBER_PATTERN)"""
    )

    fun parseErrorCode(response: String): Int {
        return response.trim().toIntOrNull()
            ?: throw PiGcsParseException(response, "无法解析 PI GCS 错误码")
    }

    fun parseAxisDouble(
        response: String,
        expectedAxis: PiAxis
    ): Double {
        return parseAxisIdDouble(
            response = response,
            expectedAxis = expectedAxis.toAxisId()
        )
    }

    fun parseAxisIdDouble(
        response: String,
        expectedAxis: PiAxisId
    ): Double {
        val trimmed = response.trim()
        val delimiterIndex = trimmed.indexOfAny(charArrayOf('=', ':', ' ', '\t'))

        if (delimiterIndex > 0) {
            val axisCode = trimmed.substring(0, delimiterIndex).trim()
            if (axisCode.equals(expectedAxis.value, ignoreCase = true)) {
                val valueText = trimmed.substring(delimiterIndex + 1)
                    .trimStart('=', ':', ' ', '\t')
                    .trim()
                valueText.toDoubleOrNull()?.let { return it }
            }
        }

        val match = parseAxisValuePairs(trimmed).firstOrNull {
            it.axis.equals(expectedAxis.value, ignoreCase = true)
        } ?: throw PiGcsParseException(
            response,
            "PI GCS 响应中未找到轴 ${expectedAxis.value}"
        )

        return match.value
    }

    fun parseAxisInt(response: String, expectedAxis: PiAxis): Int {
        return parseAxisDouble(response, expectedAxis).toInt()
    }

    fun parseAxisIdInt(response: String, expectedAxis: PiAxisId): Int {
        return parseAxisIdDouble(response, expectedAxis).toInt()
    }

    fun parseAxisBoolean(response: String, expectedAxis: PiAxis): Boolean {
        return parseAxisInt(response, expectedAxis) != 0
    }

    fun parseAxisIdBoolean(response: String, expectedAxis: PiAxisId): Boolean {
        return parseAxisIdInt(response, expectedAxis) != 0
    }

    fun parseAxisDoubleMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Double> {
        val axisIds = expectedAxes.map { it.toAxisId() }
        val parsed = parseAxisIdDoubleMap(response, axisIds)

        return LinkedHashMap<PiAxis, Double>(expectedAxes.size).apply {
            expectedAxes.forEach { axis ->
                put(axis, parsed.getValue(axis.toAxisId()))
            }
        }
    }

    fun parseAxisIdDoubleMap(
        response: String,
        expectedAxes: List<PiAxisId>
    ): Map<PiAxisId, Double> {
        require(expectedAxes.isNotEmpty()) { "expectedAxes 不能为空" }
        require(expectedAxes.size == expectedAxes.distinct().size) {
            "expectedAxes 不能包含重复轴: $expectedAxes"
        }

        val pairs = parseAxisValuePairs(response)

        if (pairs.isEmpty()) {
            if (expectedAxes.size == 1) {
                return linkedMapOf(expectedAxes.first() to parsePlainDouble(response))
            }
            throw PiGcsParseException(response, "无法解析多轴 PI GCS 响应")
        }

        val rawMap = HashMap<String, Double>(pairs.size * 2)
        pairs.forEach { pair ->
            rawMap[pair.axis.uppercase(Locale.ROOT)] = pair.value
        }

        return LinkedHashMap<PiAxisId, Double>(expectedAxes.size).apply {
            expectedAxes.forEach { axis ->
                val value = rawMap[axis.value.uppercase(Locale.ROOT)]
                    ?: throw PiGcsParseException(
                        response,
                        "PI GCS 响应中缺少轴 ${axis.value}"
                    )
                put(axis, value)
            }
        }
    }

    fun parseAxisIntMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Int> {
        return parseAxisDoubleMap(response, expectedAxes)
            .mapValuesTo(LinkedHashMap()) { it.value.toInt() }
    }

    fun parseAxisIdIntMap(
        response: String,
        expectedAxes: List<PiAxisId>
    ): Map<PiAxisId, Int> {
        return parseAxisIdDoubleMap(response, expectedAxes)
            .mapValuesTo(LinkedHashMap()) { it.value.toInt() }
    }

    fun parseAxisBooleanMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Boolean> {
        return parseAxisIntMap(response, expectedAxes)
            .mapValuesTo(LinkedHashMap()) { it.value != 0 }
    }

    fun parseAxisIdBooleanMap(
        response: String,
        expectedAxes: List<PiAxisId>
    ): Map<PiAxisId, Boolean> {
        return parseAxisIdIntMap(response, expectedAxes)
            .mapValuesTo(LinkedHashMap()) { it.value != 0 }
    }

    /**
     * 返回控制器原始轴标识，支持数字轴和 GCS 3.0 轴名。
     */
    fun parseAxisIds(response: String): List<PiAxisId> {
        val tokens = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(",", " ")
            .replace(";", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            throw PiGcsParseException(response, "无法解析 PI GCS 轴列表")
        }

        val axes = tokens.map { token ->
            runCatching { PiAxisId.of(token) }
                .getOrElse {
                    throw PiGcsParseException(response, "未知 PI GCS 轴名: $token")
                }
        }

        if (axes.size != axes.distinct().size) {
            throw PiGcsParseException(response, "PI GCS 轴列表包含重复轴")
        }

        return axes
    }

    /**
     * 兼容原有六轴 API。遇到数字轴或 AXIS_1 等动态轴名时会明确失败，
     * 调用方应改用 [parseAxisIds]。
     */
    fun parseAxes(response: String): List<PiAxis> {
        return parseAxisIds(response).map { axisId ->
            axisId.knownHexapodAxis
                ?: throw PiGcsParseException(
                    response,
                    "轴 ${axisId.value} 不是 X/Y/Z/U/V/W，请使用动态轴 API"
                )
        }
    }

    fun parseAxisValuePairs(response: String): List<AxisValue> {
        if (response.isBlank()) return emptyList()

        return axisValueRegex
            .findAll(response)
            .map { match ->
                val axis = match.groupValues[1]
                val valueText = match.groupValues[2]
                val value = valueText.toDoubleOrNull()
                    ?: throw PiGcsParseException(
                        response,
                        "无法解析 PI GCS 数值: $valueText"
                    )

                AxisValue(axis = axis, value = value)
            }
            .toList()
    }

    fun parsePlainDouble(response: String): Double {
        val text = response.trim()
        val match = plainNumberRegex.matchEntire(text)
            ?: throw PiGcsParseException(response, "无法解析 PI GCS 纯数字响应")

        return match.groupValues[1].toDoubleOrNull()
            ?: throw PiGcsParseException(response, "无法解析 PI GCS Double 数值")
    }

    fun parsePlainInt(response: String): Int {
        return response.trim().toIntOrNull()
            ?: throw PiGcsParseException(response, "无法解析 PI GCS Int 数值")
    }
}

/** PI GCS 返回中的轴值对。 */
data class AxisValue(
    val axis: String,
    val value: Double
)
