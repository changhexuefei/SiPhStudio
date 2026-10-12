package org.jason.pi.gcs.simulation

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Publishes an offline bundle to a *new* directory only after staging and verification.
 * This does not claim crash-durable fsync semantics or establish equipment-owner approval.
 */
object PhysicalReadOnlyEvidencePublisher {
    fun publish(destination: Path, record: PhysicalReadOnlyApprovalRecord?) =
        publishWithPreMoveCheck(destination, record) {}

    /** Internal test seam, executed after staging validation and before promotion. */
    internal fun publishWithPreMoveCheck(
        destination: Path,
        record: PhysicalReadOnlyApprovalRecord?,
        beforeMove: () -> Unit
    ) = publishWithSyncCheck(destination, record, ::syncBundleFiles, beforeMove)

    /**
     * Test seam for injected synchronization failures.
     * All three staged files must be forced before the atomic rename.
     */
    internal fun publishWithSyncCheck(
        destination: Path,
        record: PhysicalReadOnlyApprovalRecord?,
        sync: (Path) -> Unit,
        beforeMove: () -> Unit = {}
    ) = publishWithMoveCheck(destination, record, sync, beforeMove) { staging, target ->
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Injection seam for platform move failures. */
    internal fun publishWithMoveCheck(
        destination: Path,
        record: PhysicalReadOnlyApprovalRecord?,
        sync: (Path) -> Unit = ::syncBundleFiles,
        beforeMove: () -> Unit = {},
        move: (Path, Path) -> Unit
    ) {
        require(record != null) { "Missing approval record" }
        val target = destination.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "Missing parent directory" }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Missing parent directory" }
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Destination already exists" }
        val staging = Files.createTempDirectory(parent, ".pi-readonly-staging-")
        var primaryFailure: Throwable? = null
        try {
            PhysicalReadOnlyEvidenceBundle.write(staging, record)
            check(PhysicalReadOnlyEvidenceBundle.verify(staging, record).isEmpty()) {
                "Staged evidence bundle verification failed"
            }
            sync(staging)
            // Do not fall back to non-atomic move: incomplete publication must not be exposed.
            // No REPLACE_EXISTING: a pre-existing final bundle must never be overwritten.
            beforeMove()
            // Reject an occupied target after pre-move hooks. This narrows but
            // cannot eliminate races without OS-level exclusive rename semantics.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw java.nio.file.FileAlreadyExistsException(target.toString())
            }
            move(staging, target)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                cleanupStaging(staging)
            } catch (cleanupFailure: IOException) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    internal fun syncBundleFiles(
        staging: Path,
        forceFile: (Path) -> Unit = { file ->
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.force(true)
            }
        }
    ) {
        for (name in listOf("approval.json", "integrity.json", "bundle.json")) {
            val file = staging.resolve(name)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Cannot synchronize missing or linked evidence file: $name")
            }
            forceFile(file)
        }
    }

    /**
     * Best-effort cleanup of known staging contents. Do not follow links or
     * traverse subdirectories. Preserve the first I/O error with suppressed detail.
     */
    internal fun cleanupStaging(
        staging: Path,
        delete: (Path) -> Unit = { Files.deleteIfExists(it) }
    ) {
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return
        val errors = mutableListOf<IOException>()
        try {
            Files.list(staging).use { files ->
                files.forEach { entry ->
                    try {
                        delete(entry)
                    } catch (e: IOException) {
                        errors += e
                    }
                }
            }
        } catch (e: IOException) {
            errors += e
        }
        try {
            delete(staging)
        } catch (e: IOException) {
            errors += e
        }
        if (errors.isNotEmpty()) {
            val first = errors.first()
            errors.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}
