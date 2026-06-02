package org.jason.siph.domain.positioner


interface PivotAwareOpticalPositionerPort : OpticalPositionerPort {

    suspend fun moveByAroundPivot(
        delta: OpticalDelta,
        pivot: VirtualPivotPoint,
        wait: Boolean = true
    )
}