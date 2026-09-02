package org.jason.siph.domain.production

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionGovernanceServiceTest {

    @Test
    fun recipeRequiresIndependentApproverAndRecordsDeniedAttempt() = runBlocking {
        var now = 1_000L
        var sequence = 0
        val repository = InMemoryProductionRepository()
        val audit = audit(repository, { now++ }) { "audit-${++sequence}" }
        val governance = ProductionGovernanceService(
            repository = repository,
            authorization = RoleBasedProductionAuthorizationService(),
            audit = audit,
            nowEpochMs = { now++ }
        )
        val draft = recipe(createdBy = "supervisor-author")
        repository.saveRecipe(draft)
        val author = ProductionActor(
            id = "supervisor-author",
            displayName = "Author Supervisor",
            roles = setOf(ProductionRole.Supervisor)
        )
        val independent = ProductionActor(
            id = "supervisor-reviewer",
            displayName = "Reviewer Supervisor",
            roles = setOf(ProductionRole.Supervisor)
        )

        assertFailsWith<IllegalArgumentException> {
            governance.approveRecipe(author, draft.id, draft.version, "Self approval attempt")
        }
        val approved = governance.approveRecipe(
            independent,
            draft.id,
            draft.version,
            "Independent technical review completed"
        )

        assertEquals(RecipeApprovalState.Approved, approved.approvalState)
        assertEquals(independent.id, approved.approvedBy)
        val events = repository.listAuditEvents().reversed()
        assertEquals(2, events.size)
        assertTrue(!events.first().success)
        assertTrue(events.last().success)
        assertEquals(events.first().eventHash, events.last().previousHash)
    }

    @Test
    fun anomalyOverridePreservesAutomaticClassificationAndReviewerEvidence() = runBlocking {
        var now = 2_000L
        var sequence = 0
        val repository = InMemoryProductionRepository()
        val governance = ProductionGovernanceService(
            repository = repository,
            authorization = RoleBasedProductionAuthorizationService(),
            audit = audit(repository, { now++ }) { "audit-${++sequence}" },
            nowEpochMs = { now++ }
        )
        val automatic = AnomalyClassification(
            primaryType = ProductionAnomalyType.OpticalPowerTooLow,
            confidence = 0.88,
            evidence = listOf(AnomalyEvidence("power", "-18.0", "measurement")),
            recommendedAction = RecommendedAction.LocalRealign,
            classifierVersion = "rules-v1"
        )
        repository.saveAnomalyCase(
            AnomalyCase(
                id = "anomaly-1",
                lotId = "lot-1",
                taskId = "task-1",
                automaticClassification = automatic,
                openedAtEpochMs = now++
            )
        )
        val reviewed = automatic.copy(
            primaryType = ProductionAnomalyType.InstrumentCommunicationError,
            confidence = 1.0,
            evidence = automatic.evidence + AnomalyEvidence(
                "review",
                "Power-meter communication dropped before acquisition completed",
                "quality-review"
            ),
            recommendedAction = RecommendedAction.InspectHardware,
            classifierVersion = "human-review"
        )
        val qualityEngineer = ProductionActor(
            id = "quality-1",
            displayName = "Quality Engineer",
            roles = setOf(ProductionRole.QualityEngineer)
        )

        val result = governance.overrideAnomaly(
            actor = qualityEngineer,
            anomalyId = "anomaly-1",
            classification = reviewed,
            reason = "Communication log proves the measurement was incomplete",
            closeCase = true
        )

        assertEquals(ProductionAnomalyType.OpticalPowerTooLow, result.automaticClassification.primaryType)
        assertEquals(ProductionAnomalyType.InstrumentCommunicationError, result.reviewedClassification?.primaryType)
        assertEquals(qualityEngineer.id, result.reviewedBy)
        assertNotNull(result.closedAtEpochMs)
        assertTrue(repository.listAuditEvents().single().success)
    }

    private fun recipe(createdBy: String) = ProductionMeasurementRecipe(
        id = "governance-recipe",
        version = 1,
        name = "Governance Recipe",
        measurementType = ProductionMeasurementType.OpticalElectrical,
        steps = listOf(
            MeasurementStepDefinition("validate", MeasurementStepType.ValidateCalibration),
            MeasurementStepDefinition("measure", MeasurementStepType.CaptureElectricalSignal)
        ),
        requiredDeviceCapabilities = setOf("laser", "electrical"),
        qualityRuleSetId = "quality-v1",
        calibrationPolicyId = "calibration-v1",
        approvalState = RecipeApprovalState.AwaitingApproval,
        createdBy = createdBy,
        createdAtEpochMs = 500L
    )

    private fun audit(
        repository: ProductionRepository,
        nowEpochMs: () -> Long,
        idFactory: () -> String
    ) = DefaultProductionAuditService(
        repository = repository,
        hasher = PortableAuditHasher(),
        nowEpochMs = nowEpochMs,
        idFactory = idFactory,
        applicationVersion = "test",
        workstationId = "ci"
    )
}
