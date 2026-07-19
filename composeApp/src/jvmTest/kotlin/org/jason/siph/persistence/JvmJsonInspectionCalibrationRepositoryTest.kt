package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.inspection.CameraStageCalibration
import org.jason.siph.domain.inspection.VisionPointPx
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmJsonInspectionCalibrationRepositoryTest {

    @Test
    fun cameraCalibrationSurvivesRepositoryRestart() = runBlocking {
        val directory = Files.createTempDirectory("inspection-calibration-test")
        val path = directory.resolve("inspection.json")
        val calibration = CameraStageCalibration(
            id = "camera-cal",
            cameraId = "camera-1",
            opticalCenterPx = VisionPointPx(320.0, 240.0),
            micrometersPerPixelX = 0.42,
            micrometersPerPixelY = 0.43,
            cameraToStageRotationDeg = 0.7,
            calibratedAtEpochMs = 123L,
            verified = true,
            rmsErrorUm = 0.18
        )

        JvmJsonInspectionCalibrationRepository(path).saveCameraCalibration(calibration)
        val restored = JvmJsonInspectionCalibrationRepository(path)
            .findCameraCalibration(calibration.id)

        assertNotNull(restored)
        assertEquals(calibration, restored)
    }
}
