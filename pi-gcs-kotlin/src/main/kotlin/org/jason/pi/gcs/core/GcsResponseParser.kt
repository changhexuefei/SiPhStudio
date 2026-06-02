package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * PI GCS 响应解析器。
 *
 * 这一层只负责把 PI 控制器返回的字符串解析成 Kotlin 类型。
 *
 * 常见响应格式：
 * - X=1.234
 * - X 1.234
 * - X\t1.234
 * - X=1.0 Y=2.0 Z=3.0
 * - 0
 * - X Y Z U V W
 */
object GcsResponseParser {

    private const val NUMBER_PATTERN: String =
        """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"""

    private val plainNumberRegex = Regex(
        pattern = """^\s*($NUMBER_PATTERN)\s*$"""
    )

    private val axisValueRegex = Regex(
        pattern = """([A-Za-z][A-Za-z0-9_]*)\s*(?:=|:|\s)\s*($NUMBER_PATTERN)"""
    )

    /**
     * 解析 ERR? 返回值。
     *
     * 常见返回：
     * - 0
     * - -1
     */
    fun parseErrorCode(
        response: String
    ): Int {
        val text = response.trim()

        return text.toIntOrNull()
            ?: throw PiGcsParseException(
                response = response,
                message = "无法解析 PI GCS 错误码"
            )
    }

    /**
     * 解析单轴 Double 值。
     *
     * 支持：
     * - X=1.234
     * - X 1.234
     * - 1.234
     */
    fun parseAxisDouble(
        response: String,
        expectedAxis: PiAxis
    ): Double {
        val pairs = parseAxisValuePairs(response)

        if (pairs.isNotEmpty()) {
            val match = pairs.firstOrNull {
                it.axis.equals(expectedAxis.code, ignoreCase = true)
            } ?: throw PiGcsParseException(
                response = response,
                message = "PI GCS 响应中未找到轴 ${expectedAxis.code}"
            )

            return match.value
        }

        return parsePlainDouble(response)
    }

    /**
     * 解析单轴 Int 值。
     *
     * 例如：
     * - ONT? X -> X=1
     * - SVO? X -> X=1
     */
    fun parseAxisInt(
        response: String,
        expectedAxis: PiAxis
    ): Int {
        val value = parseAxisDouble(
            response = response,
            expectedAxis = expectedAxis
        )

        return value.toInt()
    }

    /**
     * 解析单轴 Boolean 值。
     *
     * 约定：
     * - 0 = false
     * - 非 0 = true
     */
    fun parseAxisBoolean(
        response: String,
        expectedAxis: PiAxis
    ): Boolean {
        return parseAxisInt(
            response = response,
            expectedAxis = expectedAxis
        ) != 0
    }

    /**
     * 解析多轴 Double Map。
     *
     * 支持：
     * - X=1.0 Y=2.0 Z=3.0
     * - X 1.0
     *   Y 2.0
     *   Z 3.0
     */
    fun parseAxisDoubleMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Double> {
        require(expectedAxes.isNotEmpty()) {
            "expectedAxes 不能为空"
        }

        val pairs = parseAxisValuePairs(response)

        if (pairs.isEmpty()) {
            if (expectedAxes.size == 1) {
                return mapOf(
                    expectedAxes.first() to parsePlainDouble(response)
                )
            }

            throw PiGcsParseException(
                response = response,
                message = "无法解析多轴 PI GCS 响应"
            )
        }

        val rawMap = pairs.associateBy(
            keySelector = { it.axis.uppercase() },
            valueTransform = { it.value }
        )

        return expectedAxes.associateWith { axis ->
            rawMap[axis.code.uppercase()]
                ?: throw PiGcsParseException(
                    response = response,
                    message = "PI GCS 响应中缺少轴 ${axis.code}"
                )
        }
    }

    /**
     * 解析多轴 Int Map。
     */
    fun parseAxisIntMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Int> {
        return parseAxisDoubleMap(
            response = response,
            expectedAxes = expectedAxes
        ).mapValues { (_, value) ->
            value.toInt()
        }
    }

    /**
     * 解析多轴 Boolean Map。
     */
    fun parseAxisBooleanMap(
        response: String,
        expectedAxes: List<PiAxis>
    ): Map<PiAxis, Boolean> {
        return parseAxisIntMap(
            response = response,
            expectedAxes = expectedAxes
        ).mapValues { (_, value) ->
            value != 0
        }
    }

    /**
     * 解析轴列表。
     *
     * 常见用途：
     * - SAI?
     *
     * 支持：
     * - X Y Z U V W
     * - X,Y,Z,U,V,W
     * - X;Y;Z;U;V;W
     * - X
     *   Y
     *   Z
     */
    fun parseAxes(
        response: String
    ): List<PiAxis> {
        val tokens = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(",", " ")
            .replace(";", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            throw PiGcsParseException(
                response = response,
                message = "无法解析 PI GCS 轴列表"
            )
        }

        return tokens.map { token ->
            runCatching {
                PiAxis.fromCode(token)
            }.getOrElse {
                throw PiGcsParseException(
                    response = response,
                    message = "未知 PI GCS 轴名: $token"
                )
            }
        }
    }

    /**
     * 尝试解析所有 axis-value pair。
     */
    fun parseAxisValuePairs(
        response: String
    ): List<AxisValue> {
        val normalized = response
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (normalized.isBlank()) {
            return emptyList()
        }

        return axisValueRegex
            .findAll(normalized)
            .map { match ->
                val axis = match.groupValues[1].trim()
                val valueText = match.groupValues[2].trim()

                val value = valueText.toDoubleOrNull()
                    ?: throw PiGcsParseException(
                        response = response,
                        message = "无法解析 PI GCS 数值: $valueText"
                    )

                AxisValue(
                    axis = axis,
                    value = value
                )
            }
            .toList()
    }

    /**
     * 解析纯数字。
     *
     * 例如：
     * - 0
     * - 1.234
     * - -1
     */
    fun parsePlainDouble(
        response: String
    ): Double {
        val text = response.trim()

        val match = plainNumberRegex.matchEntire(text)
            ?: throw PiGcsParseException(
                response = response,
                message = "无法解析 PI GCS 纯数字响应"
            )

        return match.groupValues[1].toDoubleOrNull()
            ?: throw PiGcsParseException(
                response = response,
                message = "无法解析 PI GCS Double 数值"
            )
    }

    /**
     * 解析纯 Int。
     */
    fun parsePlainInt(
        response: String
    ): Int {
        val text = response.trim()

        return text.toIntOrNull()
            ?: throw PiGcsParseException(
                response = response,
                message = "无法解析 PI GCS Int 数值"
            )
    }
}

/**
 * PI GCS 返回中的轴值对。
 */
data class AxisValue(
    val axis: String,
    val value: Double
)