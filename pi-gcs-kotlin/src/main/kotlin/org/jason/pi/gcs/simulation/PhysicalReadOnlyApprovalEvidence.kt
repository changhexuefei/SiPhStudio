package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline, versioned review snapshot. This must never be interpreted as
 * equipment-owner authentication, connection permission or motion authority.
 */
object PhysicalReadOnlyApprovalEvidence {
    private val json = ObjectMapper()
    private val fields = setOf(
        "schemaVersion", "source", "result", "hardwareTested", "motionAuthorization",
        "approvedForSeparateManualReview", "reasons", "controllerModel",
        "firmwareVersion", "gcsManualRevision", "stageModel", "approvedQueries"
    )

    fun export(record: PhysicalReadOnlyApprovalRecord?, destination: Path): PhysicalReadOnlyPreflightVerdict {
        val verdict = PhysicalReadOnlyPreflightGate.evaluate(record)
        val root = json.createObjectNode().apply {
            put("schemaVersion", 1)
            put("source", "PHYSICAL_READONLY_PREPARATION")
            put("result", if (verdict.approvedForSeparateManualReview) "REVIEW_READY" else "NO_GO")
            put("hardwareTested", false)
            put("motionAuthorization", false)
            put("approvedForSeparateManualReview", verdict.approvedForSeparateManualReview)
            putArray("reasons").apply { verdict.reasons.forEach { add(it) } }
            put("controllerModel", record?.controllerModel ?: "")
            put("firmwareVersion", record?.firmwareVersion ?: "")
            put("gcsManualRevision", record?.gcsManualRevision ?: "")
            put("stageModel", record?.stageModel ?: "")
            putArray("approvedQueries").apply {
                record?.explicitlyApprovedQueries?.sorted()?.forEach { add(it) }
            }
        }
        destination.parent?.let { Files.createDirectories(it) }
        Files.writeString(destination, json.writeValueAsString(root) + "\n", StandardCharsets.UTF_8)
        return verdict
    }

    /**
     * Check an exported decision against the actual supplied approval record.
     * A standalone JSON file cannot establish that an approval is authentic.
     */
    fun validateAgainstRecord(path: Path, record: PhysicalReadOnlyApprovalRecord?): List<String> {
        val errors = validate(path).toMutableList()
        if (errors.isNotEmpty()) return errors
        return try {
            val node = json.readTree(Files.readString(path, StandardCharsets.UTF_8))
            val verdict = PhysicalReadOnlyPreflightGate.evaluate(record)
            val expectedResult = if (verdict.approvedForSeparateManualReview) "REVIEW_READY" else "NO_GO"
            if (node.path("result").asText() != expectedResult ||
                node.path("approvedForSeparateManualReview").asBoolean() != verdict.approvedForSeparateManualReview ||
                node.path("reasons").map { it.asText() } != verdict.reasons)
                errors += "RECORD_VERDICT_MISMATCH"
            val metadata = mapOf(
                "controllerModel" to (record?.controllerModel ?: ""),
                "firmwareVersion" to (record?.firmwareVersion ?: ""),
                "gcsManualRevision" to (record?.gcsManualRevision ?: ""),
                "stageModel" to (record?.stageModel ?: "")
            )
            if (metadata.any { (key, value) -> node.path(key).asText() != value })
                errors += "RECORD_METADATA_MISMATCH"
            val actualQueries: List<String> = node.path("approvedQueries").map { it.asText() }
            val expectedQueries: List<String> = record?.explicitlyApprovedQueries?.sorted() ?: emptyList()
            if (actualQueries != expectedQueries)
                errors += "RECORD_QUERY_MISMATCH"
            errors
        } catch (_: Exception) {
            listOf("INVALID_JSON")
        }
    }

    /** Checks structural consistency; no live hardware access or identity verification. */
    fun validate(path: Path): List<String> {
        return try {
        val root = json.readTree(Files.readString(path, StandardCharsets.UTF_8))
        val failures = mutableListOf<String>()
        if (root == null || !root.isObject || root.fieldNames().asSequence().toSet() != fields)
            return listOf("INVALID_SCHEMA")
        fun bool(key: String) = root.path(key).takeIf { it.isBoolean }?.asBoolean()
        fun str(key: String) = root.path(key).takeIf { it.isTextual }?.asText()
        if (!root.path("schemaVersion").isIntegralNumber || root.path("schemaVersion").asInt() != 1)
            failures += "INVALID_VERSION"
        if (str("source") != "PHYSICAL_READONLY_PREPARATION") failures += "INVALID_SOURCE"
        if (bool("hardwareTested") != false || bool("motionAuthorization") != false)
            failures += "UNSAFE_AUTHORIZATION_CLAIM"
        val ready = bool("approvedForSeparateManualReview")
        val reasons = root.path("reasons")
        if (ready == null || !reasons.isArray || reasons.any { !it.isTextual })
            failures += "INVALID_VERDICT"
        else if ((ready && (str("result") != "REVIEW_READY" || reasons.size() != 0)) ||
            (!ready && (str("result") != "NO_GO" || reasons.size() == 0)))
            failures += "VERDICT_MISMATCH"
        val queries = root.path("approvedQueries")
        if (!queries.isArray || queries.any { !it.isTextual }) {
            failures += "INVALID_QUERIES"
        } else {
            val commands = queries.map { it.asText() }
            if (commands.size != commands.distinct().size ||
                commands.any { it !in PhysicalReadOnlyPreflightGate.candidateQueries } ||
                (ready == true && commands.toSet() != PhysicalReadOnlyPreflightGate.candidateQueries))
                failures += "UNAPPROVED_QUERY"
        }
        for (key in listOf("controllerModel", "firmwareVersion", "gcsManualRevision", "stageModel")) {
            if (str(key) == null || (ready == true && str(key).isNullOrBlank()))
                failures += "INVALID_$key"
        }
        failures
    } catch (_: Exception) {
        listOf("INVALID_JSON")
        }
    }
}
