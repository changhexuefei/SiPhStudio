package org.jason.pi.gcs.hexapod

/**
 * PI Hexapod 线性轴命令单位。
 *
 * 注意：
 * SiPhTools / 业务层建议统一使用 μm。
 * 但 PI GCS 控制器实际命令单位可能是 mm。
 */
enum class LinearCommandUnit {

    /**
     * GCS 命令层线性单位为 μm。
     *
     * 业务层 10 μm -> GCS 10
     */
    Micrometer,

    /**
     * GCS 命令层线性单位为 mm。
     *
     * 业务层 10 μm -> GCS 0.010
     */
    Millimeter
}

/**
 * PI Hexapod 角度轴命令单位。
 *
 * 第一版只支持 degree。
 */
enum class AngularCommandUnit {

    /**
     * GCS 命令层角度单位为 degree。
     */
    Degree
}

/**
 * PI Hexapod 单位配置。
 *
 * 业务层统一使用：
 * - X/Y/Z: μm
 * - U/V/W: deg
 *
 * 命令层根据 PI 控制器实际配置转换：
 * - X/Y/Z: mm 或 μm
 * - U/V/W: deg
 */
data class PiHexapodUnitConfig(
    val linearCommandUnit: LinearCommandUnit = LinearCommandUnit.Millimeter,
    val angularCommandUnit: AngularCommandUnit = AngularCommandUnit.Degree
) {

    /**
     * 将业务层绝对位置转换为 GCS 命令层绝对位置。
     */
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

    /**
     * 将业务层相对增量转换为 GCS 命令层相对增量。
     */
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

    /**
     * 将 GCS 命令层位置转换为业务层位置。
     */
    fun fromCommandValues(
        values: Map<PiAxis, Double>
    ): PiHexapodPose {
        return PiHexapodPose(
            xUm = commandToLinearUm(values.requireAxis(PiAxis.X)),
            yUm = commandToLinearUm(values.requireAxis(PiAxis.Y)),
            zUm = commandToLinearUm(values.requireAxis(PiAxis.Z)),
            uDeg = commandToAngularDeg(values.requireAxis(PiAxis.U)),
            vDeg = commandToAngularDeg(values.requireAxis(PiAxis.V)),
            wDeg = commandToAngularDeg(values.requireAxis(PiAxis.W))
        )
    }

    /**
     * 将 GCS 命令层行程范围转换为业务层行程范围。
     *
     * 输入通常来自：
     * - TMN?
     * - TMX?
     */
    fun fromCommandTravelRanges(
        commandRanges: Map<PiAxis, ClosedFloatingPointRange<Double>>
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return commandRanges.mapValues { (axis, range) ->
            when {
                axis.isLinear -> {
                    commandToLinearUm(range.start)..commandToLinearUm(range.endInclusive)
                }

                axis.isAngular -> {
                    commandToAngularDeg(range.start)..commandToAngularDeg(range.endInclusive)
                }

                else -> {
                    range
                }
            }
        }
    }

    /**
     * 将业务层位置转换为指定轴的 GCS 命令值。
     */
    fun toCommandValue(
        axis: PiAxis,
        pose: PiHexapodPose
    ): Double {
        return when (axis) {
            PiAxis.X -> linearToCommand(pose.xUm)
            PiAxis.Y -> linearToCommand(pose.yUm)
            PiAxis.Z -> linearToCommand(pose.zUm)
            PiAxis.U -> angularToCommand(pose.uDeg)
            PiAxis.V -> angularToCommand(pose.vDeg)
            PiAxis.W -> angularToCommand(pose.wDeg)
        }
    }

    /**
     * 将业务层增量转换为指定轴的 GCS 命令增量。
     */
    fun toCommandDelta(
        axis: PiAxis,
        delta: PiHexapodDelta
    ): Double {
        return when (axis) {
            PiAxis.X -> linearToCommand(delta.dxUm)
            PiAxis.Y -> linearToCommand(delta.dyUm)
            PiAxis.Z -> linearToCommand(delta.dzUm)
            PiAxis.U -> angularToCommand(delta.duDeg)
            PiAxis.V -> angularToCommand(delta.dvDeg)
            PiAxis.W -> angularToCommand(delta.dwDeg)
        }
    }

    /**
     * 线性业务单位 μm -> GCS 命令单位。
     */
    fun linearToCommand(
        valueUm: Double
    ): Double {
        return when (linearCommandUnit) {
            LinearCommandUnit.Micrometer -> valueUm
            LinearCommandUnit.Millimeter -> valueUm / 1_000.0
        }
    }

    /**
     * GCS 命令线性单位 -> 业务单位 μm。
     */
    fun commandToLinearUm(
        value: Double
    ): Double {
        return when (linearCommandUnit) {
            LinearCommandUnit.Micrometer -> value
            LinearCommandUnit.Millimeter -> value * 1_000.0
        }
    }

    /**
     * 角度业务单位 deg -> GCS 命令角度单位。
     */
    fun angularToCommand(
        valueDeg: Double
    ): Double {
        return when (angularCommandUnit) {
            AngularCommandUnit.Degree -> valueDeg
        }
    }

    /**
     * GCS 命令角度单位 -> 业务角度单位 deg。
     */
    fun commandToAngularDeg(
        value: Double
    ): Double {
        return when (angularCommandUnit) {
            AngularCommandUnit.Degree -> value
        }
    }

    companion object {

        /**
         * 常用配置：
         * PI GCS 线性轴命令单位为 mm，角度轴为 deg。
         */
        val MillimeterDegree: PiHexapodUnitConfig =
            PiHexapodUnitConfig(
                linearCommandUnit = LinearCommandUnit.Millimeter,
                angularCommandUnit = AngularCommandUnit.Degree
            )

        /**
         * 如果你的 PI 控制器线性单位已经配置为 μm，可以用这个。
         */
        val MicrometerDegree: PiHexapodUnitConfig =
            PiHexapodUnitConfig(
                linearCommandUnit = LinearCommandUnit.Micrometer,
                angularCommandUnit = AngularCommandUnit.Degree
            )
    }
}

private fun Map<PiAxis, Double>.requireAxis(
    axis: PiAxis
): Double {
    return this[axis]
        ?: error("缺少 PI 轴 ${axis.code} 的位置值")
}