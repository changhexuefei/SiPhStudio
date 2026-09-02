package org.jason.siph.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.inspection.CameraStageCalibration
import org.jason.siph.domain.inspection.InspectionCalibrationRepository
import org.jason.siph.domain.inspection.PivotCalibrationResult
import org.jason.siph.domain.inspection.ProbeHeightProfile
import org.jason.siph.domain.inspection.TemperatureRecalibrationResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class JvmJsonInspectionCalibrationRepository(
    private val databasePath: Path,
    private val json: Json = defaultInspectionJson()
) : InspectionCalibrationRepository {
    private val mutex = Mutex()
    private var database = loadDatabase()

    override suspend fun listCameraCalibrations(): List<CameraStageCalibration> = mutex.withLock {
        database.cameraCalibrations.sortedByDescending { it.calibratedAtEpochMs }
    }

    override suspend fun findCameraCalibration(id: String): CameraStageCalibration? = mutex.withLock {
        database.cameraCalibrations.firstOrNull { it.id == id }
    }

    override suspend fun saveCameraCalibration(calibration: CameraStageCalibration) = mutate {
        copy(cameraCalibrations = cameraCalibrations.replaceBy(calibration, CameraStageCalibration::id))
    }

    override suspend fun listHeightProfiles(): List<ProbeHeightProfile> = mutex.withLock {
        database.heightProfiles.sortedByDescending { it.trainedAtEpochMs }
    }

    override suspend fun findHeightProfile(id: String): ProbeHeightProfile? = mutex.withLock {
        database.heightProfiles.firstOrNull { it.id == id }
    }

    override suspend fun saveHeightProfile(profile: ProbeHeightProfile) = mutate {
        copy(heightProfiles = heightProfiles.replaceBy(profile, ProbeHeightProfile::id))
    }

    override suspend fun listPivotCalibrations(): List<PivotCalibrationResult> = mutex.withLock {
        database.pivotCalibrations.sortedByDescending { it.calibratedAtEpochMs }
    }

    override suspend fun findPivotCalibration(id: String): PivotCalibrationResult? = mutex.withLock {
        database.pivotCalibrations.firstOrNull { it.id == id }
    }

    override suspend fun savePivotCalibration(result: PivotCalibrationResult) = mutate {
        copy(pivotCalibrations = pivotCalibrations.replaceBy(result, PivotCalibrationResult::id))
    }

    override suspend fun listTemperatureRecalibrations(limit: Int): List<TemperatureRecalibrationResult> {
        require(limit > 0)
        return mutex.withLock {
            database.temperatureRecalibrations.sortedByDescending { it.finishedAtEpochMs }.take(limit)
        }
    }

    override suspend fun findTemperatureRecalibration(runId: String): TemperatureRecalibrationResult? =
        mutex.withLock { database.temperatureRecalibrations.firstOrNull { it.runId == runId } }

    override suspend fun saveTemperatureRecalibration(result: TemperatureRecalibrationResult) = mutate {
        copy(
            temperatureRecalibrations = temperatureRecalibrations.replaceBy(
                result,
                TemperatureRecalibrationResult::runId
            )
        )
    }

    private suspend fun mutate(transform: InspectionDatabase.() -> InspectionDatabase) {
        mutex.withLock {
            database = database.transform()
            persistLocked()
        }
    }

    private fun loadDatabase(): InspectionDatabase {
        if (!Files.exists(databasePath)) return InspectionDatabase()
        return runCatching {
            json.decodeFromString<InspectionDatabase>(Files.readString(databasePath))
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to load inspection calibration database: $databasePath",
                error
            )
        }
    }

    private fun persistLocked() {
        val parent = databasePath.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temporary = databasePath.resolveSibling("${databasePath.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(database))
        try {
            Files.move(
                temporary,
                databasePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, databasePath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

@Serializable
private data class InspectionDatabase(
    val schemaVersion: Int = 1,
    val cameraCalibrations: List<CameraStageCalibration> = emptyList(),
    val heightProfiles: List<ProbeHeightProfile> = emptyList(),
    val pivotCalibrations: List<PivotCalibrationResult> = emptyList(),
    val temperatureRecalibrations: List<TemperatureRecalibrationResult> = emptyList()
)

private fun defaultInspectionJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
}

private fun <T> List<T>.replaceBy(value: T, key: (T) -> String): List<T> {
    val valueKey = key(value)
    val index = indexOfFirst { key(it) == valueKey }
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
