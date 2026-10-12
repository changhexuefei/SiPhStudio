package org.jason.pi.gcs.simulation

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Offline file-sync capability probe. Never reports crash-safe publication:
 * a file-channel force does not prove parent-directory metadata durability.
 */
object PhysicalReadOnlyEvidenceDurability {
    enum class FileSync { VERIFIED, FAILED, NOT_ATTEMPTED }
    enum class DirectorySync { NOT_VERIFIED }
    enum class PowerLossDurability { NOT_VERIFIED }

    data class Report(
        val fileSync: FileSync,
        val directorySync: DirectorySync = DirectorySync.NOT_VERIFIED,
        val powerLossDurability: PowerLossDurability = PowerLossDurability.NOT_VERIFIED,
        val failureType: String? = null
    )

    /**
     * Probes an existing regular file, never creates or changes its bytes.
     * Does not change publisher behavior or grant any hardware authorization.
     */
    fun probeFileSync(path: Path): Report {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Report(FileSync.NOT_ATTEMPTED, failureType = "INVALID_FILE")
        }
        return try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            Report(FileSync.VERIFIED)
        } catch (failure: Exception) {
            Report(FileSync.FAILED, failureType = failure.javaClass.simpleName)
        }
    }
}
