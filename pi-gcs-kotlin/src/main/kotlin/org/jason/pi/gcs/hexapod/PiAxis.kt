package org.jason.pi.gcs.hexapod

/**
 * PI 六轴轴名。
 *
 * 你的控制器中显示为：
 * X / Y / Z / U / V / W
 */
enum class PiAxis(
    val code: String
) {
    X("X"),
    Y("Y"),
    Z("Z"),
    U("U"),
    V("V"),
    W("W");

    companion object {

        val HEXAPOD_AXES: List<PiAxis> =
            listOf(X, Y, Z, U, V, W)

        fun fromCode(code: String): PiAxis {
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true)
            } ?: error("未知 PI 轴名: $code")
        }
    }
}