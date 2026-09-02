package org.jason.siph.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.oo.OoMeasurementCheckpoint
import org.jason.siph.domain.oo.OoMeasurementRepository
import org.jason.siph.domain.oo.OoMeasurementResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class JvmJsonOoMeasurementRepository(
    private val databasePath: Path,
    private val json: Json = defaultOoJson()
) : OoMeasurementRepository {

    private val mutex = Mutex()
    private var database = loadDatabase()

    override suspend fun listResults(limit: Int): List<OoMeasurementResult> {
        require(limit > 0)
        return mutex.withLock {
            database.results
                .sortedByDescending { it.finishedAtEpochMs }
                .take(limit)
        }
    }

    override suspend fun findResult(runId: String): OoMeasurementResult? = mutex.withLock {
        database.results.firstOrNull { it.runId == runId }
    }

    override suspend fun saveResult(result: OoMeasurementResult) = mutate {
        copy(results = results.replaceBy(result, OoMeasurementResult::runId))
    }

    override suspend fun deleteResult(runId: String) = mutate {
        copy(results = results.filterNot { it.runId == runId })
    }

    override suspend fun listCheckpoints(): List<OoMeasurementCheckpoint> = mutex.withLock {
        database.checkpoints.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun findCheckpoint(runId: String): OoMeasurementCheckpoint? = mutex.withLock {
        database.checkpoints.firstOrNull { it.runId == runId }
    }

    override suspend fun saveCheckpoint(checkpoint: OoMeasurementCheckpoint) = mutate {
        copy(checkpoints = checkpoints.replaceBy(checkpoint, OoMeasurementCheckpoint::runId))
    }

    override suspend fun deleteCheckpoint(runId: String) = mutate {
        copy(checkpoints = checkpoints.filterNot { it.runId == runId })
    }

    private suspend fun mutate(
        transform: OoDatabase.() -> OoDatabase
    ) {
        mutex.withLock {
            database = database.transform()
            persistLocked()
        }
    }

    private fun loadDatabase(): OoDatabase {
        if (!Files.exists(databasePath)) return OoDatabase()
        return runCatching {
            json.decodeFromString<OoDatabase>(Files.readString(databasePath))
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to load O-O measurement database: $databasePath",
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
private data class OoDatabase(
    val schemaVersion: Int = 1,
    val results: List<OoMeasurementResult> = emptyList(),
    val checkpoints: List<OoMeasurementCheckpoint> = emptyList()
)

private fun defaultOoJson(): Json = Json {
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
