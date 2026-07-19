package org.jason.siph.domain.oo

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
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import kotlin.math.min

interface OoMeasurementRunner {
    val state: StateFlow<OoMeasurementState>

    suspend fun run(
        recipe: OoMeasurementRecipe,
        wafer: SiPhWaferDefinition,
        runId: String,
        resumeFromCheckpoint: Boolean = true
    ): OoMeasurementResult

    suspend fun requestPause()
    suspend fun resume()
    suspend fun requestStop()
}

class DefaultOoMeasurementRunner(
    private val laser: TunableLaserPort,
    private val powerMeter: OoOpticalPowerMeterPort,
    private val prober: WaferProberPort,
    private val temperatureController: TemperatureControllerPort,
    private val alignment: OoAlignmentPort,
    private val repository: OoMeasurementRepository,
    private val traversalPlanner: WaferTraversalPlanner = WaferTraversalPlanner(),
    private val nowEpochMs: () -> Long
) : OoMeasurementRunner {

    private val runMutex = Mutex()
    private val mutableState = MutableStateFlow(OoMeasurementState())
    private val pauseRequested = MutableStateFlow(false)
    private val stopRequested = MutableStateFlow(false)

    override val state: StateFlow<OoMeasurementState> = mutableState.asStateFlow()

    override suspend fun run(
        recipe: OoMeasurementRecipe,
        wafer: SiPhWaferDefinition,
        runId: String,
        resumeFromCheckpoint: Boolean
    ): OoMeasurementResult = runMutex.withLock {
        require(runId.isNotBlank())
        require(recipe.waferId == wafer.id) {
            "Recipe wafer ${recipe.waferId} does not match loaded wafer ${wafer.id}"
        }
        pauseRequested.value = false
        stopRequested.value = false

        val route = traversalPlanner.buildRoute(
            wafer = wafer,
            strategy = recipe.traversalStrategy,
            explicitSiteOrder = recipe.explicitSiteOrder,
            selectedSites = recipe.selectedSites
        )
        require(route.isNotEmpty()) { "Wafer route has no enabled measurement sites" }

        val restored = if (resumeFromCheckpoint) repository.findCheckpoint(runId) else null
        if (restored != null) {
            require(restored.recipe.id == recipe.id) { "Checkpoint recipe mismatch" }
            require(restored.waferSnapshot.id == wafer.id) { "Checkpoint wafer mismatch" }
        }

        val startedAt = repository.findResult(runId)?.startedAtEpochMs ?: nowEpochMs()
        var result = repository.findResult(runId) ?: OoMeasurementResult(
            runId = runId,
            recipe = recipe,
            waferSnapshot = wafer,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = startedAt,
            completed = false
        )
        var checkpoint = restored ?: OoMeasurementCheckpoint(
            runId = runId,
            recipe = recipe,
            waferSnapshot = wafer,
            updatedAtEpochMs = startedAt
        )
        repository.saveCheckpoint(checkpoint)
        repository.saveResult(result)

        mutableState.value = OoMeasurementState(
            runId = runId,
            stage = OoMeasurementStage.ValidateRecipe,
            message = if (restored == null) "O-O workflow started" else "O-O workflow restored from checkpoint",
            running = true,
            completedMeasurements = checkpoint.completedMeasurementKeys.size,
            totalMeasurements = route.size * recipe.temperaturesC.size,
            startedAtEpochMs = startedAt
        )

        try {
            setStage(OoMeasurementStage.ValidateRecipe, "Validated O-O recipe and wafer route")
            if (recipe.manageDeviceConnections) connectDevices(recipe.retryPolicy, result, checkpoint)
            val snapshots = inspectDevices(recipe.retryPolicy, result, checkpoint)
            result = result.copy(deviceSnapshots = snapshots)
            repository.saveResult(result)

            setStage(OoMeasurementStage.LoadWaferMap, "Loading WaferMap")
            retryOperation(
                stage = OoMeasurementStage.LoadWaferMap,
                site = null,
                temperatureC = null,
                policy = recipe.retryPolicy,
                onFailure = { failure ->
                    result = result.copy(failures = result.failures + failure)
                    checkpoint = checkpoint.copy(
                        failures = checkpoint.failures + failure,
                        updatedAtEpochMs = nowEpochMs()
                    )
                    persistProgress(result, checkpoint)
                }
            ) {
                prober.loadMap(wafer)
                // 建立探针台内部有效 Die 索引，避免后续 dieNumber/currentIndex 为 0。
                prober.moveToFirstDie()
                prober.separate()
            }

            for (temperatureIndex in recipe.temperaturesC.indices) {
                val temperatureC = recipe.temperaturesC[temperatureIndex]
                if (temperatureIndex < checkpoint.currentTemperatureIndex) continue
                awaitIfPaused()
                ensureNotStopped()
                stabilizeTemperature(temperatureC, recipe, result, checkpoint)
                configureMeasurementDevices(recipe, result, checkpoint)

                for (siteIndex in route.indices) {
                    val site = route[siteIndex]
                    val key = measurementKey(temperatureC, site)
                    if (key in checkpoint.completedMeasurementKeys) continue
                    awaitIfPaused()
                    ensureNotStopped()
                    mutableState.update {
                        it.copy(
                            temperatureC = temperatureC,
                            site = site,
                            completedMeasurements = checkpoint.completedMeasurementKeys.size
                        )
                    }
                    checkpoint = checkpoint.copy(
                        currentTemperatureIndex = temperatureIndex,
                        currentSiteIndex = siteIndex,
                        updatedAtEpochMs = nowEpochMs()
                    )
                    repository.saveCheckpoint(checkpoint)

                    val siteResult = executeSite(
                        recipe = recipe,
                        site = site,
                        temperatureC = temperatureC,
                        existingResult = result,
                        checkpointProvider = { checkpoint },
                        onFailure = { failure ->
                            result = result.copy(failures = result.failures + failure)
                            checkpoint = checkpoint.copy(
                                failures = checkpoint.failures + failure,
                                updatedAtEpochMs = nowEpochMs()
                            )
                            persistProgress(result, checkpoint)
                        }
                    )
                    result = result.copy(
                        siteResults = result.siteResults
                            .filterNot { it.site == site && it.temperatureC == temperatureC } + siteResult,
                        finishedAtEpochMs = nowEpochMs()
                    )
                    checkpoint = checkpoint.copy(
                        completedMeasurementKeys = checkpoint.completedMeasurementKeys + key,
                        currentTemperatureIndex = temperatureIndex,
                        currentSiteIndex = siteIndex + 1,
                        updatedAtEpochMs = nowEpochMs()
                    )
                    setStage(OoMeasurementStage.PersistResult, "Persisting ${site.stableId} at $temperatureC C")
                    persistProgress(result, checkpoint)
                    mutableState.update {
                        it.copy(completedMeasurements = checkpoint.completedMeasurementKeys.size)
                    }
                }
            }

            setStage(OoMeasurementStage.ReturnSafePosition, "Returning equipment to safe state")
            safeShutdown()
            result = result.copy(
                completed = true,
                stopped = false,
                finishedAtEpochMs = nowEpochMs()
            )
            repository.saveResult(result)
            repository.deleteCheckpoint(runId)
            mutableState.update {
                it.copy(
                    stage = OoMeasurementStage.Completed,
                    message = "O-O workflow completed",
                    running = false,
                    paused = false,
                    stopRequested = false,
                    completedMeasurements = it.totalMeasurements,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            result
        } catch (cancelled: CancellationException) {
            safeShutdown()
            result = result.copy(stopped = true, finishedAtEpochMs = nowEpochMs())
            repository.saveResult(result)
            mutableState.update {
                it.copy(
                    stage = OoMeasurementStage.Stopped,
                    message = cancelled.message ?: "O-O workflow stopped",
                    running = false,
                    paused = false,
                    stopRequested = true,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            safeShutdown()
            result = result.copy(completed = false, finishedAtEpochMs = nowEpochMs())
            repository.saveResult(result)
            repository.saveCheckpoint(checkpoint.copy(updatedAtEpochMs = nowEpochMs()))
            mutableState.update {
                it.copy(
                    stage = OoMeasurementStage.Failed,
                    message = error.message ?: "O-O workflow failed",
                    running = false,
                    paused = false,
                    finishedAtEpochMs = nowEpochMs()
                )
            }
            throw error
        } finally {
            if (recipe.manageDeviceConnections) disconnectDevices()
        }
    }

    override suspend fun requestPause() {
        if (!mutableState.value.running) return
        pauseRequested.value = true
        mutableState.update {
            it.copy(paused = true, stage = OoMeasurementStage.Paused, message = "Pause requested")
        }
    }

    override suspend fun resume() {
        pauseRequested.value = false
        mutableState.update { it.copy(paused = false, message = "O-O workflow resumed") }
    }

    override suspend fun requestStop() {
        stopRequested.value = true
        mutableState.update { it.copy(stopRequested = true, message = "Stop requested") }
        safeShutdown()
    }

    private suspend fun connectDevices(
        policy: OoRetryPolicy,
        result: OoMeasurementResult,
        checkpoint: OoMeasurementCheckpoint
    ) {
        setStage(OoMeasurementStage.ConnectDevices, "Connecting O-O equipment")
        val failures = mutableListOf<OoMeasurementFailure>()
        retryOperation(OoMeasurementStage.ConnectDevices, null, null, policy, failures::add) {
            laser.connect()
            powerMeter.connect()
            prober.connect()
            temperatureController.connect()
        }
        if (failures.isNotEmpty()) {
            persistProgress(
                result.copy(failures = result.failures + failures),
                checkpoint.copy(failures = checkpoint.failures + failures, updatedAtEpochMs = nowEpochMs())
            )
        }
    }

    private suspend fun inspectDevices(
        policy: OoRetryPolicy,
        result: OoMeasurementResult,
        checkpoint: OoMeasurementCheckpoint
    ): List<OoDeviceSnapshot> {
        setStage(OoMeasurementStage.InspectDevices, "Inspecting O-O equipment")
        var snapshots = emptyList<OoDeviceSnapshot>()
        retryOperation(OoMeasurementStage.InspectDevices, null, null, policy, { failure ->
            persistProgress(
                result.copy(failures = result.failures + failure),
                checkpoint.copy(failures = checkpoint.failures + failure, updatedAtEpochMs = nowEpochMs())
            )
        }) {
            val laserIdentity = laser.identify()
            val meterIdentity = powerMeter.identify()
            val proberIdentity = prober.identify()
            val temperatureIdentity = temperatureController.identify()
            listOf(laser.descriptor, powerMeter.descriptor, prober.descriptor, temperatureController.descriptor)
                .forEach(::requireUsableDescriptor)
            laser.capabilities()
            powerMeter.capabilities()
            prober.capabilities()
            temperatureController.capabilities()
            snapshots = listOf(
                OoDeviceSnapshot("laser", laserIdentity, laser.descriptor),
                OoDeviceSnapshot("powerMeter", meterIdentity, powerMeter.descriptor),
                OoDeviceSnapshot("prober", proberIdentity, prober.descriptor),
                OoDeviceSnapshot("temperature", temperatureIdentity, temperatureController.descriptor)
            )
        }
        return snapshots
    }

    private suspend fun stabilizeTemperature(
        temperatureC: Double,
        recipe: OoMeasurementRecipe,
        result: OoMeasurementResult,
        checkpoint: OoMeasurementCheckpoint
    ) {
        setStage(OoMeasurementStage.StabilizeTemperature, "Stabilizing at $temperatureC C")
        retryOperation(OoMeasurementStage.StabilizeTemperature, null, temperatureC, recipe.retryPolicy, { failure ->
            persistProgress(
                result.copy(failures = result.failures + failure),
                checkpoint.copy(failures = checkpoint.failures + failure, updatedAtEpochMs = nowEpochMs())
            )
        }) {
            temperatureController.setSetpointC(temperatureC)
            temperatureController.startControl()
            val stability = temperatureController.waitUntilStable(recipe.temperatureStability)
            check(stability.stable) { stability.message }
            check(!stability.finalSnapshot.alarmActive) { "Temperature controller alarm is active" }
        }
    }

    private suspend fun configureMeasurementDevices(
        recipe: OoMeasurementRecipe,
        result: OoMeasurementResult,
        checkpoint: OoMeasurementCheckpoint
    ) {
        setStage(OoMeasurementStage.ConfigureLaser, "Configuring tunable laser")
        retryOperation(OoMeasurementStage.ConfigureLaser, null, mutableState.value.temperatureC, recipe.retryPolicy, { failure ->
            persistProgress(
                result.copy(failures = result.failures + failure),
                checkpoint.copy(failures = checkpoint.failures + failure, updatedAtEpochMs = nowEpochMs())
            )
        }) {
            laser.configureSweep(recipe.sweep)
            laser.setOutputEnabled(true)
        }
        setStage(OoMeasurementStage.ConfigurePowerMeter, "Configuring optical power meter")
        retryOperation(OoMeasurementStage.ConfigurePowerMeter, null, mutableState.value.temperatureC, recipe.retryPolicy, { failure ->
            persistProgress(
                result.copy(failures = result.failures + failure),
                checkpoint.copy(failures = checkpoint.failures + failure, updatedAtEpochMs = nowEpochMs())
            )
        }) {
            powerMeter.setRange(recipe.powerMeterRange, recipe.powerMeterChannel)
            powerMeter.setAveraging(recipe.powerMeterAveraging, recipe.powerMeterChannel)
            powerMeter.configureTrigger(
                OpticalTriggerConfig(
                    mode = if (recipe.acquisitionMode == SweepAcquisitionMode.HardwareTriggered) {
                        OpticalTriggerMode.ExternalRising
                    } else {
                        OpticalTriggerMode.Immediate
                    },
                    expectedPoints = recipe.sweep.pointCount
                )
            )
        }
    }

    private suspend fun executeSite(
        recipe: OoMeasurementRecipe,
        site: MeasurementSiteKey,
        temperatureC: Double,
        existingResult: OoMeasurementResult,
        checkpointProvider: () -> OoMeasurementCheckpoint,
        onFailure: suspend (OoMeasurementFailure) -> Unit
    ): OoSiteMeasurementResult {
        setStage(OoMeasurementStage.MoveToSite, "Moving to ${site.stableId}")
        retryOperation(OoMeasurementStage.MoveToSite, site, temperatureC, recipe.retryPolicy, onFailure) {
            prober.moveToSite(site)
        }

        if (recipe.contactBeforeMeasurement) {
            setStage(OoMeasurementStage.Contact, "Contacting ${site.stableId}")
            retryOperation(OoMeasurementStage.Contact, site, temperatureC, recipe.retryPolicy, onFailure) {
                prober.contact()
            }
        }

        val alignmentResult = if (recipe.enableOpticalAlignment) {
            setStage(OoMeasurementStage.OpticalAlignment, "Aligning ${site.stableId}")
            var value: OoAlignmentResult? = null
            retryOperation(OoMeasurementStage.OpticalAlignment, site, temperatureC, recipe.retryPolicy, onFailure) {
                value = alignment.align(site)
                check(value?.aligned == true) { value?.message ?: "Optical alignment failed" }
            }
            value
        } else {
            null
        }

        setStage(OoMeasurementStage.ExecuteWavelengthSweep, "Sweeping ${site.stableId}")
        val startedAt = nowEpochMs()
        var points = emptyList<OoMeasurementPoint>()
        retryOperation(OoMeasurementStage.ExecuteWavelengthSweep, site, temperatureC, recipe.retryPolicy, onFailure) {
            points = acquireSweep(recipe, site, temperatureC)
        }

        setStage(OoMeasurementStage.ValidateMeasurement, "Validating ${site.stableId}")
        val messages = validatePoints(points, recipe)
        if (messages.isNotEmpty()) {
            val failure = OoMeasurementFailure(
                stage = OoMeasurementStage.ValidateMeasurement,
                site = site,
                temperatureC = temperatureC,
                attempt = 1,
                message = messages.joinToString(),
                recoverable = true,
                occurredAtEpochMs = nowEpochMs()
            )
            onFailure(failure)
            error(failure.message)
        }

        if (recipe.separateAfterEachSite) {
            setStage(OoMeasurementStage.Separate, "Separating from ${site.stableId}")
            prober.separate()
        }

        return OoSiteMeasurementResult(
            site = site,
            temperatureC = temperatureC,
            alignmentPeakPowerDbm = alignmentResult?.peakPowerDbm,
            points = points,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = nowEpochMs(),
            valid = true,
            validationMessages = emptyList()
        )
    }

    private suspend fun acquireSweep(
        recipe: OoMeasurementRecipe,
        site: MeasurementSiteKey,
        temperatureC: Double
    ): List<OoMeasurementPoint> {
        val wavelengths = recipe.sweep.wavelengths()
        return when (recipe.acquisitionMode) {
            SweepAcquisitionMode.SoftwareStep -> buildList {
                laser.setOutputEnabled(true)
                wavelengths.forEach { wavelength ->
                    awaitIfPaused()
                    ensureNotStopped()
                    laser.setWavelengthNm(wavelength)
                    powerMeter.setWavelengthNm(wavelength, recipe.powerMeterChannel)
                    if (recipe.sweep.dwellMs > 0L) delay(recipe.sweep.dwellMs)
                    val output = powerMeter.readPowerDbm(recipe.powerMeterChannel)
                    add(point(recipe, site, temperatureC, wavelength, output))
                }
            }

            SweepAcquisitionMode.HardwareTriggered -> {
                laser.startSweep()
                try {
                    val log = powerMeter.acquireLog(
                        OpticalLogAcquisitionRequest(
                            wavelengthsNm = wavelengths,
                            channel = recipe.powerMeterChannel
                        )
                    )
                    check(log.completed) { log.message ?: "Hardware-triggered acquisition incomplete" }
                    log.wavelengthsNm.zip(log.powersDbm).map { (wavelength, output) ->
                        point(recipe, site, temperatureC, wavelength, output)
                    }
                } finally {
                    laser.stopSweep()
                }
            }
        }
    }

    private fun point(
        recipe: OoMeasurementRecipe,
        site: MeasurementSiteKey,
        temperatureC: Double,
        wavelengthNm: Double,
        outputPowerDbm: Double
    ): OoMeasurementPoint {
        val input = recipe.sweep.powerDbm
        return OoMeasurementPoint(
            wavelengthNm = wavelengthNm,
            inputPowerDbm = input,
            outputPowerDbm = outputPowerDbm,
            insertionLossDb = input - outputPowerDbm,
            temperatureC = temperatureC,
            site = site,
            timestampEpochMs = nowEpochMs()
        )
    }

    private fun validatePoints(
        points: List<OoMeasurementPoint>,
        recipe: OoMeasurementRecipe
    ): List<String> = buildList {
        if (points.size != recipe.sweep.pointCount) {
            add("Expected ${recipe.sweep.pointCount} points, actual=${points.size}")
        }
        if (points.any { !it.outputPowerDbm.isFinite() }) {
            add("Power data contains non-finite values")
        }
        if (points.zipWithNext().any { (left, right) -> right.wavelengthNm <= left.wavelengthNm }) {
            add("Wavelength data is not strictly increasing")
        }
    }

    private suspend fun retryOperation(
        stage: OoMeasurementStage,
        site: MeasurementSiteKey?,
        temperatureC: Double?,
        policy: OoRetryPolicy,
        onFailure: suspend (OoMeasurementFailure) -> Unit,
        block: suspend () -> Unit
    ) {
        var retryDelayMs = policy.initialDelayMs
        var lastError: Throwable? = null
        for (attempt in 1..policy.maxAttempts) {
            awaitIfPaused()
            ensureNotStopped()
            mutableState.update { it.copy(currentAttempt = attempt) }
            try {
                block()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                val canRetry = attempt < policy.maxAttempts
                val failure = OoMeasurementFailure(
                    stage = stage,
                    site = site,
                    temperatureC = temperatureC,
                    attempt = attempt,
                    message = error.message ?: "O-O operation failed",
                    recoverable = canRetry,
                    occurredAtEpochMs = nowEpochMs()
                )
                mutableState.update { it.copy(latestFailure = failure) }
                onFailure(failure)
                if (!canRetry) throw error
                safeTransientStop()
                if (retryDelayMs > 0L) delay(retryDelayMs)
                retryDelayMs = min(
                    policy.maximumDelayMs,
                    (retryDelayMs.toDouble() * policy.backoffMultiplier).toLong()
                )
            }
        }
        throw lastError ?: error("O-O operation failed without an exception")
    }

    private suspend fun persistProgress(
        result: OoMeasurementResult,
        checkpoint: OoMeasurementCheckpoint
    ) {
        repository.saveResult(result)
        repository.saveCheckpoint(checkpoint)
    }

    private suspend fun setStage(stage: OoMeasurementStage, message: String) {
        currentCoroutineContext().ensureActive()
        ensureNotStopped()
        mutableState.update { it.copy(stage = stage, message = message) }
    }

    private suspend fun awaitIfPaused() {
        while (pauseRequested.value) {
            currentCoroutineContext().ensureActive()
            ensureNotStopped()
            delay(20L)
        }
    }

    private fun ensureNotStopped() {
        if (stopRequested.value) throw CancellationException("O-O workflow stop requested")
    }

    private fun requireUsableDescriptor(descriptor: DeviceDescriptor) {
        if (
            descriptor.backendMode == DeviceBackendMode.Real &&
            descriptor.verificationState != DeviceVerificationState.HardwareVerified
        ) {
            error("${descriptor.model} is not hardware-verified")
        }
    }

    private suspend fun safeTransientStop() {
        withContext(NonCancellable) {
            runCatching { laser.stopSweep() }
            runCatching { prober.stop() }
            runCatching { alignment.stop() }
            runCatching { prober.separate() }
        }
    }

    private suspend fun safeShutdown() {
        withContext(NonCancellable) {
            runCatching { laser.stopSweep() }
            runCatching { laser.setOutputEnabled(false) }
            runCatching { prober.separate() }
            runCatching { prober.stop() }
            runCatching { alignment.stop() }
            runCatching { alignment.moveToSafeState() }
        }
    }

    private suspend fun disconnectDevices() {
        withContext(NonCancellable) {
            runCatching { laser.disconnect() }
            runCatching { powerMeter.disconnect() }
            runCatching { prober.disconnect() }
            runCatching { temperatureController.disconnect() }
        }
    }
}
