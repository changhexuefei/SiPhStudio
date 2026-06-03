package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis
import java.util.Locale

object GcsResponseParser {

    private const val NUMBER_PATTERN: String =
        """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"""

    // 预编译正则，避免重复创建
    private val plainNumberRegex = Regex(
        pattern = """^\s*($NUMBER_PATTERN)\s*$"""
    )

    // 优化后的正则：增强了对换行符 \n 和 空格 \s 的多行兼容性
    private val axisValueRegex = Regex(
        pattern = """([A-Za-z][A-Za-z0-9_]*)\s*(?:=|:|\s)\s*($NUMBER_PATTERN)"""
    )

    fun parseErrorCode(response: String): Int {
        return response.trim().toIntOrNull()
            ?: throw PiGcsParseException(response, "无法解析 PI GCS 错误码")
    }

    /**
     * 🚀 优化：单轴 Double 解析，优先采用低开销的字符串直接切分，不匹配时再降级用正则
     */
    fun parseAxisDouble(response: String, expectedAxis: PiAxis): Double {
        val trimmed = response.trim()

        // 性能优化路径：针对最常见的 "X=1.234" 或 "X 1.234" 格式，直接走非正则的高速切分
        val delimiterIndex = trimmed.indexOfAny(charArrayOf('=', ' ', '\t'))
        if (delimiterIndex > 0) {
            val axisCode = trimmed.substring(0, delimiterIndex).trim()
            if (axisCode.equals(expectedAxis.code, ignoreCase = true)) {
                val valueStr = trimmed.substring(delimiterIndex + 1).trim()
                valueStr.toDoubleOrNull()?.let { return it }
            }
        }

        // 降级路径：复杂格式（如带有其他杂质字符串）走正则解析
        val pairs = parseAxisValuePairs(trimmed)
        val match = pairs.firstOrNull {
            it.axis.equals(expectedAxis.code, ignoreCase = true)
        } ?: throw PiGcsParseException(response, "PI GCS 响应中未找到轴 ${expectedAxis.code}")

        return match.value
    }

    fun parseAxisInt(response: String, expectedAxis: PiAxis): Int {
        return parseAxisDouble(response, expectedAxis).toInt()
    }

    fun parseAxisBoolean(response: String, expectedAxis: PiAxis): Boolean {
        return parseAxisInt(response, expectedAxis) != 0
    }

    /**
     * 🚀 优化：多轴 Map 解析
     * 完美支持单行多轴（X=1 Y=2）和 Ktor 批量拉取到的多行多轴响应（X=1\nY=2）
     */
    fun parseAxisDoubleMap(response: String, expectedAxes: List<PiAxis>): Map<PiAxis, Double> {
        require(expectedAxes.isNotEmpty()) { "expectedAxes 不能为空" }

        val pairs = parseAxisValuePairs(response)

        if (pairs.isEmpty()) {
            if (expectedAxes.size == 1) {
                return mapOf(expectedAxes.first() to parsePlainDouble(response))
            }
            throw PiGcsParseException(response, "无法解析多轴 PI GCS 响应")
        }

        // 优化点：使用容量确定的 HashMap，减少扩容开销
        val rawMap = HashMap<String, Double>(pairs.size * 2)
        for (pair in pairs) {
            rawMap[pair.axis.uppercase(Locale.ROOT)] = pair.value
        }

        val result = HashMap<PiAxis, Double>(expectedAxes.size * 2)
        for (axis in expectedAxes) {
            val value = rawMap[axis.code.uppercase(Locale.ROOT)]
                ?: throw PiGcsParseException(response, "PI GCS 响应中缺少轴 ${axis.code}")
            result[axis] = value
        }

        return result
    }

    fun parseAxisIntMap(response: String, expectedAxes: List<PiAxis>): Map<PiAxis, Int> {
        return parseAxisDoubleMap(response, expectedAxes).mapValues { it.value.toInt() }
    }

    fun parseAxisBooleanMap(response: String, expectedAxes: List<PiAxis>): Map<PiAxis, Boolean> {
        return parseAxisIntMap(response, expectedAxes).mapValues { it.value != 0 }
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
        }
    }

    /**
     * 🚀 优化：解析 axis-value 键值对
     * 增强多行扫描效率，避免创建多余的 String 副本
     */
    fun parseAxisValuePairs(response: String): List<AxisValue> {
        if (response.isBlank()) return emptyList()

        // 使用 Sequence 转换为 List，避免正则查找过程中的多重中间集合拷贝
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
/**
 * PI GCS 返回中的轴值对。
 */
data class AxisValue(
    val axis: String,
    val value: Double
)