package org.jason.siph.ui.model

import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.KtorTcpGcsTransport

/**
 * Operator-supplied physical PI TCP configuration.
 *
 * Constructing a port does not connect to equipment. The connection lifecycle
 * and operator-facing UI must explicitly request connect() later.
 */
data class PiPhysicalTcpConnectionConfig(
    val host: String,
    val port: Int = 50000
) {
    init {
        require(host.isNotBlank()) { "PI controller host must not be blank" }
        require(host == host.trim()) { "PI controller host must not contain surrounding spaces" }
        require(host.none { it.isWhitespace() || it.isISOControl() }) {
            "PI controller host must not contain whitespace or control characters"
        }
        require(port in 1..65535) { "PI controller TCP port must be 1..65535" }
    }
}

object PiPhysicalTcpConnectionFactory {
    /**
     * Creates a disconnected PI port. Does not use Demo, start servos,
     * reference axes or issue any motion command.
     */
    fun create(config: PiPhysicalTcpConnectionConfig): PiGcsHexapodPort =
        PiGcsHexapodPort(
            GcsDevice(GcsClient(KtorTcpGcsTransport(config.host, config.port))),
            PiHexapodPose.ZERO
        )
}
