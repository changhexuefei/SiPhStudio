package org.jason.pi.gcs.physical

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Machine-readable evidence contract for an operator-authorized physical
 * read-only PI GCS preflight.
 *
 * This contract never opens a transport and never executes commands.
 * It only validates and exports evidence supplied by a separately authorized
 * one-shot physical-readonly probe.
 */
object PhysicalReadOnlyEvidenceContract {
    const val SCHEMA_VERSION = 1

    private val allowedQueries = setOf(
        "*IDN?",
        "ERR?",
        "POS? X Y Z U V W",
    )

    data class Observation(
        val command: String,
        val outcome: String,
        val response: String? = null,
    )

    data class Report(
        val result: String,
        val controllerModel: String,
        val firmware: String,
        val manualRevision: String,
        val authorizationId: String,
        val operatorTestCaseId: String,
        val commitSha: String,
        val transportType: String,
        val motionSafety: String = "NOT_TESTED",
        val motionAuthorization: Boolean = false,
        val observations: List<Observation>,
    )

    fun validate(report: Report): List<String> {
        val errors = mutableListOf<String>()

        if (report.result !in setOf("PASS", "FAIL", "NOT_RUN")) errors += "INVALID_RESULT"
        if (report.controllerModel.isBlank()) errors += "MISSING_CONTROLLER_MODEL"
        if (report.firmware.isBlank()) errors += "MISSING_FIRMWARE"
        if (report.manualRevision.isBlank()) errors += "MISSING_MANUAL_REVISION"
        if (report.authorizationId.isBlank()) errors += "MISSING_AUTHORIZATION"
        if (report.operatorTestCaseId.isBlank()) errors += "MISSING_TEST_CASE_ID"
        if (report.commitSha.isBlank()) errors += "MISSING_COMMIT_SHA"
        if (report.transportType.isBlank()) errors += "MISSING_TRANSPORT_TYPE"
        if (report.motionSafety != "NOT_TESTED") errors += "MOTION_SAFETY_MUST_BE_NOT_TESTED"
        if (report.motionAuthorization) errors += "MOTION_AUTHORIZATION_MUST_BE_FALSE"

        if (report.result == "PASS" && report.observations.isEmpty()) {
            errors += "PASS_WITHOUT_OBSERVATIONS"
        }

        report.observations.forEachIndexed { index, observation ->
            if (observation.command !in allowedQueries) {
                errors += "UNSAFE_COMMAND_AT_$index"
            }
            if (observation.outcome !in setOf("REPLIED", "ERROR", "TIMEOUT", "DISCONNECTED", "SKIPPED")) {
                errors += "INVALID_OUTCOME_AT_$index"
            }
        }

        if (report.result == "PASS") {
            val commands = report.observations.map { it.command }.toSet()
            if ("*IDN?" !in commands) errors += "PASS_MISSING_IDENTITY_QUERY"
            if ("ERR?" !in commands) errors += "PASS_MISSING_ERROR_QUERY"
            if ("POS? X Y Z U V W" !in commands) errors += "PASS_MISSING_POSITION_QUERY"
            if (report.observations.any { it.outcome != "REPLIED" }) {
                errors += "PASS_WITH_NON_REPLIED_OBSERVATION"
            }
        }

        return errors.distinct()
    }

    fun export(report: Report, directory: Path): Boolean {
        Files.createDirectories(directory)
        val failures = validate(report)
        val accepted = failures.isEmpty()

        Files.writeString(
            directory.resolve("summary.json"),
            summaryJson(report, failures) + "\n",
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            directory.resolve("trace.jsonl"),
            report.observations.joinToString("\n", postfix = if (report.observations.isEmpty()) "" else "\n") {
                observationJson(it)
            },
            StandardCharsets.UTF_8,
        )
        return accepted
    }

    private fun summaryJson(report: Report, failures: List<String>): String =
        """{"schemaVersion":$SCHEMA_VERSION,"source":"PHYSICAL_READONLY","result":${q(report.result)},"controllerModel":${q(report.controllerModel)},"firmware":${q(report.firmware)},"manualRevision":${q(report.manualRevision)},"authorizationId":${q(report.authorizationId)},"operatorTestCaseId":${q(report.operatorTestCaseId)},"commitSha":${q(report.commitSha)},"transportType":${q(report.transportType)},"motionSafety":${q(report.motionSafety)},"motionAuthorization":${report.motionAuthorization},"observationCount":${report.observations.size},"failures":[${failures.joinToString(",") { q(it) }}]}"""

    private fun observationJson(observation: Observation): String =
        """{"command":${q(observation.command)},"outcome":${q(observation.outcome)},"response":${observation.response?.let(::q) ?: "null"}}"""

    private fun q(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
