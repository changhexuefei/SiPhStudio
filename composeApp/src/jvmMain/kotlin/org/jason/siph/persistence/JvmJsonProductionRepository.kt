package org.jason.siph.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.AnomalyCase
import org.jason.siph.domain.production.AuditEvent
import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.CalibrationQualification
import org.jason.siph.domain.production.CalibrationWaferDefinition
import org.jason.siph.domain.production.FiberArrayDefinition
import org.jason.siph.domain.production.ProductionCheckpoint
import org.jason.siph.domain.production.ProductionLot
import org.jason.siph.domain.production.ProductionMeasurementRecipe
import org.jason.siph.domain.production.ProductionMeasurementResult
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionTask
import org.jason.siph.domain.production.QualityObservation
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class JvmJsonProductionRepository(
    private val databasePath: Path,
    private val json: Json = defaultProductionJson()
) : ProductionRepository {
    private val mutex = Mutex()
    private var database = loadDatabase()

    override suspend fun saveFiberArray(definition: FiberArrayDefinition) = mutate {
        copy(fiberArrays = fiberArrays.replaceBy(definition, FiberArrayDefinition::id))
    }

    override suspend fun findFiberArray(id: String): FiberArrayDefinition? = mutex.withLock {
        database.fiberArrays.firstOrNull { it.id == id }
    }

    override suspend fun listFiberArrays(): List<FiberArrayDefinition> = mutex.withLock {
        database.fiberArrays.sortedBy { it.name }
    }

    override suspend fun saveRecipe(recipe: ProductionMeasurementRecipe) = mutate {
        copy(recipes = recipes.replaceBy(recipe) { it.stableVersionId })
    }

    override suspend fun findRecipe(id: String, version: Int): ProductionMeasurementRecipe? = mutex.withLock {
        database.recipes.firstOrNull { it.id == id && it.version == version }
    }

    override suspend fun listRecipes(): List<ProductionMeasurementRecipe> = mutex.withLock {
        database.recipes.sortedWith(compareBy<ProductionMeasurementRecipe> { it.id }.thenByDescending { it.version })
    }

    override suspend fun saveCalibrationWafer(definition: CalibrationWaferDefinition) = mutate {
        copy(calibrationWafers = calibrationWafers.replaceBy(definition, CalibrationWaferDefinition::id))
    }

    override suspend fun findCalibrationWafer(id: String): CalibrationWaferDefinition? = mutex.withLock {
        database.calibrationWafers.firstOrNull { it.id == id }
    }

    override suspend fun listCalibrationWafers(): List<CalibrationWaferDefinition> = mutex.withLock {
        database.calibrationWafers.sortedBy { it.serialNumber }
    }

    override suspend fun saveCalibrationQualification(qualification: CalibrationQualification) = mutate {
        copy(qualifications = qualifications.replaceBy(qualification, CalibrationQualification::id))
    }

    override suspend fun findCalibrationQualification(id: String): CalibrationQualification? = mutex.withLock {
        database.qualifications.firstOrNull { it.id == id }
    }

    override suspend fun listCalibrationQualifications(): List<CalibrationQualification> = mutex.withLock {
        database.qualifications.sortedByDescending { it.finishedAtEpochMs }
    }

    override suspend fun saveLot(lot: ProductionLot) = mutate {
        copy(lots = lots.replaceBy(lot, ProductionLot::id))
    }

    override suspend fun findLot(id: String): ProductionLot? = mutex.withLock {
        database.lots.firstOrNull { it.id == id }
    }

    override suspend fun listLots(): List<ProductionLot> = mutex.withLock {
        database.lots.sortedWith(compareByDescending<ProductionLot> { it.priority }.thenBy { it.createdAtEpochMs })
    }

    override suspend fun saveTask(task: ProductionTask) = mutate {
        copy(tasks = tasks.replaceBy(task, ProductionTask::id))
    }

    override suspend fun findTask(id: String): ProductionTask? = mutex.withLock {
        database.tasks.firstOrNull { it.id == id }
    }

    override suspend fun listTasks(lotId: String?): List<ProductionTask> = mutex.withLock {
        database.tasks.filter { lotId == null || it.lotId == lotId }
            .sortedWith(compareByDescending<ProductionTask> { it.priority }.thenBy { it.id })
    }

    override suspend fun saveCheckpoint(checkpoint: ProductionCheckpoint) = mutate {
        copy(checkpoints = checkpoints.replaceBy(checkpoint, ProductionCheckpoint::taskId))
    }

    override suspend fun findCheckpoint(taskId: String): ProductionCheckpoint? = mutex.withLock {
        database.checkpoints.firstOrNull { it.taskId == taskId }
    }

    override suspend fun deleteCheckpoint(taskId: String) = mutate {
        copy(checkpoints = checkpoints.filterNot { it.taskId == taskId })
    }

    override suspend fun saveMeasurementResult(result: ProductionMeasurementResult) = mutate {
        val duplicate = results.firstOrNull { it.idempotencyKey == result.idempotencyKey }
        require(duplicate == null || duplicate.resultId == result.resultId) {
            "A production result already exists for idempotencyKey=${result.idempotencyKey}"
        }
        copy(results = results.replaceBy(result, ProductionMeasurementResult::resultId))
    }

    override suspend fun findMeasurementResultByIdempotencyKey(key: String): ProductionMeasurementResult? =
        mutex.withLock { database.results.firstOrNull { it.idempotencyKey == key } }

    override suspend fun listMeasurementResults(lotId: String?): List<ProductionMeasurementResult> = mutex.withLock {
        val taskIds = if (lotId == null) null else database.tasks.filter { it.lotId == lotId }.map { it.id }.toSet()
        database.results.filter { taskIds == null || it.taskId in taskIds }
            .sortedByDescending { it.finishedAtEpochMs }
    }

    override suspend fun saveQualityObservation(observation: QualityObservation) = mutate {
        copy(quality = quality.replaceBy(observation, QualityObservation::id))
    }

    override suspend fun listQualityObservations(metricName: String?): List<QualityObservation> = mutex.withLock {
        database.quality.filter { metricName == null || it.metricName == metricName }
            .sortedBy { it.timestampEpochMs }
    }

    override suspend fun saveAnomalyCase(case: AnomalyCase) = mutate {
        copy(anomalies = anomalies.replaceBy(case, AnomalyCase::id))
    }

    override suspend fun findAnomalyCase(id: String): AnomalyCase? = mutex.withLock {
        database.anomalies.firstOrNull { it.id == id }
    }

    override suspend fun listAnomalyCases(lotId: String?): List<AnomalyCase> = mutex.withLock {
        database.anomalies.filter { lotId == null || it.lotId == lotId }
            .sortedByDescending { it.openedAtEpochMs }
    }

    override suspend fun appendAuditEvent(event: AuditEvent) = mutate {
        val expectedPrevious = audits.lastOrNull()?.eventHash
        require(event.previousHash == expectedPrevious) {
            "Audit hash chain mismatch: expected previousHash=$expectedPrevious, actual=${event.previousHash}"
        }
        copy(audits = audits + event)
    }

    override suspend fun latestAuditEvent(): AuditEvent? = mutex.withLock { database.audits.lastOrNull() }

    override suspend fun listAuditEvents(limit: Int): List<AuditEvent> {
        require(limit > 0)
        return mutex.withLock { database.audits.takeLast(limit).reversed() }
    }

    private suspend fun mutate(transform: ProductionDatabase.() -> ProductionDatabase) {
        mutex.withLock {
            database = database.transform()
            persistLocked()
        }
    }

    private fun loadDatabase(): ProductionDatabase {
        if (!Files.exists(databasePath)) return ProductionDatabase()
        return runCatching {
            json.decodeFromString<ProductionDatabase>(Files.readString(databasePath))
        }.getOrElse { error ->
            throw IllegalStateException("Failed to load production database: $databasePath", error)
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

class JvmSha256AuditHasher : AuditHasher {
    override fun hash(canonicalValue: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalValue.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

@Serializable
private data class ProductionDatabase(
    val schemaVersion: Int = 1,
    val fiberArrays: List<FiberArrayDefinition> = emptyList(),
    val recipes: List<ProductionMeasurementRecipe> = emptyList(),
    val calibrationWafers: List<CalibrationWaferDefinition> = emptyList(),
    val qualifications: List<CalibrationQualification> = emptyList(),
    val lots: List<ProductionLot> = emptyList(),
    val tasks: List<ProductionTask> = emptyList(),
    val checkpoints: List<ProductionCheckpoint> = emptyList(),
    val results: List<ProductionMeasurementResult> = emptyList(),
    val quality: List<QualityObservation> = emptyList(),
    val anomalies: List<AnomalyCase> = emptyList(),
    val audits: List<AuditEvent> = emptyList()
)

private fun defaultProductionJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    classDiscriminator = "type"
}

private fun <T> List<T>.replaceBy(value: T, key: (T) -> String): List<T> {
    val valueKey = key(value)
    val index = indexOfFirst { key(it) == valueKey }
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
