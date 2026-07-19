package org.jason.siph.persistence

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.AuditEvent
import org.jason.siph.domain.production.DeviceCapabilityVerificationState
import org.jason.siph.domain.production.EnterpriseAuthenticationResult
import org.jason.siph.domain.production.EnterpriseIdentityProviderKind
import org.jason.siph.domain.production.EnterpriseRoleMapping
import org.jason.siph.domain.production.HttpMethodNotAllowedException
import org.jason.siph.domain.production.MesFieldMapping
import org.jason.siph.domain.production.MesFieldSource
import org.jason.siph.domain.production.MesMappingProfile
import org.jason.siph.domain.production.ProductionAcceptanceCriteria
import org.jason.siph.domain.production.ProductionAcceptanceEvaluator
import org.jason.siph.domain.production.ProductionAcceptanceKind
import org.jason.siph.domain.production.ProductionDeviceCapabilityEvidence
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionWorkerCapabilityManifest
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnterpriseProductionIntegrationTest {

    @Test
    fun mesProfileMapsPayloadAndSendsIdempotentHttpRequest() = runBlocking {
        var receivedBody = ""
        var receivedKey = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mes/result") { exchange ->
                receivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
                receivedKey = exchange.requestHeaders.getFirst("Idempotency-Key")
                exchange.responseHeaders.add("X-Request-Id", "MES-REQ-1")
                exchange.sendResponseHeaders(202, 0)
                exchange.responseBody.close()
            }
            start()
        }
        try {
            val profile = MesMappingProfile(
                id = "mes-result-v1",
                version = 1,
                endpointPath = "/mes/result",
                eventTypes = setOf("PRODUCTION_TASK_COMPLETED"),
                fieldMappings = listOf(
                    MesFieldMapping("eventId", MesFieldSource.EventId),
                    MesFieldMapping("resultId", MesFieldSource.PayloadPath, sourcePath = "resultId"),
                    MesFieldMapping("source", MesFieldSource.StaticValue, staticValue = "SiPhStudio")
                )
            )
            val gateway = HttpMesGateway(
                baseUri = URI.create("http://127.0.0.1:${server.address.port}"),
                profile = profile,
                allowInsecureHttp = true
            )
            val result = gateway.submit(
                ProductionOutboxEvent(
                    id = "event-1",
                    destination = ProductionOutboxDestination.Mes,
                    eventType = "PRODUCTION_TASK_COMPLETED",
                    aggregateType = "ProductionTask",
                    aggregateId = "task-1",
                    idempotencyKey = "MES:task-1",
                    payloadJson = "{\"resultId\":\"result-1\"}",
                    createdAtEpochMs = 1L
                )
            )
            assertTrue(result.accepted)
            assertEquals("MES-REQ-1", result.remoteReference)
            assertEquals("MES:task-1", receivedKey)
            assertTrue(receivedBody.contains("\"resultId\":\"result-1\""))
            assertTrue(receivedBody.contains("\"source\":\"SiPhStudio\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun keycloakCompatibleIntrospectionMapsExternalRoles() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/realms/test/protocol/openid-connect/token/introspect") { exchange ->
                val response = """
                    {
                      "active": true,
                      "sub": "user-42",
                      "preferred_username": "operator.a",
                      "name": "Operator A",
                      "email": "operator@example.test",
                      "exp": 4102444800,
                      "realm_access": {"roles": ["siph-operator", "offline_access"]}
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val gateway = OidcIntrospectionIdentityGateway(
                OidcIntrospectionConfig(
                    introspectionEndpoint = URI.create(
                        "http://127.0.0.1:${server.address.port}/realms/test/protocol/openid-connect/token/introspect"
                    ),
                    clientId = "siph-client",
                    clientSecret = "secret",
                    providerKind = EnterpriseIdentityProviderKind.Keycloak,
                    roleMappings = listOf(EnterpriseRoleMapping("siph-operator", ProductionRole.Operator)),
                    allowInsecureHttp = true
                ),
                nowEpochMs = { 1_000L }
            )
            val authenticated = assertIs<EnterpriseAuthenticationResult.Authenticated>(
                gateway.validateBearerToken("access-token")
            )
            assertEquals("user-42", authenticated.principal.subjectId)
            assertEquals(setOf(ProductionRole.Operator), authenticated.principal.roles)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun remoteWormServerPersistsChainAndRejectsMutation() = runBlocking {
        val directory = createTempDirectory("worm-audit")
        val archive = FileSystemWormAuditArchive(directory)
        JvmWormAuditHttpServer(archive, bearerToken = "audit-secret").use { server ->
            server.start()
            val sink = HttpRemoteAuditSink(
                endpoint = server.endpoint,
                bearerTokenProvider = { "audit-secret" },
                allowInsecureHttp = true
            )
            val first = audit("audit-1", previous = null, hash = "hash-1")
            val second = audit("audit-2", previous = "hash-1", hash = "hash-2")
            assertTrue(sink.append(outbox(first)).accepted)
            assertTrue(sink.append(outbox(second)).accepted)
            assertEquals(listOf("audit-2", "audit-1"), archive.list().map { it.id })

            val mutation = first.copy(eventHash = "changed")
            assertFalse(sink.append(outbox(mutation)).accepted)
            assertEquals(2, archive.list().size)
        }
    }

    @Test
    fun productionWorkerManifestRequiresQualifiedUnexpiredEvidence() {
        val now = 10_000L
        val directory = createTempDirectory("worker-manifest")
        val file = directory.resolve("worker.json")
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

    @Test
    fun acceptanceReportSeparatesDigitalEvidenceFromHardwareAcceptance() {
        val report = ProductionAcceptanceEvaluator().evaluate(
            id = "acceptance-1",
            kind = ProductionAcceptanceKind.PostgreSqlConcurrency,
            environmentName = "github-postgres-service",
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 61_000L,
            submittedTasks = 400,
            passedTasks = 400,
            failedTasks = 0,
            duplicateResults = 0,
            taskLatenciesMs = List(400) { 25L + it % 10 },
            criteria = ProductionAcceptanceCriteria(
                minimumCompletedTasks = 400,
                minimumThroughputTasksPerHour = 20_000.0,
                minimumSuccessRate = 1.0,
                maximumP95TaskLatencyMs = 100L,
                minimumContinuousRunMs = 60_000L
            ),
            evidenceReferences = listOf("ci://postgres-stress"),
            limitations = listOf("No optical, electrical, motion, thermal, or wafer hardware was exercised")
        )
        assertTrue(report.passed)
        assertEquals(ProductionAcceptanceKind.PostgreSqlConcurrency, report.kind)
        assertTrue(report.limitations.isNotEmpty())
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
