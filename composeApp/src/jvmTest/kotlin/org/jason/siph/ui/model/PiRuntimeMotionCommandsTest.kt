package org.jason.siph.ui.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.hexapod.PiAxis
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
    @Test
    fun activeMotionPreventsSessionReplacementUntilCommandReturns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : GcsTransport {
            override var isOpen = false
                private set
            private var lastCommand = ""
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) {
                lastCommand = command
                if (command.startsWith("MOV ")) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            override suspend fun readLine(): String =
                if (lastCommand == "ERR?") "0" else error("Unexpected GCS readback: $lastCommand")
            override fun close() { isOpen = false }
        }
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        val commands = PiRuntimeMotionCommands(this)
        commands.bind(port)
        val pending = async { commands.moveTo(PiHexapodPose.ZERO, wait = false) }
        entered.await()
        assertFailsWith<IllegalStateException> { commands.bind(null) }
        assertFailsWith<IllegalStateException> { commands.close() }
        assertFailsWith<IllegalStateException> { commands.moveToSafePose() }
        release.complete(Unit)
        pending.await()
        commands.bind(null)
        assertFalse(commands.uiState.value.motionAllowed)
        commands.close()
        port.close()
    }

    @Test
    fun inFlightStopPreventsSessionSwapAndDisposal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : GcsTransport {
            override var isOpen = false
                private set
            private var lastCommand = ""
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) {
                lastCommand = command
                if (command == "STP") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            override suspend fun readLine(): String =
                if (lastCommand == "ERR?") "0" else error("Unexpected query: $lastCommand")
            override fun close() { isOpen = false }
        }
        val port = PiGcsHexapodPort(
            GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO, axes = listOf(PiAxis.X)
        )
        port.connect()
        val commands = PiRuntimeMotionCommands(this)
        commands.bind(port)
        val stopping = async { commands.stop() }
        entered.await()
        assertFailsWith<IllegalStateException> { commands.bind(null) }
        assertFailsWith<IllegalStateException> { commands.close() }
        assertFailsWith<IllegalStateException> { commands.stop() }
        assertFailsWith<IllegalStateException> { commands.moveToSafePose() }
        release.complete(Unit)
        stopping.await()
        assertFalse(commands.uiState.value.motionAllowed)
        commands.bind(null)
        commands.close()
        port.close()
    }

}
