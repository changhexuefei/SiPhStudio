package org.jason.siph.di

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.EnterpriseIdentityGateway
import org.jason.siph.domain.production.EnterpriseIdentityProviderKind
import org.jason.siph.domain.production.EnterpriseRoleMapping
import org.jason.siph.domain.production.InMemoryRemoteAuditSink
import org.jason.siph.domain.production.LocalTestEnterpriseIdentityGateway
import org.jason.siph.domain.production.LocalTestUser
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.MesMappingProfile
import org.jason.siph.domain.production.MockMesGateway
import org.jason.siph.domain.production.NotConfiguredMesGateway
import org.jason.siph.domain.production.NotConfiguredRemoteAuditSink
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.RemoteAuditSink
import org.jason.siph.domain.production.UnavailableEnterpriseIdentityGateway
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.persistence.HttpMesGateway
import org.jason.siph.persistence.HttpRemoteAuditSink
import org.jason.siph.persistence.LdapEnterpriseIdentityGateway
import org.jason.siph.persistence.LdapIdentityConfig
import org.jason.siph.persistence.OidcIntrospectionConfig
import org.jason.siph.persistence.OidcIntrospectionIdentityGateway
import org.jason.siph.persistence.WorkerCapabilityManifestLoader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

actual fun createPlatformMesGateway(runtimeMode: HardwareRuntimeMode): MesGateway {
    if (runtimeMode == HardwareRuntimeMode.Demo) return MockMesGateway()
    val baseUrl = property(PROPERTY_MES_BASE_URL)
        ?: return NotConfiguredMesGateway("Set -D$PROPERTY_MES_BASE_URL=https://mes.example to enable MES delivery")
    val mappingPath = property(PROPERTY_MES_MAPPING_FILE)?.let(Path::of)
        ?: return NotConfiguredMesGateway("Set -D$PROPERTY_MES_MAPPING_FILE=<json file> to define MES field mapping")
    val profile = runCatching { loadJson<MesMappingProfile>(mappingPath) }
        .getOrElse { return NotConfiguredMesGateway("MES mapping could not be loaded: ${it.message}") }
    return HttpMesGateway(
        baseUri = URI.create(baseUrl),
        profile = profile,
        bearerTokenProvider = { property(PROPERTY_MES_BEARER_TOKEN) },
        allowInsecureHttp = booleanProperty(PROPERTY_ALLOW_INSECURE_HTTP, false)
    )
}

actual fun createPlatformRemoteAuditSink(runtimeMode: HardwareRuntimeMode): RemoteAuditSink {
    if (runtimeMode == HardwareRuntimeMode.Demo) return InMemoryRemoteAuditSink()
    val endpoint = property(PROPERTY_AUDIT_ENDPOINT)
        ?: return NotConfiguredRemoteAuditSink(
            "Set -D$PROPERTY_AUDIT_ENDPOINT=https://audit.example/api/v1/events to enable remote WORM replication"
        )
    return runCatching {
        HttpRemoteAuditSink(
            endpoint = URI.create(endpoint),
            bearerTokenProvider = { property(PROPERTY_AUDIT_BEARER_TOKEN) },
            allowInsecureHttp = booleanProperty(PROPERTY_ALLOW_INSECURE_HTTP, false)
        )
    }.getOrElse {
        NotConfiguredRemoteAuditSink("Remote audit endpoint is invalid: ${it.message}")
    }
}

actual fun createPlatformEnterpriseIdentityGateway(
    runtimeMode: HardwareRuntimeMode
): EnterpriseIdentityGateway {
    if (runtimeMode == HardwareRuntimeMode.Demo) {
        return LocalTestEnterpriseIdentityGateway(
            users = mapOf(
                "operator" to LocalTestUser(
                    username = "operator",
                    displayName = "Digital Operator",
                    password = "operator-demo".toCharArray(),
                    testToken = "demo-operator-token",
                    roles = setOf(ProductionRole.Operator)
                ),
                "supervisor" to LocalTestUser(
                    username = "supervisor",
                    displayName = "Digital Supervisor",
                    password = "supervisor-demo".toCharArray(),
                    testToken = "demo-supervisor-token",
                    roles = setOf(ProductionRole.Supervisor, ProductionRole.QualityEngineer)
                )
            ),
            nowEpochMs = { System.currentTimeMillis() }
        )
    }
    return when (property(PROPERTY_IDENTITY_PROVIDER)?.lowercase()) {
        "oidc", "keycloak" -> createOidcIdentityGateway()
        "ldap", "ad", "active-directory" -> createLdapIdentityGateway()
        null -> UnavailableEnterpriseIdentityGateway(
            "Set -D$PROPERTY_IDENTITY_PROVIDER=keycloak|oidc|ldap|ad to enable enterprise login"
        )
        else -> UnavailableEnterpriseIdentityGateway("Unsupported enterprise identity provider")
    }
}

actual fun createPlatformWorkerRegistration(
    runtimeMode: HardwareRuntimeMode,
    nowEpochMs: Long
): ProductionWorkerRegistration? {
    if (runtimeMode != HardwareRuntimeMode.Real) return null
    val path = property(PROPERTY_WORKER_MANIFEST)?.let(Path::of) ?: return null
    return WorkerCapabilityManifestLoader.load(path, nowEpochMs)
}

