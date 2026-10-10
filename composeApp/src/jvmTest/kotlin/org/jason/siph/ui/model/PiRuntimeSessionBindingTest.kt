package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.GcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PiRuntimeSessionBindingTest {
    @Test
    fun replacementIgnoresOldControllerTransitions() = runBlocking {
        val first = makePort()
        val second = makePort()
        val binding = PiRuntimeSessionBinding(this)
        first.connect()
        binding.bind(first)
        yield()
        assertTrue(binding.state.value.motionAllowed)
        binding.bind(second)
        assertFalse(binding.state.value.motionAllowed)
        second.connect()
        yield()
        assertTrue(binding.state.value.motionAllowed)
        first.stop()
        yield()
        assertEquals(PiUiMotionPhase.Ready.name, binding.state.value.piMotionPhase)
        assertTrue(binding.state.value.motionAllowed)
        binding.bind(null)
        assertEquals(PiUiMotionPhase.Disconnected.name, binding.state.value.piMotionPhase)
        assertFalse(binding.state.value.motionAllowed)
        binding.close()
        first.close()
        second.close()
    }

    @Test
    fun closedBindingCannotBeReactivated() = runBlocking {
        val port = makePort()
        val binding = PiRuntimeSessionBinding(this)
        binding.bind(port)
        binding.close()
        port.connect()
        yield()
        assertEquals(PiUiMotionPhase.Disconnected.name, binding.state.value.piMotionPhase)
        assertFalse(binding.state.value.motionAllowed)
        assertFailsWith<IllegalStateException> { binding.bind(port) }
        binding.close()
        port.close()
    }

    private fun makePort(): PiGcsHexapodPort {
        val transport = object : GcsTransport {
            override var isOpen = false
                private set
            override suspend fun open() { isOpen = true }
            private var lastCommand: String? = null
            override suspend fun writeLine(command: String) { lastCommand = command }
            override suspend fun readLine(): String = when (lastCommand) {
                "ERR?" -> "0"
                else -> error("Unexpected GCS query: $lastCommand")
            }
            override fun close() { isOpen = false }
        }
        return PiGcsHexapodPort(GcsDevice(GcsClient(transport)), PiHexapodPose.ZERO)
    }
}
