package org.jason.siph.domain.inspection

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FacetDetectorTest {

    @Test
    fun facetDetectorRejectsCompactFiberBlobAndFitsFacetLine() = runBlocking {
        var now = 20_000L
        val positioner = DemoOpticalPositioner()
        positioner.connect()
        val environment = SimulatedInspectionEnvironment(
            poseProvider = { positioner.currentPose() },
            temperatureProvider = { 25.0 },
            targetKind = VisionFeatureKind.Facet
        )
        val camera = SimulatedCameraAcquisitionPort(
            environment = environment,
            nowEpochMs = { now++ }
        )
        camera.connect()

        val detection = FacetLineDetector().detect(
            frame = camera.capture(),
            request = VisionFeatureRequest(
                kind = VisionFeatureKind.Facet,
                minimumConfidence = 0.65
            )
        )

        assertTrue(detection.found, detection.message)
        assertTrue(abs((detection.angleDeg ?: 0.0) - 12.0) < 2.0)
        assertTrue((detection.widthPx ?: 0.0) > 60.0)
    }
}
