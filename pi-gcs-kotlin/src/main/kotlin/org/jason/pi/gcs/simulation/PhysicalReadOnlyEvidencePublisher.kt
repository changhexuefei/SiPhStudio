package org.jason.pi.gcs.simulation

import java.nio.file.Files
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
    ) {
        require(record != null) { "Missing approval record" }
        val target = destination.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "Missing parent directory" }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Missing parent directory" }
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Destination already exists" }
        val staging = Files.createTempDirectory(parent, ".pi-readonly-staging-")
        try {
            PhysicalReadOnlyEvidenceBundle.write(staging, record)
            check(PhysicalReadOnlyEvidenceBundle.verify(staging, record).isEmpty()) {
                "Staged evidence bundle verification failed"
            }
            // Do not fall back to non-atomic move: incomplete publication must not be exposed.
            // No REPLACE_EXISTING: a pre-existing final bundle must never be overwritten.
            beforeMove()
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(staging).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(staging)
            }
        }
    }
}
