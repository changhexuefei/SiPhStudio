package org.jason.pi.gcs.simulation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Offline CI artifact validation. A PASS never certifies physical PI hardware. */
object VirtualGcsSoakSummary {
    private fun escape(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun aggregate(root: Path, cycleCount: Int = 20): Boolean {
        require(cycleCount in 1..1000)
        Files.createDirectories(root)
        val failures = mutableListOf<String>()
        var passed = 0
        for (n in 1..cycleCount) {
            val name = "cycle-" + n.toString().padStart(2, '0')
            val folder = root.resolve(name)
            val summary = folder.resolve("summary.json")
            val trace = folder.resolve("trace.jsonl")
            if (!Files.isRegularFile(summary) || !Files.isRegularFile(trace)) {
                failures.add("$name:MISSING_EVIDENCE")
                continue
            }
            val content = Files.readString(summary, StandardCharsets.UTF_8)
            val rows = Files.readAllLines(trace, StandardCharsets.UTF_8).filter { it.isNotBlank() }
            if (!content.contains("\"schemaVersion\":1") ||
                !content.contains("\"source\":\"VIRTUAL_ONLY\"") ||
                !content.contains("\"scenario\":\"recovery-cycle-$n\"") ||
                !content.contains("\"result\":\"PASS\"") ||
                rows.isEmpty() || rows.any { !it.contains("\"schemaVersion\":1") } ||
                !content.contains("\"eventCount\":${rows.size}")
            ) {
                failures.add("$name:INVALID_EVIDENCE")
            } else passed++
        }
        val output = """{"schemaVersion":1,"source":"VIRTUAL_ONLY","scenario":"recovery-soak","expectedCycles":$cycleCount,"passedCycles":$passed,"failedCycles":${cycleCount - passed},"result":${escape(if (failures.isEmpty()) "PASS" else "FAIL")},"failures":[${failures.joinToString(",") { escape(it) }}]}""" + "\n"
        Files.writeString(root.resolve("aggregate-summary.json"), output, StandardCharsets.UTF_8)
        return failures.isEmpty()
    }
}
