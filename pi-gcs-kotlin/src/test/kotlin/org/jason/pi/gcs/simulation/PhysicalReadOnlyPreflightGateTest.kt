package org.jason.pi.gcs.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyPreflightGateTest {
    private fun complete() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner-reference",
        authorizationReference = "approval-reference",
        controllerModel = "PI-controller-model-to-verify",
        controllerSerialReference = "serial-reference",
        firmwareVersion = "documented-firmware",
        gcsManualRevision = "confirmed-manual-revision",
        stageModel = "six-axis-stage-reference",
        axisMappingConfirmed = true,
        endpointVerified = true,
        safetyProcedureReference = "approved-lab-procedure",
        interlocksVerified = true,
        emergencyStopVerified = true,
        operatorReference = "operator-reference",
        witnessReference = "witness-reference",
        approvalDate = "2026-10-11",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    @Test
    fun missingApprovalCannotPass() {
        val result = PhysicalReadOnlyPreflightGate.evaluate(null)
        assertFalse(result.approvedForSeparateManualReview)
        assertEquals(listOf("MISSING_APPROVAL_RECORD"), result.reasons)
        assertFalse(result.hardwareTested)
        assertFalse(result.motionAuthorization)
    }

    @Test
    fun completeRecordIsReviewOnlyAndNeverAuthorizesHardware() {
        val result = PhysicalReadOnlyPreflightGate.evaluate(complete())
        assertTrue(result.approvedForSeparateManualReview)
        assertTrue(result.reasons.isEmpty())
        assertEquals("PHYSICAL_READONLY_PREPARATION", result.source)
        assertFalse(result.hardwareTested)
        assertFalse(result.motionAuthorization)
    }

    @Test
    fun incompleteSafetyOrEndpointFailsClosed() {
        val result = PhysicalReadOnlyPreflightGate.evaluate(
            complete().copy(
                interlocksVerified = false,
                emergencyStopVerified = false,
                endpointVerified = false,
                axisMappingConfirmed = false,
                authorizationReference = "NOT PROVIDED"
            )
        )
        assertFalse(result.approvedForSeparateManualReview)
        assertTrue(result.reasons.contains("MISSING_AUTHORIZATION"))
        assertTrue(result.reasons.contains("INTERLOCKS_NOT_VERIFIED"))
        assertTrue(result.reasons.contains("EMERGENCY_STOP_NOT_VERIFIED"))
        assertTrue(result.reasons.contains("ENDPOINT_NOT_VERIFIED"))
        assertTrue(result.reasons.contains("AXIS_MAPPING_NOT_VERIFIED"))
    }

    @Test
    fun unknownWriteOrUnapprovedQueryFailsClosed() {
        for (commands in listOf(
            setOf("MOV X 1"),
            setOf("*IDN?", "ERR?"),
            PhysicalReadOnlyPreflightGate.candidateQueries + "SVO X 1"
        )) {
            val result = PhysicalReadOnlyPreflightGate.evaluate(
                complete().copy(explicitlyApprovedQueries = commands)
            )
            assertFalse(result.approvedForSeparateManualReview)
            assertTrue(result.reasons.contains("QUERY_ALLOWLIST_NOT_EXACT"))
            assertFalse(result.motionAuthorization)
        }
    }
}
