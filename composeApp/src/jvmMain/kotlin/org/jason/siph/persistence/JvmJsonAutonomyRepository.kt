package org.jason.siph.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.autonomy.AutonomyRepositoryBundle
import org.jason.siph.domain.autonomy.CalibrationProfile
import org.jason.siph.domain.autonomy.CalibrationVerificationRecord
import org.jason.siph.domain.autonomy.DriftBaseline
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhMeasurementRecord
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.SiPhWorkflowCheckpoint
import org.jason.siph.domain.autonomy.TrainedMeasurementPosition
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * JVM 单文件 JSON 仓储。
 *
 * 所有第一阶段资产在同一个数据库快照中原子替换，避免校准已保存但训练位置、检查点
 * 或测量记录尚未保存的跨文件不一致。未知字段会被忽略，便于后续 schema 迁移。
 */
class JvmJsonAutonomyRepository(
    private val databasePath: Path,
    private val json: Json = defaultAutonomyJson()
) : AutonomyRepositoryBundle {

    private val mutex = Mutex()
    private var database: AutonomyDatabase = loadDatabase()
    private val mutableActiveProfile = MutableStateFlow(
        database.activeProfileId?.let { id -> database.profiles.firstOrNull { it.id == id } }
    )

    override val activeProfile: StateFlow<CalibrationProfile?> = mutableActiveProfile.asStateFlow()

    override suspend fun listProfiles(): List<CalibrationProfile> = mutex.withLock {
        database.profiles.sortedBy { it.name.lowercase() }
    }

    override suspend fun findProfile(id: String): CalibrationProfile? = mutex.withLock {
        database.profiles.firstOrNull { it.id == id }
    }

    override suspend fun saveProfile(profile: CalibrationProfile) = mutate {
        copy(profiles = profiles.replaceBy(profile, CalibrationProfile::id))
    }.also {
        if (mutableActiveProfile.value?.id == profile.id) mutableActiveProfile.value = profile
    }

    override suspend fun deleteProfile(id: String) = mutate {
        copy(
            profiles = profiles.filterNot { it.id == id },
            activeProfileId = activeProfileId.takeUnless { it == id }
        )
    }.also {
        if (mutableActiveProfile.value?.id == id) mutableActiveProfile.value = null
    }

    override suspend fun activateProfile(id: String) {
        val profile = mutex.withLock {
            val resolved = database.profiles.firstOrNull { it.id == id }
                ?: error("Calibration profile not found: $id")
            database = database.copy(activeProfileId = id)
            persistLocked()
            resolved
        }
        mutableActiveProfile.value = profile
    }

    override suspend fun clearActiveProfile() {
        mutate { copy(activeProfileId = null) }
        mutableActiveProfile.value = null
    }

    override suspend fun listPositions(): List<TrainedMeasurementPosition> = mutex.withLock {
        database.positions.sortedByDescending { it.trainedAtEpochMs }
    }

    override suspend fun findPosition(id: String): TrainedMeasurementPosition? = mutex.withLock {
        database.positions.firstOrNull { it.id == id }
    }

    override suspend fun findPosition(site: MeasurementSiteKey): TrainedMeasurementPosition? = mutex.withLock {
        database.positions.filter { it.site == site }.maxByOrNull { it.trainedAtEpochMs }
    }

    override suspend fun savePosition(position: TrainedMeasurementPosition) = mutate {
        copy(positions = positions.replaceBy(position, TrainedMeasurementPosition::id))
    }

    override suspend fun deletePosition(id: String) = mutate {
        copy(positions = positions.filterNot { it.id == id })
    }

    override suspend fun listWafers(): List<SiPhWaferDefinition> = mutex.withLock {
        database.wafers.sortedBy { it.id.lowercase() }
    }

    override suspend fun findWafer(id: String): SiPhWaferDefinition? = mutex.withLock {
        database.wafers.firstOrNull { it.id == id }
    }

    override suspend fun saveWafer(wafer: SiPhWaferDefinition) = mutate {
        copy(wafers = wafers.replaceBy(wafer, SiPhWaferDefinition::id))
    }

    override suspend fun deleteWafer(id: String) = mutate {
        copy(wafers = wafers.filterNot { it.id == id })
    }

    override suspend fun listVerifications(
        profileId: String?
    ): List<CalibrationVerificationRecord> = mutex.withLock {
        database.calibrationVerifications
            .filter { profileId == null || it.profileId == profileId }
            .sortedByDescending { it.verifiedAtEpochMs }
    }

    override suspend fun saveVerification(record: CalibrationVerificationRecord) = mutate {
        copy(calibrationVerifications = calibrationVerifications + record)
    }

    override suspend fun listBaselines(): List<DriftBaseline> = mutex.withLock {
        database.driftBaselines.sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun findBaseline(id: String): DriftBaseline? = mutex.withLock {
        database.driftBaselines.firstOrNull { it.id == id }
    }

    override suspend fun findBaseline(site: MeasurementSiteKey): DriftBaseline? = mutex.withLock {
        database.driftBaselines.filter { it.site == site }.maxByOrNull { it.createdAtEpochMs }
    }

    override suspend fun saveBaseline(baseline: DriftBaseline) = mutate {
        copy(driftBaselines = driftBaselines.replaceBy(baseline, DriftBaseline::id))
    }

    override suspend fun deleteBaseline(id: String) = mutate {
        copy(driftBaselines = driftBaselines.filterNot { it.id == id })
    }

    override suspend fun listCheckpoints(): List<SiPhWorkflowCheckpoint> = mutex.withLock {
        database.checkpoints.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun findCheckpoint(runId: String): SiPhWorkflowCheckpoint? = mutex.withLock {
        database.checkpoints.firstOrNull { it.runId == runId }
    }

    override suspend fun saveCheckpoint(checkpoint: SiPhWorkflowCheckpoint) = mutate {
        copy(checkpoints = checkpoints.replaceBy(checkpoint, SiPhWorkflowCheckpoint::runId))
    }

    override suspend fun deleteCheckpoint(runId: String) = mutate {
        copy(checkpoints = checkpoints.filterNot { it.runId == runId })
    }

    override suspend fun listRecords(limit: Int): List<SiPhMeasurementRecord> {
        require(limit > 0) { "limit must be positive" }
        return mutex.withLock {
            database.measurementRecords
                .sortedByDescending { it.provenance.finishedAtEpochMs }
                .take(limit)
        }
    }

    override suspend fun findRecord(id: String): SiPhMeasurementRecord? = mutex.withLock {
        database.measurementRecords.firstOrNull { it.id == id }
    }

    override suspend fun saveRecord(record: SiPhMeasurementRecord) = mutate {
        copy(measurementRecords = measurementRecords.replaceBy(record, SiPhMeasurementRecord::id))
    }

    override suspend fun deleteRecord(id: String) = mutate {
        copy(measurementRecords = measurementRecords.filterNot { it.id == id })
    }

    private suspend fun mutate(
        transform: AutonomyDatabase.() -> AutonomyDatabase
    ) {
        mutex.withLock {
            database = database.transform()
            persistLocked()
        }
    }

    private fun loadDatabase(): AutonomyDatabase {
        if (!Files.exists(databasePath)) return AutonomyDatabase()
        return runCatching {
            json.decodeFromString<AutonomyDatabase>(Files.readString(databasePath))
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to load SiPh autonomy database: $databasePath",
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
private data class AutonomyDatabase(
    val schemaVersion: Int = 1,
    val activeProfileId: String? = null,
    val profiles: List<CalibrationProfile> = emptyList(),
    val positions: List<TrainedMeasurementPosition> = emptyList(),
    val wafers: List<SiPhWaferDefinition> = emptyList(),
    val calibrationVerifications: List<CalibrationVerificationRecord> = emptyList(),
    val driftBaselines: List<DriftBaseline> = emptyList(),
    val checkpoints: List<SiPhWorkflowCheckpoint> = emptyList(),
    val measurementRecords: List<SiPhMeasurementRecord> = emptyList()
)

private fun defaultAutonomyJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
}

private fun <T> List<T>.replaceBy(
    value: T,
    key: (T) -> String
): List<T> {
    val valueKey = key(value)
    val index = indexOfFirst { key(it) == valueKey }
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
