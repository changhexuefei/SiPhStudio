package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.DistributedProductionCoordinator
import org.jason.siph.domain.production.EnterpriseIdentityGateway
import org.jason.siph.domain.production.InMemoryDistributedProductionCoordinator
import org.jason.siph.domain.production.InMemoryProductionRepository
import org.jason.siph.domain.production.InMemoryRemoteAuditSink
import org.jason.siph.domain.production.LocalTestEnterpriseIdentityGateway
import org.jason.siph.domain.production.LocalTestUser
import org.jason.siph.domain.production.MesGateway
import org.jason.siph.domain.production.MockMesGateway
import org.jason.siph.domain.production.NotConfiguredMesGateway
import org.jason.siph.domain.production.NotConfiguredRemoteAuditSink
import org.jason.siph.domain.production.PortableAuditHasher
import org.jason.siph.domain.production.ProductionRepository
import org.jason.siph.domain.production.ProductionRole
import org.jason.siph.domain.production.ProductionWorkerRegistration
import org.jason.siph.domain.production.RemoteAuditSink
import org.jason.siph.domain.production.UnavailableDistributedProductionCoordinator
import org.jason.siph.domain.production.UnavailableEnterpriseIdentityGateway
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import kotlin.time.Clock

actual fun createPlatformProductionRepository(): ProductionRepository = InMemoryProductionRepository()

actual fun createPlatformAuditHasher(): AuditHasher = PortableAuditHasher()

actual fun createPlatformDistributedProductionCoordinator(
    runtimeMode: HardwareRuntimeMode
): DistributedProductionCoordinator = when (runtimeMode) {
    HardwareRuntimeMode.Demo -> InMemoryDistributedProductionCoordinator()
    HardwareRuntimeMode.Real -> UnavailableDistributedProductionCoordinator(
        "PostgreSQL distributed production coordination is only available on the JVM target"
    )
}

actual fun createPlatformMesGateway(runtimeMode: HardwareRuntimeMode): MesGateway = when (runtimeMode) {
    HardwareRuntimeMode.Demo -> MockMesGateway()
    HardwareRuntimeMode.Real -> NotConfiguredMesGateway("Real MES delivery is only available on the JVM target")
}

actual fun createPlatformRemoteAuditSink(runtimeMode: HardwareRuntimeMode): RemoteAuditSink = when (runtimeMode) {
    HardwareRuntimeMode.Demo -> InMemoryRemoteAuditSink()
    HardwareRuntimeMode.Real -> NotConfiguredRemoteAuditSink("Remote WORM audit is only available on the JVM target")
}

actual fun createPlatformEnterpriseIdentityGateway(
    runtimeMode: HardwareRuntimeMode
): EnterpriseIdentityGateway = when (runtimeMode) {
    HardwareRuntimeMode.Demo -> LocalTestEnterpriseIdentityGateway(
        users = mapOf(
            "operator" to LocalTestUser(
                username = "operator",
                displayName = "Web Demo Operator",
                password = "operator-demo".toCharArray(),
                testToken = "demo-operator-token",
                roles = setOf(ProductionRole.Operator)
            )
        ),
        nowEpochMs = { Clock.System.now().toEpochMilliseconds() }
    )
    HardwareRuntimeMode.Real -> UnavailableEnterpriseIdentityGateway(
        "Enterprise LDAP/OIDC authentication is only available on the JVM target"
    )
}

actual fun createPlatformWorkerRegistration(
    runtimeMode: HardwareRuntimeMode,
    nowEpochMs: Long
): ProductionWorkerRegistration? = null
