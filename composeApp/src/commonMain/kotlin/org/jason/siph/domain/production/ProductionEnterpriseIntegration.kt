package org.jason.siph.domain.production

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class MesFieldSource {
    EventId,
    EventType,
    AggregateType,
    AggregateId,
    IdempotencyKey,
    CreatedAtEpochMs,
    PayloadJson,
    PayloadPath,
    StaticValue
}

@Serializable
data class MesFieldMapping(
    val targetField: String,
    val source: MesFieldSource,
    val sourcePath: String? = null,
    val staticValue: String? = null,
    val required: Boolean = true
) {
    init {
        require(targetField.isNotBlank())
        if (source == MesFieldSource.PayloadPath) require(!sourcePath.isNullOrBlank())
        if (source == MesFieldSource.StaticValue) require(staticValue != null)
    }
}

@Serializable
data class MesMappingProfile(
    val id: String,
    val version: Int,
    val endpointPath: String,
    val eventTypes: Set<String>,
    val fieldMappings: List<MesFieldMapping>,
    val staticHeaders: Map<String, String> = emptyMap(),
    val acceptedHttpStatusCodes: Set<Int> = setOf(200, 201, 202, 204)
) {
    init {
        require(id.isNotBlank() && version > 0)
        require(endpointPath.startsWith('/'))
        require(eventTypes.isNotEmpty() && eventTypes.all(String::isNotBlank))
        require(fieldMappings.isNotEmpty())
        require(fieldMappings.map { it.targetField }.distinct().size == fieldMappings.size)
        require(staticHeaders.keys.all(String::isNotBlank))
        require(acceptedHttpStatusCodes.isNotEmpty())
    }
}

