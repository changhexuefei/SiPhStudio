package org.jason.siph.domain.production

class UnavailableProductionMeasurementExecutor : ProductionMeasurementExecutor {
    override suspend fun execute(
        reservation: ReservedProductionTask,
        recipe: ProductionMeasurementRecipe,
        checkpoint: ProductionCheckpoint?
    ): ProductionMeasurementResult = error(
        "Real production executor is not configured or hardware-verified"
    )

    override suspend fun requestStop() = Unit
}
