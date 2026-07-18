package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * PI 控制器轴标识。
 *
 * 与只覆盖 X/Y/Z/U/V/W 的 [PiAxis] 不同，这个类型同时支持：
 * - GCS 2.0 数字轴，例如 `1`、`2`；
 * - GCS 3.0 轴名，例如 `AXIS_1`；
 * - 厂商配置中的自定义轴名。
 *
 * 轴标识会经过严格校验，避免空白符和控制字符进入 GCS 命令。
 */
@JvmInline
value class PiAxisId private constructor(
    val value: String
) {

    val knownHexapodAxis: PiAxis?
        get() = PiAxis.entries.firstOrNull {
            it.code.equals(value, ignoreCase = true)
        }

    override fun toString(): String = value

    companion object {

        private val validAxisId = Regex("[A-Za-z0-9_.-]+")

        fun of(raw: String): PiAxisId {
            val normalized = raw.trim()

            require(normalized.isNotEmpty()) {
                "PI axis id 不能为空"
            }
            require(validAxisId.matches(normalized)) {
                "非法 PI axis id: '$raw'"
            }

            return PiAxisId(normalized)
        }

        fun from(axis: PiAxis): PiAxisId = of(axis.code)
    }
}

fun PiAxis.toAxisId(): PiAxisId = PiAxisId.from(this)