private fun createOidcIdentityGateway(): EnterpriseIdentityGateway {
    val endpoint = property(PROPERTY_OIDC_INTROSPECTION)
        ?: return UnavailableEnterpriseIdentityGateway("OIDC introspection endpoint is not configured")
    val clientId = property(PROPERTY_OIDC_CLIENT_ID)
        ?: return UnavailableEnterpriseIdentityGateway("OIDC client ID is not configured")
    val clientSecret = property(PROPERTY_OIDC_CLIENT_SECRET)
        ?: return UnavailableEnterpriseIdentityGateway("OIDC client secret is not configured")
    val roleMappings = parseRoleMappings(property(PROPERTY_IDENTITY_ROLE_MAPPINGS))
    if (roleMappings.isEmpty()) {
        return UnavailableEnterpriseIdentityGateway("Enterprise role mappings are not configured")
    }
    val provider = if (property(PROPERTY_IDENTITY_PROVIDER).equals("keycloak", true)) {
        EnterpriseIdentityProviderKind.Keycloak
    } else {
        EnterpriseIdentityProviderKind.Oidc
    }
    return runCatching {
        OidcIntrospectionIdentityGateway(
            OidcIntrospectionConfig(
                introspectionEndpoint = URI.create(endpoint),
                clientId = clientId,
                clientSecret = clientSecret,
                providerKind = provider,
                roleMappings = roleMappings,
                allowInsecureHttp = booleanProperty(PROPERTY_ALLOW_INSECURE_HTTP, false)
            )
        )
    }.getOrElse { UnavailableEnterpriseIdentityGateway("OIDC configuration is invalid: ${it.message}") }
}

private fun createLdapIdentityGateway(): EnterpriseIdentityGateway {
    val providerUrl = property(PROPERTY_LDAP_URL)
        ?: return UnavailableEnterpriseIdentityGateway("LDAP provider URL is not configured")
    val userDnTemplate = property(PROPERTY_LDAP_USER_DN_TEMPLATE)
        ?: return UnavailableEnterpriseIdentityGateway("LDAP user DN template is not configured")
    val groupSearchBase = property(PROPERTY_LDAP_GROUP_BASE)
        ?: return UnavailableEnterpriseIdentityGateway("LDAP group search base is not configured")
    val groupFilter = property(PROPERTY_LDAP_GROUP_FILTER)
        ?: "(&(objectClass=group)(member={userDn}))"
    val roleMappings = parseRoleMappings(property(PROPERTY_IDENTITY_ROLE_MAPPINGS))
    if (roleMappings.isEmpty()) {
        return UnavailableEnterpriseIdentityGateway("Enterprise role mappings are not configured")
    }
    val provider = if (property(PROPERTY_IDENTITY_PROVIDER).let { it.equals("ad", true) || it.equals("active-directory", true) }) {
        EnterpriseIdentityProviderKind.ActiveDirectory
    } else {
        EnterpriseIdentityProviderKind.Ldap
    }
    return runCatching {
        LdapEnterpriseIdentityGateway(
            LdapIdentityConfig(
                providerUrl = providerUrl,
                userDnTemplate = userDnTemplate,
                groupSearchBase = groupSearchBase,
                groupSearchFilter = groupFilter,
                groupNameAttribute = property(PROPERTY_LDAP_GROUP_ATTRIBUTE) ?: "cn",
                providerKind = provider,
                roleMappings = roleMappings
            )
        )
    }.getOrElse { UnavailableEnterpriseIdentityGateway("LDAP configuration is invalid: ${it.message}") }
}

private fun parseRoleMappings(value: String?): List<EnterpriseRoleMapping> = value
    ?.split(',')
    ?.mapNotNull { entry ->
        val parts = entry.split('=', limit = 2).map(String::trim)
        if (parts.size != 2 || parts.any(String::isBlank)) return@mapNotNull null
        val role = runCatching { ProductionRole.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
        EnterpriseRoleMapping(parts[0], role)
    }
    .orEmpty()

private inline fun <reified T> loadJson(path: Path): T {
    require(Files.isRegularFile(path)) { "Configuration file does not exist: $path" }
    return Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }.decodeFromString(Files.readString(path))
}

private fun property(name: String): String? = System.getProperty(name)
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun booleanProperty(name: String, default: Boolean): Boolean =
    property(name)?.toBooleanStrictOrNull() ?: default

private const val PROPERTY_MES_BASE_URL = "siph.production.mes.baseUrl"
private const val PROPERTY_MES_MAPPING_FILE = "siph.production.mes.mappingFile"
private const val PROPERTY_MES_BEARER_TOKEN = "siph.production.mes.bearerToken"
private const val PROPERTY_AUDIT_ENDPOINT = "siph.production.audit.endpoint"
private const val PROPERTY_AUDIT_BEARER_TOKEN = "siph.production.audit.bearerToken"
private const val PROPERTY_ALLOW_INSECURE_HTTP = "siph.production.integration.allowInsecureHttp"
private const val PROPERTY_IDENTITY_PROVIDER = "siph.production.identity.provider"
private const val PROPERTY_IDENTITY_ROLE_MAPPINGS = "siph.production.identity.roleMappings"
private const val PROPERTY_OIDC_INTROSPECTION = "siph.production.identity.oidc.introspectionEndpoint"
private const val PROPERTY_OIDC_CLIENT_ID = "siph.production.identity.oidc.clientId"
private const val PROPERTY_OIDC_CLIENT_SECRET = "siph.production.identity.oidc.clientSecret"
private const val PROPERTY_LDAP_URL = "siph.production.identity.ldap.url"
private const val PROPERTY_LDAP_USER_DN_TEMPLATE = "siph.production.identity.ldap.userDnTemplate"
private const val PROPERTY_LDAP_GROUP_BASE = "siph.production.identity.ldap.groupSearchBase"
private const val PROPERTY_LDAP_GROUP_FILTER = "siph.production.identity.ldap.groupSearchFilter"
private const val PROPERTY_LDAP_GROUP_ATTRIBUTE = "siph.production.identity.ldap.groupNameAttribute"
private const val PROPERTY_WORKER_MANIFEST = "siph.production.worker.capabilityManifest"
