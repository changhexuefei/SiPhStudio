package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Strict structural validation of simulator-only CI recovery evidence. */
object VirtualGcsSoakSummary {
    private val json = ObjectMapper()
    private fun parse(source: String): JsonNode = json.readTree(source)
        ?: error("Empty JSON document")
    private fun string(node: JsonNode, key: String) = node.path(key).takeIf { it.isTextual }?.asText()
    private fun integer(node: JsonNode, key: String) = node.path(key).takeIf { it.isIntegralNumber }?.asLong()

    private fun validCycle(folder: Path, cycle: Int): Boolean = try {
        val summary = parse(Files.readString(folder.resolve("summary.json"), StandardCharsets.UTF_8))
        require(summary.isObject)
        val lines = Files.readAllLines(folder.resolve("trace.jsonl"), StandardCharsets.UTF_8)
        require(lines.isNotEmpty() && lines.none { it.isBlank() })
        val events = lines.map { line ->
            val e = parse(line)
            require(e.isObject && integer(e, "schemaVersion") == 1L)
            fun optional(key: String): String? {
                val field = e.path(key)
                require(field.isNull || field.isTextual)
                return if (field.isNull) null else field.asText()
            }
            require(integer(e, "sequence") != null && integer(e, "connection") != null)
            VirtualGcsEvent(
                sequence = integer(e, "sequence")!!,
                connection = integer(e, "connection")!!,
                type = string(e, "type") ?: error("Missing type"),
                command = optional("command"),
                outcome = optional("outcome"),
                fault = optional("fault")
            )
        }
        require(integer(summary, "schemaVersion") == 1L)
        require(string(summary, "source") == "VIRTUAL_ONLY")
        require(string(summary, "scenario") == "recovery-cycle-$cycle")
        require(string(summary, "result") == "PASS")
        require(integer(summary, "eventCount") == events.size.toLong())
        require(summary.path("failures").isArray && summary.path("failures").size() == 0)
        require(VirtualGcsEvidenceContract.validateScenario(events, VirtualGcsFault.Timeout).isEmpty())
        require(events.count { it.type == "CONNECTED" } == 2)
        require(events.count { it.type == "DISCONNECTED" } == 2)
        require(events.count { it.outcome == "FAULT_TIMEOUT" } == 1)
        require(events.count { it.outcome == "REPLIED" } == 2)
        require(events.last().type == "SERVER_CLOSED")
        true
    } catch (_: Exception) {
        false
    }

    fun aggregate(root: Path, cycleCount: Int = 20, runId: String? = null, revision: String? = null): Boolean {
        require((runId == null) == (revision == null))
        require(cycleCount in 1..1000)
        Files.createDirectories(root)
        val failures = mutableListOf<String>()
        var passed = 0
        // Directory inventory is part of the contract: stale cycles and unknown
        // files must not be silently accepted as a clean current CI run.
        val expected = (1..cycleCount).map { "cycle-" + it.toString().padStart(2, '0') }.toSet()
        Files.list(root).use { children ->
            children.forEach { entry ->
                val name = entry.fileName.toString()
                if (name != "aggregate-summary.json" && name !in expected)
                    failures += "$name:UNEXPECTED_ENTRY"
            }
        }
        for (cycle in 1..cycleCount) {
            val name = "cycle-" + cycle.toString().padStart(2, '0')
            val folder = root.resolve(name)
            if (!Files.isDirectory(folder) || !Files.isRegularFile(folder.resolve("summary.json")) ||
                !Files.isRegularFile(folder.resolve("trace.jsonl"))) {
                failures += "$name:MISSING_EVIDENCE"
            } else if (Files.list(folder).use { entries ->
                    entries.any { it.fileName.toString() !in setOf("summary.json", "trace.jsonl", "provenance.json") }
                }) {
                failures += "$name:UNEXPECTED_FILE"
            } else if (runId != null && !Files.isRegularFile(folder.resolve("provenance.json"))) {
                failures += "$name:MISSING_MANIFEST"
            } else if (!validCycle(folder, cycle) ||
                (runId != null && !VirtualGcsEvidenceProvenance.matches(folder, runId, revision!!))) {
                failures += "$name:INVALID_EVIDENCE"
            } else passed++
        }
        val output = json.createObjectNode().apply {
            put("schemaVersion", 1)
            put("source", "VIRTUAL_ONLY")
            put("scenario", "recovery-soak")
            if (runId != null) { put("runId", runId); put("revision", revision!!) }
            put("expectedCycles", cycleCount)
            put("passedCycles", passed)
            put("failedCycles", cycleCount - passed)
            put("result", if (failures.isEmpty()) "PASS" else "FAIL")
            putArray("failures").apply { failures.forEach { add(it) } }
        }
        Files.writeString(root.resolve("aggregate-summary.json"), json.writeValueAsString(output) + "\n", StandardCharsets.UTF_8)
        return failures.isEmpty()
    }
}
