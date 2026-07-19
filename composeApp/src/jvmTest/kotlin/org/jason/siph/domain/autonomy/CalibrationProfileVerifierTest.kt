package org.jason.siph.domain.autonomy

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationProfileVerifierTest {

    @Test
    fun verificationPersistsRecordAndActivatesPassingProfile() = runBlocking {
        var now = 100L
        val repository = InMemoryAutonomyRepository()
        val profile = CalibrationProfile(
            id = "calibration-a",
            name = "Calibration A",
            controllerIdentity = "PI-TEST",
            fixtureId = "fixture-a",
            geometry = PhotonicCouplingGeometry.VerticalGrating,
            measurementPose = OpticalPose.ZERO,
            createdAtEpochMs = 1L,
            verified = false
        )
        repository.saveProfile(profile)
        val verifier = CalibrationProfileVerifier(
            positioner = CalibrationTestPositioner(),
            profiles = repository,
            verifications = repository,
            nowEpochMs = { now++ }
        )

        val result = verifier.verify(
            CalibrationProfileVerificationRequest(
                profileId = profile.id,
                verifiedBy = "test-operator",
                maximumPoseErrorUm = 0.1,
                activateOnPass = true
            )
        )

        assertTrue(result.passed)
        assertTrue(repository.findProfile(profile.id)?.verified == true)
        assertEquals(profile.id, repository.activeProfile.value?.id)
        assertEquals(1, repository.listVerifications(profile.id).size)
    }
}

private class CalibrationTestPositioner : OpticalPositionerPort {
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = "PI-TEST-H811"
    override suspend fun startup(reference: Boolean) = Unit
    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) = Unit
    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) = Unit
    override suspend fun currentPose(): OpticalPose = OpticalPose.ZERO
    override suspend fun waitOnTarget(timeoutMs: Long) = Unit
    override suspend fun stop() = Unit
    override suspend fun moveToSafePose() = Unit
}
