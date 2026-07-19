package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.autonomy.CalibrationProfile
import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.SiPhWorkflowCheckpoint
import org.jason.siph.domain.autonomy.SiPhWorkflowRecipe
import org.jason.siph.domain.autonomy.SiPhWorkflowStage
import org.jason.siph.domain.autonomy.TrainedMeasurementPosition
import org.jason.siph.domain.positioner.OpticalPose
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmJsonAutonomyRepositoryTest {

    @Test
    fun databaseSurvivesRepositoryRecreationAndRestoresActiveAssets() = runBlocking {
        val directory = Files.createTempDirectory("siph-autonomy-test")
        val databasePath = directory.resolve("autonomy-workflow.json")
        val site = MeasurementSiteKey(
            waferId = "wafer-a",
            die = DieIndex(1, 2),
            subDieId = "sub-a",
            couplerId = "gc-a"
        )
        val profile = CalibrationProfile(
            id = "cal-a",
            name = "Calibration A",
            controllerIdentity = "PI-TEST",
            fixtureId = "fixture-a",
            geometry = PhotonicCouplingGeometry.VerticalGrating,
            measurementPose = OpticalPose.ZERO,
            createdAtEpochMs = 10L,
            verifiedAtEpochMs = 11L,
            verifiedBy = "operator",
            verified = true
        )
        val position = TrainedMeasurementPosition(
            id = "position-a",
            name = "Position A",
            site = site,
            pose = OpticalPose.ZERO,
            referencePowerDbm = -12.5,
            calibrationProfileId = profile.id,
            trainedAtEpochMs = 20L,
            verifiedAtEpochMs = 21L,
            verified = true
        )
        val recipe = SiPhWorkflowRecipe(
            id = "recipe-a",
            site = site,
            calibrationProfileId = profile.id,
            trainedPositionId = position.id,
            enableVerification = false,
            enableDriftAssessment = false
        )
        val checkpoint = SiPhWorkflowCheckpoint(
            runId = "run-a",
            recipe = recipe,
            completedStages = setOf(SiPhWorkflowStage.InspectHardware),
            currentStage = SiPhWorkflowStage.InspectHardware,
            trainedPosition = position,
            updatedAtEpochMs = 30L
        )

        val writer = JvmJsonAutonomyRepository(databasePath)
        writer.saveProfile(profile)
        writer.activateProfile(profile.id)
        writer.savePosition(position)
        writer.saveCheckpoint(checkpoint)

        val reader = JvmJsonAutonomyRepository(databasePath)

        assertEquals(profile, reader.activeProfile.value)
        assertEquals(profile, reader.findProfile(profile.id))
        assertEquals(position, reader.findPosition(site))
        assertEquals(checkpoint, reader.findCheckpoint(checkpoint.runId))
        assertTrue(Files.readString(databasePath).contains("schemaVersion"))
        assertNotNull(Files.size(databasePath).takeIf { it > 0L })
    }
}
