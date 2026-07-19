package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.production.DefaultProductionAuditService
import org.jason.siph.domain.production.MeasurementStepDefinition
import org.jason.siph.domain.production.MeasurementStepType
import org.jason.siph.domain.production.ProductionActor
import org.jason.siph.domain.production.ProductionMeasurementRecipe
import org.jason.siph.domain.production.ProductionMeasurementType
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionTask
import org.jason.siph.domain.production.RecipeApprovalState
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmJsonProductionRepositoryTest {

    @Test
    fun productionDatabaseAndAuditChainSurviveRestart() = runBlocking {
        val directory = Files.createTempDirectory("siph-production-test")
        val databasePath = directory.resolve("production-runtime.json")
        try {
            var now = 1_000L
            var sequence = 0
            val repository = JvmJsonProductionRepository(databasePath)
            val recipe = recipe()
            val task = task(recipe)
            repository.saveRecipe(recipe)
            repository.saveTask(task)
            val audit = DefaultProductionAuditService(
                repository = repository,
                hasher = JvmSha256AuditHasher(),
                nowEpochMs = { now++ },
                idFactory = { "event-${++sequence}" },
                applicationVersion = "test",
                workstationId = "ci"
            )
            val actor = ProductionActor(
                id = "supervisor",
                displayName = "Supervisor",
                roles = setOf(ProductionRole.Supervisor)
            )
            val first = audit.record(
                actor = actor,
                action = "RECIPE_APPROVE",
                targetType = "Recipe",
                targetId = recipe.stableVersionId,
                correlationId = "approval",
                success = true
            )
            val second = audit.record(
                actor = actor,
                action = "TASK_CREATE",
                targetType = "ProductionTask",
                targetId = task.id,
                correlationId = "task-create",
                success = true
            )

            val reopened = JvmJsonProductionRepository(databasePath)

            assertNotNull(reopened.findRecipe(recipe.id, recipe.version))
            assertNotNull(reopened.findTask(task.id))
            assertEquals(2, reopened.listAuditEvents().size)
            assertEquals(first.eventHash, second.previousHash)
            assertTrue(first.eventHash.length == 64)
            assertTrue(databasePath.toFile().length() > 0L)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }

    private fun recipe() = ProductionMeasurementRecipe(
        id = "persistent-recipe",
        version = 1,
        name = "Persistent Recipe",
        measurementType = ProductionMeasurementType.OpticalElectrical,
        steps = listOf(
            MeasurementStepDefinition("validate", MeasurementStepType.ValidateCalibration),
            MeasurementStepDefinition("acquire", MeasurementStepType.CaptureElectricalSignal)
        ),
        requiredDeviceCapabilities = setOf("laser", "electrical"),
        qualityRuleSetId = "quality-v1",
        calibrationPolicyId = "cal-v1",
        approvalState = RecipeApprovalState.Approved,
        createdBy = "engineer",
        approvedBy = "supervisor",
        createdAtEpochMs = 1L,
        approvedAtEpochMs = 2L
    )

    private fun task(recipe: ProductionMeasurementRecipe) = ProductionTask(
        id = "persistent-task",
        lotId = "persistent-lot",
        waferId = "persistent-wafer",
        site = MeasurementSiteKey(
            waferId = "persistent-wafer",
            die = DieIndex(0, 0),
            subDieId = "sub",
            couplerId = "coupler"
        ),
        recipeId = recipe.id,
        recipeVersion = recipe.version,
        priority = 1,
        idempotencyKey = "persistent-key"
    )
}
