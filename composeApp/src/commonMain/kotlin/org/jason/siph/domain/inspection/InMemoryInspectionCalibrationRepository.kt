package org.jason.siph.domain.inspection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryInspectionCalibrationRepository(
    initialCameraCalibrations: List<CameraStageCalibration> = emptyList(),
    initialHeightProfiles: List<ProbeHeightProfile> = emptyList(),
    initialPivotCalibrations: List<PivotCalibrationResult> = emptyList(),
    initialTemperatureRecalibrations: List<TemperatureRecalibrationResult> = emptyList()
) : InspectionCalibrationRepository {

    private val mutex = Mutex()
    private val cameraCalibrations = initialCameraCalibrations.associateBy { it.id }.toMutableMap()
    private val heightProfiles = initialHeightProfiles.associateBy { it.id }.toMutableMap()
    private val pivotCalibrations = initialPivotCalibrations.associateBy { it.id }.toMutableMap()
    private val temperatureRecalibrations = initialTemperatureRecalibrations.associateBy { it.runId }.toMutableMap()

    override suspend fun listCameraCalibrations(): List<CameraStageCalibration> = mutex.withLock {
        cameraCalibrations.values.sortedByDescending { it.calibratedAtEpochMs }
    }

    override suspend fun findCameraCalibration(id: String): CameraStageCalibration? = mutex.withLock {
        cameraCalibrations[id]
    }

    override suspend fun saveCameraCalibration(calibration: CameraStageCalibration) {
        mutex.withLock { cameraCalibrations[calibration.id] = calibration }
    }

    override suspend fun listHeightProfiles(): List<ProbeHeightProfile> = mutex.withLock {
        heightProfiles.values.sortedByDescending { it.trainedAtEpochMs }
    }

    override suspend fun findHeightProfile(id: String): ProbeHeightProfile? = mutex.withLock {
        heightProfiles[id]
    }

    override suspend fun saveHeightProfile(profile: ProbeHeightProfile) {
        mutex.withLock { heightProfiles[profile.id] = profile }
    }

    override suspend fun listPivotCalibrations(): List<PivotCalibrationResult> = mutex.withLock {
        pivotCalibrations.values.sortedByDescending { it.calibratedAtEpochMs }
    }

    override suspend fun findPivotCalibration(id: String): PivotCalibrationResult? = mutex.withLock {
        pivotCalibrations[id]
    }

    override suspend fun savePivotCalibration(result: PivotCalibrationResult) {
        mutex.withLock { pivotCalibrations[result.id] = result }
    }

    override suspend fun listTemperatureRecalibrations(limit: Int): List<TemperatureRecalibrationResult> {
        require(limit > 0)
        return mutex.withLock {
            temperatureRecalibrations.values.sortedByDescending { it.finishedAtEpochMs }.take(limit)
        }
    }

    override suspend fun findTemperatureRecalibration(runId: String): TemperatureRecalibrationResult? =
        mutex.withLock { temperatureRecalibrations[runId] }

    override suspend fun saveTemperatureRecalibration(result: TemperatureRecalibrationResult) {
        mutex.withLock { temperatureRecalibrations[result.runId] = result }
    }
}
