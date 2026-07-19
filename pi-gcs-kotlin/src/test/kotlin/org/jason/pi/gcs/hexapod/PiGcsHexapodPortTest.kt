package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiConnectionType
import org.jason.pi.gcs.core.PiControllerProbeOptions
import org.jason.pi.gcs.core.PiGcsCommandException
import org.jason.pi.gcs.pitools.PiTravelRangeException
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiGcsHexapodPortTest {

    @Test
    fun connectPublishesControllerProfileAndLoadsTravelRange() = runBlocking {
        val transport = ScriptedHexapodTransport(
            responses = successfulConnectResponses()
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
        assertEquals(
            expected = listOf(
                "*IDN?", "ERR?",
                "VER?", "ERR?",
                "SAI?", "ERR?",
                "TMN? X Y Z U V W",
                "TMX? X Y Z U V W"
            ),
            actual = transport.writes
        )

        val businessRange = port.queryBusinessTravelRange()
        assertEquals(-100_000.0, businessRange.getValue(PiAxis.X).start)
        assertEquals(100_000.0, businessRange.getValue(PiAxis.X).endInclusive)
        assertEquals(-10.0, businessRange.getValue(PiAxis.U).start)
        assertEquals(10.0, businessRange.getValue(PiAxis.U).endInclusive)

        port.disconnect()

        assertEquals(
            PiHexapodConnectionPhase.Disconnected,
            port.connectionState.value.phase
        )
        assertFalse(transport.isOpen)
    }

    @Test
    fun absoluteMoveOutsideControllerRangeIsRejectedBeforeMovCommand() = runBlocking {
        val transport = ScriptedHexapodTransport(
            responses = successfulConnectResponses()
        )
        val port = PiGcsHexapodPort(
            device = GcsDevice(GcsClient(transport)),
            safePose = PiHexapodPose.ZERO
        )
        port.connect()
        val writesBeforeMove = transport.writes.toList()

        assertFailsWith<PiTravelRangeException> {
            port.moveTo(
                pose = PiHexapodPose(
                    xUm = 100_001.0,
                    yUm = 0.0,
                    zUm = 0.0,
                    uDeg = 0.0,
                    vDeg = 0.0,
                    wDeg = 0.0
                ),
                wait = false
            )
        }

        assertEquals(writesBeforeMove, transport.writes)
        assertTrue(transport.writes.none { it.startsWith("MOV ") })
        port.disconnect()
    }

    @Test
    fun nonFinitePoseAndDeltaAreRejectedAtModelBoundary() {
        assertFailsWith<IllegalArgumentException> {
            PiHexapodPose(
                xUm = Double.NaN,
                yUm = 0.0,
                zUm = 0.0,
                uDeg = 0.0,
                vDeg = 0.0,
                wDeg = 0.0
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PiHexapodDelta(dxUm = Double.POSITIVE_INFINITY)
        }
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

private fun successfulConnectResponses(): List<String> = listOf(
    "Physik Instrumente, C-887.52, SN 12345",
    "0",
    "GCS 2.0 Firmware 1.2.3",
    "0",
    "X Y Z U V W",
    "0",
    "X=-100.0",
    "Y=-100.0",
    "Z=-50.0",
    "U=-10.0",
    "V=-10.0",
    "W=-10.0",
    "X=100.0",
    "Y=100.0",
    "Z=50.0",
    "U=10.0",
    "V=10.0",
    "W=10.0"
)

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
