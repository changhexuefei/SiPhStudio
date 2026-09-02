package org.jason.siph.domain.production

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnterpriseSessionServiceTest {

    @Test
    fun sessionCreatesProductionActorAndClearsCredentials() = runBlocking {
        val gateway = LocalTestEnterpriseIdentityGateway(
            users = mapOf(
                "operator" to LocalTestUser(
                    username = "operator",
                    displayName = "Test Operator",
                    password = "correct-password".toCharArray(),
                    testToken = "operator-token",
                    roles = setOf(ProductionRole.Operator, ProductionRole.QualityEngineer)
                )
            ),
            nowEpochMs = { 1_000L }
        )
        val service = EnterpriseSessionService(gateway)
        val suppliedPassword = "correct-password".toCharArray()

        val result = service.loginWithPassword("operator", suppliedPassword)

        assertTrue(result is EnterpriseAuthenticationResult.Authenticated)
        assertTrue(suppliedPassword.all { it == '\u0000' })
        val actor = assertNotNull(service.state.value.actor)
        assertEquals("local:operator", actor.id)
        assertEquals(setOf(ProductionRole.Operator, ProductionRole.QualityEngineer), actor.roles)

        service.logout()
        assertFalse(service.state.value.authenticated)
    }
}
