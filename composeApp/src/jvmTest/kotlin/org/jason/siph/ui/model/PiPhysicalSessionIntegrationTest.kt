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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop Physical-mode integration without real equipment or motion commands.
 * Exercises the same lifecycle, projection and refresh coordinator as main.kt.
 */
class PiPhysicalSessionIntegrationTest {
    @Test
    fun connectReadDisconnectReconnectNeverRetainsPreviousControllerReadback() = runBlocking {
        val commands = PiRuntimeMotionCommands(this)
        val lifecycle = PiPhysicalConnectionLifecycle(commands)
        val projection = PiPhysicalReadbackProjection(lifecycle, commands)
        val coordinator = PiPhysicalReadbackCoordinator { ui -> projection.refreshOrError(ui) }
        val firstTransport = ReadOnlyTransport("PI,HEXAPOD,A,1", 0.001)
        val first = makePort(firstTransport)
        val initial = CouplingToolUiState().rejectUnwiredPhysicalAction()

        val withoutConnection = coordinator.refresh(initial)
        assertTrue(withoutConnection?.status?.isError == true)
        assertFalse(withoutConnection!!.positioner.motionAllowed)
        assertNull(withoutConnection.positioner.idn)

        lifecycle.connect(first)
        coordinator.invalidate()
        val a = coordinator.refresh(initial)
        assertEquals("PI,HEXAPOD,A,1", a?.positioner?.idn)
        assertEquals(1.0, a?.positioner?.currentPose?.xUm)
        assertEquals(1, firstTransport.identityQueries)
        assertEquals(1, firstTransport.poseQueries)
        assertEquals(0, firstTransport.motionCommands)

        coordinator.invalidate()
        lifecycle.disconnect()
        assertFalse(firstTransport.isOpen)
        assertFalse(commands.uiState.value.motionAllowed)
        val disconnected = coordinator.refresh(a!!)
        assertTrue(disconnected?.status?.isError == true)
        assertNull(disconnected?.positioner?.idn)
        assertFalse(disconnected!!.positioner.motionAllowed)

        val secondTransport = ReadOnlyTransport("PI,HEXAPOD,B,2", 0.005)
        lifecycle.connect(makePort(secondTransport))
        coordinator.invalidate()
        val b = coordinator.refresh(disconnected)
        assertEquals("PI,HEXAPOD,B,2", b?.positioner?.idn)
        assertEquals(5.0, b?.positioner?.currentPose?.xUm)
        assertEquals(0, secondTransport.motionCommands)
        assertEquals(0, firstTransport.motionCommands)
        lifecycle.disconnectIfConnected()
        coordinator.close()
        commands.close()
    }

    private fun makePort(transport: GcsTransport): PiGcsHexapodPort =
        PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)

    private class ReadOnlyTransport(
        private val identity: String,
        private val xMm: Double
    ) : GcsTransport {
        override var isOpen = false
            private set
        var identityQueries = 0
            private set
        var poseQueries = 0
            private set
        var motionCommands = 0
            private set
        private var command = ""
        private var axisIndex = 0
        private val axes get() = listOf(
            "X=$xMm", "Y=0.002", "Z=0.003", "U=0.1", "V=0.2", "W=0.3"
        )

        override suspend fun open() { isOpen = true }
        override suspend fun writeLine(command: String) {
            this.command = command
            axisIndex = 0
            when {
                command == "*IDN?" -> identityQueries++
                command.startsWith("POS?") -> poseQueries++
                command != "ERR?" -> motionCommands++
            }
        }
        override suspend fun readLine(): String = when {
            command == "*IDN?" -> identity
            command == "ERR?" -> "0"
            command.startsWith("POS?") -> axes[axisIndex++]
            else -> error("Unexpected physical GCS response request: $command")
        }
        override fun close() { isOpen = false }
    }
}
