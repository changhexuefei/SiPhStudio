package org.jason.siph.domain.autonomy

import kotlinx.coroutines.flow.StateFlow

/** 光纤探针间隙、横向漂移和 Z 位移跟踪的厂商无关接口。 */
interface ProbeTrackingPort {
    val status: StateFlow<AutonomyCapabilityStatus>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun startTracking(
        reference: ProbeTrackingReference
    )

    suspend fun stopTracking()

    suspend fun snapshot(): ProbeTrackingSample
}
