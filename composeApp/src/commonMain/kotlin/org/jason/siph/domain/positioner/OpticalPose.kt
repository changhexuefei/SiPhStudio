package org.jason.siph.domain.positioner

import kotlinx.serialization.Serializable

@Serializable
data class OpticalPose(
    val xUm: Double,
    val yUm: Double,
    val zUm: Double,
    val uDeg: Double,
    val vDeg: Double,
    val wDeg: Double
) {
    init {
        require(xUm.isFinite()) { "xUm must be finite" }
        require(yUm.isFinite()) { "yUm must be finite" }
        require(zUm.isFinite()) { "zUm must be finite" }
        require(uDeg.isFinite()) { "uDeg must be finite" }
        require(vDeg.isFinite()) { "vDeg must be finite" }
        require(wDeg.isFinite()) { "wDeg must be finite" }
    }

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

@Serializable
data class OpticalDelta(
    val dxUm: Double = 0.0,
    val dyUm: Double = 0.0,
    val dzUm: Double = 0.0,
    val duDeg: Double = 0.0,
    val dvDeg: Double = 0.0,
    val dwDeg: Double = 0.0
) {
    init {
        require(dxUm.isFinite()) { "dxUm must be finite" }
        require(dyUm.isFinite()) { "dyUm must be finite" }
        require(dzUm.isFinite()) { "dzUm must be finite" }
        require(duDeg.isFinite()) { "duDeg must be finite" }
        require(dvDeg.isFinite()) { "dvDeg must be finite" }
        require(dwDeg.isFinite()) { "dwDeg must be finite" }
    }
}

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
