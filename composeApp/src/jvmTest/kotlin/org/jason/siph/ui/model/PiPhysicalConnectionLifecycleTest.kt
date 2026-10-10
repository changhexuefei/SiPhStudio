package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PiPhysicalConnectionLifecycleTest {
    @Test
    fun physicalConnectionBindsOnlyAfterSuccessfulDeviceOpen() = runBlocking {
        val transport = FakeTransport()
        val commands = PiRuntimeMotionCommands(this)
        val owner = PiPhysicalConnectionLifecycle(commands)
        val port = makePort(transport)
        assertFalse(commands.uiState.value.motionAllowed)
        owner.connect(port)
        assertTrue(transport.isOpen)
        assertTrue(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { owner.connect(port) }
        owner.disconnect()
        assertFalse(transport.isOpen)
        assertFalse(commands.uiState.value.motionAllowed)
        commands.close()
        Unit
    }

    @Test
    fun failedPhysicalConnectionNeverClaimsReady() = runBlocking {
        val transport = FakeTransport(failOpen = true)
        val commands = PiRuntimeMotionCommands(this)
        val owner = PiPhysicalConnectionLifecycle(commands)
        assertFailsWith<IllegalStateException> { owner.connect(makePort(transport)) }
        assertFalse(commands.uiState.value.motionAllowed)
        assertFalse(transport.isOpen)
        assertFailsWith<IllegalStateException> { owner.disconnect() }
        commands.close()
        Unit
    }

    @Test
    fun bindFailureClosesOpenedTransportAndNeverClaimsSession() = runBlocking {
        val transport = FakeTransport()
        val commands = PiRuntimeMotionCommands(this)
        // A disposed command dispatcher cannot accept a newly opened port.
        commands.close()
        val owner = PiPhysicalConnectionLifecycle(commands)
        val port = makePort(transport)
        val failure = assertFailsWith<IllegalStateException> { owner.connect(port) }
        assertTrue(failure.message?.contains("closed") == true)
        assertFalse(transport.isOpen)
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { owner.disconnect() }
        Unit
    }

    @Test
    fun failedDisconnectKeepsOwnershipUntilRetrySucceeds() = runBlocking {
        val transport = object : GcsTransport {
            override var isOpen = false
                private set
            var failClose = true
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) = Unit
            override suspend fun readLine(): String = error("Unexpected read")
            override fun close() {
                if (failClose) {
                    failClose = false
                    error("Simulated transport close failure")
                }
                isOpen = false
            }
        }
        val commands = PiRuntimeMotionCommands(this)
        val owner = PiPhysicalConnectionLifecycle(commands)
        val port = makePort(transport)
        owner.connect(port)
        assertFailsWith<IllegalStateException> { owner.disconnect() }
        assertTrue(transport.isOpen)
        assertFalse(commands.uiState.value.motionAllowed)
        assertFailsWith<IllegalStateException> { owner.connect(makePort(FakeTransport())) }
        // Retry the same physical close while the UI stays unbound.
        owner.disconnect()
        assertFalse(transport.isOpen)
        assertFalse(commands.uiState.value.motionAllowed)
        commands.close()
        Unit
    }

    @Test
    fun physicalIdentityReadbackRequiresOwnedSession() = runBlocking {
        val transport = object : GcsTransport {
            override var isOpen = false
                private set
            private var last = ""
            override suspend fun open() { isOpen = true }
            override suspend fun writeLine(command: String) { last = command }
            override suspend fun readLine(): String {
                check(last == "*IDN?") { "Unexpected device query: $last" }
                return "PI,Hexapod,TEST,1.0"
            }
            override fun close() { isOpen = false }
        }
        val commands = PiRuntimeMotionCommands(this)
        val owner = PiPhysicalConnectionLifecycle(commands)
        assertFailsWith<IllegalStateException> { owner.identify() }
        assertFailsWith<IllegalStateException> { owner.readPose() }
        owner.connect(makePort(transport))
        assertTrue(owner.identify().contains("PI,Hexapod"))
        owner.disconnect()
        assertFailsWith<IllegalStateException> { owner.identify() }
        assertFailsWith<IllegalStateException> { owner.readPose() }
        commands.close()
        Unit
    }

    private fun makePort(transport: GcsTransport): PiGcsHexapodPort =
        PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)

    private class FakeTransport(private val failOpen: Boolean = false) : GcsTransport {
        override var isOpen: Boolean = false
            private set
        override suspend fun open() {
            if (failOpen) error("Simulated PI connection failure")
            isOpen = true
        }
        override suspend fun writeLine(command: String) = Unit
        override suspend fun readLine(): String = error("Unexpected read")
        override fun close() { isOpen = false }
    }
}
