package org.jason.pi.gcs.simulation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalReadOnlyApprovalEvidenceTest {
    @Test
    fun missingApprovalExportsNoGoWithNoMotionAuthority() {
        val path = Files.createTempFile("pi-preflight-no-go", ".json")
        try {
            val verdict = PhysicalReadOnlyApprovalEvidence.export(null, path)
            assertFalse(verdict.approvedForSeparateManualReview)
            assertTrue(PhysicalReadOnlyApprovalEvidence.validate(path).isEmpty())
            val node = ObjectMapper().readTree(path.toFile())
            assertEquals("NO_GO", node.path("result").asText())
            assertFalse(node.path("hardwareTested").asBoolean())
            assertFalse(node.path("motionAuthorization").asBoolean())
            assertEquals("MISSING_APPROVAL_RECORD", node.path("reasons")[0].asText())
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun corruptedOrUnsafeEvidenceIsRejected() {
        val path = Files.createTempFile("pi-preflight-invalid", ".json")
        try {
            PhysicalReadOnlyApprovalEvidence.export(null, path)
            val mapper = ObjectMapper()
            val unsafe = mapper.readTree(path.toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
            unsafe.put("motionAuthorization", true)
            Files.writeString(path, mapper.writeValueAsString(unsafe))
            assertTrue(PhysicalReadOnlyApprovalEvidence.validate(path).contains("UNSAFE_AUTHORIZATION_CLAIM"))
            unsafe.put("motionAuthorization", false)
            unsafe.put("result", "REVIEW_READY")
            Files.writeString(path, mapper.writeValueAsString(unsafe))
            assertTrue(PhysicalReadOnlyApprovalEvidence.validate(path).contains("VERDICT_MISMATCH"))
            unsafe.put("result", "NO_GO")
            unsafe.putArray("approvedQueries").add("MOV X 10")
            Files.writeString(path, mapper.writeValueAsString(unsafe))
            assertTrue(PhysicalReadOnlyApprovalEvidence.validate(path).contains("UNAPPROVED_QUERY"))
            Files.writeString(path, "{broken-json")
            assertEquals(listOf("INVALID_JSON"), PhysicalReadOnlyApprovalEvidence.validate(path))
        } finally { Files.deleteIfExists(path) }
    }
}
