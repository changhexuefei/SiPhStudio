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
 * 第一阶段自主硅光工作流。
 *
 * 不依赖视觉、晶圆台或位移传感器。所有运动均通过 [OpticalPositionerPort]，
 * 因而继续受现有软件限位、控制器行程和安全位保护。
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
    private val stopRequested = MutableStateFlow(false)

    override val state: StateFlow<SiPhWorkflowState> = mutableState.asStateFlow()

    override suspend fun run(
        recipe: SiPhWorkflowRecipe,
        runId: String,
        resumeFromCheckpoint: Boolean
    ): SiPhMeasurementRecord = runMutex.withLock {
        require(runId.isNotBlank()) { "runId must not be blank" }
        stopRequested.value = false

        val stageOrder = stageOrder(recipe)
        val startedAt = nowEpochMs()
        val restored = if (resumeFromCheckpoint) checkpoints.findCheckpoint(runId) else null
        if (restored != null && restored.recipe.id != recipe.id) {
            error("Checkpoint recipe mismatch: ${restored.recipe.id} != ${recipe.id}")
        }

        var checkpoint: SiPhWorkflowCheckpoint = restored ?: SiPhWorkflowCheckpoint(
            runId = runId,
            recipe = recipe,
            completedStages = emptySet(),
            currentStage = SiPhWorkflowStage.Idle,
            updatedAtEpochMs = startedAt
        )
        if (restored == null) checkpoints.saveCheckpoint(checkpoint)

        val context = WorkflowRunContext(
            trainedPosition = checkpoint.trainedPosition,
            record = checkpoint.measurementRecordId?.let { records.findRecord(it) }
        )

        /*
         * 检查点尚未产生测量记录时，恢复后必须重新构建设备身份、校准和训练位置上下文。
         * 这些阶段都是只读或安全绝对移动，可重复执行。
         */
        val completedStages = checkpoint.completedStages.toMutableSet()
        if (context.record == null) {
            completedStages.removeAll(
                setOf(
                    SiPhWorkflowStage.InspectHardware,
                    SiPhWorkflowStage.VerifyCalibration,
                    SiPhWorkflowStage.ResolveMeasurementPosition,
                    SiPhWorkflowStage.MoveToMeasurementPosition,
                    SiPhWorkflowStage.AutoCoupling
                )
            )
        }

        val progress = stageOrder.map { stage ->
            WorkflowStageProgress(
                stage = stage,
                status = if (stage in completedStages) {
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
            message = if (restored == null) {
                "Workflow started"
            } else {
                "Workflow restored from checkpoint"
            },
            running = true,
            completedStageCount = completedStages.size,
            totalStageCount = stageOrder.size,
            measurementRecordId = context.record?.id,
            startedAtEpochMs = startedAt
        )

        try {
            for (stage in stageOrder) {
                currentCoroutineContext().ensureActive()
                ensureNotStopped()
                if (stage in completedStages) continue

                executeStageWithRetry(
                    stage = stage,
                    retryPolicy = recipe.retryPolicy,
                    progress = progress,
                    trainedPositionProvider = { context.trainedPosition },
                    onAttemptFailure = { failure ->
                        checkpoint = checkpoint.copy(
                            currentStage = stage,
                            failures = checkpoint.failures + failure,
                            trainedPosition = context.trainedPosition,
                            measurementRecordId = context.record?.id ?: checkpoint.measurementRecordId,
                            updatedAtEpochMs = nowEpochMs()
                        )
                        checkpoints.saveCheckpoint(checkpoint)
                    }
                ) {
                    executeStage(
                        stage = stage,
                        recipe = recipe,
                        runId = runId,
                        workflowStartedAt = startedAt,
                        context = context
                    )
                }

                completedStages += stage
                checkpoint = checkpoint.copy(
                    completedStages = completedStages.toSet(),
                    currentStage = stage,
                    trainedPosition = context.trainedPosition,
                    bestPose = context.record?.provenance?.bestPose ?: checkpoint.bestPose,
                    bestPowerDbm = context.record?.bestPowerDbm ?: checkpoint.bestPowerDbm,
                    measurementRecordId = context.record?.id ?: checkpoint.measurementRecordId,
                    updatedAtEpochMs = nowEpochMs()
                )
                checkpoints.saveCheckpoint(checkpoint)
                updateCompletedStage(stage, progress, completedStages.size)
            }

            val completed = requireNotNull(context.record) {
                "Workflow completed without a measurement record"
            }
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
            val existingFailure = mutableState.value.lastFailure
            val failure = existingFailure ?: WorkflowFailure(
                stage = mutableState.value.stage,
                attempt = mutableState.value.attempt,
                message = error.message ?: error::class.simpleName ?: "Workflow failure",
                recoverable = (error as? WorkflowStageException)?.recoverable ?: false,
                occurredAtEpochMs = nowEpochMs()
            )
            if (checkpoint.failures.lastOrNull() != failure) {
                checkpoint = checkpoint.copy(
                    currentStage = failure.stage,
                    failures = checkpoint.failures + failure,
                    trainedPosition = context.trainedPosition,
                    measurementRecordId = context.record?.id ?: checkpoint.measurementRecordId,
                    updatedAtEpochMs = nowEpochMs()
                )
                checkpoints.saveCheckpoint(checkpoint)
            }
            context.record?.let { partial ->
                records.saveRecord(
                    partial.copy(
                        completed = false,
                        failureMessage = failure.message,
                        provenance = partial.provenance.copy(finishedAtEpochMs = nowEpochMs())
                    )
                )
            }
            mutableState.update {
                it.copy(
                    stage = SiPhWorkflowStage.Failed,
                    message = failure.message,
                    running = false,
                    lastFailure = failure,
                    measurementRecordId = context.record?.id,
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

    private suspend fun executeStage(
        stage: SiPhWorkflowStage,
        recipe: SiPhWorkflowRecipe,
        runId: String,
        workflowStartedAt: Long,
        context: WorkflowRunContext
    ) {
        when (stage) {
            SiPhWorkflowStage.InspectHardware -> inspectHardware(recipe, context)
            SiPhWorkflowStage.VerifyCalibration -> verifyCalibration(recipe, context)
            SiPhWorkflowStage.ResolveMeasurementPosition -> resolveMeasurementPosition(recipe, context)
            SiPhWorkflowStage.MoveToMeasurementPosition -> moveToMeasurementPosition(recipe, context)
            SiPhWorkflowStage.AutoCoupling -> runCoupling(
                recipe = recipe,
                runId = runId,
                workflowStartedAt = workflowStartedAt,
                context = context
            )
            SiPhWorkflowStage.VerifyReturnRepeatability -> verifyReturnRepeatability(recipe, context)
            SiPhWorkflowStage.AssessDrift -> assessDrift(recipe, context)
            SiPhWorkflowStage.PersistMeasurement -> persistCompletedMeasurement(context)
            else -> Unit
        }
    }

    private suspend fun inspectHardware(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        if (recipe.manageDeviceConnections) {
            positioner.connect()
            powerMeter.connect()
            positioner.startup(reference = false)
        }
        context.positionerIdentity = positioner.identify().also {
            require(it.isNotBlank()) { "Positioner identity is blank" }
        }
        context.powerMeterIdentity = powerMeter.identify().also {
            require(it.isNotBlank()) { "Power meter identity is blank" }
        }
        powerMeter.setWavelengthNm(
            wavelengthNm = recipe.couplingConfig.wavelengthNm,
            channel = recipe.couplingConfig.powerMeterChannel
        )
    }

    private suspend fun verifyCalibration(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        val calibration = resolveCalibration(recipe)
        val expectedController = calibration.controllerIdentity
        val actualController = context.positionerIdentity ?: positioner.identify().also {
            context.positionerIdentity = it
        }
        if (!expectedController.isNullOrBlank() &&
            !actualController.contains(expectedController, ignoreCase = true)
        ) {
            throw WorkflowStageException(
                "Controller identity does not match calibration profile",
                recoverable = false
            )
        }
        context.calibration = calibration
    }

    private suspend fun resolveMeasurementPosition(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        val calibration = context.calibration ?: resolveCalibration(recipe).also {
            context.calibration = it
        }
        val trained = resolvePosition(recipe)
        if (trained.calibrationProfileId != calibration.id) {
            throw WorkflowStageException(
                "Trained position belongs to calibration ${trained.calibrationProfileId}, " +
                    "active calibration is ${calibration.id}",
                recoverable = false
            )
        }
        context.trainedPosition = trained
    }

    private suspend fun moveToMeasurementPosition(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        val trained = context.trainedPosition ?: resolvePosition(recipe).also {
            context.trainedPosition = it
        }
        positioner.moveTo(trained.pose, wait = true)
        val actual = positioner.currentPose()
        val errorUm = actual.linearDistanceTo(trained.pose)
        if (errorUm > recipe.verificationConfig.maxReturnPositionErrorUm) {
            throw WorkflowStageException(
                "Initial move position error $errorUm um exceeds allowed value",
                recoverable = true
            )
        }
    }

    private suspend fun runCoupling(
        recipe: SiPhWorkflowRecipe,
        runId: String,
        workflowStartedAt: Long,
        context: WorkflowRunContext
    ) {
        val calibration = context.calibration ?: resolveCalibration(recipe).also {
            context.calibration = it
        }
        val trained = context.trainedPosition ?: resolvePosition(recipe).also {
            context.trainedPosition = it
        }
        val positionerIdentity = context.positionerIdentity ?: positioner.identify().also {
            context.positionerIdentity = it
        }
        val powerMeterIdentity = context.powerMeterIdentity ?: powerMeter.identify().also {
            context.powerMeterIdentity = it
        }
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
        context.couplingResult = result
        context.record = createPartialRecord(
            runId = runId,
            recipe = recipe,
            calibration = calibration,
            trainedPosition = trained,
            positionerIdentity = positionerIdentity,
            powerMeterIdentity = powerMeterIdentity,
            result = result,
            startedAt = workflowStartedAt
        )
        records.saveRecord(requireNotNull(context.record))
    }

    private suspend fun verifyReturnRepeatability(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        val record = requireNotNull(context.record) { "Measurement record is unavailable" }
        val bestPose = context.couplingResult?.bestPose ?: record.provenance.bestPose
        val result = verifier.verify(
            bestPose = bestPose,
            powerMeterChannel = recipe.couplingConfig.powerMeterChannel,
            config = recipe.verificationConfig
        )
        context.verification = result
        context.record = record.copy(verification = result)
        records.saveRecord(requireNotNull(context.record))
        if (!result.passed) {
            throw WorkflowStageException(
                "Optical return verification failed: ${result.failures.joinToString()}",
                recoverable = true
            )
        }
    }

    private suspend fun assessDrift(
        recipe: SiPhWorkflowRecipe,
        context: WorkflowRunContext
    ) {
        val record = requireNotNull(context.record) { "Measurement record is unavailable" }
        val calibrationId = context.calibration?.id ?: record.provenance.calibrationProfileId
        val baseline = baselines.findBaseline(recipe.site)
        if (baseline == null) {
            baselines.saveBaseline(
                DriftBaseline(
                    id = "baseline-${recipe.site.stableId}",
                    site = recipe.site,
                    referencePose = record.provenance.bestPose,
                    referencePowerDbm = record.bestPowerDbm,
                    calibrationProfileId = calibrationId,
                    createdAtEpochMs = nowEpochMs()
                )
            )
            context.driftAssessment = null
            return
        }

        val assessment = driftEvaluator.assess(
            baseline = baseline,
            currentPose = record.provenance.bestPose,
            currentPowerDbm = record.bestPowerDbm,
            currentTemperatureC = null,
            policy = recipe.driftPolicy
        )
        context.driftAssessment = assessment
        context.record = record.copy(driftAssessment = assessment)
        records.saveRecord(requireNotNull(context.record))

        when (assessment.action) {
            DriftAction.StopWorkflow -> throw WorkflowStageException(
                "Drift policy requested workflow stop",
                recoverable = false
            )
            DriftAction.FullRecalibration -> throw WorkflowStageException(
                "Drift requires full recalibration",
                recoverable = false
            )
            DriftAction.LocalRealign,
            DriftAction.Continue -> Unit
        }
    }

    private suspend fun persistCompletedMeasurement(context: WorkflowRunContext) {
        val record = requireNotNull(context.record) { "Measurement record is unavailable" }
        val completed = record.copy(
            verification = context.verification ?: record.verification,
            driftAssessment = context.driftAssessment ?: record.driftAssessment,
            completed = true,
            failureMessage = null,
            provenance = record.provenance.copy(
                finalPose = positioner.currentPose(),
                finishedAtEpochMs = nowEpochMs()
            )
        )
        context.record = completed
        records.saveRecord(completed)
    }

    private suspend fun executeStageWithRetry(
        stage: SiPhWorkflowStage,
        retryPolicy: WorkflowRetryPolicy,
        progress: MutableList<WorkflowStageProgress>,
        trainedPositionProvider: () -> TrainedMeasurementPosition?,
        onAttemptFailure: suspend (WorkflowFailure) -> Unit,
        block: suspend () -> Unit
    ) {
        var retryDelayMs = retryPolicy.initialDelayMs
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
                val stageRetryable = stage in retryPolicy.retryableStages
                val errorRetryable = (error as? WorkflowStageException)?.recoverable ?: true
                val canRetry = stageRetryable && errorRetryable && attempt < retryPolicy.maxAttempts
                val failure = WorkflowFailure(
                    stage = stage,
                    attempt = attempt,
                    message = error.message ?: "Stage failed",
                    recoverable = canRetry,
                    occurredAtEpochMs = nowEpochMs()
                )
                mutableState.update { it.copy(lastFailure = failure) }
                updateStage(stage, WorkflowStageStatus.Failed, attempt, progress, failure.message)
                onAttemptFailure(failure)
                if (!canRetry) throw error

                stopSafely()
                trainedPositionProvider()?.let { trained ->
                    runCatching { positioner.moveTo(trained.pose, wait = true) }
                }
                if (retryDelayMs > 0L) delay(retryDelayMs)
                retryDelayMs = min(
                    retryPolicy.maximumDelayMs,
                    (retryDelayMs.toDouble() * retryPolicy.backoffMultiplier).toLong()
                )
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
            throw WorkflowStageException(
                "Trained position site does not match recipe site",
                recoverable = false
            )
        }
        if (recipe.requireVerifiedMeasurementPosition && !position.verified) {
            throw WorkflowStageException(
                "Trained measurement position is not verified",
                recoverable = false
            )
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

    private fun updateStage(
        stage: SiPhWorkflowStage,
        status: WorkflowStageStatus,
        attempt: Int,
        progress: MutableList<WorkflowStageProgress>,
        message: String
    ) {
        val index = progress.indexOfFirst { it.stage == stage }
        check(index >= 0) { "Unknown workflow stage: $stage" }
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

    private fun stageOrder(recipe: SiPhWorkflowRecipe): List<SiPhWorkflowStage> = buildList {
        add(SiPhWorkflowStage.InspectHardware)
        add(SiPhWorkflowStage.VerifyCalibration)
        add(SiPhWorkflowStage.ResolveMeasurementPosition)
        add(SiPhWorkflowStage.MoveToMeasurementPosition)
        add(SiPhWorkflowStage.AutoCoupling)
        if (recipe.enableVerification) add(SiPhWorkflowStage.VerifyReturnRepeatability)
        if (recipe.enableDriftAssessment) add(SiPhWorkflowStage.AssessDrift)
        add(SiPhWorkflowStage.PersistMeasurement)
    }

    private fun ensureNotStopped() {
        if (stopRequested.value) throw CancellationException("Workflow stop requested")
    }

    private suspend fun stopSafely() {
        withContext(NonCancellable) { runCatching { positioner.stop() } }
    }

    private class WorkflowRunContext(
        var calibration: CalibrationProfile? = null,
        var trainedPosition: TrainedMeasurementPosition? = null,
        var positionerIdentity: String? = null,
        var powerMeterIdentity: String? = null,
        var couplingResult: CouplingResult? = null,
        var record: SiPhMeasurementRecord? = null,
        var verification: OpticalAlignmentVerificationResult? = record?.verification,
        var driftAssessment: DriftAssessment? = record?.driftAssessment
    )
}

private class WorkflowStageException(
    message: String,
    val recoverable: Boolean
) : IllegalStateException(message)
