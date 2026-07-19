package org.jason.siph.domain.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jason.siph.domain.coupling.CouplingResult
import org.jason.siph.domain.coupling.CouplingResultStatus
import org.jason.siph.domain.coupling.CouplingRunner
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalPositionerPort
import kotlin.math.min
import kotlin.math.pow

interface SiPhWorkflowRunner {
    val state: StateFlow<SiPhWorkflowState>

    suspend fun run(
        recipe: SiPhWorkflowRecipe,
        runId: String,
        resumeFromCheckpoint: Boolean = true
    ): SiPhMeasurementRecord

    suspend fun requestStop()
}

/**
 * 不依赖视觉、晶圆台和位移传感器的第一阶段自主工作流。
 *
 * 所有运动均经过现有 [OpticalPositionerPort]，因此真实模式仍受统一软件限位、
 * 控制器行程和安全位保护。每个成功阶段都会落检查点，失败阶段按策略自动停止、
 * 恢复到训练位置并重试。
 */
class DefaultSiPhWorkflowRunner(
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,
    private val couplingRunner: CouplingRunner,
    private val calibrationProfiles: CalibrationProfileRepository,
    private val positions: MeasurementPositionRepository,
    private val baselines: DriftBaselineRepository,
    private val checkpoints: WorkflowCheckpointRepository,
    private val records: MeasurementRecordRepository,
    private val verifier: OpticalAlignmentVerifier,
    private val driftEvaluator: DriftEvaluator,
    private val runtimeModeProvider: () -> String,
    private val nowEpochMs: () -> Long
) : SiPhWorkflowRunner {

    private val runMutex = Mutex()
    private val mutableState = MutableStateFlow(SiPhWorkflowState())
    override val state: StateFlow<SiPhWorkflowState> = mutableState.asStateFlow()
    private val stopRequested = MutableStateFlow(false)

    override suspend fun run(
        recipe: SiPhWorkflowRecipe,
        runId: String,
        resumeFromCheckpoint: Boolean
    ): SiPhMeasurementRecord = runMutex.withLock {
        require(runId.isNotBlank()) { "runId must not be blank" }
        stopRequested.value = false

        val stageOrder = buildList {
            add(SiPhWorkflowStage.InspectHardware)
            add(SiPhWorkflowStage.VerifyCalibration)
            add(SiPhWorkflowStage.ResolveMeasurementPosition)
            add(SiPhWorkflowStage.MoveToMeasurementPosition)
            add(SiPhWorkflowStage.AutoCoupling)
            if (recipe.enableVerification) add(SiPhWorkflowStage.VerifyReturnRepeatability)
            if (recipe.enableDriftAssessment) add(SiPhWorkflowStage.AssessDrift)
            add(SiPhWorkflowStage.PersistMeasurement)
        }
        val startedAt = nowEpochMs()
        var checkpoint = if (resumeFromCheckpoint) checkpoints.findCheckpoint(runId) else null
        if (checkpoint != null && checkpoint.recipe.id != recipe.id) {
            error("Checkpoint recipe mismatch: ${checkpoint.recipe.id} != ${recipe.id}")
        }
        if (checkpoint == null) {
            checkpoint = SiPhWorkflowCheckpoint(
                runId = runId,
                recipe = recipe,
                completedStages = emptySet(),
                currentStage = SiPhWorkflowStage.Idle,
                updatedAtEpochMs = startedAt
            )
            checkpoints.saveCheckpoint(checkpoint)
        }

        val progress = stageOrder.map { stage ->
            WorkflowStageProgress(
                stage = stage,
                status = if (stage in checkpoint.completedStages) {
                    WorkflowStageStatus.Succeeded
                } else {
                    WorkflowStageStatus.Pending
                }
            )
        }.toMutableList()
        mutableState.value = SiPhWorkflowState(
            runId = runId,
            recipeId = recipe.id,
            stage = checkpoint.currentStage,
            stageProgress = progress,
            message = if (checkpoint.completedStages.isEmpty()) {
                "Workflow started"
            } else {
                "Workflow resumed from ${checkpoint.currentStage}"
            },
            running = true,
            completedStageCount = checkpoint.completedStages.size,
            totalStageCount = stageOrder.size,
            measurementRecordId = checkpoint.measurementRecordId,
            startedAtEpochMs = startedAt
        )

        var calibration: CalibrationProfile? = null
        var trainedPosition: TrainedMeasurementPosition? = checkpoint.trainedPosition
        var positionerIdentity: String? = null
        var powerMeterIdentity: String? = null
        var couplingResult: CouplingResult? = null
        var record: SiPhMeasurementRecord? = checkpoint.measurementRecordId
            ?.let { records.findRecord(it) }
        var verification = record?.verification
        var driftAssessment = record?.driftAssessment

        try {
            for (stage in stageOrder) {
                currentCoroutineContext().ensureActive()
                ensureNotStopped()
                if (stage in checkpoint.completedStages) continue

                executeStageWithRetry(
                    stage = stage,
                    retryPolicy = recipe.retryPolicy,
                    progress = progress,
                    trainedPositionProvider = { trainedPosition }
                ) {
                    when (stage) {
                        SiPhWorkflowStage.InspectHardware -> {
                            if (recipe.manageDeviceConnections) {
                                positioner.connect()
                                powerMeter.connect()
                                positioner.startup(reference = false)
                            }
                            positionerIdentity = positioner.identify().also {
                                require(it.isNotBlank()) { "Positioner identity is blank" }
                            }
                            powerMeterIdentity = powerMeter.identify().also {
                                require(it.isNotBlank()) { "Power meter identity is blank" }
                            }
                            powerMeter.setWavelengthNm(
                                wavelengthNm = recipe.couplingConfig.wavelengthNm,
                                channel = recipe.couplingConfig.powerMeterChannel
                            )
                        }

                        SiPhWorkflowStage.VerifyCalibration -> {
                            calibration = resolveCalibration(recipe)
                            val expectedController = calibration?.controllerIdentity
                            if (!expectedController.isNullOrBlank() &&
                                positionerIdentity != null &&
                                !positionerIdentity!!.contains(expectedController, ignoreCase = true)
                            ) {
                                throw WorkflowStageException(
                                    "Controller identity does not match calibration profile",
                                    recoverable = false
                                )
                            }
                        }

                        SiPhWorkflowStage.ResolveMeasurementPosition -> {
                            trainedPosition = resolvePosition(recipe)
                            if (trainedPosition!!.calibrationProfileId != calibration!!.id) {
                                throw WorkflowStageException(
                                    "Trained position belongs to calibration ${trainedPosition!!.calibrationProfileId}, " +
                                        "active calibration is ${calibration!!.id}",
                                    recoverable = false
                                )
                            }
                        }

                        SiPhWorkflowStage.MoveToMeasurementPosition -> {
                            positioner.moveTo(trainedPosition!!.pose, wait = true)
                            val actual = positioner.currentPose()
                            val errorUm = actual.linearDistanceTo(trainedPosition!!.pose)
                            if (errorUm > recipe.verificationConfig.maxReturnPositionErrorUm) {
                                throw WorkflowStageException(
                                    "Initial move position error $errorUm um exceeds allowed value",
                                    recoverable = true
                                )
                            }
                        }

                        SiPhWorkflowStage.AutoCoupling -> {
                            val startPose = positioner.currentPose()
                            val result = couplingRunner.run(
                                initialPose = startPose,
                                config = recipe.couplingConfig,
                                shouldStop = { stopRequested.value }
                            )
                            when (result.status) {
                                CouplingResultStatus.Success,
                                CouplingResultStatus.TargetNotReached -> Unit
                                CouplingResultStatus.Stopped -> throw CancellationException(
                                    result.message ?: "Coupling stopped"
                                )
                                CouplingResultStatus.FirstLightNotFound -> throw WorkflowStageException(
                                    result.message ?: "First light was not found",
                                    recoverable = true
                                )
                                CouplingResultStatus.Failed -> throw WorkflowStageException(
                                    result.message ?: "Coupling failed",
                                    recoverable = true
                                )
                            }
                            couplingResult = result
                            record = createPartialRecord(
                                runId = runId,
                                recipe = recipe,
                                calibration = calibration!!,
                                trainedPosition = trainedPosition!!,
                                positionerIdentity = requireNotNull(positionerIdentity),
                                powerMeterIdentity = requireNotNull(powerMeterIdentity),
                                result = result,
                                startedAt = startedAt
                            )
                            records.saveRecord(record!!)
                        }

                        SiPhWorkflowStage.VerifyReturnRepeatability -> {
                            val result = requireCouplingResult(couplingResult, record)
                            verification = verifier.verify(
                                bestPose = result.bestPose,
                                powerMeterChannel = recipe.couplingConfig.powerMeterChannel,
                                config = recipe.verificationConfig
                            )
                            record = requireNotNull(record).copy(verification = verification)
                            records.saveRecord(record!!)
                            if (verification?.passed != true) {
                                throw WorkflowStageException(
                                    "Optical return verification failed: ${verification?.failures?.joinToString()}",
                                    recoverable = true
                                )
                            }
                        }

                        SiPhWorkflowStage.AssessDrift -> {
                            val currentRecord = requireNotNull(record)
                            val baseline = baselines.findBaseline(recipe.site)
                            if (baseline == null) {
                                baselines.saveBaseline(
                                    DriftBaseline(
                                        id = "baseline-${recipe.site.stableId}",
                                        site = recipe.site,
                                        referencePose = currentRecord.provenance.bestPose,
                                        referencePowerDbm = currentRecord.bestPowerDbm,
                                        calibrationProfileId = calibration!!.id,
                                        createdAtEpochMs = nowEpochMs()
                                    )
                                )
                                driftAssessment = null
                            } else {
                                driftAssessment = driftEvaluator.assess(
                                    baseline = baseline,
                                    currentPose = currentRecord.provenance.bestPose,
                                    currentPowerDbm = currentRecord.bestPowerDbm,
                                    currentTemperatureC = null,
                                    policy = recipe.driftPolicy
                                )
                                record = currentRecord.copy(driftAssessment = driftAssessment)
                                records.saveRecord(record!!)
                                when (driftAssessment?.action) {
                                    DriftAction.StopWorkflow -> throw WorkflowStageException(
                                        "Drift policy requested workflow stop",
                                        recoverable = false
                                    )
                                    DriftAction.FullRecalibration -> throw WorkflowStageException(
                                        "Drift requires full recalibration",
                                        recoverable = false
                                    )
                                    DriftAction.LocalRealign,
                                    DriftAction.Continue,
                                    null -> Unit
                                }
                            }
                        }

                        SiPhWorkflowStage.PersistMeasurement -> {
                            val current = requireNotNull(record)
                            record = current.copy(
                                verification = verification,
                                driftAssessment = driftAssessment,
                                completed = true,
                                provenance = current.provenance.copy(
                                    finalPose = positioner.currentPose(),
                                    finishedAtEpochMs = nowEpochMs()
                                )
                            )
                            records.saveRecord(record!!)
                        }

                        else -> Unit
                    }
                }

                checkpoint = checkpoint.copy(
                    completedStages = checkpoint.completedStages + stage,
                    currentStage = stage,
                    trainedPosition = trainedPosition,
                    bestPose = record?.provenance?.bestPose ?: checkpoint.bestPose,
                    bestPowerDbm = record?.bestPowerDbm ?: checkpoint.bestPowerDbm,
                    measurementRecordId = record?.id ?: checkpoint.measurementRecordId,
                    updatedAtEpochMs = nowEpochMs()
                )
                checkpoints.saveCheckpoint(checkpoint)
                updateCompletedStage(stage, progress, checkpoint.completedStages.size)
            }

            val completed = requireNotNull(record) { "Workflow completed without measurement record" }
            checkpoints.deleteCheckpoint(runId)
            mutableState.update {
                it.copy(
                    stage = SiPhWorkflowStage.Completed,
                    message = "Workflow completed and measurement was persisted",
                    running = false,
                    stopRequested = false,
                    completedStageCount = stageOrder.size,
                    measurementRecordId = completed.id,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            completed
        } catch (cancelled: CancellationException) {
            stopSafely()
            mutableState.update {
                it.copy(
                    stage = SiPhWorkflowStage.Stopped,
                    message = cancelled.message ?: "Workflow stopped",
                    running = false,
                    stopRequested = true,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            stopSafely()
            val failure = WorkflowFailure(
                stage = mutableState.value.stage,
                attempt = mutableState.value.attempt,
                message = error.message ?: error::class.simpleName ?: "Workflow failure",
                recoverable = false,
                occurredAtEpochMs = nowEpochMs()
            )
            checkpoint = checkpoint.copy(
                currentStage = failure.stage,
                failures = checkpoint.failures + failure,
                measurementRecordId = record?.id ?: checkpoint.measurementRecordId,
                updatedAtEpochMs = nowEpochMs()
            )
            checkpoints.saveCheckpoint(checkpoint)
            record?.let {
                records.saveRecord(
                    it.copy(
                        completed = false,
                        failureMessage = failure.message,
                        provenance = it.provenance.copy(finishedAtEpochMs = nowEpochMs())
                    )
                )
            }
            mutableState.update {
                it.copy(
                    stage = SiPhWorkflowStage.Failed,
                    message = failure.message,
                    running = false,
                    lastFailure = failure,
                    measurementRecordId = record?.id,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            throw error
        } finally {
            if (recipe.manageDeviceConnections) {
                withContext(NonCancellable) {
                    runCatching { positioner.disconnect() }
                    runCatching { powerMeter.disconnect() }
                }
            }
        }
    }

    override suspend fun requestStop() {
        stopRequested.value = true
        mutableState.update { it.copy(stopRequested = true, message = "Stop requested") }
        stopSafely()
    }

    private suspend fun executeStageWithRetry(
        stage: SiPhWorkflowStage,
        retryPolicy: WorkflowRetryPolicy,
        progress: MutableList<WorkflowStageProgress>,
        trainedPositionProvider: () -> TrainedMeasurementPosition?,
        block: suspend () -> Unit
    ) {
        var delayMs = retryPolicy.initialDelayMs
        var lastError: Throwable? = null

        for (attempt in 1..retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()
            ensureNotStopped()
            updateStage(stage, WorkflowStageStatus.Running, attempt, progress, "Running")
            try {
                block()
                updateStage(stage, WorkflowStageStatus.Succeeded, attempt, progress, "Completed")
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                val stageRecoverable = stage in retryPolicy.retryableStages
                val errorRecoverable = (error as? WorkflowStageException)?.recoverable ?: true
                val canRetry = stageRecoverable && errorRecoverable && attempt < retryPolicy.maxAttempts
                val failure = WorkflowFailure(
                    stage = stage,
                    attempt = attempt,
                    message = error.message ?: "Stage failed",
                    recoverable = canRetry,
                    occurredAtEpochMs = nowEpochMs()
                )
                mutableState.update { it.copy(lastFailure = failure) }
                updateStage(stage, WorkflowStageStatus.Failed, attempt, progress, failure.message)
                if (!canRetry) throw error

                stopSafely()
                trainedPositionProvider()?.let { trained ->
                    runCatching { positioner.moveTo(trained.pose, wait = true) }
                }
                if (delayMs > 0L) delay(delayMs)
                delayMs = min(
                    retryPolicy.maximumDelayMs.toDouble(),
                    delayMs.toDouble() * retryPolicy.backoffMultiplier.pow(1.0)
                ).toLong()
            }
        }
        throw lastError ?: error("Stage failed without an exception")
    }

    private suspend fun resolveCalibration(recipe: SiPhWorkflowRecipe): CalibrationProfile {
        val profile = recipe.calibrationProfileId
            ?.let { calibrationProfiles.findProfile(it) }
            ?: calibrationProfiles.activeProfile.value
            ?: error("No calibration profile is active")
        if (recipe.requireVerifiedCalibration && !profile.verified) {
            throw WorkflowStageException("Calibration profile is not verified", recoverable = false)
        }
        return profile
    }

    private suspend fun resolvePosition(recipe: SiPhWorkflowRecipe): TrainedMeasurementPosition {
        val position = recipe.trainedPositionId
            ?.let { positions.findPosition(it) }
            ?: positions.findPosition(recipe.site)
            ?: error("No trained measurement position exists for ${recipe.site.stableId}")
        if (position.site != recipe.site) {
            throw WorkflowStageException("Trained position site does not match recipe site", recoverable = false)
        }
        if (recipe.requireVerifiedMeasurementPosition && !position.verified) {
            throw WorkflowStageException("Trained measurement position is not verified", recoverable = false)
        }
        return position
    }

    private fun createPartialRecord(
        runId: String,
        recipe: SiPhWorkflowRecipe,
        calibration: CalibrationProfile,
        trainedPosition: TrainedMeasurementPosition,
        positionerIdentity: String,
        powerMeterIdentity: String,
        result: CouplingResult,
        startedAt: Long
    ): SiPhMeasurementRecord {
        val trace = result.samples.map { sample ->
            AlignmentTracePoint(
                index = sample.index,
                stage = sample.stage.name,
                pose = sample.pose,
                powerDbm = sample.powerDbm,
                timestampEpochMs = sample.timestampMs
            )
        }
        return SiPhMeasurementRecord(
            id = "measurement-$runId",
            provenance = MeasurementProvenance(
                runId = runId,
                recipeId = recipe.id,
                site = recipe.site,
                calibrationProfileId = calibration.id,
                trainedPositionId = trainedPosition.id,
                safetyProfileId = trainedPosition.safetyProfileId,
                devices = MeasurementDeviceSnapshot(
                    positionerIdentity = positionerIdentity,
                    powerMeterIdentity = powerMeterIdentity,
                    runtimeMode = runtimeModeProvider()
                ),
                startPose = trainedPosition.pose,
                bestPose = result.bestPose,
                finalPose = result.finalPose,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = nowEpochMs()
            ),
            bestPowerDbm = result.bestPowerDbm,
            finalPowerDbm = result.finalPowerDbm,
            couplingStatus = result.status.name,
            trace = trace,
            verification = null,
            driftAssessment = null,
            completed = false
        )
    }

    private fun requireCouplingResult(
        result: CouplingResult?,
        record: SiPhMeasurementRecord?
    ): CouplingResult {
        if (result != null) return result
        val persisted = requireNotNull(record) { "Coupling result and persisted record are unavailable" }
        return CouplingResult(
            status = runCatching { CouplingResultStatus.valueOf(persisted.couplingStatus) }
                .getOrDefault(CouplingResultStatus.TargetNotReached),
            bestPose = persisted.provenance.bestPose,
            bestPowerDbm = persisted.bestPowerDbm,
            finalPose = persisted.provenance.finalPose,
            finalPowerDbm = persisted.finalPowerDbm,
            samples = emptyList(),
            message = "Restored from persisted measurement record",
            startedAtMs = persisted.provenance.startedAtEpochMs,
            finishedAtMs = persisted.provenance.finishedAtEpochMs
        )
    }

    private fun updateStage(
        stage: SiPhWorkflowStage,
        status: WorkflowStageStatus,
        attempt: Int,
        progress: MutableList<WorkflowStageProgress>,
        message: String
    ) {
        val index = progress.indexOfFirst { it.stage == stage }
        val previous = progress[index]
        progress[index] = previous.copy(
            status = status,
            attempt = attempt,
            startedAtEpochMs = previous.startedAtEpochMs ?: nowEpochMs(),
            finishedAtEpochMs = nowEpochMs().takeIf {
                status == WorkflowStageStatus.Succeeded || status == WorkflowStageStatus.Failed
            },
            message = message
        )
        mutableState.update {
            it.copy(
                stage = stage,
                stageProgress = progress.toList(),
                message = "$stage: $message",
                attempt = attempt
            )
        }
    }

    private fun updateCompletedStage(
        stage: SiPhWorkflowStage,
        progress: MutableList<WorkflowStageProgress>,
        completedCount: Int
    ) {
        mutableState.update {
            it.copy(
                stage = stage,
                stageProgress = progress.toList(),
                completedStageCount = completedCount,
                message = "$stage completed"
            )
        }
    }

    private fun ensureNotStopped() {
        if (stopRequested.value) throw CancellationException("Workflow stop requested")
    }

    private suspend fun stopSafely() {
        withContext(NonCancellable) { runCatching { positioner.stop() } }
    }
}

private class WorkflowStageException(
    message: String,
    val recoverable: Boolean
) : IllegalStateException(message)
