package org.jason.siph.domain.positioner

data class OpticalPose(
    val xUm: Double,
    val yUm: Double,
    val zUm: Double,
    val uDeg: Double,
    val vDeg: Double,
    val wDeg: Double
) {
    companion object {
        val ZERO = OpticalPose(
            xUm = 0.0,
            yUm = 0.0,
            zUm = 0.0,
            uDeg = 0.0,
            vDeg = 0.0,
            wDeg = 0.0
        )
    }
}

data class OpticalDelta(
    val dxUm: Double = 0.0,
    val dyUm: Double = 0.0,
    val dzUm: Double = 0.0,
    val duDeg: Double = 0.0,
    val dvDeg: Double = 0.0,
    val dwDeg: Double = 0.0
)

operator fun OpticalPose.plus(
    delta: OpticalDelta
): OpticalPose {
    return copy(
        xUm = xUm + delta.dxUm,
        yUm = yUm + delta.dyUm,
        zUm = zUm + delta.dzUm,
        uDeg = uDeg + delta.duDeg,
        vDeg = vDeg + delta.dvDeg,
        wDeg = wDeg + delta.dwDeg
    )
}