class MesPayloadMapper(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun map(event: ProductionOutboxEvent, profile: MesMappingProfile): String {
        require(event.eventType in profile.eventTypes) {
            "MES profile ${profile.id} does not accept event type ${event.eventType}"
        }
        val payload = runCatching { json.parseToJsonElement(event.payloadJson) }.getOrNull()
        val mapped = buildJsonObject {
            profile.fieldMappings.forEach { mapping ->
                val value = resolve(event, payload, mapping)
                if (value == null || value is JsonNull) {
                    require(!mapping.required) {
                        "Required MES field ${mapping.targetField} could not be resolved"
                    }
                } else {
                    put(mapping.targetField, value)
                }
            }
        }
        return mapped.toString()
    }

    private fun resolve(
        event: ProductionOutboxEvent,
        payload: JsonElement?,
        mapping: MesFieldMapping
    ): JsonElement? = when (mapping.source) {
        MesFieldSource.EventId -> JsonPrimitive(event.id)
        MesFieldSource.EventType -> JsonPrimitive(event.eventType)
        MesFieldSource.AggregateType -> JsonPrimitive(event.aggregateType)
        MesFieldSource.AggregateId -> JsonPrimitive(event.aggregateId)
        MesFieldSource.IdempotencyKey -> JsonPrimitive(event.idempotencyKey)
        MesFieldSource.CreatedAtEpochMs -> JsonPrimitive(event.createdAtEpochMs)
        MesFieldSource.PayloadJson -> payload ?: JsonPrimitive(event.payloadJson)
        MesFieldSource.PayloadPath -> payload.findPath(mapping.sourcePath.orEmpty())
        MesFieldSource.StaticValue -> JsonPrimitive(mapping.staticValue.orEmpty())
    }

    private fun JsonElement?.findPath(path: String): JsonElement? {
        var current = this ?: return null
        path.split('.').filter(String::isNotBlank).forEach { segment ->
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }
}

@Serializable
enum class EnterpriseIdentityProviderKind {
    LocalTest,
    Ldap,
    ActiveDirectory,
    Oidc,
    Keycloak,
    Unavailable
}

@Serializable
data class EnterpriseIdentityStatus(
    val provider: EnterpriseIdentityProviderKind,
    val configured: Boolean,
    val healthy: Boolean,
    val detail: String
)

@Serializable
data class EnterprisePrincipal(
    val subjectId: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val groups: Set<String> = emptySet(),
    val roles: Set<ProductionRole>,
    val authenticatedAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val provider: EnterpriseIdentityProviderKind
) {
    init {
        require(subjectId.isNotBlank() && username.isNotBlank() && displayName.isNotBlank())
        require(roles.isNotEmpty())
        require(expiresAtEpochMs == null || expiresAtEpochMs > authenticatedAtEpochMs)
    }

    fun asProductionActor(): ProductionActor = ProductionActor(
        id = subjectId,
        displayName = displayName,
        roles = roles,
        enabled = true
    )
}

sealed interface EnterpriseAuthenticationResult {
    data class Authenticated(val principal: EnterprisePrincipal) : EnterpriseAuthenticationResult
    data class Rejected(val reason: String) : EnterpriseAuthenticationResult
    data class Unavailable(val reason: String) : EnterpriseAuthenticationResult
}

interface EnterpriseIdentityGateway {
    val status: EnterpriseIdentityStatus

    suspend fun authenticatePassword(username: String, password: CharArray): EnterpriseAuthenticationResult

    suspend fun validateBearerToken(token: String): EnterpriseAuthenticationResult
}

@Serializable
data class EnterpriseRoleMapping(
    val externalName: String,
    val productionRole: ProductionRole
) {
    init {
        require(externalName.isNotBlank())
    }
}

@Serializable
enum class DeviceCapabilityVerificationState {
    SimulationOnly,
    ProtocolImplemented,
    HardwareVerified,
    ProductionQualified
}

@Serializable
data class ProductionDeviceCapabilityEvidence(
    val capability: String,
    val deviceId: String,
    val model: String,
    val serialNumber: String,
    val verificationState: DeviceCapabilityVerificationState,
    val verificationEvidenceId: String,
    val verifiedAtEpochMs: Long,
    val validUntilEpochMs: Long? = null
) {
    init {
        require(capability.isNotBlank())
        require(deviceId.isNotBlank() && model.isNotBlank() && serialNumber.isNotBlank())
        require(verificationEvidenceId.isNotBlank())
        require(validUntilEpochMs == null || validUntilEpochMs > verifiedAtEpochMs)
    }

    fun validForProduction(nowEpochMs: Long): Boolean =
        verificationState == DeviceCapabilityVerificationState.ProductionQualified &&
            (validUntilEpochMs == null || nowEpochMs < validUntilEpochMs)
}

@Serializable
data class ProductionWorkerCapabilityManifest(
    val workerId: String,
    val workstationId: String,
    val equipmentGroupId: String,
    val softwareVersion: String,
    val safetyProfileId: String,
    val calibrationQualificationId: String,
    val evidence: List<ProductionDeviceCapabilityEvidence>,
    val maximumParallelTasks: Int = 1,
    val issuedAtEpochMs: Long,
    val issuedBy: String,
    val approvedBy: String
) {
    init {
        require(workerId.isNotBlank() && workstationId.isNotBlank() && equipmentGroupId.isNotBlank())
        require(softwareVersion.isNotBlank())
        require(safetyProfileId.isNotBlank() && calibrationQualificationId.isNotBlank())
        require(evidence.isNotEmpty())
        require(maximumParallelTasks > 0)
        require(issuedBy.isNotBlank() && approvedBy.isNotBlank() && issuedBy != approvedBy)
    }

    fun toRegistration(nowEpochMs: Long): ProductionWorkerRegistration {
        val valid = evidence.filter { it.validForProduction(nowEpochMs) }
        require(valid.size == evidence.size) {
            "Every worker capability must be ProductionQualified and unexpired"
        }
        return ProductionWorkerRegistration(
            workerId = workerId,
            workstationId = workstationId,
            equipmentGroupId = equipmentGroupId,
            capabilities = valid.map { it.capability }.toSet(),
            softwareVersion = softwareVersion,
            maximumParallelTasks = maximumParallelTasks,
            registeredAtEpochMs = nowEpochMs
        )
    }
}

@Serializable
enum class ProductionAcceptanceKind {
    DigitalInfrastructure,
    PostgreSqlConcurrency,
    MesContract,
    IdentityIntegration,
    RemoteAudit,
    HardwareSoak,
    FullProduction
}

@Serializable
data class ProductionAcceptanceCriteria(
    val minimumCompletedTasks: Int,
    val minimumThroughputTasksPerHour: Double,
    val minimumSuccessRate: Double,
    val maximumDuplicateResults: Int = 0,
    val maximumP95TaskLatencyMs: Long,
    val minimumContinuousRunMs: Long
) {
    init {
        require(minimumCompletedTasks > 0)
        require(minimumThroughputTasksPerHour > 0.0)
        require(minimumSuccessRate in 0.0..1.0)
        require(maximumDuplicateResults >= 0)
        require(maximumP95TaskLatencyMs > 0L)
        require(minimumContinuousRunMs > 0L)
    }
}

@Serializable
data class ProductionAcceptanceReport(
    val id: String,
    val kind: ProductionAcceptanceKind,
    val environmentName: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val submittedTasks: Int,
    val completedTasks: Int,
    val passedTasks: Int,
    val failedTasks: Int,
    val duplicateResults: Int,
    val throughputTasksPerHour: Double,
    val p95TaskLatencyMs: Long,
    val continuousRunMs: Long,
    val criteria: ProductionAcceptanceCriteria,
    val evidenceReferences: List<String>,
    val passed: Boolean,
    val limitations: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank() && environmentName.isNotBlank())
        require(finishedAtEpochMs >= startedAtEpochMs)
        require(submittedTasks >= 0 && completedTasks >= 0)
        require(passedTasks >= 0 && failedTasks >= 0 && duplicateResults >= 0)
        require(throughputTasksPerHour >= 0.0)
        require(p95TaskLatencyMs >= 0L && continuousRunMs >= 0L)
        require(evidenceReferences.all(String::isNotBlank))
    }
}
