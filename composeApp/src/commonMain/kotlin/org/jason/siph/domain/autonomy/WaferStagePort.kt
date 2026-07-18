package org.jason.siph.domain.autonomy

import kotlinx.coroutines.flow.StateFlow

/** 晶圆台、晶圆图和 Sub-Die 导航的厂商无关接口。 */
interface WaferStagePort {
    val status: StateFlow<AutonomyCapabilityStatus>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun loadMap(
        definition: WaferMapDefinition
    )

    suspend fun snapshot(): WaferStageSnapshot

    suspend fun moveToSite(
        site: WaferSite,
        wait: Boolean = true
    )

    suspend fun trainMeasurementPosition(
        name: String
    ): MeasurementPositionTrainingResult
}
