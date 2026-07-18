package org.jason.siph.domain.autonomy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun unavailableStatus(name: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.NotConfigured,
    detail = "$name adapter is not configured"
)

class UnavailableVisionAlignmentPort : VisionAlignmentPort {
    private val mutableStatus = MutableStateFlow(unavailableStatus("Vision alignment"))
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    override suspend fun connect(): Nothing = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun captureFrame(): Nothing = unavailable()
    override suspend fun locateTarget(request: VisionTargetRequest): Nothing = unavailable()
    override suspend fun calibrate(request: VisionCalibrationRequest): Nothing = unavailable()

    private fun unavailable(): Nothing =
        throw AutonomyCapabilityUnavailableException("Vision alignment")
}

class UnavailableWaferStagePort : WaferStagePort {
    private val mutableStatus = MutableStateFlow(unavailableStatus("Wafer stage"))
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    override suspend fun connect(): Nothing = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun loadMap(definition: WaferMapDefinition): Nothing = unavailable()
    override suspend fun snapshot(): Nothing = unavailable()
    override suspend fun moveToSite(site: WaferSite, wait: Boolean): Nothing = unavailable()
    override suspend fun trainMeasurementPosition(name: String): Nothing = unavailable()

    private fun unavailable(): Nothing =
        throw AutonomyCapabilityUnavailableException("Wafer stage")
}

class UnavailableProbeTrackingPort : ProbeTrackingPort {
    private val mutableStatus = MutableStateFlow(unavailableStatus("Probe tracking"))
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    override suspend fun connect(): Nothing = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun startTracking(reference: ProbeTrackingReference): Nothing = unavailable()
    override suspend fun stopTracking() = Unit
    override suspend fun snapshot(): Nothing = unavailable()

    private fun unavailable(): Nothing =
        throw AutonomyCapabilityUnavailableException("Probe tracking")
}

/**
 * 公共源码中的默认配置仓库。
 * 真实桌面应用可在 JVM 模块中用 JSON/数据库实现覆盖。
 */
class InMemoryCalibrationProfileRepository(
    initialProfiles: List<CalibrationProfile> = emptyList(),
    initialActiveProfileId: String? = null
) : CalibrationProfileRepository {
    private val mutex = Mutex()
    private val profiles = initialProfiles.associateBy { it.id }.toMutableMap()
    private val mutableActiveProfile = MutableStateFlow(
        initialActiveProfileId?.let(profiles::get)
    )

    override val activeProfile: StateFlow<CalibrationProfile?> =
        mutableActiveProfile.asStateFlow()

    override suspend fun listProfiles(): List<CalibrationProfile> = mutex.withLock {
        profiles.values.sortedBy { it.name.lowercase() }
    }

    override suspend fun findProfile(id: String): CalibrationProfile? = mutex.withLock {
        profiles[id]
    }

    override suspend fun saveProfile(profile: CalibrationProfile) {
        mutex.withLock {
            profiles[profile.id] = profile
            if (mutableActiveProfile.value?.id == profile.id) {
                mutableActiveProfile.value = profile
            }
        }
    }

    override suspend fun deleteProfile(id: String) {
        mutex.withLock {
            profiles.remove(id)
            if (mutableActiveProfile.value?.id == id) {
                mutableActiveProfile.value = null
            }
        }
    }

    override suspend fun activateProfile(id: String) {
        mutex.withLock {
            mutableActiveProfile.value = profiles[id]
                ?: error("Calibration profile not found: $id")
        }
    }

    override suspend fun clearActiveProfile() {
        mutableActiveProfile.value = null
    }
}
