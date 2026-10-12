package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Untrusted diagnostics for manual offline recovery; never proof of lock ownership.
 */
object PhysicalReadOnlyPublicationLockAudit {
    data class Snapshot(
        val runId: String?,
        val pid: Long?,
        val createdUtc: Instant?,
        val warnings: List<String>
    )

    fun read(lock: Path): Snapshot {
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            return Snapshot(null, null, null, listOf("INVALID_LOCK_FILE"))
        }
        val text = try {
            if (Files.size(lock) > 4096) return Snapshot(null, null, null, listOf("LOCK_TOO_LARGE"))
            Files.readString(lock)
        } catch (_: java.io.IOException) {
            return Snapshot(null, null, null, listOf("LOCK_READ_FAILED"))
        }
        val parts = text.lines().filter { it.isNotBlank() }
        val fields = parts.mapNotNull { line ->
            val pos = line.indexOf('=')
            if (pos <= 0) null else line.substring(0, pos) to line.substring(pos + 1)
        }
        if (fields.size != parts.size || fields.map { it.first }.toSet().size != fields.size) {
            return Snapshot(null, null, null, listOf("INVALID_LOCK_FORMAT"))
        }
        val values = fields.toMap()
        if (values.keys != setOf("version", "runId", "pid", "createdUtc") || values["version"] != "1") {
            return Snapshot(null, null, null, listOf("UNKNOWN_LOCK_SCHEMA"))
        }
        return try {
            val id = UUID.fromString(values.getValue("runId")).toString()
            val pid = values.getValue("pid").toLong()
            require(pid > 0)
            val created = Instant.parse(values.getValue("createdUtc"))
            Snapshot(id, pid, created, emptyList())
        } catch (_: Exception) {
            Snapshot(null, null, null, listOf("INVALID_LOCK_METADATA"))
        }
    }
}
