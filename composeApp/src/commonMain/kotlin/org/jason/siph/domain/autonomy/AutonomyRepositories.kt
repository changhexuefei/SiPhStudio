package org.jason.siph.domain.autonomy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MeasurementPositionRepository {
    suspend fun listPositions(): List<TrainedMeasurementPosition>
    suspend fun findPosition(id: String): TrainedMeasurementPosition?
    suspend fun findPosition(site: MeasurementSiteKey): TrainedMeasurementPosition?
    suspend fun savePosition(position: TrainedMeasurementPosition)
    suspend fun deletePosition(id: String)
}

interface WaferDefinitionRepository {
    suspend fun listWafers(): List<SiPhWaferDefinition>
    suspend fun findWafer(id: String): SiPhWaferDefinition?
    suspend fun saveWafer(wafer: SiPhWaferDefinition)
    suspend fun deleteWafer(id: String)
}

interface CalibrationVerificationRepository {
    suspend fun listVerifications(profileId: String? = null): List<CalibrationVerificationRecord>
    suspend fun saveVerification(record: CalibrationVerificationRecord)
}

interface DriftBaselineRepository {
    suspend fun listBaselines(): List<DriftBaseline>
    suspend fun findBaseline(id: String): DriftBaseline?
    suspend fun findBaseline(site: MeasurementSiteKey): DriftBaseline?
    suspend fun saveBaseline(baseline: DriftBaseline)
    suspend fun deleteBaseline(id: String)
}

interface WorkflowCheckpointRepository {
    suspend fun findCheckpoint(runId: String): SiPhWorkflowCheckpoint?
    suspend fun saveCheckpoint(checkpoint: SiPhWorkflowCheckpoint)
    suspend fun deleteCheckpoint(runId: String)
}

interface MeasurementRecordRepository {
    suspend fun listRecords(limit: Int = 100): List<SiPhMeasurementRecord>
    suspend fun findRecord(id: String): SiPhMeasurementRecord?
    suspend fun saveRecord(record: SiPhMeasurementRecord)
    suspend fun deleteRecord(id: String)
}

/** 测试、Wasm 和未启用文件系统持久化时使用的线程安全仓储。 */
class InMemoryAutonomyRepository(
    initialPositions: List<TrainedMeasurementPosition> = emptyList(),
    initialWafers: List<SiPhWaferDefinition> = emptyList(),
    initialVerifications: List<CalibrationVerificationRecord> = emptyList(),
    initialBaselines: List<DriftBaseline> = emptyList(),
    initialCheckpoints: List<SiPhWorkflowCheckpoint> = emptyList(),
    initialRecords: List<SiPhMeasurementRecord> = emptyList()
) : MeasurementPositionRepository,
    WaferDefinitionRepository,
    CalibrationVerificationRepository,
    DriftBaselineRepository,
    WorkflowCheckpointRepository,
    MeasurementRecordRepository {

    private val mutex = Mutex()
    private val positions = initialPositions.associateBy { it.id }.toMutableMap()
    private val wafers = initialWafers.associateBy { it.id }.toMutableMap()
    private val verifications = initialVerifications.toMutableList()
    private val baselines = initialBaselines.associateBy { it.id }.toMutableMap()
    private val checkpoints = initialCheckpoints.associateBy { it.runId }.toMutableMap()
    private val records = initialRecords.associateBy { it.id }.toMutableMap()

    override suspend fun listPositions(): List<TrainedMeasurementPosition> = mutex.withLock {
        positions.values.sortedByDescending { it.trainedAtEpochMs }
    }

    override suspend fun findPosition(id: String): TrainedMeasurementPosition? = mutex.withLock {
        positions[id]
    }

    override suspend fun findPosition(site: MeasurementSiteKey): TrainedMeasurementPosition? = mutex.withLock {
        positions.values
            .asSequence()
            .filter { it.site == site }
            .maxByOrNull { it.trainedAtEpochMs }
    }

    override suspend fun savePosition(position: TrainedMeasurementPosition) {
        mutex.withLock { positions[position.id] = position }
    }

    override suspend fun deletePosition(id: String) {
        mutex.withLock { positions.remove(id) }
    }

    override suspend fun listWafers(): List<SiPhWaferDefinition> = mutex.withLock {
        wafers.values.sortedBy { it.id.lowercase() }
    }

    override suspend fun findWafer(id: String): SiPhWaferDefinition? = mutex.withLock {
        wafers[id]
    }

    override suspend fun saveWafer(wafer: SiPhWaferDefinition) {
        mutex.withLock { wafers[wafer.id] = wafer }
    }

    override suspend fun deleteWafer(id: String) {
        mutex.withLock { wafers.remove(id) }
    }

    override suspend fun listVerifications(
        profileId: String?
    ): List<CalibrationVerificationRecord> = mutex.withLock {
        verifications
            .asSequence()
            .filter { profileId == null || it.profileId == profileId }
            .sortedByDescending { it.verifiedAtEpochMs }
            .toList()
    }

    override suspend fun saveVerification(record: CalibrationVerificationRecord) {
        mutex.withLock { verifications += record }
    }

    override suspend fun listBaselines(): List<DriftBaseline> = mutex.withLock {
        baselines.values.sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun findBaseline(id: String): DriftBaseline? = mutex.withLock {
        baselines[id]
    }

    override suspend fun findBaseline(site: MeasurementSiteKey): DriftBaseline? = mutex.withLock {
        baselines.values
            .asSequence()
            .filter { it.site == site }
            .maxByOrNull { it.createdAtEpochMs }
    }

    override suspend fun saveBaseline(baseline: DriftBaseline) {
        mutex.withLock { baselines[baseline.id] = baseline }
    }

    override suspend fun deleteBaseline(id: String) {
        mutex.withLock { baselines.remove(id) }
    }

    override suspend fun findCheckpoint(runId: String): SiPhWorkflowCheckpoint? = mutex.withLock {
        checkpoints[runId]
    }

    override suspend fun saveCheckpoint(checkpoint: SiPhWorkflowCheckpoint) {
        mutex.withLock { checkpoints[checkpoint.runId] = checkpoint }
    }

    override suspend fun deleteCheckpoint(runId: String) {
        mutex.withLock { checkpoints.remove(runId) }
    }

    override suspend fun listRecords(limit: Int): List<SiPhMeasurementRecord> {
        require(limit > 0) { "limit must be positive" }
        return mutex.withLock {
            records.values
                .sortedByDescending { it.provenance.finishedAtEpochMs }
                .take(limit)
        }
    }

    override suspend fun findRecord(id: String): SiPhMeasurementRecord? = mutex.withLock {
        records[id]
    }

    override suspend fun saveRecord(record: SiPhMeasurementRecord) {
        mutex.withLock { records[record.id] = record }
    }

    override suspend fun deleteRecord(id: String) {
        mutex.withLock { records.remove(id) }
    }
}
