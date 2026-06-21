package org.jason.pi.gcs.core

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GcsDeviceTest {

    @Test
    fun commandChecksErrorCodeAfterWrite() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("0")
        )
        val device = GcsDevice(GcsClient(transport))

        device.moveAbsolute(PiAxis.X, 1.25)

        assertEquals(
            expected = listOf("MOV X 1.250000000", "ERR?"),
            actual = transport.writes
        )
    }

    @Test
    fun commandThrowsWhenControllerReportsError() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("5")
        )
        val device = GcsDevice(GcsClient(transport))

        val exception = assertFailsWith<PiGcsCommandException> {
            device.servoOn(PiAxis.X)
        }

        assertEquals("SVO X 1", exception.command)
        assertEquals(5, exception.errorCode)
    }

    @Test
    fun qPositionUsesSingleMultiAxisQuery() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("X=1.0 Y=2.5 Z=-3.0")
        )
        val device = GcsDevice(GcsClient(transport))

        val positions = device.qPOS(listOf(PiAxis.X, PiAxis.Y, PiAxis.Z))

        assertEquals(
            expected = listOf("POS? X Y Z"),
            actual = transport.writes
        )
        assertEquals(1.0, positions.getValue(PiAxis.X))
        assertEquals(2.5, positions.getValue(PiAxis.Y))
        assertEquals(-3.0, positions.getValue(PiAxis.Z))
    }

    @Test
    fun qOnTargetParsesBooleanMap() = runBlocking {
        val transport = ScriptedTransport(
            responses = listOf("X=1 Y=0")
        )
        val device = GcsDevice(GcsClient(transport))

        val states = device.qONT(listOf(PiAxis.X, PiAxis.Y))

        assertTrue(states.getValue(PiAxis.X))
        assertEquals(false, states.getValue(PiAxis.Y))
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
}

private class ScriptedTransport(
    responses: List<String>
) : GcsTransport {

    private val pendingResponses = ArrayDeque(responses)

    val writes: MutableList<String> = mutableListOf()

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
        return pendingResponses.removeFirst()
    }

    override fun close() {
        isOpen = false
    }
}
