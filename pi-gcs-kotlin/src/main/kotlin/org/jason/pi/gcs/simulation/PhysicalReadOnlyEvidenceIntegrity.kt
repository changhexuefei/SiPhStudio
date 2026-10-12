package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Offline, byte-exact checksum for approval evidence. A checksum is NOT a signature
 * or independent proof of approval: an attacker can replace both file and manifest.
 */
object PhysicalReadOnlyEvidenceIntegrity {
    private val json = ObjectMapper()
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun writeManifest(evidence: Path, manifest: Path) {
        require(Files.isRegularFile(evidence)) { "Missing evidence" }
        require(PhysicalReadOnlyApprovalEvidence.validate(evidence).isEmpty()) {
            "Cannot fingerprint invalid approval evidence"
        }
        val root = json.createObjectNode().apply {
            put("schemaVersion", 1)
            put("source", "PHYSICAL_READONLY_PREPARATION")
            put("algorithm", "SHA-256")
            put("evidenceSha256", sha256(evidence))
            put("hardwareTested", false)
            put("motionAuthorization", false)
        }
        manifest.parent?.let { Files.createDirectories(it) }
        Files.writeString(manifest, json.writeValueAsString(root) + "\n", StandardCharsets.UTF_8)
    }

    /**
     * Verify byte integrity first, then cross-check the evidence against the supplied
     * offline approval record. This is still not authentication and grants no hardware access.
     */
    fun verifyAgainstRecord(
        evidence: Path,
        manifest: Path,
        record: PhysicalReadOnlyApprovalRecord?
    ): List<String> {
        val errors = verify(evidence, manifest).toMutableList()
        if (errors.isNotEmpty()) return errors
        errors += PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(evidence, record)
        return errors.distinct()
    }

    /** No hardware operation. Returns fail-closed errors for malformed or changed files. */
    fun verify(evidence: Path, manifest: Path): List<String> {
        if (!Files.isRegularFile(evidence) || !Files.isRegularFile(manifest))
            return listOf("MISSING_EVIDENCE_OR_MANIFEST")
        val errors = PhysicalReadOnlyApprovalEvidence.validate(evidence).toMutableList()
        try {
            val node = json.readTree(Files.readString(manifest, StandardCharsets.UTF_8))
            if (node == null || !node.isObject ||
                node.fieldNames().asSequence().toSet() != setOf(
                    "schemaVersion", "source", "algorithm", "evidenceSha256",
                    "hardwareTested", "motionAuthorization"
                ) ||
                !node.path("schemaVersion").isIntegralNumber ||
                node.path("schemaVersion").asInt() != 1 ||
                !node.path("source").isTextual ||
                node.path("source").asText() != "PHYSICAL_READONLY_PREPARATION" ||
                !node.path("algorithm").isTextual || node.path("algorithm").asText() != "SHA-256" ||
                !node.path("hardwareTested").isBoolean || node.path("hardwareTested").asBoolean() ||
                !node.path("motionAuthorization").isBoolean || node.path("motionAuthorization").asBoolean() ||
                !node.path("evidenceSha256").isTextual ||
                !digestPattern.matches(node.path("evidenceSha256").asText())
            ) {
                errors += "INVALID_INTEGRITY_MANIFEST"
            } else if (sha256(evidence) != node.path("evidenceSha256").asText()) {
                errors += "EVIDENCE_SHA256_MISMATCH"
            }
        } catch (_: Exception) {
            errors += "INVALID_INTEGRITY_MANIFEST"
        }
        return errors
    }
}
