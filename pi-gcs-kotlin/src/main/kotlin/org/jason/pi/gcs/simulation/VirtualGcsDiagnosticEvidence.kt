package org.jason.pi.gcs.simulation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Stable, simulator-only diagnostic record. Never includes client IP or device credentials. */
data class VirtualGcsEvent(
    val sequence: Long,
    val connection: Long,
    val type: String,
    val command: String? = null,
    val outcome: String? = null,
    val fault: String? = null
)

/**
 * Thread-safe in-memory evidence journal for loopback tests.
 * Commands are a strictly allowlisted subset of read-only GCS protocol strings.
 */
class VirtualGcsDiagnosticEvidence {
    private val counter = AtomicLong()
    private val events = mutableListOf<VirtualGcsEvent>()

    @Synchronized
    fun record(connection: Long, type: String, command: String? = null,
               outcome: String? = null, fault: String? = null) {
        require(type in setOf("CONNECTED", "QUERY", "DISCONNECTED", "SERVER_CLOSED"))
        require(command == null || command in setOf("*IDN?", "ERR?", "POS? X Y Z U V W", "REJECTED"))
        events.add(VirtualGcsEvent(counter.incrementAndGet(), connection, type, command, outcome, fault))
    }

    @Synchronized
    fun snapshot(): List<VirtualGcsEvent> = events.toList()

    fun jsonLines(): String = snapshot().joinToString(separator = "\n", postfix = "\n") { e ->
        fun str(value: String?): String = if (value == null) "null" else
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\""
        "{\"schemaVersion\":1,\"sequence\":${e.sequence},\"connection\":${e.connection}," +
            "\"type\":${str(e.type)},\"command\":${str(e.command)}," +
            "\"outcome\":${str(e.outcome)},\"fault\":${str(e.fault)}}"
    }

    fun exportJsonLines(destination: Path) {
        Files.writeString(destination, jsonLines(), StandardCharsets.UTF_8)
    }
}
