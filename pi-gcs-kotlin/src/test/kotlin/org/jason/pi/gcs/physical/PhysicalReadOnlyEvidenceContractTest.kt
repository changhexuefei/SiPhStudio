package org.jason.pi.gcs.physical

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceContractTest {
    @Test
    fun acceptsCompleteOperatorAuthorizedReadOnlyEvidence() {
        val report = PhysicalReadOnlyEvidenceContract.Report(
            result = "PASS",
            controllerModel = "PI-CONTROLLER-EXAMPLE",
            firmware = "example-firmware",
            manualRevision = "operator-reviewed-revision",
            authorizationId = "LAB-READONLY-001",
            operatorTestCaseId = "PI-READONLY-001",
            commitSha = "0123456789abcdef",
            transportType = "TCP",
            observations = listOf(
                PhysicalReadOnlyEvidenceContract.Observation("*IDN?", "REPLIED", "PI,CONTROLLER,..."),
                PhysicalReadOnlyEvidenceContract.Observation("ERR?", "REPLIED", "0"),
                PhysicalReadOnlyEvidenceContract.Observation(
                    "POS? X Y Z U V W",
                    "REPLIED",
                    "X=0 Y=0 Z=0 U=0 V=0 W=0",
                ),
            ),
        )

        val directory = Path.of("build", "ci", "physical-readonly-evidence-contract", "valid")
        assertTrue(PhysicalReadOnlyEvidenceContract.export(report, directory))

        val summary = Files.readString(directory.resolve("summary.json"))
        assertTrue(summary.contains("\"source\":\"PHYSICAL_READONLY\""))
        assertTrue(summary.contains("\"motionSafety\":\"NOT_TESTED\""))
        assertTrue(summary.contains("\"motionAuthorization\":false"))
    }

    @Test
    fun rejectsMotionCommandsAndMotionAuthorization() {
        val report = PhysicalReadOnlyEvidenceContract.Report(
            result = "PASS",
            controllerModel = "PI-CONTROLLER-EXAMPLE",
            firmware = "example-firmware",
            manualRevision = "operator-reviewed-revision",
            authorizationId = "LAB-READONLY-001",
            operatorTestCaseId = "PI-READONLY-002",
            commitSha = "0123456789abcdef",
            transportType = "TCP",
            motionAuthorization = true,
            observations = listOf(
                PhysicalReadOnlyEvidenceContract.Observation("MOV X 1.0", "REPLIED"),
            ),
        )

        val failures = PhysicalReadOnlyEvidenceContract.validate(report)
        assertTrue("MOTION_AUTHORIZATION_MUST_BE_FALSE" in failures)
        assertTrue(failures.any { it.startsWith("UNSAFE_COMMAND_AT_") })
        assertFalse(failures.isEmpty())
    }

    @Test
    fun passRequiresAllThreeApprovedQueriesAndOnlyRepliedOutcomes() {
        val report = PhysicalReadOnlyEvidenceContract.Report(
            result = "PASS",
            controllerModel = "PI-CONTROLLER-EXAMPLE",
            firmware = "example-firmware",
            manualRevision = "operator-reviewed-revision",
            authorizationId = "LAB-READONLY-001",
            operatorTestCaseId = "PI-READONLY-003",
            commitSha = "0123456789abcdef",
            transportType = "TCP",
            observations = listOf(
                PhysicalReadOnlyEvidenceContract.Observation("*IDN?", "REPLIED"),
                PhysicalReadOnlyEvidenceContract.Observation("ERR?", "TIMEOUT"),
            ),
        )

        val failures = PhysicalReadOnlyEvidenceContract.validate(report)
        assertTrue("PASS_MISSING_POSITION_QUERY" in failures)
        assertTrue("PASS_WITH_NON_REPLIED_OBSERVATION" in failures)
    }
}
