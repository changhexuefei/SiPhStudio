package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiConnectionType
import org.jason.pi.gcs.core.PiControllerProbeOptions
import org.jason.pi.gcs.core.PiGcsCommandException
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiGcsHexapodPortTest {

    @Test
    fun connectPublishesControllerProfile() = runBlocking {
        val transport = ScriptedHexapodTransport(
            responses = listOf(
                "Physik Instrumente, C-887.52, SN 12345",
                "0",
                "GCS 2.0 Firmware 1.2.3",
                "0",
                "X Y Z U V W",
                "0"
            )
        )
        val port = PiGcsHexapodPort(
            device = GcsDevice(GcsClient(transport)),
            safePose = PiHexapodPose.ZERO,
            probeOptions = PiControllerProbeOptions(
                connectionType = PiConnectionType.TcpIp
            )
        )

        port.connect()

        val connected = port.connectionState.value
        assertEquals(PiHexapodConnectionPhase.Connected, connected.phase)
        assertTrue(connected.isConnected)
        assertNotNull(connected.profile)
        assertEquals(PiConnectionType.TcpIp, connected.profile.info.connectionType)
        assertEquals(PiAxis.HEXAPOD_AXES, connected.profile.knownHexapodAxes)
        assertEquals(
            "Physik Instrumente, C-887.52, SN 12345",
            port.identify()
        )

        port.disconnect()

        assertEquals(
            PiHexapodConnectionPhase.Disconnected,
            port.connectionState.value.phase
        )
        assertFalse(transport.isOpen)
    }

    @Test
    fun connectFailureClosesTransportAndPublishesFailedState() = runBlocking {
        val transport = ScriptedHexapodTransport(
            responses = listOf(
                "Physik Instrumente, C-887.52",
                "-1024"
            )
        )
        val port = PiGcsHexapodPort(
            device = GcsDevice(GcsClient(transport)),
            safePose = PiHexapodPose.ZERO
        )

        assertFailsWith<PiGcsCommandException> {
            port.connect()
        }

        val failed = port.connectionState.value
        assertEquals(PiHexapodConnectionPhase.Failed, failed.phase)
        assertTrue(failed.errorMessage.orEmpty().contains("-1024"))
        assertFalse(transport.isOpen)
    }
}

private class ScriptedHexapodTransport(
    responses: List<String>
) : GcsTransport {

    private val pendingResponses = ArrayDeque(responses)

    val writes = mutableListOf<String>()

    override var isOpen: Boolean = false
        private set

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun writeLine(command: String) {
        writes += command
    }

    override suspend fun readLine(): String {
        check(pendingResponses.isNotEmpty()) {
            "No response left for writes=$writes"
        }
        return pendingResponses.removeFirst()
    }

    override fun close() {
        isOpen = false
    }
}
