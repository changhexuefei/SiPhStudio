package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Simulator evidence provenance (not a signature or tamper-proof attestation).
 * Binds the exact bytes of both files to one CI run and source revision.
 */
object VirtualGcsEvidenceProvenance {
    private val json = ObjectMapper()
    private val hex = "0123456789abcdef".toCharArray()

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }

    fun write(folder: Path, runId: String, revision: String) {
        require(runId.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        require(revision.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        val manifest = json.createObjectNode().apply {
            put("schemaVersion", 1)
            put("source", "VIRTUAL_ONLY")
            put("runId", runId)
            put("revision", revision)
            put("summarySha256", sha256(folder.resolve("summary.json")))
            put("traceSha256", sha256(folder.resolve("trace.jsonl")))
        }
        Files.writeString(folder.resolve("provenance.json"), json.writeValueAsString(manifest) + "\n")
    }

    fun matches(folder: Path, runId: String, revision: String): Boolean = try {
        val path = folder.resolve("provenance.json")
        val manifest = json.readTree(Files.readString(path))
        manifest.isObject &&
            manifest.path("schemaVersion").isIntegralNumber && manifest.path("schemaVersion").asInt() == 1 &&
            manifest.path("source").asText() == "VIRTUAL_ONLY" &&
            manifest.path("runId").asText() == runId &&
            manifest.path("revision").asText() == revision &&
            manifest.path("summarySha256").asText() == sha256(folder.resolve("summary.json")) &&
            manifest.path("traceSha256").asText() == sha256(folder.resolve("trace.jsonl"))
    } catch (_: Exception) {
        false
    }
}
