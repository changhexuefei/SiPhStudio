package org.jason.pi.gcs.simulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PhysicalReadOnlyEvidenceBundleTest {
    private fun record() = PhysicalReadOnlyApprovalRecord(
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

    private fun withBundle(block: (Path, PhysicalReadOnlyApprovalRecord) -> Unit) {
        val dir = Files.createTempDirectory("pi-offline-bundle")
        try {
            val source = record()
            PhysicalReadOnlyEvidenceBundle.write(dir, source)
            block(dir, source)
        } finally {
            Files.list(dir).use { items -> items.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun validBundleAndSourceRecordPass() = withBundle { dir, source ->
        assertTrue(PhysicalReadOnlyEvidenceBundle.verify(dir, source).isEmpty())
    }

    @Test
    fun unexpectedFileFailsClosed() = withBundle { dir, source ->
        Files.writeString(dir.resolve("stale.txt"), "leftover")
        assertTrue("BUNDLE_INVENTORY_MISMATCH" in PhysicalReadOnlyEvidenceBundle.verify(dir, source))
    }

    @Test
    fun missingManifestFailsClosed() = withBundle { dir, source ->
        Files.delete(dir.resolve("integrity.json"))
        val errors = PhysicalReadOnlyEvidenceBundle.verify(dir, source)
        assertTrue("BUNDLE_INVENTORY_MISMATCH" in errors)
        assertTrue("MISSING_BUNDLE_FILE" in errors)
    }

    @Test
    fun modifiedEvidenceFailsClosed() = withBundle { dir, source ->
        val file = dir.resolve("approval.json")
        Files.writeString(file, Files.readString(file) + " ")
        assertTrue("BUNDLE_FILE_SHA256_MISMATCH" in PhysicalReadOnlyEvidenceBundle.verify(dir, source))
    }

    @Test
    fun modifiedIntegrityManifestFailsClosed() = withBundle { dir, source ->
        Files.writeString(dir.resolve("integrity.json"), "{}")
        assertTrue("BUNDLE_FILE_SHA256_MISMATCH" in PhysicalReadOnlyEvidenceBundle.verify(dir, source))
    }

    @Test
    fun changedSourceRecordFailsClosed() = withBundle { dir, source ->
        assertTrue("RECORD_METADATA_MISMATCH" in
            PhysicalReadOnlyEvidenceBundle.verify(dir, source.copy(firmwareVersion = "altered")))
    }

    @Test
    fun missingSourceRecordFailsClosed() = withBundle { dir, _ ->
        assertTrue("MISSING_APPROVAL_RECORD" in PhysicalReadOnlyEvidenceBundle.verify(dir, null))
    }

    @Test
    fun forgedSafetyClaimFailsClosed() = withBundle { dir, source ->
        val file = dir.resolve("bundle.json")
        Files.writeString(file, Files.readString(file).replace(
            "\"motionAuthorization\":false", "\"motionAuthorization\":true"
        ))
        assertTrue("INVALID_BUNDLE_MANIFEST" in PhysicalReadOnlyEvidenceBundle.verify(dir, source))
    }
}
