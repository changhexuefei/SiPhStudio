package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PiPhysicalReadbackProjectionTest {
    @Test
    fun projectsActualIdentityAndPoseWithoutCreatingFakeReady() = runBlocking {
        val transport = object : GcsTransport {
            override var isOpen: Boolean = false
                private set
            private var command = ""
            private var index = 0
            private val axes = listOf("X=0.001", "Y=0.002", "Z=0.003",
                "U=0.1", "V=0.2", "W=0.3")
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(value: String) {
                command = value
                index = 0
            }
            override suspend fun readLine(): String = when {
                command == "*IDN?" -> "PI,HEXAPOD,TEST,1.0"
                command.startsWith("POS?") -> axes[index++]
                command == "ERR?" -> "0"
                else -> error("Unexpected query: $command")
            }
            override fun close() { isOpen = false }
        }
        val commands = PiRuntimeMotionCommands(this)
        val lifecycle = PiPhysicalConnectionLifecycle(commands)
        val projection = PiPhysicalReadbackProjection(lifecycle, commands)
        assertFailsWith<IllegalStateException> { projection.refresh(CouplingToolUiState()) }
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
        lifecycle.connect(port)
        val actual = projection.refresh(CouplingToolUiState())
        assertEquals("PI,HEXAPOD,TEST,1.0", actual.positioner.idn)
        assertEquals(1.0, actual.positioner.currentPose.xUm)
        assertEquals(2.0, actual.positioner.currentPose.yUm)
        assertEquals(3.0, actual.positioner.currentPose.zUm)
        assertEquals(0.1, actual.positioner.currentPose.uDeg)
        assertTrue(actual.positioner.requiresVerifiedPiState)
        assertEquals(commands.uiState.value.motionAllowed, actual.positioner.motionAllowed)
        lifecycle.disconnect()
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { projection.refresh(CouplingToolUiState()) }
        commands.close()
        Unit
    }
    @Test
    fun failedReadbackClearsStaleDisplayAndDeniesMotion() = runBlocking {
        val commands = PiRuntimeMotionCommands(this)
        val lifecycle = PiPhysicalConnectionLifecycle(commands)
        val projection = PiPhysicalReadbackProjection(lifecycle, commands)
        val stale = CouplingToolUiState(
            positioner = PositionerUiState(
                connected = true,
                idn = "OLD DEVICE",
                currentPose = org.jason.siph.domain.positioner.OpticalPose(
                    10.0, 20.0, 30.0, 1.0, 2.0, 3.0
                ),
                linearStepUm = 2.5,
                angleStepDeg = 0.02
            )
        )
        val error = projection.refreshOrError(stale)
        assertFalse(error.positioner.motionAllowed)
        assertFalse(error.positioner.connected)
        assertEquals(null, error.positioner.idn)
        assertEquals(org.jason.siph.domain.positioner.OpticalPose.ZERO, error.positioner.currentPose)
        assertEquals(2.5, error.positioner.linearStepUm)
        assertEquals(0.02, error.positioner.angleStepDeg)
        assertTrue(error.status.isError)
        assertTrue(error.status.message?.contains("failed") == true)
        commands.close()
        Unit
    }

}
