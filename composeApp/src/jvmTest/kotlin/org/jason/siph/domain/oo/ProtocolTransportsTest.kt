package org.jason.siph.domain.oo

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTransportsTest {

    @Test
    fun recordedProtocolCanBeReplayedWithStrictOrdering() {
        runBlocking {
            var now = 100L
            val scripted = ScriptedTextProtocolTransport(
                steps = listOf(
                    ScriptedProtocolStep("*IDN?", "VENDOR,MODEL,1,1.0"),
                    ScriptedProtocolStep("OUT 1")
                )
            )
            val recording = RecordingTextProtocolTransport(
                deviceId = "laser-a",
                delegate = scripted,
                nowEpochMs = { now++ }
            )

            recording.connect()
            assertEquals("VENDOR,MODEL,1,1.0", recording.query("*IDN?"))
            recording.write("OUT 1")
            recording.disconnect()

            assertEquals(
                listOf(
                    ProtocolExchangeDirection.Query,
                    ProtocolExchangeDirection.Response,
                    ProtocolExchangeDirection.Write
                ),
                recording.exchanges.map { it.direction }
            )

            val replay = ReplayTextProtocolTransport(recording.exchanges)
            replay.connect()
            assertEquals("VENDOR,MODEL,1,1.0", replay.query("*IDN?"))
            replay.write("OUT 1")
            replay.disconnect()
        }
    }
}
