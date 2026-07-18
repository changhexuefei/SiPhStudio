package org.jason.pi.gcs.hexapod

import org.jason.pi.gcs.core.PiControllerProfile

/** PI 六轴连接阶段。 */
enum class PiHexapodConnectionPhase {
    Disconnected,
    Connecting,
    Connected,
    Failed
}

/**
 * 可直接由 Compose/ViewModel 观察的 PI 六轴连接状态。
 */
data class PiHexapodConnectionState(
    val phase: PiHexapodConnectionPhase = PiHexapodConnectionPhase.Disconnected,
    val profile: PiControllerProfile? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = phase == PiHexapodConnectionPhase.Connected

    val isBusy: Boolean
        get() = phase == PiHexapodConnectionPhase.Connecting

    companion object {
        val Disconnected = PiHexapodConnectionState()
    }
}
