package org.jason.siph.domain.production

class UnavailableMesGateway(
    private val reason: String = "MES gateway is not configured"
) : MesGateway {
    override suspend fun submit(event: ProductionOutboxEvent): MesSubmissionResult = error(reason)
}

class UnavailableRemoteAuditSink(
    private val reason: String = "Remote append-only audit sink is not configured"
) : RemoteAuditSink {
    override suspend fun append(event: ProductionOutboxEvent): RemoteAuditReceipt = error(reason)
}
