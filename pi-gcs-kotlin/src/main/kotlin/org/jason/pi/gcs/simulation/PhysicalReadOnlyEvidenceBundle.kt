package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption

/**
 * Versioned, closed-inventory offline review bundle.
 * Its hashes are consistency checks, not signatures or permission for hardware access.
 */
object PhysicalReadOnlyEvidenceBundle {
    private val json = ObjectMapper()
    private const val SOURCE = "PHYSICAL_READONLY_PREPARATION"
    private const val EVIDENCE = "approval.json"
    private const val INTEGRITY = "integrity.json"
    private const val BUNDLE = "bundle.json"
    private val inventory = setOf(EVIDENCE, INTEGRITY, BUNDLE)
    private val fields = setOf(
        "schemaVersion", "source", "algorithm", "files",
        "hardwareTested", "motionAuthorization"
    )

    /** Creation is fail-closed against an absent or inconsistent source record. */
    fun write(directory: Path, record: PhysicalReadOnlyApprovalRecord?) {
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Missing bundle directory or symbolic link" }
        require(Files.list(directory).use { it.findAny().isEmpty }) { "Bundle directory must be empty" }
        require(record != null) { "Missing approval record" }
        val evidence = directory.resolve(EVIDENCE)
        val integrity = directory.resolve(INTEGRITY)
        PhysicalReadOnlyApprovalEvidence.export(record, evidence)
        require(PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(evidence, record).isEmpty()) {
            "Approval evidence does not match source record"
        }
        PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, integrity)
        val root = json.createObjectNode().apply {
            put("schemaVersion", 1)
            put("source", SOURCE)
            put("algorithm", "SHA-256")
            put("hardwareTested", false)
            put("motionAuthorization", false)
            putObject("files").apply {
                put(EVIDENCE, PhysicalReadOnlyEvidenceIntegrity.sha256(evidence))
                put(INTEGRITY, PhysicalReadOnlyEvidenceIntegrity.sha256(integrity))
            }
        }
        Files.writeString(directory.resolve(BUNDLE), json.writeValueAsString(root) + "\n", StandardCharsets.UTF_8)
    }

    /** No commands or network calls. A successful result does not authorize physical testing. */
    fun verify(directory: Path, record: PhysicalReadOnlyApprovalRecord?): List<String> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return listOf("MISSING_BUNDLE_DIRECTORY")
        val names = try {
            Files.list(directory).use { stream ->
                stream.map { it.fileName.toString() }.toList().toSet()
            }
        } catch (_: Exception) {
            return listOf("INVALID_BUNDLE_DIRECTORY")
        }
        val failures = mutableListOf<String>()
        if (names != inventory) failures += "BUNDLE_INVENTORY_MISMATCH"
        if (!inventory.all { Files.isRegularFile(directory.resolve(it), LinkOption.NOFOLLOW_LINKS) }) {
            failures += "MISSING_BUNDLE_FILE"
            return failures.distinct()
        }
        try {
            val root = json.readTree(Files.readString(directory.resolve(BUNDLE), StandardCharsets.UTF_8))
            val digests = root?.path("files")
            val valid = root != null && root.isObject &&
                root.fieldNames().asSequence().toSet() == fields &&
                root.path("schemaVersion").isIntegralNumber &&
                root.path("schemaVersion").asInt() == 1 &&
                root.path("source").isTextual && root.path("source").asText() == SOURCE &&
                root.path("algorithm").isTextual && root.path("algorithm").asText() == "SHA-256" &&
                root.path("hardwareTested").isBoolean && !root.path("hardwareTested").asBoolean() &&
                root.path("motionAuthorization").isBoolean && !root.path("motionAuthorization").asBoolean() &&
                digests != null && digests.isObject &&
                digests.fieldNames().asSequence().toSet() == setOf(EVIDENCE, INTEGRITY) &&
                listOf(EVIDENCE, INTEGRITY).all {
                    digests.path(it).isTextual &&
                        Regex("[0-9a-f]{64}").matches(digests.path(it).asText())
                }
            if (!valid) {
                failures += "INVALID_BUNDLE_MANIFEST"
            } else {
                for (filename in listOf(EVIDENCE, INTEGRITY)) {
                    if (PhysicalReadOnlyEvidenceIntegrity.sha256(directory.resolve(filename)) !=
                        digests!!.path(filename).asText()
                    ) failures += "BUNDLE_FILE_SHA256_MISMATCH"
                }
            }
        } catch (_: Exception) {
            failures += "INVALID_BUNDLE_MANIFEST"
        }
        if (record == null) {
            failures += "MISSING_APPROVAL_RECORD"
        } else {
            failures += PhysicalReadOnlyEvidenceIntegrity.verifyAgainstRecord(
                directory.resolve(EVIDENCE), directory.resolve(INTEGRITY), record
            )
        }
        return failures.distinct()
    }
}
