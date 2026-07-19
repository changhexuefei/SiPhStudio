package org.jason.siph.domain.inspection

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class InspectionReplayTest {

    @Test
    fun recordedCameraAndZSamplesReplayDeterministically() = runBlocking {
        var now = 10_000L
        val positioner = DemoOpticalPositioner()
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 }
        )
        val camera = SimulatedCameraAcquisitionPort(
            environment = environment,
            nowEpochMs = { now++ }
        )
        val sensor = SimulatedZDisplacementSensorPort(environment) { now++ }
        val archive = InMemoryInspectionReplayArchive()
        val recordingCamera = RecordingCameraAcquisitionPort(camera, archive)
        val recordingSensor = RecordingZDisplacementSensorPort(sensor, archive)
        recordingCamera.connect()
        recordingSensor.connect()

        val frame = recordingCamera.capture()
        val sample = recordingSensor.sample()
        val replayCamera = ReplayCameraAcquisitionPort(archive.frames())
        val replaySensor = ReplayZDisplacementSensorPort(archive.zSamples())
        replayCamera.connect()
        replaySensor.connect()

        val replayedFrame = replayCamera.capture()
        val replayedSample = replaySensor.sample()
        assertEquals(frame.frameId, replayedFrame.frameId)
        assertContentEquals(frame.pixels, replayedFrame.pixels)
        assertEquals(sample, replayedSample)
    }
}
