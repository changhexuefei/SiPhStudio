package org.jason.siph.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jason.siph.domain.production.EnterpriseAuthenticationResult
import org.jason.siph.domain.production.EnterpriseIdentityGateway
import org.jason.siph.domain.production.EnterpriseIdentityProviderKind
import org.jason.siph.domain.production.EnterpriseIdentityStatus
import org.jason.siph.domain.production.EnterprisePrincipal
import org.jason.siph.domain.production.EnterpriseRoleMapping
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.MesMappingProfile
import org.jason.siph.domain.production.MesPayloadMapper
import org.jason.siph.domain.production.MesSubmissionResult
import org.jason.siph.domain.production.ProductionOutboxEvent
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionWorkerCapabilityManifest
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.RemoteAuditReceipt
import org.jason.siph.domain.production.RemoteAuditSink
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext
import javax.naming.directory.SearchControls

class HttpMesGateway(
    private val baseUri: URI,
    private val profile: MesMappingProfile,
    private val bearerTokenProvider: () -> String? = { null },
    private val mapper: MesPayloadMapper = MesPayloadMapper(),
    private val client: HttpClient = defaultHttpClient(),
    private val allowInsecureHttp: Boolean = false
) : MesGateway {
    init {
        requireSecureUri(baseUri, allowInsecureHttp)
    }

    override suspend fun submit(event: ProductionOutboxEvent): MesSubmissionResult = withContext(Dispatchers.IO) {
        val target = baseUri.resolve(profile.endpointPath)
        val request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", event.idempotencyKey)
            .header("X-SiPh-Event-Type", event.eventType)
            .apply {
                profile.staticHeaders.forEach(::header)
                bearerTokenProvider()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }
            .POST(HttpRequest.BodyPublishers.ofString(mapper.map(event, profile), StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val accepted = response.statusCode() in profile.acceptedHttpStatusCodes
        MesSubmissionResult(
            accepted = accepted,
            remoteReference = response.headers().firstValue("X-Request-Id").orElse(null)
                ?: response.headers().firstValue("Location").orElse(null),
            message = if (accepted) {
                "MES accepted ${event.eventType} with HTTP ${response.statusCode()}"
            } else {
                "MES rejected ${event.eventType} with HTTP ${response.statusCode()}: ${response.body().take(1_000)}"
            }
        )
    }
}

class HttpRemoteAuditSink(
    private val endpoint: URI,
    private val bearerTokenProvider: () -> String? = { null },
    private val acceptedHttpStatusCodes: Set<Int> = setOf(200, 201, 202, 204),
    private val client: HttpClient = defaultHttpClient(),
    private val allowInsecureHttp: Boolean = false
) : RemoteAuditSink {
    init {
        requireSecureUri(endpoint, allowInsecureHttp)
        require(acceptedHttpStatusCodes.isNotEmpty())
    }

    override suspend fun append(event: ProductionOutboxEvent): RemoteAuditReceipt = withContext(Dispatchers.IO) {
        require(event.eventType == "AUDIT_EVENT_APPENDED") {
            "Remote WORM sink only accepts audit append events"
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", event.idempotencyKey)
            .header("X-Audit-Aggregate-Id", event.aggregateId)
            .apply {
                bearerTokenProvider()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }
            .POST(HttpRequest.BodyPublishers.ofString(event.payloadJson, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val accepted = response.statusCode() in acceptedHttpStatusCodes
        RemoteAuditReceipt(
            accepted = accepted,
            remoteReference = response.headers().firstValue("X-Audit-Receipt").orElse(null)
                ?: response.headers().firstValue("X-Request-Id").orElse(null),
            message = if (accepted) {
                "Remote append-only audit service accepted event ${event.aggregateId}"
            } else {
                "Remote audit service rejected event with HTTP ${response.statusCode()}: ${response.body().take(1_000)}"
            }
        )
    }
}

data class OidcIntrospectionConfig(
    val introspectionEndpoint: URI,
    val clientId: String,
    val clientSecret: String,
    val providerKind: EnterpriseIdentityProviderKind,
    val roleMappings: List<EnterpriseRoleMapping>,
    val allowInsecureHttp: Boolean = false
) {
    init {
        require(providerKind == EnterpriseIdentityProviderKind.Oidc || providerKind == EnterpriseIdentityProviderKind.Keycloak)
        require(clientId.isNotBlank() && clientSecret.isNotBlank())
        require(roleMappings.isNotEmpty())
        requireSecureUri(introspectionEndpoint, allowInsecureHttp)
    }
}

class OidcIntrospectionIdentityGateway(
    private val config: OidcIntrospectionConfig,
    private val client: HttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) : EnterpriseIdentityGateway {
    override val status = EnterpriseIdentityStatus(
        provider = config.providerKind,
        configured = true,
        healthy = true,
        detail = "OIDC token introspection is configured at ${config.introspectionEndpoint.host}"
    )

    override suspend fun authenticatePassword(
        username: String,
        password: CharArray
    ): EnterpriseAuthenticationResult = EnterpriseAuthenticationResult.Unavailable(
        "OIDC password grant is intentionally disabled; use browser/device login and bearer token validation"
    )

    override suspend fun validateBearerToken(token: String): EnterpriseAuthenticationResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext EnterpriseAuthenticationResult.Rejected("Bearer token is empty")
        val form = "token=${urlEncode(token)}&token_type_hint=access_token"
        val basic = Base64.getEncoder().encodeToString(
            "${config.clientId}:${config.clientSecret}".toByteArray(StandardCharsets.UTF_8)
        )
        val request = HttpRequest.newBuilder(config.introspectionEndpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            return@withContext EnterpriseAuthenticationResult.Unavailable(
                "OIDC introspection failed with HTTP ${response.statusCode()}"
            )
        }
        val document = runCatching { json.parseToJsonElement(response.body()).jsonObject }
            .getOrElse { return@withContext EnterpriseAuthenticationResult.Unavailable("Invalid OIDC introspection JSON") }
        if (document["active"]?.jsonPrimitive?.booleanOrNull != true) {
            return@withContext EnterpriseAuthenticationResult.Rejected("Bearer token is inactive")
        }
        val externalRoles = extractExternalRoles(document)
        val roles = config.roleMappings
            .filter { it.externalName in externalRoles }
            .map { it.productionRole }
            .toSet()
        if (roles.isEmpty()) {
            return@withContext EnterpriseAuthenticationResult.Rejected(
                "Authenticated identity has no mapped production role"
            )
        }
        val subject = document.string("sub") ?: return@withContext EnterpriseAuthenticationResult.Rejected("Token has no subject")
        val username = document.string("preferred_username") ?: document.string("username") ?: subject
        val displayName = document.string("name") ?: username
        val expiration = document["exp"]?.jsonPrimitive?.longOrNull?.times(1_000L)
        EnterpriseAuthenticationResult.Authenticated(
            EnterprisePrincipal(
                subjectId = subject,
                username = username,
                displayName = displayName,
                email = document.string("email"),
                groups = document.stringSet("groups"),
                roles = roles,
                authenticatedAtEpochMs = nowEpochMs(),
                expiresAtEpochMs = expiration,
                provider = config.providerKind
            )
        )
    }

    private fun extractExternalRoles(document: JsonObject): Set<String> = buildSet {
        addAll(document.stringSet("groups"))
        addAll(document.stringSet("roles"))
        document.string("scope")?.split(' ')?.filter(String::isNotBlank)?.let(::addAll)
        document["realm_access"]?.asObject()?.get("roles")?.asStringSet()?.let(::addAll)
        document["resource_access"]?.asObject()?.values?.forEach { resource ->
            resource.asObject()?.get("roles")?.asStringSet()?.let(::addAll)
        }
    }
}

data class LdapIdentityConfig(
    val providerUrl: String,
    val userDnTemplate: String,
    val groupSearchBase: String,
    val groupSearchFilter: String,
    val groupNameAttribute: String = "cn",
    val providerKind: EnterpriseIdentityProviderKind = EnterpriseIdentityProviderKind.Ldap,
    val roleMappings: List<EnterpriseRoleMapping>
) {
    init {
        require(providerUrl.isNotBlank())
        require(userDnTemplate.contains("{username}"))
        require(groupSearchBase.isNotBlank())
        require(groupSearchFilter.contains("{username}") || groupSearchFilter.contains("{userDn}"))
        require(groupNameAttribute.isNotBlank())
        require(providerKind == EnterpriseIdentityProviderKind.Ldap || providerKind == EnterpriseIdentityProviderKind.ActiveDirectory)
        require(roleMappings.isNotEmpty())
    }
}

class LdapEnterpriseIdentityGateway(
    private val config: LdapIdentityConfig,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) : EnterpriseIdentityGateway {
    override val status = EnterpriseIdentityStatus(
        provider = config.providerKind,
        configured = true,
        healthy = true,
        detail = "LDAP bind is configured at ${config.providerUrl}"
    )

    override suspend fun authenticatePassword(
        username: String,
        password: CharArray
    ): EnterpriseAuthenticationResult = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isEmpty()) {
            return@withContext EnterpriseAuthenticationResult.Rejected("Username and password are required")
        }
        val userDn = config.userDnTemplate.replace("{username}", username)
        val passwordText = password.concatToString()
        val environment = Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, config.providerUrl)
            put(Context.SECURITY_AUTHENTICATION, "simple")
            put(Context.SECURITY_PRINCIPAL, userDn)
            put(Context.SECURITY_CREDENTIALS, passwordText)
            put("com.sun.jndi.ldap.connect.timeout", "10000")
            put("com.sun.jndi.ldap.read.timeout", "10000")
        }
        try {
            InitialDirContext(environment).use { context ->
                val groups = searchGroups(context, username, userDn)
                val roles = config.roleMappings
                    .filter { it.externalName in groups }
                    .map { it.productionRole }
                    .toSet()
                if (roles.isEmpty()) {
                    EnterpriseAuthenticationResult.Rejected("LDAP identity has no mapped production role")
                } else {
                    EnterpriseAuthenticationResult.Authenticated(
                        EnterprisePrincipal(
                            subjectId = "ldap:$userDn",
                            username = username,
                            displayName = username,
                            groups = groups,
                            roles = roles,
                            authenticatedAtEpochMs = nowEpochMs(),
                            provider = config.providerKind
                        )
                    )
                }
            }
        } catch (error: NamingException) {
            EnterpriseAuthenticationResult.Rejected("LDAP authentication failed: ${error.message ?: error::class.simpleName}")
        }
    }

    override suspend fun validateBearerToken(token: String): EnterpriseAuthenticationResult =
        EnterpriseAuthenticationResult.Unavailable("LDAP/Active Directory adapter validates username and password, not bearer tokens")

    private fun searchGroups(
        context: InitialDirContext,
        username: String,
        userDn: String
    ): Set<String> {
        val filter = config.groupSearchFilter
            .replace("{username}", escapeLdapFilter(username))
            .replace("{userDn}", escapeLdapFilter(userDn))
        val controls = SearchControls().apply {
            searchScope = SearchControls.SUBTREE_SCOPE
            returningAttributes = arrayOf(config.groupNameAttribute)
            countLimit = 500
            timeLimit = 10_000
        }
        val results = context.search(config.groupSearchBase, filter, controls)
        return buildSet {
            results.use { enumeration ->
                while (enumeration.hasMore()) {
                    val item = enumeration.next()
                    item.attributes.get(config.groupNameAttribute)?.get()?.toString()?.let(::add)
                }
            }
        }
    }
}

object WorkerCapabilityManifestLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(path: Path, nowEpochMs: Long): ProductionWorkerRegistration {
        require(Files.isRegularFile(path)) { "Worker capability manifest does not exist: $path" }
        val manifest = json.decodeFromString<ProductionWorkerCapabilityManifest>(Files.readString(path))
        return manifest.toRegistration(nowEpochMs)
    }
}

private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

private fun requireSecureUri(uri: URI, allowInsecureHttp: Boolean) {
    require(uri.scheme.equals("https", ignoreCase = true) || (allowInsecureHttp && uri.scheme.equals("http", ignoreCase = true))) {
        "Production integration endpoint must use HTTPS unless allowInsecureHttp is explicitly enabled for tests"
    }
    require(!uri.host.isNullOrBlank())
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun escapeLdapFilter(value: String): String = buildString {
    value.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\5c"
                '*' -> "\\2a"
                '(' -> "\\28"
                ')' -> "\\29"
                '\u0000' -> "\\00"
                else -> character
            }
        )
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringSet(name: String): Set<String> = this[name]?.asStringSet().orEmpty()

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement.asStringSet(): Set<String> = when (this) {
    is JsonArray -> mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    else -> emptySet()
}
