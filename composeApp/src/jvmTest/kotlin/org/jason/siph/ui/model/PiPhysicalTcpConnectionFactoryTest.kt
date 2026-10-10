package org.jason.siph.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PiPhysicalTcpConnectionFactoryTest {
    @Test
    fun validatesEndpointWithoutOpeningAnyConnection() {
        val config = PiPhysicalTcpConnectionConfig("192.168.1.10", 50000)
        assertEquals("192.168.1.10", config.host)
        assertEquals(50000, config.port)
        val port = PiPhysicalTcpConnectionFactory.create(config)
        // Factory must never establish a hardware session or infer Ready.
        assertFalse(port.motionState.value.phase.name == "Ready")
        port.close()
    }

    @Test
    fun rejectsUnsafeOrInvalidEndpoints() {
        assertFailsWith<IllegalArgumentException> { PiPhysicalTcpConnectionConfig("") }
        assertFailsWith<IllegalArgumentException> { PiPhysicalTcpConnectionConfig(" 192.168.1.10") }
        assertFailsWith<IllegalArgumentException> { PiPhysicalTcpConnectionConfig("pi controller") }
        assertFailsWith<IllegalArgumentException> { PiPhysicalTcpConnectionConfig("127.0.0.1", 0) }
        assertFailsWith<IllegalArgumentException> { PiPhysicalTcpConnectionConfig("127.0.0.1", 65536) }
    }
}
