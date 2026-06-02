package org.jason.pi.gcs.hexapod

/**
 * PI 六轴位置。
 *
 * 业务层统一使用：
 * - X/Y/Z：um
 * - U/V/W：deg
 *
 * 注意：
 * PI 控制器 GCS 命令中的线性单位通常可能是 mm。
 * 所以真正发命令之前，需要通过 PiHexapodUnitConfig 做单位转换。
 */
data class PiHexapodPose(
    val xUm: Double,
    val yUm: Double,
    val zUm: Double,
    val uDeg: Double,
    val vDeg: Double,
    val wDeg: Double
) {
    companion object {
        val ZERO = PiHexapodPose(
            xUm = 0.0,
            yUm = 0.0,
            zUm = 0.0,
            uDeg = 0.0,
            vDeg = 0.0,
            wDeg = 0.0
        )
    }
}

/**
 * PI 六轴增量。
 */
data class PiHexapodDelta(
    val dxUm: Double = 0.0,
    val dyUm: Double = 0.0,
    val dzUm: Double = 0.0,
    val duDeg: Double = 0.0,
    val dvDeg: Double = 0.0,
    val dwDeg: Double = 0.0
)

operator fun PiHexapodPose.plus(
    delta: PiHexapodDelta
): PiHexapodPose {
    return copy(
        xUm = xUm + delta.dxUm,
        yUm = yUm + delta.dyUm,
        zUm = zUm + delta.dzUm,
        uDeg = uDeg + delta.duDeg,
        vDeg = vDeg + delta.dvDeg,
        wDeg = wDeg + delta.dwDeg
    )
}