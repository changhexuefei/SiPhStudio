package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceConsistencyTest {
    private fun reviewRecord() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "lab-owner",
        authorizationReference = "approval-reference",
        controllerModel = "model-reference",
        controllerSerialReference = "serial-reference",
        firmwareVersion = "firmware-reference",
        gcsManualRevision = "manual-reference",
        stageModel = "stage-reference",
        axisMappingConfirmed = true,
        endpointVerified = true,
        safetyProcedureReference = "procedure-reference",
        interlocksVerified = true,
        emergencyStopVerified = true,
        operatorReference = "operator-reference",
        witnessReference = "witness-reference",
        approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

    @Test
    fun exportedEvidenceMatchesOriginalRecordButNotChangedRecord() {
        val path = Files.createTempFile("pi-approval-consistency", ".json")
        try {
            val original = reviewRecord()
            PhysicalReadOnlyApprovalEvidence.export(original, path)
            assertTrue(PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, original).isEmpty())
            assertTrue("RECORD_METADATA_MISMATCH" in
                PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, original.copy(firmwareVersion = "changed")))
            assertTrue("RECORD_VERDICT_MISMATCH" in
                PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, original.copy(interlocksVerified = false)))
            assertTrue("RECORD_QUERY_MISMATCH" in
                PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, original.copy(
                    explicitlyApprovedQueries = setOf("*IDN?", "ERR?")
                )))
            assertTrue("RECORD_VERDICT_MISMATCH" in
                PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, null))
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun structurallyValidButTamperedMetadataCannotMatchRecord() {
        val path = Files.createTempFile("pi-approval-tamper", ".json")
        try {
            val record = reviewRecord()
            PhysicalReadOnlyApprovalEvidence.export(record, path)
            val mapper = ObjectMapper()
            val node = mapper.readTree(path.toFile()) as ObjectNode
            node.put("stageModel", "replaced-stage")
            Files.writeString(path, mapper.writeValueAsString(node))
            assertTrue(PhysicalReadOnlyApprovalEvidence.validate(path).isEmpty())
            assertTrue("RECORD_METADATA_MISMATCH" in
                PhysicalReadOnlyApprovalEvidence.validateAgainstRecord(path, record))
        } finally { Files.deleteIfExists(path) }
    }
}
