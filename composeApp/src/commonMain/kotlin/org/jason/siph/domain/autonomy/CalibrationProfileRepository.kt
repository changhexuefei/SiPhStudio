package org.jason.siph.domain.autonomy

import kotlinx.coroutines.flow.StateFlow

/** 经过设备和夹具身份绑定的校准配置存储接口。 */
interface CalibrationProfileRepository {
    val activeProfile: StateFlow<CalibrationProfile?>

    suspend fun listProfiles(): List<CalibrationProfile>

    suspend fun findProfile(id: String): CalibrationProfile?

    suspend fun saveProfile(profile: CalibrationProfile)

    suspend fun deleteProfile(id: String)

    suspend fun activateProfile(id: String)

    suspend fun clearActiveProfile()
}
