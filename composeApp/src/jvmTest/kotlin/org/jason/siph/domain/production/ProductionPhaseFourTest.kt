package org.jason.siph.domain.production

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionPhaseFourTest {

    @Test
    fun fiberArrayGeometryAndChannelBalanceAreEvaluated() {
        val definition = FiberArrayDefinition(
            id = "fa-4",
            name = "Four Channel Array",
            channelCount = 4,
            nominalPitchUm = 127.0,
            referenceChannel = 0,
            channels = List(4) { index ->
                FiberArrayChannel(index, "CH${index + 1}", offsetXUm = index * 127.0, offsetYUm = 0.0)
            },
            verified = true
        )
        val observations = definition.channels.map { channel ->
            FiberChannelObservation(
                channelIndex = channel.index,
                measuredXUm = 10.0 + channel.offsetXUm * 1.01,
                measuredYUm = -5.0,
                powerDbm = -4.0 - channel.index * 0.2,
                detected = true,
                confidence = 0.98
            )
        }

        val result = FiberArrayAlignmentService().evaluate(
            definition = definition,
            observations = observations,
            minimumPowerDbm = -6.0,
            maximumImbalanceDb = 1.0
        )

        assertTrue(result.passed, result.message)
        assertTrue(abs(result.translationXUm - 10.0) < 1e-6)
        assertTrue(abs(result.translationYUm + 5.0) < 1e-6)
        assertTrue(abs(result.correctedPitchUm - 128.27) < 1e-6)
        assertTrue(result.maximumChannelImbalanceDb < 1.0)
    }

    @Test
    fun calibrationGateBindsRecipeEquipmentAndInspectionProfiles() = runBlocking {
        val repository = InMemoryProductionRepository()
        val recipe = approvedRecipe(ProductionMeasurementType.OpticalOptical)
        val wafer = calibrationWafer()
        val qualification = qualification(
            wafer = wafer,
            recipe = recipe,
            equipment = equipment(),
            cameraCalibrationId = "camera-cal-1",
            heightProfileId = "height-1",
            pivotProfileId = "pivot-1"
        )
        repository.saveRecipe(recipe)
        repository.saveCalibrationWafer(wafer)
        repository.saveCalibrationQualification(qualification)
        val gate = DefaultProductionCalibrationGate(repository)

        val allowed = gate.evaluate(
            ProductionCalibrationContext(
                recipe = recipe,
                equipmentIdentities = equipment(),
                cameraCalibrationId = "camera-cal-1",
                probeHeightProfileId = "height-1",
                pivotProfileId = "pivot-1",
                nowEpochMs = 20_000L
            )
        )
        val changedEquipment = gate.evaluate(
            ProductionCalibrationContext(
                recipe = recipe,
                equipmentIdentities = equipment() + ("laser" to "different-laser"),
                cameraCalibrationId = "camera-cal-1",
                probeHeightProfileId = "height-1",
                pivotProfileId = "pivot-1",
                nowEpochMs = 20_000L
            )
        )

        assertTrue(allowed.allowed)
        assertEquals(ProductionCalibrationDecisionType.EquipmentChanged, changedEquipment.type)
    }

    @Test
    fun lotWorkerExecutesOeTasksPersistsQualityAndAudit() = runBlocking {
        var now = 100_000L
        var id = 0
        val repository = InMemoryProductionRepository()
        val recipe = approvedRecipe(ProductionMeasurementType.OpticalElectrical)
        val wafer = calibrationWafer()
        repository.saveRecipe(recipe)
        repository.saveCalibrationWafer(wafer)
        repository.saveCalibrationQualification(
            qualification(
                wafer = wafer,
                recipe = recipe,
                equipment = equipment(),
                cameraCalibrationId = null,
                heightProfileId = null,
                pivotProfileId = null
            )
        )
        val lot = ProductionLot(
            id = "lot-1",
            lotNumber = "LOT-2026-001",
            productCode = "PD-01",
            recipeId = recipe.id,
            recipeVersion = recipe.version,
            wafers = listOf(ProductionWafer("wafer-1", 1)),
            priority = 10,
            state = LotState.Queued,
            createdAtEpochMs = now++,
            createdBy = "engineer-1",
            approvedBy = "supervisor-1"
        )
        val sites = listOf(site("wafer-1", 0), site("wafer-1", 1))
        val tasks = ProductionLotPlanner().buildTasks(lot, mapOf("wafer-1" to sites))
        val scheduler = DefaultProductionScheduler(repository) { now++ }
        scheduler.enqueueLot(lot, tasks)
        val audit = DefaultProductionAuditService(
            repository = repository,
            hasher = TestAuditHasher(),
            nowEpochMs = { now++ },
            idFactory = { "audit-${++id}" },
            applicationVersion = "test",
            workstationId = "ci"
        )
        val worker = DefaultProductionWorker(
            workerId = "worker-1",
            repository = repository,
            scheduler = scheduler,
            calibrationGate = DefaultProductionCalibrationGate(repository),
            executor = SimulatedProductionMeasurementExecutor(repository) { now++ },
            anomalyClassifier = RuleBasedProductionAnomalyClassifier(),
            audit = audit,
            authorization = RoleBasedProductionAuthorizationService(),
            equipmentIdentities = ::equipment,
            cameraCalibrationId = { null },
            probeHeightProfileId = { null },
            pivotProfileId = { null },
            nowEpochMs = { now++ },
            leaseDurationMs = 5_000L
        )
        val operator = ProductionActor(
            id = "operator-1",
            displayName = "Operator One",
            roles = setOf(ProductionRole.Operator)
        )

        val executed = worker.runUntilEmpty(operator)

        assertEquals(2, executed)
        assertEquals(LotState.Completed, repository.findLot(lot.id)?.state)
        assertEquals(2, repository.listMeasurementResults(lot.id).size)
        assertTrue(repository.listQualityObservations().isNotEmpty())
        assertEquals(4, repository.listAuditEvents().size)
        assertTrue(repository.listTasks(lot.id).all { it.state == ProductionTaskState.Passed })
    }

    @Test
    fun schedulerReleasesExpiredLeaseAndRetriesWithoutDuplicateResult() = runBlocking {
        var now = 1_000L
        val repository = InMemoryProductionRepository()
        val recipe = approvedRecipe(ProductionMeasurementType.OpticalOptical)
        repository.saveRecipe(recipe)
        val lot = ProductionLot(
            id = "lot-lease",
            lotNumber = "LEASE",
            productCode = "TEST",
            recipeId = recipe.id,
            recipeVersion = recipe.version,
            wafers = listOf(ProductionWafer("wafer-lease", 1)),
            state = LotState.Queued,
            createdAtEpochMs = now,
            createdBy = "engineer"
        )
        val task = ProductionLotPlanner().buildTasks(
            lot,
            mapOf("wafer-lease" to listOf(site("wafer-lease", 0)))
        ).single()
        val scheduler = DefaultProductionScheduler(repository) { now }
        scheduler.enqueueLot(lot, listOf(task))
        val first = assertNotNull(scheduler.reserveNext("worker-a", leaseDurationMs = 10L))
        now = 1_020L

        assertEquals(1, scheduler.releaseExpiredLeases(now))
        val second = assertNotNull(scheduler.reserveNext("worker-b", leaseDurationMs = 10L))
        assertEquals(first.task.id, second.task.id)
        assertEquals(2, second.task.attemptCount)
    }

    @Test
    fun spcDetectsTrendAndCalculatesCapability() {
        val observations = (1..8).map { index ->
            QualityObservation(
                id = "obs-$index",
                lotId = "lot",
                waferId = "wafer",
                site = site("wafer", index),
                metricName = "responsivityAperW",
                value = 0.70 + index * 0.01,
                unit = "A/W",
                timestampEpochMs = index.toLong(),
                recipeId = "recipe",
                recipeVersion = 1,
                equipmentGroupId = "sim"
            )
        }

        val result = DefaultQualitySpcEngine().analyze(
            metricName = "responsivityAperW",
            observations = observations,
            lowerSpecificationLimit = 0.65,
            upperSpecificationLimit = 0.90
        )

        assertTrue(result.violations.any { it.rule == SpcRuleType.SixIncreasingOrDecreasing })
        assertNotNull(result.capability)
        assertTrue(result.capability.sampleCount == 8)
    }

    @Test
    fun communicationFailureIsNotMisclassifiedAsProductFailure() {
        val classification = RuleBasedProductionAnomalyClassifier().classify(
            ProductionAnomalyContext(
                lotId = "lot",
                taskId = "task",
                errorMessage = "VISA communication timeout while reading power",
                metrics = listOf(
                    MeasurementMetric("outputPowerDbm", -100.0, "dBm", -10.0, 0.0)
                )
            )
        )

        assertEquals(ProductionAnomalyType.InstrumentCommunicationError, classification.primaryType)
        assertEquals(RecommendedAction.InspectHardware, classification.recommendedAction)
    }

    @Test
    fun authorizationAndAuditChainRejectUnauthorizedOperations() = runBlocking {
        val authorization = RoleBasedProductionAuthorizationService()
        val operator = ProductionActor("operator", "Operator", setOf(ProductionRole.Operator))
        val supervisor = ProductionActor("supervisor", "Supervisor", setOf(ProductionRole.Supervisor))
        assertFailsWith<IllegalArgumentException> {
            authorization.requirePermission(operator, ProductionPermission.RecipeApprove)
        }
        authorization.requirePermission(supervisor, ProductionPermission.RecipeApprove)

        var now = 1L
        var id = 0
        val repository = InMemoryProductionRepository()
        val audit = DefaultProductionAuditService(
            repository,
            TestAuditHasher(),
            nowEpochMs = { now++ },
            idFactory = { "event-${++id}" },
            applicationVersion = "test",
            workstationId = "ci"
        )
        val first = audit.record(
            actor = supervisor,
            action = "RECIPE_APPROVE",
            targetType = "Recipe",
            targetId = "recipe-v1",
            correlationId = "approval-1",
            success = true
        )
        val second = audit.record(
            actor = supervisor,
            action = "LOT_APPROVE",
            targetType = "Lot",
            targetId = "lot-1",
            correlationId = "approval-2",
            success = true
        )

        assertEquals(first.eventHash, second.previousHash)
        assertEquals(2, repository.listAuditEvents().size)
    }

    private fun approvedRecipe(type: ProductionMeasurementType) = ProductionMeasurementRecipe(
        id = "recipe-${type.name}",
        version = 1,
        name = "${type.name} Production Recipe",
        measurementType = type,
        steps = listOf(
            MeasurementStepDefinition("validate", MeasurementStepType.ValidateCalibration),
            MeasurementStepDefinition("align", MeasurementStepType.AlignOpticalPath),
            MeasurementStepDefinition("acquire", MeasurementStepType.CaptureOpticalPower),
            MeasurementStepDefinition("quality", MeasurementStepType.EvaluateQuality)
        ),
        requiredDeviceCapabilities = setOf("laser", "powerMeter", "prober"),
        qualityRuleSetId = "quality-v1",
        calibrationPolicyId = "calibration-v1",
        approvalState = RecipeApprovalState.Approved,
        createdBy = "engineer-1",
        approvedBy = "supervisor-1",
        createdAtEpochMs = 1_000L,
        approvedAtEpochMs = 2_000L
    )

    private fun calibrationWafer() = CalibrationWaferDefinition(
        id = "cal-wafer-1",
        serialNumber = "CW-0001",
        revision = "A",
        validFromEpochMs = 0L,
        validUntilEpochMs = 1_000_000L,
        referenceSites = listOf(
            CalibrationReferenceSite(
                site = site("cal-wafer", 0),
                expectedMetrics = listOf(
                    CalibrationExpectedMetric("outputPowerDbm", -4.0, 0.5, 1.0, "dBm")
                )
            )
        ),
        approved = true
    )

    private fun qualification(
        wafer: CalibrationWaferDefinition,
        recipe: ProductionMeasurementRecipe,
        equipment: Map<String, String>,
        cameraCalibrationId: String?,
        heightProfileId: String?,
        pivotProfileId: String?
    ) = CalibrationQualificationService().qualify(
        id = "qualification-${recipe.id}",
        wafer = wafer,
        recipe = recipe,
        measured = mapOf("${wafer.referenceSites.single().site.stableId}:outputPowerDbm" to -4.1),
        equipmentIdentities = equipment,
        temperatureC = 25.0,
        startedAtEpochMs = 10_000L,
        finishedAtEpochMs = 11_000L,
        executedBy = "quality-1",
        cameraCalibrationId = cameraCalibrationId,
        probeHeightProfileId = heightProfileId,
        pivotProfileId = pivotProfileId
    )

    private fun equipment() = mapOf(
        "laser" to "SiPhStudio Simulated Tunable Laser",
        "powerMeter" to "SiPhStudio Simulated Optical Power Meter",
        "electrical" to "SiPhStudio Simulated Electrical Analyzer",
        "prober" to "SiPhStudio Simulated Wafer Prober"
    )

    private fun site(waferId: String, column: Int) = MeasurementSiteKey(
        waferId = waferId,
        die = DieIndex(row = 0, column = column),
        subDieId = "sub-1",
        couplerId = "coupler-1"
    )

    private class TestAuditHasher : AuditHasher {
        override fun hash(canonicalValue: String): String =
            canonicalValue.fold(7L) { accumulator, character -> accumulator * 31L + character.code }
                .toString(16)
    }
}
