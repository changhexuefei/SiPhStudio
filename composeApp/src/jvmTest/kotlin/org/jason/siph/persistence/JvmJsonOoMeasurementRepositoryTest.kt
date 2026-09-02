package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.OpticalCouplerDefinition
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.SiPhDieDefinition
import org.jason.siph.domain.autonomy.SiPhSubDieDefinition
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.WaferCoordinateTransform
import org.jason.siph.domain.oo.LaserSweepConfig
import org.jason.siph.domain.oo.OoMeasurementCheckpoint
import org.jason.siph.domain.oo.OoMeasurementRecipe
import org.jason.siph.domain.oo.OoMeasurementResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmJsonOoMeasurementRepositoryTest {

    @Test
    fun resultAndCheckpointSurviveRepositoryRecreation() {
        runBlocking {
            val directory = Files.createTempDirectory("siph-oo-test")
            val databasePath = directory.resolve("oo-measurements.json")
            val wafer = wafer()
            val recipe = OoMeasurementRecipe(
                id = "recipe-a",
                waferId = wafer.id,
                sweep = LaserSweepConfig(
                    startWavelengthNm = 1550.0,
                    stopWavelengthNm = 1550.2,
                    stepWavelengthNm = 0.1,
                    powerDbm = 0.0
                )
            )
            val result = OoMeasurementResult(
                runId = "run-a",
                recipe = recipe,
                waferSnapshot = wafer,
                startedAtEpochMs = 10L,
                finishedAtEpochMs = 11L,
                completed = false
            )
            val checkpoint = OoMeasurementCheckpoint(
                runId = result.runId,
                recipe = recipe,
                waferSnapshot = wafer,
                completedMeasurementKeys = setOf("25.0:${site().stableId}"),
                updatedAtEpochMs = 12L
            )

            val writer = JvmJsonOoMeasurementRepository(databasePath)
            writer.saveResult(result)
            writer.saveCheckpoint(checkpoint)

            val reader = JvmJsonOoMeasurementRepository(databasePath)
            assertEquals(result, reader.findResult(result.runId))
            assertEquals(checkpoint, reader.findCheckpoint(checkpoint.runId))
            assertNotNull(Files.size(databasePath).takeIf { it > 0L })
        }
    }

    private fun site() = MeasurementSiteKey(
        waferId = "wafer-a",
        die = DieIndex(0, 0),
        subDieId = "sub",
        couplerId = "gc"
    )

    private fun wafer() = SiPhWaferDefinition(
        id = "wafer-a",
        diameterMm = 200.0,
        transform = WaferCoordinateTransform(
            originStageXUm = 0.0,
            originStageYUm = 0.0,
            diePitchXUm = 1_000.0,
            diePitchYUm = 1_000.0
        ),
        dies = listOf(
            SiPhDieDefinition(
                index = DieIndex(0, 0),
                subDies = listOf(
                    SiPhSubDieDefinition(
                        id = "sub",
                        name = "Sub",
                        originOffsetXUm = 0.0,
                        originOffsetYUm = 0.0,
                        couplers = listOf(
                            OpticalCouplerDefinition(
                                id = "gc",
                                name = "GC",
                                geometry = PhotonicCouplingGeometry.VerticalGrating,
                                offsetXUm = 0.0,
                                offsetYUm = 0.0
                            )
                        )
                    )
                )
            )
        ),
        createdAtEpochMs = 1L
    )
}
