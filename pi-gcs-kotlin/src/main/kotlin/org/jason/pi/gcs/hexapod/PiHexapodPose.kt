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

/**
 * PI 控制器命令单位配置。
 *
 * 你的 UI / 算法建议始终使用 um。
 * 发给 PI 控制器时，再转换成控制器实际单位。
 */
data class PiHexapodUnitConfig(
    val linearCommandUnit: LinearCommandUnit = LinearCommandUnit.Millimeter,
    val angularCommandUnit: AngularCommandUnit = AngularCommandUnit.Degree
) {

    fun toCommandValues(
        pose: PiHexapodPose
    ): Map<PiAxis, Double> {
        return mapOf(
            PiAxis.X to linearToCommand(pose.xUm),
            PiAxis.Y to linearToCommand(pose.yUm),
            PiAxis.Z to linearToCommand(pose.zUm),
            PiAxis.U to angularToCommand(pose.uDeg),
            PiAxis.V to angularToCommand(pose.vDeg),
            PiAxis.W to angularToCommand(pose.wDeg)
        )
    }

    fun toCommandDeltas(
        delta: PiHexapodDelta
    ): Map<PiAxis, Double> {
        return mapOf(
            PiAxis.X to linearToCommand(delta.dxUm),
            PiAxis.Y to linearToCommand(delta.dyUm),
            PiAxis.Z to linearToCommand(delta.dzUm),
            PiAxis.U to angularToCommand(delta.duDeg),
            PiAxis.V to angularToCommand(delta.dvDeg),
            PiAxis.W to angularToCommand(delta.dwDeg)
        )
    }

    fun fromCommandValues(
        values: Map<PiAxis, Double>
    ): PiHexapodPose {
        return PiHexapodPose(
            xUm = commandToLinearUm(values.getValue(PiAxis.X)),
            yUm = commandToLinearUm(values.getValue(PiAxis.Y)),
            zUm = commandToLinearUm(values.getValue(PiAxis.Z)),
            uDeg = commandToAngularDeg(values.getValue(PiAxis.U)),
            vDeg = commandToAngularDeg(values.getValue(PiAxis.V)),
            wDeg = commandToAngularDeg(values.getValue(PiAxis.W))
        )
    }

    private fun linearToCommand(valueUm: Double): Double {
        return when (linearCommandUnit) {
            LinearCommandUnit.Micrometer -> valueUm
            LinearCommandUnit.Millimeter -> valueUm / 1000.0
        }
    }

    private fun commandToLinearUm(value: Double): Double {
        return when (linearCommandUnit) {
            LinearCommandUnit.Micrometer -> value
            LinearCommandUnit.Millimeter -> value * 1000.0
        }
    }

    private fun angularToCommand(valueDeg: Double): Double {
        return when (angularCommandUnit) {
            AngularCommandUnit.Degree -> valueDeg
        }
    }

    private fun commandToAngularDeg(value: Double): Double {
        return when (angularCommandUnit) {
            AngularCommandUnit.Degree -> value
        }
    }
}

enum class LinearCommandUnit {
    Micrometer,
    Millimeter
}

enum class AngularCommandUnit {
    Degree
}