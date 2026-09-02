package org.jason.pi.gcs.core

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GcsDeviceTest {

    @Test
    fun commandChecksErrorCodeAfterWrite() = runBlocking {
        val transport = ScriptedTransport(responses = listOf("0"))
        val device = GcsDevice(GcsClient(transport))

        device.moveAbsolute(PiAxis.X, 1.25)

        assertEquals(
            expected = listOf("MOV X 1.250000000", "ERR?"),
            actual = transport.writes
        )
    }

    @Test
    fun commandExposesStructuredControllerError() = runBlocking {
        val transport = ScriptedTransport(responses = listOf("-1024"))
        val device = GcsDevice(GcsClient(transport))

        val exception = assertFailsWith<PiGcsCommandException> {
            device.servoOn(PiAxis.X)
        }

        assertEquals("SVO X 1", exception.command)
        assertEquals(-1024, exception.errorCode)
        assertEquals("E_1024_PI_MOTION_ERROR", exception.descriptor.symbol)
        assertEquals(PiGcsErrorCategory.Motion, exception.descriptor.category)
        assertTrue(exception.message.orEmpty().contains("position error too large"))
    }

    @Test
    fun unknownControllerErrorKeepsOriginalCode() = runBlocking {
        val transport = ScriptedTransport(responses = listOf("5"))
        val device = GcsDevice(GcsClient(transport))

        val exception = assertFailsWith<PiGcsCommandException> {
            device.servoOn(PiAxis.X)
        }

        assertEquals(5, exception.descriptor.code)
        assertEquals(PiGcsErrorCategory.Unknown, exception.descriptor.category)
    }

    @Test
    fun qPositionReadsOneResponseLinePerAxis() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf(
                "X=1.0",
                "Y=2.5",
                "Z=-3.0"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val positions = device.qPOS(listOf(PiAxis.X, PiAxis.Y, PiAxis.Z))

        assertEquals(listOf("POS? X Y Z"), transport.writes)
        assertEquals(3, transport.readCount)
        assertEquals(1.0, positions.getValue(PiAxis.X))
        assertEquals(2.5, positions.getValue(PiAxis.Y))
        assertEquals(-3.0, positions.getValue(PiAxis.Z))
    }

    @Test
    fun dynamicAxisPositionSupportsNumericAndGcs3Names() = runBlocking {
        val axis1 = PiAxisId.of("1")
        val axis2 = PiAxisId.of("AXIS_2")
        val transport = ScriptedTransport(
            responses = listOf(
                "1=0.125",
                "AXIS_2=-2.5"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val positions = device.qPOSIds(listOf(axis1, axis2))

        assertEquals(listOf("POS? 1 AXIS_2"), transport.writes)
        assertEquals(0.125, positions.getValue(axis1))
        assertEquals(-2.5, positions.getValue(axis2))
    }

    @Test
    fun qOnTargetReadsAndParsesMultiLineBooleanMap() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf(
                "X=1",
                "Y=0"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val states = device.qONT(listOf(PiAxis.X, PiAxis.Y))

        assertTrue(states.getValue(PiAxis.X))
        assertFalse(states.getValue(PiAxis.Y))
        assertEquals(2, transport.readCount)
    }

    @Test
    fun servoOnAllUsesSingleBatchCommand() = runBlocking {
        val transport = ScriptedTransport(responses = listOf("0"))
        val device = GcsDevice(GcsClient(transport))

        device.servoOnAll(listOf(PiAxis.X, PiAxis.Y, PiAxis.Z))

        assertEquals(
            expected = listOf(
                "SVO X 1 Y 1 Z 1",
                "ERR?"
            ),
            actual = transport.writes
        )
    }

    @Test
    fun referenceAllUsesConfiguredReferenceMode() = runBlocking {
        val transport = ScriptedTransport(responses = listOf("0"))
        val device = GcsDevice(GcsClient(transport))

        device.referenceAll(
            axes = listOf(PiAxis.X, PiAxis.Y, PiAxis.Z),
            mode = PiReferenceCommand.FNL
        )

        assertEquals(
            expected = listOf(
                "FNL X Y Z",
                "ERR?"
            ),
            actual = transport.writes
        )
    }

    @Test
    fun qServoReadsOneLinePerAxis() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf(
                "X=1",
                "Y=1",
                "Z=0"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val states = device.qServo(listOf(PiAxis.X, PiAxis.Y, PiAxis.Z))

        assertTrue(states.getValue(PiAxis.X))
        assertTrue(states.getValue(PiAxis.Y))
        assertFalse(states.getValue(PiAxis.Z))
    }

    @Test
    fun qAxesParsesControllerAxisList() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("X Y Z U V W")
        )
        val device = GcsDevice(GcsClient(transport))

        assertEquals(
            expected = PiAxis.HEXAPOD_AXES,
            actual = device.qAxes()
        )
    }

    @Test
    fun qAxisIdsKeepsControllerSpecificAxisNames() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("1 2 AXIS_3")
        )
        val device = GcsDevice(GcsClient(transport))

        assertEquals(
            expected = listOf(
                PiAxisId.of("1"),
                PiAxisId.of("2"),
                PiAxisId.of("AXIS_3")
            ),
            actual = device.qAxisIds()
        )
    }

    @Test
    fun checkedQueryReadsResponseAndErrorInsideOneTransaction() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf(
                "GCS 2.0 Firmware 1.2.3",
                "0"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val response = device.executeChecked(GcsCommand.qVER())

        assertEquals("GCS 2.0 Firmware 1.2.3", response)
        assertEquals(listOf("VER?", "ERR?"), transport.writes)
    }

    @Test
    fun controllerInspectionUsesOnlyCheckedReadOnlyCommandsByDefault() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf(
                "Physik Instrumente, C-887.52, SN 12345",
                "0",
                "GCS 2.0 Firmware 1.2.3",
                "0",
                "X Y Z U V W",
                "0"
            )
        )
        val device = GcsDevice(GcsClient(transport))

        val profile = device.inspectController(
            PiControllerProbeOptions(
                connectionType = PiConnectionType.TcpIp,
                assumedReferenceModes = setOf(PiReferenceCommand.FRF)
            )
        )

        assertEquals(
            listOf(
                "*IDN?", "ERR?",
                "VER?", "ERR?",
                "SAI?", "ERR?"
            ),
            transport.writes
        )
        assertEquals(PiConnectionType.TcpIp, profile.info.connectionType)
        assertEquals(PiAxis.HEXAPOD_AXES, profile.knownHexapodAxes)
        assertTrue(profile.capabilities.supports(PiGcsFeature.Identify))
        assertTrue(profile.capabilities.supports(PiGcsFeature.AxisDiscovery))
        assertTrue(profile.capabilities.supports(PiGcsFeature.ReferenceFRF))
        assertFalse(profile.capabilities.supports(PiGcsFeature.ReferenceFNL))
        assertEquals(
            PiCapabilityStatus.NotProbed,
            profile.capabilities[PiGcsFeature.PositionQuery].status
        )
    }
}

private class ScriptedTransport(
    responses: List<String>
) : GcsTransport {

    private val pendingResponses = ArrayDeque(responses)

    val writes: MutableList<String> = mutableListOf()

    var readCount: Int = 0
        private set

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
            "No scripted response left for writes=$writes"
        }
        readCount += 1
        return pendingResponses.removeFirst()
    }

    override fun close() {
        isOpen = false
    }
}
