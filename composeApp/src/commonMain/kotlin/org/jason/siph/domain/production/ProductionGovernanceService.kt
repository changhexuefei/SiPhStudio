package org.jason.siph.domain.production

class ProductionGovernanceService(
    private val repository: ProductionRepository,
    private val authorization: ProductionAuthorizationService,
    private val audit: ProductionAuditService,
    private val nowEpochMs: () -> Long
) {
    suspend fun approveRecipe(
        actor: ProductionActor,
        recipeId: String,
        version: Int,
        reason: String
    ): ProductionMeasurementRecipe {
        val correlationId = "recipe-approval-$recipeId-v$version-${nowEpochMs()}"
        val current = repository.findRecipe(recipeId, version)
            ?: error("Recipe not found: $recipeId v$version")
        return auditedMutation(
            actor = actor,
            action = "RECIPE_APPROVE",
            targetType = "ProductionMeasurementRecipe",
            targetId = current.stableVersionId,
            correlationId = correlationId,
            reason = reason,
            before = "state=${current.approvalState};createdBy=${current.createdBy}"
        ) {
            authorization.requirePermission(actor, ProductionPermission.RecipeApprove)
            require(actor.id != current.createdBy) {
                "Recipe creator cannot approve the same recipe version"
            }
            require(current.approvalState in setOf(RecipeApprovalState.Draft, RecipeApprovalState.AwaitingApproval)) {
                "Recipe cannot be approved from state ${current.approvalState}"
            }
            val approved = current.copy(
                approvalState = RecipeApprovalState.Approved,
                approvedBy = actor.id,
                approvedAtEpochMs = nowEpochMs()
            )
            repository.saveRecipe(approved)
            approved
        }
    }

    suspend fun approveLot(
        actor: ProductionActor,
        lotId: String,
        reason: String
    ): ProductionLot {
        val correlationId = "lot-approval-$lotId-${nowEpochMs()}"
        val current = repository.findLot(lotId) ?: error("Lot not found: $lotId")
        return auditedMutation(
            actor = actor,
            action = "LOT_APPROVE",
            targetType = "ProductionLot",
            targetId = lotId,
            correlationId = correlationId,
            reason = reason,
            before = "state=${current.state};createdBy=${current.createdBy}"
        ) {
            authorization.requirePermission(actor, ProductionPermission.LotApprove)
            require(actor.id != current.createdBy) { "Lot creator cannot approve the same lot" }
            require(current.state in setOf(LotState.Draft, LotState.AwaitingApproval)) {
                "Lot cannot be approved from state ${current.state}"
            }
            val approved = current.copy(
                state = LotState.Queued,
                approvedBy = actor.id
            )
            repository.saveLot(approved)
            approved
        }
    }

    suspend fun retryTask(
        actor: ProductionActor,
        taskId: String,
        reason: String
    ): ProductionTask {
        val correlationId = "task-retry-$taskId-${nowEpochMs()}"
        val current = repository.findTask(taskId) ?: error("Task not found: $taskId")
        return auditedMutation(
            actor = actor,
            action = "TASK_RETRY",
            targetType = "ProductionTask",
            targetId = taskId,
            correlationId = correlationId,
            reason = reason,
            before = "state=${current.state};attempts=${current.attemptCount}"
        ) {
            authorization.requirePermission(actor, ProductionPermission.TaskRetry)
            require(current.state == ProductionTaskState.Failed) {
                "Only failed tasks can be manually retried"
            }
            require(current.attemptCount < current.maximumAttempts) {
                "Task has exhausted its maximum attempts"
            }
            val retryPending = current.copy(
                state = ProductionTaskState.RetryPending,
                leaseOwner = null,
                leaseExpiresAtEpochMs = null,
                lastError = "Manual retry approved: $reason"
            )
            repository.saveTask(retryPending)
            retryPending
        }
    }

    suspend fun overrideAnomaly(
        actor: ProductionActor,
        anomalyId: String,
        classification: AnomalyClassification,
        reason: String,
        closeCase: Boolean
    ): AnomalyCase {
        val correlationId = "anomaly-review-$anomalyId-${nowEpochMs()}"
        val current = repository.findAnomalyCase(anomalyId)
            ?: error("Anomaly case not found: $anomalyId")
        return auditedMutation(
            actor = actor,
            action = "ANOMALY_OVERRIDE",
            targetType = "AnomalyCase",
            targetId = anomalyId,
            correlationId = correlationId,
            reason = reason,
            before = "automatic=${current.automaticClassification.primaryType};reviewed=${current.reviewedClassification?.primaryType}"
        ) {
            authorization.requirePermission(actor, ProductionPermission.AnomalyOverride)
            require(reason.isNotBlank()) { "Anomaly override reason is required" }
            val reviewed = current.copy(
                reviewedClassification = classification,
                reviewReason = reason,
                reviewedBy = actor.id,
                closedAtEpochMs = nowEpochMs().takeIf { closeCase }
            )
            repository.saveAnomalyCase(reviewed)
            reviewed
        }
    }

    private suspend fun <T> auditedMutation(
        actor: ProductionActor,
        action: String,
        targetType: String,
        targetId: String,
        correlationId: String,
        reason: String,
        before: String,
        mutation: suspend () -> T
    ): T {
        return try {
            val result = mutation()
            audit.record(
                actor = actor,
                action = action,
                targetType = targetType,
                targetId = targetId,
                correlationId = correlationId,
                reason = reason,
                beforeJson = before,
                afterJson = result.toString(),
                success = true
            )
            result
        } catch (error: Throwable) {
            audit.record(
                actor = actor,
                action = action,
                targetType = targetType,
                targetId = targetId,
                correlationId = correlationId,
                reason = reason,
                beforeJson = before,
                success = false,
                errorMessage = error.message ?: error::class.simpleName
            )
            throw error
        }
    }
}
