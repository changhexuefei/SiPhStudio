package org.jason.siph.domain.oo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface OoMeasurementRepository {
    suspend fun listResults(limit: Int = 100): List<OoMeasurementResult>
    suspend fun findResult(runId: String): OoMeasurementResult?
    suspend fun saveResult(result: OoMeasurementResult)
    suspend fun deleteResult(runId: String)
    suspend fun listCheckpoints(): List<OoMeasurementCheckpoint>
    suspend fun findCheckpoint(runId: String): OoMeasurementCheckpoint?
    suspend fun saveCheckpoint(checkpoint: OoMeasurementCheckpoint)
    suspend fun deleteCheckpoint(runId: String)
}

class InMemoryOoMeasurementRepository(
    initialResults: List<OoMeasurementResult> = emptyList(),
    initialCheckpoints: List<OoMeasurementCheckpoint> = emptyList()
) : OoMeasurementRepository {

    private val mutex = Mutex()
    private val results = initialResults.associateBy { it.runId }.toMutableMap()
    private val checkpoints = initialCheckpoints.associateBy { it.runId }.toMutableMap()

    override suspend fun listResults(limit: Int): List<OoMeasurementResult> {
        require(limit > 0)
        return mutex.withLock {
            results.values
                .sortedByDescending { it.finishedAtEpochMs }
                .take(limit)
        }
    }

    override suspend fun findResult(runId: String): OoMeasurementResult? = mutex.withLock {
        results[runId]
    }

    override suspend fun saveResult(result: OoMeasurementResult) {
        mutex.withLock { results[result.runId] = result }
    }

    override suspend fun deleteResult(runId: String) {
        mutex.withLock { results.remove(runId) }
    }

    override suspend fun listCheckpoints(): List<OoMeasurementCheckpoint> = mutex.withLock {
        checkpoints.values.sortedByDescending { it.updatedAtEpochMs }
    }

    override suspend fun findCheckpoint(runId: String): OoMeasurementCheckpoint? = mutex.withLock {
        checkpoints[runId]
    }

    override suspend fun saveCheckpoint(checkpoint: OoMeasurementCheckpoint) {
        mutex.withLock { checkpoints[checkpoint.runId] = checkpoint }
    }

    override suspend fun deleteCheckpoint(runId: String) {
        mutex.withLock { checkpoints.remove(runId) }
    }
}
