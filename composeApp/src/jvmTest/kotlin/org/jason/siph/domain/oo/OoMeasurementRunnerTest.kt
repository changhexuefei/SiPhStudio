package org.jason.siph.domain.oo

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.OpticalCouplerDefinition
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.SiPhDieDefinition
import org.jason.siph.domain.autonomy.SiPhSubDieDefinition
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.WaferCoordinateTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OoMeasurementRunnerTest {

    @Test
    fun simulationRunsMultipleTemperaturesAndSitesToCompletion() {
        runBlocking {
            var now = 1_000L
            val repository = InMemoryOoMeasurementRepository()
            val environment = SimulatedOoEnvironment()
            val runner = runner(environment, repository) { now++ }
            val wafer = waferDefinition()
            val recipe = recipe(wafer.id)

            val result = runner.run(recipe, wafer, runId = "run-complete")

            assertTrue(result.completed)
            assertEquals(4, result.siteResults.size)
            assertTrue(result.siteResults.all { it.points.size == 3 && it.valid })
            assertEquals(setOf(25.0, 45.0), result.siteResults.map { it.temperatureC }.toSet())
            assertNull(repository.findCheckpoint("run-complete"))
            assertEquals(OoMeasurementStage.Completed, runner.state.value.stage)
        }
    }

    @Test
    fun injectedMoveFailureIsRetriedAndRecorded() {
        runBlocking {
            var now = 2_000L
            val repository = InMemoryOoMeasurementRepository()
            val environment = SimulatedOoEnvironment(
                DeviceFaultPlan(
                    failOperationCounts = mapOf("prober.moveToSite" to 1)
                )
            )
            val runner = runner(environment, repository) { now++ }
            val wafer = waferDefinition()
            val result = runner.run(
                recipe = recipe(wafer.id).copy(
                    temperaturesC = listOf(25.0),
                    retryPolicy = OoRetryPolicy(
                        maxAttempts = 2,
                        initialDelayMs = 0L,
                        maximumDelayMs = 0L
                    )
                ),
                wafer = wafer,
                runId = "run-retry"
            )

            assertTrue(result.completed)
            assertTrue(result.failures.any {
                it.stage == OoMeasurementStage.MoveToSite && it.recoverable
            })
            assertNull(repository.findCheckpoint("run-retry"))
        }
    }

    @Test
    fun incompleteTriggeredSweepKeepsCheckpointForRecovery() {
        runBlocking {
            var now = 3_000L
            val repository = InMemoryOoMeasurementRepository()
            val environment = SimulatedOoEnvironment(
                DeviceFaultPlan(laserSweepStopsEarly = true)
            )
            val runner = runner(environment, repository) { now++ }
            val wafer = waferDefinition()
            val recipe = recipe(wafer.id).copy(
                temperaturesC = listOf(25.0),
                acquisitionMode = SweepAcquisitionMode.HardwareTriggered,
                retryPolicy = OoRetryPolicy(
                    maxAttempts = 1,
                    initialDelayMs = 0L,
                    maximumDelayMs = 0L
                )
            )

            assertFailsWith<IllegalStateException> {
                runner.run(recipe, wafer, runId = "run-incomplete")
            }

            assertNotNull(repository.findCheckpoint("run-incomplete"))
            assertTrue(repository.findResult("run-incomplete")?.completed == false)
            assertEquals(OoMeasurementStage.Failed, runner.state.value.stage)
        }
    }

    private fun runner(
        environment: SimulatedOoEnvironment,
        repository: OoMeasurementRepository,
        now: () -> Long
    ): DefaultOoMeasurementRunner = DefaultOoMeasurementRunner(
        laser = SimulatedTunableLaser(environment),
        powerMeter = SimulatedOoPowerMeter(environment),
        prober = SimulatedWaferProber(environment),
        temperatureController = SimulatedTemperatureController(environment),
        alignment = SimulatedOoAlignmentPort(environment),
        repository = repository,
        nowEpochMs = now
    )

    private fun recipe(waferId: String) = OoMeasurementRecipe(
        id = "recipe-$waferId",
        waferId = waferId,
        temperaturesC = listOf(25.0, 45.0),
        sweep = LaserSweepConfig(
            startWavelengthNm = 1549.8,
            stopWavelengthNm = 1550.0,
            stepWavelengthNm = 0.1,
            powerDbm = 0.0,
            dwellMs = 0L
        ),
        contactBeforeMeasurement = true,
        temperatureStability = TemperatureStabilityPolicy(
            targetToleranceC = 0.1,
            maximumSlopeCPerMinute = 0.1,
            stableWindowMs = 0L,
            timeoutMs = 100L,
            pollIntervalMs = 1L
        )
    )

    private fun waferDefinition(): SiPhWaferDefinition = SiPhWaferDefinition(
        id = "wafer-oo",
        diameterMm = 200.0,
        transform = WaferCoordinateTransform(
            originStageXUm = 100.0,
            originStageYUm = 200.0,
            diePitchXUm = 1_000.0,
            diePitchYUm = 1_000.0
        ),
        dies = listOf(die(0, 0), die(1, 0)),
        createdAtEpochMs = 1L
    )

    private fun die(column: Int, row: Int) = SiPhDieDefinition(
        index = DieIndex(column, row),
        subDies = listOf(
            SiPhSubDieDefinition(
                id = "sub",
                name = "Sub",
                originOffsetXUm = 10.0,
                originOffsetYUm = 20.0,
                couplers = listOf(
                    OpticalCouplerDefinition(
                        id = "gc",
                        name = "GC",
                        geometry = PhotonicCouplingGeometry.VerticalGrating,
                        offsetXUm = 5.0,
                        offsetYUm = 6.0
                    )
                )
            )
        )
    )
}
