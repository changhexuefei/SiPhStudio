package org.jason.siph.domain.autonomy

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.positioner.OpticalPose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutonomyPortsTest {

    @Test
    fun unavailableAdaptersNeverReportConnectedHardware() {
        runBlocking {
            val vision = UnavailableVisionAlignmentPort()
            val waferStage = UnavailableWaferStagePort()
            val tracking = UnavailableProbeTrackingPort()

            assertEquals(AutonomyCapabilityState.NotConfigured, vision.status.value.state)
            assertEquals(AutonomyCapabilityState.NotConfigured, waferStage.status.value.state)
            assertEquals(AutonomyCapabilityState.NotConfigured, tracking.status.value.state)
            assertFalse(vision.status.value.connected)
            assertFalse(waferStage.status.value.connected)
            assertFalse(tracking.status.value.connected)

            assertFailsWith<AutonomyCapabilityUnavailableException> {
                vision.connect()
            }
            assertFailsWith<AutonomyCapabilityUnavailableException> {
                waferStage.snapshot()
            }
            assertFailsWith<AutonomyCapabilityUnavailableException> {
                tracking.snapshot()
            }
        }
    }

    @Test
    fun calibrationProfileCanBeSavedActivatedUpdatedAndCleared() {
        runBlocking {
            val repository = InMemoryCalibrationProfileRepository()
            val original = CalibrationProfile(
                id = "fixture-a",
                name = "Fixture A",
                controllerIdentity = "PI-H811-001",
                fixtureId = "fixture-a",
                geometry = PhotonicCouplingGeometry.VerticalGrating,
                measurementPose = OpticalPose.ZERO,
                createdAtEpochMs = 100L,
                verified = false
            )

            repository.saveProfile(original)
            assertEquals(listOf(original), repository.listProfiles())
            assertNull(repository.activeProfile.value)

            repository.activateProfile(original.id)
            assertEquals(original, repository.activeProfile.value)

            val verified = original.copy(
                verified = true,
                verifiedAtEpochMs = 200L,
                verifiedBy = "operator"
            )
            repository.saveProfile(verified)

            assertTrue(repository.activeProfile.value?.verified == true)
            assertEquals("operator", repository.findProfile(original.id)?.verifiedBy)

            repository.clearActiveProfile()
            assertNull(repository.activeProfile.value)

            repository.deleteProfile(original.id)
            assertTrue(repository.listProfiles().isEmpty())
        }
    }
}
