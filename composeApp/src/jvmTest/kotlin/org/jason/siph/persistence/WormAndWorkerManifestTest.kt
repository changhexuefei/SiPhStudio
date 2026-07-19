package org.jason.siph.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.AuditEvent
import org.jason.siph.domain.production.DeviceCapabilityVerificationState
import org.jason.siph.domain.production.ProductionDeviceCapabilityEvidence
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionWorkerCapabilityManifest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WormAndWorkerManifestTest {

    @Test
    fun remoteWormServerPersistsChainAndRejectsMutation() = runBlocking {
        val directory = createTempDirectory("worm-audit")
        val archive = FileSystemWormAuditArchive(directory)
        JvmWormAuditHttpServer(archive, bearerToken = "audit-test-token").use { server ->
            server.start()
            val sink = HttpRemoteAuditSink(
                endpoint = server.endpoint,
                bearerTokenProvider = { "audit-test-token" },
                allowInsecureHttp = true
            )
            val first = audit("audit-1", previous = null, hash = "hash-1")
            val second = audit("audit-2", previous = "hash-1", hash = "hash-2")
            assertTrue(sink.append(outbox(first)).accepted)
            assertTrue(sink.append(outbox(second)).accepted)
            assertEquals(listOf("audit-2", "audit-1"), archive.list().map { it.id })

            assertFalse(sink.append(outbox(first.copy(eventHash = "changed"))).accepted)
            assertEquals(2, archive.list().size)
        }
    }

    @Test
    fun workerManifestRegistersOnlyProductionQualifiedEvidence() {
        val now = 10_000L
        val file = createTempDirectory("worker-manifest").resolve("worker.json")
        val manifest = ProductionWorkerCapabilityManifest(
            workerId = "station-01",
            workstationId = "host-01",
            equipmentGroupId = "oeo-line-a",
            softwareVersion = "1.0.0",
            safetyProfileId = "safety-approved-1",
            calibrationQualificationId = "cal-approved-1",
            evidence = listOf(
                ProductionDeviceCapabilityEvidence(
                    capability = "laser",
                    deviceId = "laser-1",
                    model = "TSL",
                    serialNumber = "SN-1",
                    verificationState = DeviceCapabilityVerificationState.ProductionQualified,
                    verificationEvidenceId = "FAT-1",
                    verifiedAtEpochMs = 1_000L,
                    validUntilEpochMs = 20_000L
                )
            ),
            issuedAtEpochMs = 2_000L,
            issuedBy = "engineer-a",
            approvedBy = "quality-b"
        )
        Files.writeString(file, Json.encodeToString(manifest))
        val registration = WorkerCapabilityManifestLoader.load(file, now)
        assertEquals("station-01", registration.workerId)
        assertEquals(setOf("laser"), registration.capabilities)
    }

    private fun audit(id: String, previous: String?, hash: String) = AuditEvent(
        id = id,
        timestampEpochMs = 1L,
        actorId = "actor",
        actorRoles = setOf(ProductionRole.Auditor),
        action = "TEST",
        targetType = "Audit",
        targetId = id,
        correlationId = "correlation-$id",
        applicationVersion = "test",
        workstationId = "test",
        success = true,
        previousHash = previous,
        eventHash = hash
    )

    private fun outbox(event: AuditEvent) = ProductionOutboxEvent(
        id = "outbox-${event.id}",
        destination = ProductionOutboxDestination.AuditServer,
        eventType = "AUDIT_EVENT_APPENDED",
        aggregateType = "AuditEvent",
        aggregateId = event.id,
        idempotencyKey = "AUDIT:${event.eventHash}",
        payloadJson = Json.encodeToString(event),
        createdAtEpochMs = 1L
    )
}
