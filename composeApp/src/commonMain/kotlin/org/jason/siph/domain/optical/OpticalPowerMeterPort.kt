package org.jason.siph.domain.optical

interface OpticalPowerMeterPort {

    suspend fun connect()

    suspend fun disconnect()

    suspend fun identify(): String

    suspend fun setWavelengthNm(
        wavelengthNm: Double,
        channel: Int = 1
    )

    suspend fun readPowerDbm(
        channel: Int = 1
    ): Double
}