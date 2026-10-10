package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PiRuntimeMotionCommandsTest {
    @Test
    fun noSessionDeniesPhysicalMovementAndStop() = runBlocking {
        val commands = PiRuntimeMotionCommands(this)
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { commands.moveToSafePose() }
        assertFailsWith<IllegalStateException> { commands.stop() }
        commands.close()
        assertFailsWith<IllegalStateException> { commands.bind(null) }
        Unit // JUnit4 requires a void-returning test method.
    }

    @Test
    fun selectedDisconnectedSessionCannotDispatchMovement() = runBlocking {
        val transport = object : GcsTransport {
            override val isOpen: Boolean = false
            override suspend fun open() = error("Unexpected device open")
            override suspend fun writeLine(command: String) = error("Unexpected GCS command: $command")
            override suspend fun readLine(): String = error("Unexpected GCS query")
            override fun close() = Unit
        }
        val port = PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
        val commands = PiRuntimeMotionCommands(this)
        commands.bind(port)
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { commands.moveToSafePose() }
        commands.bind(null)
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { commands.moveToSafePose() }
        commands.close()
    }
}
