package org.jason.siph.domain.production

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


data class EnterpriseSessionState(
    val identityStatus: EnterpriseIdentityStatus,
    val principal: EnterprisePrincipal? = null,
    val authenticating: Boolean = false,
    val message: String = identityStatus.detail,
    val errorMessage: String? = null
) {
    val authenticated: Boolean get() = principal != null
    val actor: ProductionActor? get() = principal?.asProductionActor()
}

class EnterpriseSessionService(
    private val gateway: EnterpriseIdentityGateway
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(
        EnterpriseSessionState(identityStatus = gateway.status)
    )
    val state: StateFlow<EnterpriseSessionState> = mutableState.asStateFlow()

    suspend fun loginWithPassword(username: String, password: CharArray): EnterpriseAuthenticationResult =
        authenticate {
            try {
                gateway.authenticatePassword(username.trim(), password)
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun loginWithBearerToken(token: CharArray): EnterpriseAuthenticationResult = authenticate {
        val value = token.concatToString()
        try {
            gateway.validateBearerToken(value)
        } finally {
            token.fill('\u0000')
        }
    }

    suspend fun logout() = mutex.withLock {
        mutableState.value = EnterpriseSessionState(
            identityStatus = gateway.status,
            principal = null,
            message = "Enterprise session signed out"
        )
    }

    private suspend fun authenticate(
        operation: suspend () -> EnterpriseAuthenticationResult
    ): EnterpriseAuthenticationResult = mutex.withLock {
        mutableState.update {
            it.copy(authenticating = true, errorMessage = null, message = "Authenticating enterprise identity")
        }
        val result = runCatching { operation() }
            .getOrElse { EnterpriseAuthenticationResult.Unavailable(it.message ?: "Identity provider failed") }
        mutableState.value = when (result) {
            is EnterpriseAuthenticationResult.Authenticated -> EnterpriseSessionState(
                identityStatus = gateway.status,
                principal = result.principal,
                authenticating = false,
                message = "Signed in as ${result.principal.displayName}"
            )
            is EnterpriseAuthenticationResult.Rejected -> EnterpriseSessionState(
                identityStatus = gateway.status,
                principal = null,
                authenticating = false,
                message = "Enterprise sign-in rejected",
                errorMessage = result.reason
            )
            is EnterpriseAuthenticationResult.Unavailable -> EnterpriseSessionState(
                identityStatus = gateway.status,
                principal = null,
                authenticating = false,
                message = "Enterprise identity provider unavailable",
                errorMessage = result.reason
            )
        }
        result
    }
}
