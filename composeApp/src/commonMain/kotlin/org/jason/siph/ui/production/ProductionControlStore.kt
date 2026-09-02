package org.jason.siph.ui.production

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.production.AnomalyCase
import org.jason.siph.domain.production.CalibrationExpectedMetric
import org.jason.siph.domain.production.CalibrationQualification
import org.jason.siph.domain.production.CalibrationQualificationService
import org.jason.siph.domain.production.CalibrationReferenceSite
import org.jason.siph.domain.production.CalibrationWaferDefinition
import org.jason.siph.domain.production.DefaultProductionWorker
import org.jason.siph.domain.production.DefaultQualitySpcEngine
import org.jason.siph.domain.production.FiberArrayChannel
import org.jason.siph.domain.production.FiberArrayDefinition
import org.jason.siph.domain.production.LotState
import org.jason.siph.domain.production.MeasurementStepDefinition
import org.jason.siph.domain.production.MeasurementStepType
import org.jason.siph.domain.production.ProductionActor
import org.jason.siph.domain.production.ProductionLot
import org.jason.siph.domain.production.ProductionLotPlanner
import org.jason.siph.domain.production.ProductionMeasurementRecipe
import org.jason.siph.domain.production.ProductionMeasurementResult
import org.jason.siph.domain.production.ProductionMeasurementType
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionScheduler
import org.jason.siph.domain.production.ProductionTask
import org.jason.siph.domain.production.ProductionTaskState
import org.jason.siph.domain.production.ProductionWafer
import org.jason.siph.domain.production.QualityObservation
import org.jason.siph.domain.production.RecipeApprovalState
import org.jason.siph.domain.production.SpcAnalysisResult
import org.jason.siph.domain.production.SpcViolation
import org.jason.siph.domain.runtime.HardwareRuntimeMode

sealed interface ProductionControlAction {
    data object Refresh : ProductionControlAction
    data object RunNext : ProductionControlAction
    data object RunLot : ProductionControlAction
    data object Stop : ProductionControlAction
}

data class ProductionControlUiState(
    val worker: org.jason.siph.domain.production.ProductionWorkerState,
    val lots: List<ProductionLot> = emptyList(),
    val tasks: List<ProductionTask> = emptyList(),
    val results: List<ProductionMeasurementResult> = emptyList(),
    val qualifications: List<CalibrationQualification> = emptyList(),
    val quality: List<QualityObservation> = emptyList(),
    val anomalies: List<AnomalyCase> = emptyList(),
    val auditCount: Int = 0,
    val spc: SpcAnalysisResult? = null,
    val simulationBackend: Boolean,
    val busy: Boolean = false,
    val message: String = "Production assets are loading",
    val errorMessage: String? = null
) {
    val pendingTaskCount: Int
        get() = tasks.count { it.state == ProductionTaskState.Pending || it.state == ProductionTaskState.RetryPending }

    val passedTaskCount: Int
        get() = tasks.count { it.state == ProductionTaskState.Passed }

    val failedTaskCount: Int
        get() = tasks.count { it.state == ProductionTaskState.Failed }

    val terminalTaskCount: Int
        get() = tasks.count {
            it.state in setOf(
                ProductionTaskState.Passed,
                ProductionTaskState.Failed,
                ProductionTaskState.Skipped,
                ProductionTaskState.Aborted
            )
        }

    val yieldPercent: Double
        get() = if (terminalTaskCount == 0) 0.0 else passedTaskCount * 100.0 / terminalTaskCount

    val spcViolationCount: Int
        get() = spc?.violations?.size ?: 0

    val canRun: Boolean
        get() = simulationBackend && !busy && !worker.running && pendingTaskCount > 0
}

