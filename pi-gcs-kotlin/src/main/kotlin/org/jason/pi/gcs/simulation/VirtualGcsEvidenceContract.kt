package org.jason.pi.gcs.simulation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * CI evidence contract for the *virtual* GCS peer only.
 * Produces a summary after validating journal sequence, connection ownership
 * and terminal lifecycle events. No physical equipment is involved.
 */
object VirtualGcsEvidenceContract {
    const val SCHEMA_VERSION = 1

    fun validate(events: List<VirtualGcsEvent>): List<String> {
        val errors = mutableListOf<String>()
        if (events.isEmpty()) errors += "EMPTY_TRACE"
        if (events.map { it.sequence } != (1L..events.size.toLong()).toList())
            errors += "SEQUENCE_GAP"
        val connections = events.filter { it.type == "CONNECTED" }.map { it.connection }.toSet()
        if (events.any { it.type == "QUERY" && it.connection !in connections })
            errors += "QUERY_WITHOUT_CONNECTION"
        if (events.any { it.type == "DISCONNECTED" && it.connection !in connections })
            errors += "DISCONNECT_WITHOUT_CONNECTION"
        if (events.none { it.type == "SERVER_CLOSED" })
            errors += "MISSING_SERVER_CLOSED"
        if (events.any { it.command != null &&
                it.command !in setOf("*IDN?", "ERR?", "POS? X Y Z U V W", "REJECTED") })
            errors += "UNSAFE_COMMAND_IN_TRACE"
        // Validate each session as an ordered lifecycle, not merely by set membership.
        val active = mutableSetOf<Long>()
        val seen = mutableSetOf<Long>()
        var closed = false
        for (event in events) {
            if (closed) errors += "EVENT_AFTER_SERVER_CLOSED"
            when (event.type) {
                "CONNECTED" -> {
                    if (event.connection <= 0 || !seen.add(event.connection))
                        errors += "DUPLICATE_CONNECTION"
                    active.add(event.connection)
                }
                "QUERY" -> if (event.connection !in active) errors += "QUERY_OUTSIDE_SESSION"
                "DISCONNECTED" -> if (!active.remove(event.connection))
                    errors += "DISCONNECT_OUTSIDE_SESSION"
                "SERVER_CLOSED" -> {
                    if (event.connection != 0L || closed) errors += "INVALID_SERVER_CLOSE"
                    if (active.isNotEmpty()) errors += "UNCLOSED_CONNECTION"
                    closed = true
                }
                else -> errors += "UNKNOWN_EVENT_TYPE"
            }
        }
        if (active.isNotEmpty()) errors += "UNCLOSED_CONNECTION"
        if (seen.isEmpty()) errors += "NO_CONNECTIONS"
        return errors.distinct()
    }

    /**
     * Scenario gate: an injected fault may not be reported as PASS unless the
     * journal proves the *expected* one-shot failure on the right read-only query.
     * This is simulation evidence only, not a claim of physical equipment safety.
     */
    fun validateScenario(events: List<VirtualGcsEvent>, expectedFault: VirtualGcsFault): List<String> {
        val errors = validate(events).toMutableList()
        val faultQueries = events.filter { it.type == "QUERY" &&
            (it.fault != null || it.outcome == "FAULT_REPLY" || it.outcome == "FAULT_DISCONNECT" || it.outcome == "FAULT_TIMEOUT") }
        if (expectedFault == VirtualGcsFault.None) {
            if (faultQueries.isNotEmpty()) errors += "UNEXPECTED_FAULT"
        } else {
            val expectedCommand = when (expectedFault) {
                VirtualGcsFault.MalformedPosition, VirtualGcsFault.TruncatedPosition -> "POS? X Y Z U V W"
                VirtualGcsFault.Disconnect, VirtualGcsFault.Timeout -> "*IDN?"
                VirtualGcsFault.None -> error("Unreachable")
            }
            val expectedOutcome = when (expectedFault) {
                VirtualGcsFault.Disconnect -> "FAULT_DISCONNECT"
                VirtualGcsFault.Timeout -> "FAULT_TIMEOUT"
                else -> "FAULT_REPLY"
            }
            if (faultQueries.size != 1) errors += "FAULT_EVENT_COUNT_MISMATCH"
            if (faultQueries.none { it.fault == expectedFault.name &&
                    it.command == expectedCommand && it.outcome == expectedOutcome })
                errors += "EXPECTED_FAULT_NOT_OBSERVED"
        }
        return errors.distinct()
    }

    fun exportBundle(evidence: VirtualGcsDiagnosticEvidence, directory: Path, scenario: String): Boolean {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(scenario)) { "Invalid scenario identifier" }
        Files.createDirectories(directory)
        val events = evidence.snapshot()
        val failures = validate(events)
        val pass = failures.isEmpty()
        Files.writeString(directory.resolve("trace.jsonl"), evidence.jsonLines(), StandardCharsets.UTF_8)
        fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val summary = """{"schemaVersion":$SCHEMA_VERSION,"source":"VIRTUAL_ONLY","scenario":${quoted(scenario)},"result":${quoted(if (pass) "PASS" else "FAIL")},"eventCount":${events.size},"failures":[${failures.joinToString(",") { quoted(it) }}]}"""
        Files.writeString(directory.resolve("summary.json"), summary + "\n", StandardCharsets.UTF_8)
        return pass
    }
}
