package org.jason.pi.gcs.core

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.GcsTransport
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GcsProtocolTest {

    @Test
    fun parsesPlainSingleAxisResponse() {
        assertEquals(
            expected = 1.25,
            actual = GcsResponseParser.parseAxisDouble("1.25", PiAxis.X)
        )
    }

    @Test
    fun parsesMultiLineAxisResponseInRequestedOrder() {
        val result = GcsResponseParser.parseAxisDoubleMap(
            response = "Y=2.0\nX=1.0",
            expectedAxes = listOf(PiAxis.X, PiAxis.Y)
        )

        assertEquals(listOf(PiAxis.X, PiAxis.Y), result.keys.toList())
        assertEquals(1.0, result.getValue(PiAxis.X))
        assertEquals(2.0, result.getValue(PiAxis.Y))
    }

    @Test
    fun detectsWhenAccumulatedAxisResponseIsComplete() {
        assertFalse(
            GcsResponseParser.containsAllAxes(
                response = "X=1.0",
                expectedAxes = listOf(PiAxis.X, PiAxis.Y)
            )
        )

        assertTrue(
            GcsResponseParser.containsAllAxes(
                response = "X=1.0\nY=2.0",
                expectedAxes = listOf(PiAxis.X, PiAxis.Y)
            )
        )
    }

    @Test
    fun createsOneBatchServoCommand() {
        val command = GcsCommand.servo(
            axes = listOf(PiAxis.X, PiAxis.Y, PiAxis.Z),
            enabled = true
        )

        assertEquals("SVO X 1 Y 1 Z 1", command.text)
    }

    @Test
    fun rejectsNonFiniteMotionValues() {
        assertFailsWith<IllegalArgumentException> {
            GcsCommand.moveAbsolute(PiAxis.X, Double.NaN)
        }

        assertFailsWith<IllegalArgumentException> {
            GcsCommand.moveRelative(PiAxis.X, Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun readsEveryLineBelongingToAMultiAxisQuery() = runBlocking {
        val transport = FakeTransport(
            responses = listOf("X=1.0", "Y=2.0")
        )
        val client = GcsClient(
            transport = transport,
            checkErrorAfterCommand = true
        )
        val device = GcsDevice(client)

        device.connect()
        val positions = device.qPOS(listOf(PiAxis.X, PiAxis.Y))

        assertEquals(1.0, positions.getValue(PiAxis.X))
        assertEquals(2.0, positions.getValue(PiAxis.Y))
        assertEquals(listOf("POS? X Y"), transport.writes)
    }

    @Test
    fun stopsReadingWhenOneLineAlreadyContainsAllAxes() = runBlocking {
        val transport = FakeTransport(
            responses = listOf("X=1.0 Y=2.0")
        )
        val client = GcsClient(transport)
        val device = GcsDevice(client)

        device.connect()
        val positions = device.qPOS(listOf(PiAxis.X, PiAxis.Y))

        assertEquals(1.0, positions.getValue(PiAxis.X))
        assertEquals(2.0, positions.getValue(PiAxis.Y))
        assertEquals(0, transport.remainingResponses)
    }

    @Test
    fun serializesBatchCommandAndErrorCheck() = runBlocking {
        val transport = FakeTransport(responses = listOf("0"))
        val client = GcsClient(transport)
        val device = GcsDevice(client)

        device.connect()
        device.setServo(
            axes = listOf(PiAxis.X, PiAxis.Y),
            enabled = true
        )

        assertEquals(
            listOf("SVO X 1 Y 1", "ERR?"),
            transport.writes
        )
    }
}

private class FakeTransport(
    responses: List<String>
) : GcsTransport {

    private val responseQueue = ArrayDeque(responses)

    override var isOpen: Boolean = false
        private set

    val writes: MutableList<String> = mutableListOf()

    val remainingResponses: Int
        get() = responseQueue.size

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun writeLine(command: String) {
        check(isOpen)
        writes += command
    }

    override suspend fun readLine(): String {
        check(isOpen)
        return responseQueue.pollFirst()
            ?: error("No fake PI GCS response available")
    }

    override fun close() {
        isOpen = false
    }
}