class ProductionControlStore(
    private val scope: CoroutineScope,
    private val runtimeMode: HardwareRuntimeMode,
    private val repository: ProductionRepository,
    private val scheduler: ProductionScheduler,
    private val worker: DefaultProductionWorker,
    private val nowEpochMs: () -> Long
) {
    private val operator = ProductionActor(
        id = "demo-operator",
        displayName = "Digital Production Operator",
        roles = setOf(ProductionRole.Operator)
    )
    private val mutableState = MutableStateFlow(
        ProductionControlUiState(
            worker = worker.state.value,
            simulationBackend = runtimeMode == HardwareRuntimeMode.Demo
        )
    )
    val state: StateFlow<ProductionControlUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    init {
        scope.launch {
            worker.state.collect { workerState ->
                mutableState.update {
                    it.copy(
                        worker = workerState,
                        busy = workerState.running || activeJob?.isActive == true,
                        message = workerState.message,
                        errorMessage = workerState.errorMessage
                    )
                }
                if (!workerState.running) refreshAssets()
            }
        }
        dispatch(ProductionControlAction.Refresh)
    }

    fun dispatch(action: ProductionControlAction) {
        when (action) {
            ProductionControlAction.Refresh -> refresh()
            ProductionControlAction.RunNext -> run(maximumTasks = 1)
            ProductionControlAction.RunLot -> run(maximumTasks = Int.MAX_VALUE)
            ProductionControlAction.Stop -> scope.launch { worker.requestStop() }
        }
    }

    private fun refresh() {
        if (mutableState.value.worker.running) return
        scope.launch {
            mutableState.update { it.copy(busy = true, errorMessage = null) }
            runCatching {
                ensureDemoAssets()
                refreshAssets()
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = if (it.simulationBackend) {
                            "Digital production assets are ready"
                        } else {
                            "Real production remains locked until verified services are injected"
                        },
                        errorMessage = null
                    )
                }
            }.onFailure(::publishFailure)
        }
    }

    private fun run(maximumTasks: Int) {
        val snapshot = mutableState.value
        if (!snapshot.simulationBackend) {
            publishFailure(IllegalStateException("Production demo is disabled in Real mode"))
            return
        }
        if (!snapshot.canRun || activeJob?.isActive == true) return
        activeJob = scope.launch {
            mutableState.update { it.copy(busy = true, message = "Starting digital production lot") }
            try {
                val executed = worker.runUntilEmpty(operator, maximumTasks)
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = "$executed production task(s) executed")
                }
            } catch (cancelled: CancellationException) {
                refreshAssets()
                mutableState.update {
                    it.copy(busy = false, message = cancelled.message ?: "Production stopped")
                }
            } catch (error: Throwable) {
                refreshAssets()
                publishFailure(error)
            } finally {
                activeJob = null
            }
        }
    }

    private suspend fun ensureDemoAssets() {
        if (runtimeMode != HardwareRuntimeMode.Demo) return
        if (repository.listFiberArrays().none { it.id == DEMO_FIBER_ARRAY_ID }) {
            repository.saveFiberArray(demoFiberArray())
        }
        if (repository.findRecipe(DEMO_RECIPE_ID, 1) == null) {
            repository.saveRecipe(demoRecipe())
        }
        if (repository.findCalibrationWafer(DEMO_CALIBRATION_WAFER_ID) == null) {
            repository.saveCalibrationWafer(demoCalibrationWafer())
        }
        if (repository.listCalibrationQualifications().none { it.id == DEMO_QUALIFICATION_ID }) {
            repository.saveCalibrationQualification(demoQualification())
        }
        if (repository.findLot(DEMO_LOT_ID) == null) {
            val lot = demoLot()
            val tasks = ProductionLotPlanner().buildTasks(
                lot = lot,
                sitesByWafer = lot.wafers.associate { wafer ->
                    wafer.waferId to List(4) { column -> site(wafer.waferId, column) }
                }
            )
            scheduler.enqueueLot(lot, tasks)
        }
    }

    private suspend fun refreshAssets() {
        val lots = repository.listLots()
        val tasks = repository.listTasks()
        val quality = repository.listQualityObservations()
        val spc = quality.groupBy { it.metricName }
            .values
            .filter { it.size >= 2 }
            .maxByOrNull { it.size }
            ?.let { observations ->
                runCatching {
                    DefaultQualitySpcEngine().analyze(
                        metricName = observations.first().metricName,
                        observations = observations
                    )
                }.getOrNull()
            }
        mutableState.update {
            it.copy(
                lots = lots,
                tasks = tasks,
                results = repository.listMeasurementResults(),
                qualifications = repository.listCalibrationQualifications(),
                quality = quality,
                anomalies = repository.listAnomalyCases(),
                auditCount = repository.listAuditEvents(limit = 10_000).size,
                spc = spc,
                busy = worker.state.value.running || activeJob?.isActive == true
            )
        }
    }

    private fun demoFiberArray() = FiberArrayDefinition(
        id = DEMO_FIBER_ARRAY_ID,
        name = "Digital 8-Channel Fiber Array",
        channelCount = 8,
        nominalPitchUm = 127.0,
        referenceChannel = 3,
        channels = List(8) { index ->
            FiberArrayChannel(
                index = index,
                name = "CH${index + 1}",
                offsetXUm = (index - 3) * 127.0,
                offsetYUm = 0.0,
                opticalPathId = "path-${index + 1}"
            )
        },
        verified = true
    )

    private fun demoRecipe() = ProductionMeasurementRecipe(
        id = DEMO_RECIPE_ID,
        version = 1,
        name = "Digital Fiber Array O-E-O Production",
        measurementType = ProductionMeasurementType.OpticalElectricalOptical,
        fiberArrayId = DEMO_FIBER_ARRAY_ID,
        steps = listOf(
            MeasurementStepDefinition("calibration", MeasurementStepType.ValidateCalibration),
            MeasurementStepDefinition("site", MeasurementStepType.LoadSite),
            MeasurementStepDefinition("alignment", MeasurementStepType.AlignOpticalPath),
            MeasurementStepDefinition("laser", MeasurementStepType.ConfigureLaser),
            MeasurementStepDefinition("bias", MeasurementStepType.ConfigureElectricalBias),
            MeasurementStepDefinition("acquisition", MeasurementStepType.CaptureWaveform),
            MeasurementStepDefinition("quality", MeasurementStepType.EvaluateQuality),
            MeasurementStepDefinition("safe", MeasurementStepType.ReturnSafeState)
        ),
        requiredDeviceCapabilities = setOf(
            "fiberArray",
            "laser",
            "powerMeter",
            "electricalAnalyzer",
            "prober"
        ),
        qualityRuleSetId = "digital-quality-v1",
        calibrationPolicyId = "digital-calibration-v1",
        approvalState = RecipeApprovalState.Approved,
        createdBy = "demo-engineer",
        approvedBy = "demo-supervisor",
        createdAtEpochMs = nowEpochMs(),
        approvedAtEpochMs = nowEpochMs()
    )

    private fun demoCalibrationWafer() = CalibrationWaferDefinition(
        id = DEMO_CALIBRATION_WAFER_ID,
        serialNumber = "DIGITAL-CW-0001",
        revision = "A",
        validFromEpochMs = 0L,
        validUntilEpochMs = Long.MAX_VALUE,
        referenceSites = listOf(
            CalibrationReferenceSite(
                site = site("digital-calibration-wafer", 0),
                expectedMetrics = listOf(
                    CalibrationExpectedMetric("outputPowerDbm", -7.0, 0.5, 1.5, "dBm")
                )
            )
        ),
        certificateId = "digital-certificate",
        approved = true
    )

    private fun demoQualification() = CalibrationQualificationService().qualify(
        id = DEMO_QUALIFICATION_ID,
        wafer = demoCalibrationWafer(),
        recipe = demoRecipe(),
        measured = mapOf(
            "${demoCalibrationWafer().referenceSites.single().site.stableId}:outputPowerDbm" to -7.1
        ),
        equipmentIdentities = DEMO_EQUIPMENT,
        temperatureC = 25.0,
        startedAtEpochMs = nowEpochMs(),
        finishedAtEpochMs = nowEpochMs(),
        executedBy = "demo-quality"
    )

    private fun demoLot() = ProductionLot(
        id = DEMO_LOT_ID,
        lotNumber = "DIGITAL-LOT-001",
        productCode = "SIPH-OEO-ARRAY",
        recipeId = DEMO_RECIPE_ID,
        recipeVersion = 1,
        wafers = listOf(
            ProductionWafer("digital-wafer-1", 1),
            ProductionWafer("digital-wafer-2", 2)
        ),
        priority = 10,
        state = LotState.Queued,
        createdAtEpochMs = nowEpochMs(),
        createdBy = "demo-engineer",
        approvedBy = "demo-supervisor"
    )

    private fun site(waferId: String, column: Int) = MeasurementSiteKey(
        waferId = waferId,
        die = DieIndex(row = 0, column = column),
        subDieId = "sub-1",
        couplerId = "array-1"
    )

    private fun publishFailure(error: Throwable) {
        mutableState.update {
            it.copy(
                busy = false,
                message = "Production operation failed",
                errorMessage = error.message ?: error::class.simpleName
            )
        }
    }

    companion object {
        const val DEMO_FIBER_ARRAY_ID = "digital-fiber-array-8"
        const val DEMO_RECIPE_ID = "digital-production-oeo"
        const val DEMO_CALIBRATION_WAFER_ID = "digital-calibration-wafer"
        const val DEMO_QUALIFICATION_ID = "digital-calibration-qualification"
        const val DEMO_LOT_ID = "digital-lot-001"

        val DEMO_EQUIPMENT = mapOf(
            "laser" to "SiPhStudio Simulated Tunable Laser",
            "powerMeter" to "SiPhStudio Simulated Optical Power Meter",
            "electrical" to "SiPhStudio Simulated Electrical Analyzer",
            "prober" to "SiPhStudio Simulated Wafer Prober"
        )
    }
}
