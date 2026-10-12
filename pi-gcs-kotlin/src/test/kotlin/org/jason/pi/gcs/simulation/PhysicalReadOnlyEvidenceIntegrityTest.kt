package org.jason.pi.gcs.simulation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceIntegrityTest {
    @Test
    fun checksumIsRepeatableAndTamperingFailsClosed() {
        val dir = Files.createTempDirectory("pi-readonly-checksum")
        try {
            val evidence = dir.resolve("approval.json")
            val manifest = dir.resolve("integrity.json")
            PhysicalReadOnlyApprovalEvidence.export(null, evidence)
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            val originalManifest = Files.readAllBytes(manifest)
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest).isEmpty())
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            assertTrue(originalManifest.contentEquals(Files.readAllBytes(manifest)))

            // A byte-only change must be rejected even when JSON remains valid.
            Files.writeString(evidence, Files.readString(evidence) + " ")
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest)
                .contains("EVIDENCE_SHA256_MISMATCH"))

            // Checksum is not a signature: altered evidence and regenerated manifest
            // can still match. Review against a trusted source record is separate.
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest).isEmpty())

            Files.writeString(manifest, "{not-json")
            assertTrue(PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest)
                .contains("INVALID_INTEGRITY_MANIFEST"))
            Files.delete(manifest)
            assertEquals(listOf("MISSING_EVIDENCE_OR_MANIFEST"),
                PhysicalReadOnlyEvidenceIntegrity.verify(evidence, manifest))
        } finally {
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun invalidApprovalEvidenceCannotBeFingerprintStamped() {
        val dir = Files.createTempDirectory("pi-invalid-approval")
        try {
            val evidence = dir.resolve("approval.json")
            Files.writeString(evidence, "{invalid")
            var rejected = false
            try {
                PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, dir.resolve("integrity.json"))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
    @Test
    fun combinedVerificationBindsEvidenceToSuppliedApprovalRecord() {
        val dir = Files.createTempDirectory("pi-readonly-record-binding")
        try {
            val evidence = dir.resolve("approval.json")
            val manifest = dir.resolve("integrity.json")
            val record = completeRecord()
            PhysicalReadOnlyApprovalEvidence.export(record, evidence)
            PhysicalReadOnlyEvidenceIntegrity.writeManifest(evidence, manifest)

            assertTrue(
                PhysicalReadOnlyEvidenceIntegrity.verifyAgainstRecord(evidence, manifest, record).isEmpty()
            )

            val changedFirmware = record.copy(firmwareVersion = "DIFFERENT")
            assertTrue(
                PhysicalReadOnlyEvidenceIntegrity.verifyAgainstRecord(evidence, manifest, changedFirmware)
                    .contains("RECORD_METADATA_MISMATCH")
            )

            Files.writeString(evidence, Files.readString(evidence) + " ")
            assertTrue(
                PhysicalReadOnlyEvidenceIntegrity.verifyAgainstRecord(evidence, manifest, record)
                    .contains("EVIDENCE_SHA256_MISMATCH")
            )
        } finally {
            Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }

    private fun completeRecord() = PhysicalReadOnlyApprovalRecord(
        equipmentOwner = "owner-ref",
        authorizationReference = "auth-ref",
        controllerModel = "C-887",
        controllerSerialReference = "serial-ref",
        firmwareVersion = "1.0",
        gcsManualRevision = "manual-rev",
        stageModel = "H-811",
        axisMappingConfirmed = true,
        endpointVerified = true,
        safetyProcedureReference = "safety-ref",
        interlocksVerified = true,
        emergencyStopVerified = true,
        operatorReference = "operator-ref",
        witnessReference = "witness-ref",
        approvalDate = "2026-10-12",
        explicitlyApprovedQueries = PhysicalReadOnlyPreflightGate.candidateQueries
    )

}
