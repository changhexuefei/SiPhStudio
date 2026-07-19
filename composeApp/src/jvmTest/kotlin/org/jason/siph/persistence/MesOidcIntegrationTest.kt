package org.jason.siph.persistence

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jason.siph.domain.production.EnterpriseAuthenticationResult
import org.jason.siph.domain.production.EnterpriseIdentityProviderKind
import org.jason.siph.domain.production.EnterpriseRoleMapping
import org.jason.siph.domain.production.MesFieldMapping
import org.jason.siph.domain.production.MesFieldSource
import org.jason.siph.domain.production.MesMappingProfile
import org.jason.siph.domain.production.ProductionOutboxDestination
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionRole
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MesOidcIntegrationTest {

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
            val gateway = HttpMesGateway(
                baseUri = URI.create("http://127.0.0.1:${server.address.port}"),
                profile = MesMappingProfile(
                    id = "mes-result-v1",
                    version = 1,
                    endpointPath = "/mes/result",
                    eventTypes = setOf("PRODUCTION_TASK_COMPLETED"),
                    fieldMappings = listOf(
                        MesFieldMapping("eventId", MesFieldSource.EventId),
                        MesFieldMapping("resultId", MesFieldSource.PayloadPath, sourcePath = "resultId"),
                        MesFieldMapping("source", MesFieldSource.StaticValue, staticValue = "SiPhStudio")
                    )
                ),
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
            createContext("/introspect") { exchange ->
                val response = """
                    {
                      "active": true,
                      "sub": "user-42",
                      "preferred_username": "operator.a",
                      "name": "Operator A",
                      "exp": 4102444800,
                      "realm_access": {"roles": ["siph-operator"]}
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
                    introspectionEndpoint = URI.create("http://127.0.0.1:${server.address.port}/introspect"),
                    clientId = "siph-client",
                    clientSecret = "test-only",
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
}
