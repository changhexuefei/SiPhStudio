package org.jason.siph.ui.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jason.siph.domain.coupling.AdaptiveCouplingRunner
import org.jason.siph.domain.coupling.CouplingConfig
import org.jason.siph.domain.coupling.CouplingResult
import org.jason.siph.domain.coupling.CouplingResultStatus
import org.jason.siph.domain.coupling.CouplingSample
import org.jason.siph.domain.coupling.CouplingSpiralPlane
import org.jason.siph.domain.coupling.CouplingStage
import org.jason.siph.domain.coupling.CouplingRunner
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalCoordinateFrame
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPowerMeter
import org.jason.siph.ui.model.CouplingConfigUiState
import org.jason.siph.ui.model.CouplingPlane
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.model.CouplingStageUi
import org.jason.siph.ui.model.CouplingState
import org.jason.siph.ui.model.CouplingToolAction
import org.jason.siph.ui.model.CouplingToolRunState
import org.jason.siph.ui.model.CouplingToolUiState
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * SiPh Studio 耦光页面的单一状态源。
 *
 * UI 只发送 [CouplingToolAction]，所有硬件 I/O 和算法执行都在协程中完成，
 * 并通过 [state] 持续发布结果。
 */
class CouplingToolStore(
    private val scope: CoroutineScope,
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,
    private val runner: CouplingRunner,
    private val nowMs: () -> Long
) {

    private val _state = MutableStateFlow(
        CouplingToolUiState(
            status = org.jason.siph.ui.model.CouplingToolStatusState(
                deviceText = "PI: Disconnected | PowerMeter: Disconnected",
                powerText = "Power: -- dBm",
                stateText = "State: Idle",
                message = "Demo hardware ready"
            )
        )
    )
    val state: StateFlow<CouplingToolUiState> = _state.asStateFlow()

    private var couplingJob: Job? = null
    private var deviceJob: Job? = null
    private var powerMeterConnected = false

    fun dispatch(action: CouplingToolAction) {
        when (action) {
            is CouplingToolAction.SelectPage -> updateState {
                it.copy(
                    selectedPage = action.page,
                    status = it.status.copy(message = "Switched to ${action.page.title}")
                )
            }

            CouplingToolAction.ConnectPositioner -> connectDevices()
            CouplingToolAction.DisconnectPositioner -> disconnectDevices()
            CouplingToolAction.ReadPose -> readPose()
            CouplingToolAction.MoveSafe -> moveSafe()
            CouplingToolAction.StopPositioner -> stopPositioner()
            is CouplingToolAction.JogPositioner -> jog(action)

            is CouplingToolAction.UpdateLinearStep -> updateState {
                it.copy(
                    positioner = it.positioner.copy(
                        linearStepUm = action.valueUm.coerceAtLeast(0.0001)
                    )
                )
            }

            is CouplingToolAction.UpdateAngleStep -> updateState {
                it.copy(
                    positioner = it.positioner.copy(
                        angleStepDeg = action.valueDeg.coerceAtLeast(0.000001)
                    )
                )
            }

            is CouplingToolAction.UpdateCouplingConfig -> updateConfig(action.config)
            is CouplingToolAction.UpdateVirtualPivot -> updateVirtualPivot(action.pivot)
            CouplingToolAction.CapturePivotFromCurrentPose -> capturePivot()
            CouplingToolAction.DisableVirtualPivot -> updateVirtualPivot(VirtualPivotPoint.Disabled)
            CouplingToolAction.StartCoupling -> startCoupling()
            CouplingToolAction.StopCoupling -> stopCoupling()
            CouplingToolAction.ClearCouplingData -> clearCouplingData()
            CouplingToolAction.SaveBestPose -> saveBestPose()
        }
    }

    private fun connectDevices() {
        if (_state.value.positioner.connected || _state.value.positioner.connecting) return

        deviceJob?.cancel()
        deviceJob = scope.launch {
            updateState {
                it.copy(
                    positioner = it.positioner.copy(
                        connecting = true,
                        errorMessage = null
                    ),
                    status = it.status.copy(
                        message = "Connecting positioner and power meter...",
                        isError = false
                    )
                )
            }

            try {
                positioner.connect()
                powerMeter.connect()
                powerMeterConnected = true
                positioner.startup(reference = false)

                val idn = positioner.identify()
                val pose = positioner.currentPose()
                val powerMeterId = powerMeter.identify()

                updateState {
                    it.copy(
                        positioner = it.positioner.copy(
                            connected = true,
                            connecting = false,
                            idn = idn,
                            currentPose = pose,
                            errorMessage = null
                        ),
                        status = it.status.copy(
                            deviceText = "PI: Connected | PowerMeter: Connected",
                            message = "$idn | $powerMeterId",
                            isError = false
                        )
                    )
                }
            } catch (error: Throwable) {
                runCatching { positioner.disconnect() }
                runCatching { powerMeter.disconnect() }
                powerMeterConnected = false

                updateState {
                    it.copy(
                        positioner = it.positioner.copy(
                            connected = false,
                            connecting = false,
                            errorMessage = error.message
                        ),
                        status = it.status.copy(
                            deviceText = "PI: Disconnected | PowerMeter: Disconnected",
                            message = error.message ?: "Device connection failed",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    private fun disconnectDevices() {
        deviceJob?.cancel()
        scope.launch {
            stopCouplingAndJoin()
            runCatching { positioner.stop() }
            runCatching { positioner.disconnect() }
            runCatching { powerMeter.disconnect() }
            powerMeterConnected = false

            updateState {
                CouplingToolUiState(
                    selectedPage = it.selectedPage,
                    coupling = it.coupling.copy(
                        isRunning = false,
                        stopRequested = false,
                        state = CouplingState.Idle,
                        currentStage = null,
                        progress = 0f
                    ),
                    status = it.status.copy(
                        deviceText = "PI: Disconnected | PowerMeter: Disconnected",
                        powerText = "Power: -- dBm",
                        stateText = "State: Idle",
                        message = "Devices disconnected",
                        isError = false
                    )
                )
            }
        }
    }

    private fun readPose() {
        launchDeviceOperation("Reading current pose") {
            val pose = positioner.currentPose()
            updateState {
                it.copy(
                    positioner = it.positioner.copy(currentPose = pose),
                    status = it.status.copy(
                        message = "Current pose: ${formatPose(pose)}",
                        isError = false
                    )
                )
            }
        }
    }

    private fun moveSafe() {
        launchDeviceOperation("Moving to safe pose") {
            setMoving(true)
            try {
                positioner.moveToSafePose()
                val pose = positioner.currentPose()
                updateState {
                    it.copy(
                        positioner = it.positioner.copy(
                            currentPose = pose,
                            isMoving = false
                        ),
                        status = it.status.copy(
                            message = "Moved to safe pose",
                            isError = false
                        )
                    )
                }
            } finally {
                setMoving(false)
            }
        }
    }

    private fun stopPositioner() {
        scope.launch {
            runCatching { positioner.stop() }
            setMoving(false)
            updateState {
                it.copy(status = it.status.copy(message = "Positioner stopped"))
            }
        }
    }

    private fun jog(action: CouplingToolAction.JogPositioner) {
        launchDeviceOperation("Jogging positioner") {
            check(!state.value.coupling.isRunning) {
                "Cannot jog while auto coupling is running"
            }
            setMoving(true)
            try {
                positioner.moveBy(action.delta, wait = true)
                val pose = positioner.currentPose()
                updateState {
                    it.copy(
                        positioner = it.positioner.copy(
                            currentPose = pose,
                            isMoving = false
                        ),
                        status = it.status.copy(
                            message = "Jog: ${formatPose(pose)}",
                            isError = false
                        )
                    )
                }
            } finally {
                setMoving(false)
            }
        }
    }

    private fun updateConfig(config: CouplingConfigUiState) {
        if (_state.value.coupling.isRunning) return

        val validation = runCatching { config.toDomain() }
        updateState {
            it.copy(
                coupling = it.coupling.copy(
                    config = config,
                    errorMessage = validation.exceptionOrNull()?.message
                ),
                status = it.status.copy(
                    message = validation.exceptionOrNull()?.message ?: "Coupling config updated",
                    isError = validation.isFailure
                )
            )
        }
    }

    private fun updateVirtualPivot(pivot: VirtualPivotPoint) {
        if (_state.value.coupling.isRunning) return

        updateState {
            it.copy(
                coupling = it.coupling.copy(
                    config = it.coupling.config.copy(
                        virtualPivotPoint = pivot,
                        enableSoftwarePivotCompensation = pivot.enabled
                    )
                ),
                status = it.status.copy(
                    message = if (pivot.enabled) "Virtual pivot updated" else "Virtual pivot disabled",
                    isError = false
                )
            )
        }
    }

    private fun capturePivot() {
        val pose = _state.value.positioner.currentPose
        updateVirtualPivot(
            VirtualPivotPoint(
                xUm = pose.xUm,
                yUm = pose.yUm,
                zUm = pose.zUm,
                frame = OpticalCoordinateFrame.Positioner,
                enabled = true,
                name = "Current optical point"
            )
        )
    }

    private fun startCoupling() {
        val snapshot = _state.value
        if (snapshot.coupling.isRunning) return

        if (!snapshot.positioner.connected || !powerMeterConnected) {
            updateError("Connect the positioner and power meter before starting")
            return
        }

        val configResult = runCatching { snapshot.coupling.config.toDomain() }
        val config = configResult.getOrElse {
            updateError(it.message ?: "Invalid coupling config")
            return
        }

        couplingJob?.cancel()
        couplingJob = scope.launch {
            val estimatedSamples = estimateSamples(config)
            val startedAt = nowMs()

            updateState {
                it.copy(
                    runState = CouplingToolRunState.Running,
                    coupling = it.coupling.copy(
                        state = CouplingState.Initializing,
                        currentStage = null,
                        currentPowerDbm = null,
                        bestPowerDbm = null,
                        bestPose = null,
                        samples = emptyList(),
                        logs = listOf(
                            "Starting adaptive coupling",
                            "Plane=${config.spiralPlane}, step=${config.spiralStepUm} um, radius=${config.maxRadiusUm} um",
                            "Power average=${config.powerAverageCount}, target=${config.targetPowerDbm} dBm"
                        ),
                        isRunning = true,
                        stopRequested = false,
                        progress = 0f,
                        estimatedSamples = estimatedSamples,
                        startedAtMs = startedAt,
                        finishedAtMs = null,
                        message = "Initializing coupling task",
                        errorMessage = null
                    ),
                    status = it.status.copy(
                        stateText = "State: Running",
                        message = "Adaptive coupling started",
                        isError = false
                    )
                )
            }

            try {
                powerMeter.setWavelengthNm(
                    wavelengthNm = config.wavelengthNm,
                    channel = config.powerMeterChannel
                )
                val initialPose = positioner.currentPose()

                val result = runner.run(
                    initialPose = initialPose,
                    config = config,
                    onSample = { sample -> onSample(sample, estimatedSamples) },
                    shouldStop = { _state.value.coupling.stopRequested }
                )
                applyResult(result)
            } catch (cancelled: CancellationException) {
                applyCancelled()
                throw cancelled
            } catch (error: Throwable) {
                applyFailure(error)
            }
        }
    }

    private fun stopCoupling() {
        val job = couplingJob ?: return
        if (!job.isActive) return

        updateState {
            it.copy(
                coupling = it.coupling.copy(
                    stopRequested = true,
                    message = "Stopping coupling..."
                ),
                status = it.status.copy(message = "Stop requested")
            )
        }

        job.cancel(CancellationException("User stopped coupling"))
        scope.launch { runCatching { positioner.stop() } }
    }

    private suspend fun stopCouplingAndJoin() {
        val job = couplingJob ?: return
        if (job.isActive) {
            job.cancel(CancellationException("Device disconnect"))
        }
        runCatching { job.join() }
        couplingJob = null
    }

    private suspend fun onSample(sample: CouplingSample, estimatedSamples: Int) {
        val uiSample = sample.toUi()

        updateState { current ->
            val previousStage = current.coupling.currentStage
            val nextBest = if (
                current.coupling.bestPowerDbm == null ||
                sample.powerDbm > current.coupling.bestPowerDbm
            ) {
                sample
            } else {
                null
            }

            val logs = if (previousStage != uiSample.stage) {
                appendLog(
                    current.coupling.logs,
                    "Stage: ${uiSample.stage.text}"
                )
            } else {
                current.coupling.logs
            }

            val nextSamples = if (current.coupling.samples.size < MAX_UI_SAMPLES) {
                current.coupling.samples + uiSample
            } else {
                current.coupling.samples.drop(1) + uiSample
            }

            current.copy(
                coupling = current.coupling.copy(
                    state = uiSample.stage.toCouplingState(),
                    currentStage = uiSample.stage,
                    currentPowerDbm = uiSample.powerDbm,
                    bestPowerDbm = nextBest?.powerDbm ?: current.coupling.bestPowerDbm,
                    bestPose = nextBest?.pose ?: current.coupling.bestPose,
                    samples = nextSamples,
                    logs = logs,
                    progress = calculateProgress(
                        sampleIndex = sample.index,
                        estimatedSamples = estimatedSamples,
                        stage = uiSample.stage
                    ),
                    message = "${uiSample.stage.text}: ${round3(uiSample.powerDbm)} dBm"
                ),
                positioner = current.positioner.copy(currentPose = uiSample.pose),
                status = current.status.copy(
                    powerText = "Power: ${round3(uiSample.powerDbm)} dBm",
                    stateText = "State: ${uiSample.stage.text}",
                    message = "Sample ${sample.index + 1}",
                    isError = false
                )
            )
        }
    }

    private fun applyResult(result: CouplingResult) {
        val finishedAt = nowMs()
        val resultState = when (result.status) {
            CouplingResultStatus.Success -> CouplingState.Coupled
            CouplingResultStatus.TargetNotReached -> CouplingState.Completed
            CouplingResultStatus.FirstLightNotFound,
            CouplingResultStatus.Failed -> CouplingState.Failed
            CouplingResultStatus.Stopped -> CouplingState.Stopped
        }
        val runState = when (result.status) {
            CouplingResultStatus.Success,
            CouplingResultStatus.TargetNotReached -> CouplingToolRunState.Completed
            CouplingResultStatus.Stopped -> CouplingToolRunState.Stopped
            CouplingResultStatus.FirstLightNotFound,
            CouplingResultStatus.Failed -> CouplingToolRunState.Error
        }

        updateState {
            it.copy(
                runState = runState,
                coupling = it.coupling.copy(
                    state = resultState,
                    currentStage = CouplingStageUi.Final,
                    currentPowerDbm = result.finalPowerDbm.finiteOrNull(),
                    bestPowerDbm = result.bestPowerDbm.finiteOrNull(),
                    bestPose = result.bestPose,
                    isRunning = false,
                    stopRequested = false,
                    progress = 1f,
                    finishedAtMs = finishedAt,
                    message = result.message,
                    errorMessage = if (runState == CouplingToolRunState.Error) result.message else null,
                    logs = appendLog(
                        appendLog(it.coupling.logs, result.message ?: result.status.name),
                        "Finished in ${finishedAt - (it.coupling.startedAtMs ?: finishedAt)} ms"
                    )
                ),
                positioner = it.positioner.copy(
                    currentPose = result.finalPose,
                    isMoving = false
                ),
                status = it.status.copy(
                    powerText = "Power: ${formatPower(result.finalPowerDbm)}",
                    stateText = "State: ${resultState.text}",
                    message = result.message ?: result.status.name,
                    isError = runState == CouplingToolRunState.Error
                )
            )
        }
    }

    private fun applyCancelled() {
        updateState {
            it.copy(
                runState = CouplingToolRunState.Stopped,
                coupling = it.coupling.copy(
                    state = CouplingState.Stopped,
                    isRunning = false,
                    stopRequested = false,
                    finishedAtMs = nowMs(),
                    message = "Coupling stopped",
                    logs = appendLog(it.coupling.logs, "Coupling cancelled; STP requested")
                ),
                positioner = it.positioner.copy(isMoving = false),
                status = it.status.copy(
                    stateText = "State: Stopped",
                    message = "Coupling stopped",
                    isError = false
                )
            )
        }
    }

    private fun applyFailure(error: Throwable) {
        updateState {
            it.copy(
                runState = CouplingToolRunState.Error,
                coupling = it.coupling.copy(
                    state = CouplingState.Failed,
                    isRunning = false,
                    stopRequested = false,
                    finishedAtMs = nowMs(),
                    message = error.message ?: "Coupling failed",
                    errorMessage = error.message,
                    logs = appendLog(it.coupling.logs, "ERROR: ${error.message}")
                ),
                positioner = it.positioner.copy(isMoving = false),
                status = it.status.copy(
                    stateText = "State: Error",
                    message = error.message ?: "Coupling failed",
                    isError = true
                )
            )
        }
    }

    private fun clearCouplingData() {
        if (_state.value.coupling.isRunning) return

        updateState {
            it.copy(
                runState = CouplingToolRunState.Idle,
                coupling = it.coupling.copy(
                    state = CouplingState.Idle,
                    currentStage = null,
                    currentPowerDbm = null,
                    bestPowerDbm = null,
                    bestPose = null,
                    samples = emptyList(),
                    logs = emptyList(),
                    progress = 0f,
                    estimatedSamples = 0,
                    startedAtMs = null,
                    finishedAtMs = null,
                    message = null,
                    errorMessage = null
                ),
                status = it.status.copy(
                    powerText = "Power: -- dBm",
                    stateText = "State: Idle",
                    message = "Coupling data cleared",
                    isError = false
                )
            )
        }
    }

    private fun saveBestPose() {
        val bestPose = _state.value.coupling.bestPose
        updateState {
            if (bestPose == null) {
                it.copy(status = it.status.copy(message = "No best pose to save"))
            } else {
                it.copy(
                    positioner = it.positioner.copy(safePose = bestPose),
                    status = it.status.copy(
                        message = "Best pose saved as safe pose: ${formatPose(bestPose)}",
                        isError = false
                    )
                )
            }
        }
    }

    private fun launchDeviceOperation(
        description: String,
        block: suspend () -> Unit
    ) {
        if (!_state.value.positioner.connected) {
            updateError("Positioner is not connected")
            return
        }

        scope.launch {
            runCatching { block() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    updateError("$description failed: ${error.message}")
                }
        }
    }

    private fun setMoving(moving: Boolean) {
        updateState {
            it.copy(positioner = it.positioner.copy(isMoving = moving))
        }
    }

    private fun updateError(message: String) {
        updateState {
            it.copy(
                status = it.status.copy(message = message, isError = true),
                coupling = it.coupling.copy(errorMessage = message)
            )
        }
    }

    private inline fun updateState(
        crossinline transform: (CouplingToolUiState) -> CouplingToolUiState
    ) {
        _state.update { transform(it) }
    }

    companion object {
        private const val MAX_UI_SAMPLES = 3000
        private const val MAX_LOG_LINES = 500

        private fun appendLog(logs: List<String>, line: String): List<String> {
            val appended = logs + line
            return if (appended.size <= MAX_LOG_LINES) appended else appended.takeLast(MAX_LOG_LINES)
        }
    }
}

/** 创建可在设备离线时完整运行的 Store。 */
fun createDemoCouplingToolStore(scope: CoroutineScope): CouplingToolStore {
    val clockOrigin = TimeSource.Monotonic.markNow()
    val clock = { clockOrigin.elapsedNow().inWholeMilliseconds }
    val positioner = DemoOpticalPositioner()
    val powerMeter = DemoOpticalPowerMeter(
        poseProvider = { positioner.currentPose() }
    )
    val runner = AdaptiveCouplingRunner(
        positioner = positioner,
        powerMeter = powerMeter,
        timeProvider = clock
    )

    return CouplingToolStore(
        scope = scope,
        positioner = positioner,
        powerMeter = powerMeter,
        runner = runner,
        nowMs = clock
    )
}

private fun CouplingConfigUiState.toDomain(): CouplingConfig {
    return CouplingConfig(
        wavelengthNm = wavelengthNm,
        powerMeterChannel = powerMeterChannel,
        spiralPlane = when (plane) {
            CouplingPlane.XY -> CouplingSpiralPlane.XY
            CouplingPlane.YZ -> CouplingSpiralPlane.YZ
            CouplingPlane.XZ -> CouplingSpiralPlane.XZ
        },
        firstLightThresholdDbm = firstLightThresholdDbm,
        targetPowerDbm = targetPowerDbm,
        spiralStepUm = spiralStepUm,
        maxRadiusUm = maxRadiusUm,
        settleDelayMs = settleDelayMs,
        powerAverageCount = powerAverageCount,
        powerAverageDelayMs = powerAverageDelayMs,
        enableFineXyz = enableFineXyz,
        minImproveDb = minImproveDb,
        maxFinePassesPerStep = maxFinePassesPerStep,
        enableIncidentAngleOptimization = enableAngleOptimization,
        virtualPivotPoint = virtualPivotPoint,
        enableSoftwarePivotCompensation = enableSoftwarePivotCompensation,
        maxTotalSamples = maxTotalSamples,
        stopWhenTargetReached = stopWhenTargetReached
    )
}

private fun CouplingSample.toUi(): CouplingSampleUi {
    return CouplingSampleUi(
        index = index,
        pose = pose,
        powerDbm = powerDbm,
        stage = when (stage) {
            CouplingStage.Initial -> CouplingStageUi.Initial
            CouplingStage.SpiralFirstLight -> CouplingStageUi.SpiralFirstLight
            CouplingStage.FineXyz -> CouplingStageUi.FineXyz
            CouplingStage.OptimizeU -> CouplingStageUi.OptimizeU
            CouplingStage.OptimizeV -> CouplingStageUi.OptimizeV
            CouplingStage.OptimizeW -> CouplingStageUi.OptimizeW
            CouplingStage.Final -> CouplingStageUi.Final
        },
        timestampMs = timestampMs
    )
}

private fun CouplingStageUi.toCouplingState(): CouplingState {
    return when (this) {
        CouplingStageUi.Initial -> CouplingState.Initializing
        CouplingStageUi.SpiralFirstLight -> CouplingState.SpiralSearching
        CouplingStageUi.FineXyz -> CouplingState.FineOptimizing
        CouplingStageUi.OptimizeU,
        CouplingStageUi.OptimizeV,
        CouplingStageUi.OptimizeW -> CouplingState.AngleOptimizing
        CouplingStageUi.Final -> CouplingState.Finalizing
    }
}

private fun estimateSamples(config: CouplingConfig): Int {
    val coarse = ceil(PI * config.maxRadiusUm * config.maxRadiusUm /
        (config.spiralStepUm * config.spiralStepUm)).toInt()
    val fine = if (config.enableFineXyz) {
        config.fineStepsUm.size * config.maxFinePassesPerStep * 6
    } else {
        0
    }
    val angle = if (config.enableIncidentAngleOptimization) {
        val averageStep = (config.uStepDeg + config.vStepDeg + config.wStepDeg) / 3.0
        ceil(config.maxAngleRangeDeg / averageStep).toInt() * 6
    } else {
        0
    }
    return (coarse + fine + angle + 2).coerceIn(1, config.maxTotalSamples)
}

private fun calculateProgress(
    sampleIndex: Int,
    estimatedSamples: Int,
    stage: CouplingStageUi
): Float {
    if (stage == CouplingStageUi.Final) return 1f
    if (estimatedSamples <= 0) return 0f

    val raw = (sampleIndex + 1).toFloat() / estimatedSamples.toFloat()
    return raw.coerceIn(0f, 0.97f)
}

private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

private fun formatPower(value: Double): String {
    return if (value.isFinite()) "${round3(value)} dBm" else "-- dBm"
}

private fun formatPose(pose: org.jason.siph.domain.positioner.OpticalPose): String {
    return "X=${round3(pose.xUm)} um, Y=${round3(pose.yUm)} um, " +
        "Z=${round3(pose.zUm)} um, U=${round3(pose.uDeg)} deg, " +
        "V=${round3(pose.vDeg)} deg, W=${round3(pose.wDeg)} deg"
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
