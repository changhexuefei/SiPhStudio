package org.jason.siph.domain.production

class NotConfiguredMesGateway(
    private val reason: String
) : MesGateway {
    override suspend fun submit(event: ProductionOutboxEvent): MesSubmissionResult =
        MesSubmissionResult(accepted = false, message = reason)
}

class NotConfiguredRemoteAuditSink(
    private val reason: String
) : RemoteAuditSink {
    override suspend fun append(event: ProductionOutboxEvent): RemoteAuditReceipt =
        RemoteAuditReceipt(accepted = false, message = reason)
}

class UnavailableEnterpriseIdentityGateway(
    detail: String
) : EnterpriseIdentityGateway {
    override val status = EnterpriseIdentityStatus(
        provider = EnterpriseIdentityProviderKind.Unavailable,
        configured = false,
        healthy = false,
        detail = detail
    )

    override suspend fun authenticatePassword(
        username: String,
        password: CharArray
    ): EnterpriseAuthenticationResult = EnterpriseAuthenticationResult.Unavailable(status.detail)

    override suspend fun validateBearerToken(token: String): EnterpriseAuthenticationResult =
        EnterpriseAuthenticationResult.Unavailable(status.detail)
}

class LocalTestEnterpriseIdentityGateway(
    private val users: Map<String, LocalTestUser>,
    private val nowEpochMs: () -> Long
) : EnterpriseIdentityGateway {
    override val status = EnterpriseIdentityStatus(
        provider = EnterpriseIdentityProviderKind.LocalTest,
        configured = true,
        healthy = true,
        detail = "Local test identity provider; never use for Real production"
    )

    override suspend fun authenticatePassword(
        username: String,
        password: CharArray
    ): EnterpriseAuthenticationResult {
        val user = users[username]
            ?: return EnterpriseAuthenticationResult.Rejected("Unknown local test user")
        if (!constantTimeEquals(user.password, password)) {
            return EnterpriseAuthenticationResult.Rejected("Invalid local test credentials")
        }
        return EnterpriseAuthenticationResult.Authenticated(user.principal(nowEpochMs()))
    }

    override suspend fun validateBearerToken(token: String): EnterpriseAuthenticationResult {
        val user = users.values.firstOrNull { it.testToken == token }
            ?: return EnterpriseAuthenticationResult.Rejected("Invalid local test token")
        return EnterpriseAuthenticationResult.Authenticated(user.principal(nowEpochMs()))
    }

    private fun constantTimeEquals(expected: CharArray, actual: CharArray): Boolean {
        var difference = expected.size xor actual.size
        val maximum = maxOf(expected.size, actual.size)
        repeat(maximum) { index ->
            val left = if (index < expected.size) expected[index].code else 0
            val right = if (index < actual.size) actual[index].code else 0
            difference = difference or (left xor right)
        }
        return difference == 0
    }
}

data class LocalTestUser(
    val username: String,
    val displayName: String,
    val password: CharArray,
    val testToken: String,
    val roles: Set<ProductionRole>
) {
    init {
        require(username.isNotBlank() && displayName.isNotBlank())
        require(password.isNotEmpty() && testToken.isNotBlank() && roles.isNotEmpty())
    }

    fun principal(nowEpochMs: Long): EnterprisePrincipal = EnterprisePrincipal(
        subjectId = "local:$username",
        username = username,
        displayName = displayName,
        roles = roles,
        authenticatedAtEpochMs = nowEpochMs,
        expiresAtEpochMs = nowEpochMs + 8 * 60 * 60 * 1_000L,
        provider = EnterpriseIdentityProviderKind.LocalTest
    )
}

class ProductionAcceptanceEvaluator {
    fun evaluate(
        id: String,
        kind: ProductionAcceptanceKind,
        environmentName: String,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        submittedTasks: Int,
        passedTasks: Int,
        failedTasks: Int,
        duplicateResults: Int,
        taskLatenciesMs: List<Long>,
        criteria: ProductionAcceptanceCriteria,
        evidenceReferences: List<String>,
        limitations: List<String> = emptyList()
    ): ProductionAcceptanceReport {
        require(taskLatenciesMs.all { it >= 0L })
        val completed = passedTasks + failedTasks
        val durationMs = (finishedAtEpochMs - startedAtEpochMs).coerceAtLeast(1L)
        val throughput = completed * 3_600_000.0 / durationMs
        val successRate = if (completed == 0) 0.0 else passedTasks.toDouble() / completed
        val p95 = percentile95(taskLatenciesMs)
        val passed = completed >= criteria.minimumCompletedTasks &&
            throughput >= criteria.minimumThroughputTasksPerHour &&
            successRate >= criteria.minimumSuccessRate &&
            duplicateResults <= criteria.maximumDuplicateResults &&
            p95 <= criteria.maximumP95TaskLatencyMs &&
            durationMs >= criteria.minimumContinuousRunMs
        return ProductionAcceptanceReport(
            id = id,
            kind = kind,
            environmentName = environmentName,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            submittedTasks = submittedTasks,
            completedTasks = completed,
            passedTasks = passedTasks,
            failedTasks = failedTasks,
            duplicateResults = duplicateResults,
            throughputTasksPerHour = throughput,
            p95TaskLatencyMs = p95,
            continuousRunMs = durationMs,
            criteria = criteria,
            evidenceReferences = evidenceReferences,
            passed = passed,
            limitations = limitations
        )
    }

    private fun percentile95(values: List<Long>): Long {
        if (values.isEmpty()) return Long.MAX_VALUE
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * 0.95).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }
}
