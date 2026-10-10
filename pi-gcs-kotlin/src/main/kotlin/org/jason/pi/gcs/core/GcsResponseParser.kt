package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

object GcsResponseParser {

    private const val NUMBER_PATTERN: String =
        """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"""

    private val plainNumberRegex = Regex(
        pattern = """^\s*($NUMBER_PATTERN)\s*$"""
    )

    private val axisValueRegex = Regex(
        pattern = """([A-Za-z][A-Za-z0-9_]*)\s*(?:=|:|\s)\s*($NUMBER_PATTERN)"""
    )

    fun parseErrorCode(response: String): Int {
        return response.trim().toIntOrNull()
            ?: throw PiGcsParseException(response, "无法解析 PI GCS 错误码")
    }

    /**
     * Parses a single-axis response.
     *
     * Supported examples:
     * - X=1.234
     * - X:1.234
     * - X 1.234
     * - 1.234
     */
    fun parseAxisDouble(
        response: String,
        expectedAxis: PiAxis
    ): Double {
        val trimmed = response.trim()

        plainNumberRegex.matchEntire(trimmed)?.let {
            return parsePlainDouble(trimmed)
        }

        val pairs = parseAxisValuePairs(trimmed)
        val match = pairs.firstOrNull {
            it.axis.equals(expectedAxis.code, ignoreCase = true)
        } ?: throw PiGcsParseException(
            response,
            "PI GCS 响应中未找到轴 ${expectedAxis.code}"
        )

        return match.value
    }

    fun parseAxisInt(
        response: String,
        expectedAxis: PiAxis
    ): Int {
        val value = parseAxisDouble(response, expectedAxis)
        val intValue = value.toInt()

        if (value != intValue.toDouble()) {
            throw PiGcsParseException(
                response,
                "轴 ${expectedAxis.code} 的 PI GCS 响应不是整数: $value"
            )
        }

        return intValue
    }

    fun parseAxisBoolean(
        response: String,
        expectedAxis: PiAxis
    ): Boolean {
        return parseAxisInt(response, expectedAxis) != 0
    }

    fun parseAxisDoubleMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Double> {
        val axes = expectedAxes.distinct()
        require(axes.isNotEmpty()) { "expectedAxes 不能为空" }

        val pairs = parseAxisValuePairs(response)

        if (pairs.isEmpty()) {
            if (axes.size == 1) {
                return linkedMapOf(axes.first() to parsePlainDouble(response))
            }
            throw PiGcsParseException(response, "无法解析多轴 PI GCS 响应")
        }

        val rawMap = HashMap<String, Double>(pairs.size * 2)
        for (pair in pairs) {
            rawMap[pair.axis.uppercase(Locale.ROOT)] = pair.value
        }

        val result = LinkedHashMap<PiAxis, Double>(axes.size)
        for (axis in axes) {
            val value = rawMap[axis.code.uppercase(Locale.ROOT)]
                ?: throw PiGcsParseException(response, "PI GCS 响应中缺少轴 ${axis.code}")
            result[axis] = value
        }

        return result
    }

    fun parseAxisIntMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Int> {
        val doubleValues = parseAxisDoubleMap(response, expectedAxes)
        val result = LinkedHashMap<PiAxis, Int>(doubleValues.size)

        for ((axis, value) in doubleValues) {
            val intValue = value.toInt()
            if (value != intValue.toDouble()) {
                throw PiGcsParseException(
                    response,
                    "轴 ${axis.code} 的 PI GCS 响应不是整数: $value"
                )
            }
            result[axis] = intValue
        }

        return result
    }

    fun parseAxisBooleanMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Boolean> {
        val intValues = parseAxisIntMap(response, expectedAxes)
        val result = LinkedHashMap<PiAxis, Boolean>(intValues.size)
        for ((axis, value) in intValues) {
            result[axis] = value != 0
        }
        return result
    }

    /**
     * Returns true after all expected axis identifiers have appeared in the
     * accumulated response. It is intentionally non-throwing so it can be used
     * as the completion predicate while response lines are still arriving.
     */
    fun containsAllAxes(
        response: String,
        expectedAxes: Collection<PiAxis>
    ): Boolean {
        val axes = expectedAxes.distinct()
        if (axes.isEmpty()) return true

        val found = parseAxisValuePairs(response)
            .asSequence()
            .map { it.axis.uppercase(Locale.ROOT) }
            .toHashSet()

        return axes.all { axis ->
            axis.code.uppercase(Locale.ROOT) in found
        }
    }

    fun parseAxes(response: String): List<PiAxis> {
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

        return tokens.map { token ->
            runCatching {
                PiAxis.fromCode(token)
            }.getOrElse {
                throw PiGcsParseException(response, "未知 PI GCS 轴名: $token")
            }
        }.distinct()
    }

    fun parseAxisValuePairs(response: String): List<AxisValue> {
        if (response.isBlank()) return emptyList()

        return axisValueRegex
            .findAll(response)
            .map { match ->
                val axis = match.groupValues[1]
                val valueText = match.groupValues[2]
                val value = valueText.toDoubleOrNull()
                    ?: throw PiGcsParseException(response, "无法解析 PI GCS 数值: $valueText")

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

data class AxisValue(
    val axis: String,
    val value: Double
)
