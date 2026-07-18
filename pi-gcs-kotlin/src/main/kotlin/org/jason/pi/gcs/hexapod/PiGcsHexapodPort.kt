package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiControllerProbeOptions
import org.jason.pi.gcs.core.inspectController
import org.jason.pi.gcs.pitools.PiStartupOptions
import org.jason.pi.gcs.pitools.PiTools
import org.jason.pi.gcs.pitools.PiWaitOptions

/** 基于 PI GCS 的六轴定位器实现。 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    private val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
    private val probeOptions: PiControllerProbeOptions = PiControllerProbeOptions()
) : PiHexapodPort {

    private val mutableConnectionState = MutableStateFlow(
        PiHexapodConnectionState.Disconnected
    )

    val connectionState: StateFlow<PiHexapodConnectionState> =
        mutableConnectionState.asStateFlow()

    init {
        require(axes.isNotEmpty()) { "PI Hexapod axes 不能为空" }
        require(axes.size == axes.distinct().size) {
            "PI Hexapod axes 不能重复: $axes"
        }
    }

    override suspend fun connect() {
        if (device.isOpen && connectionState.value.isConnected) return

        mutableConnectionState.value = PiHexapodConnectionState(
            phase = PiHexapodConnectionPhase.Connecting
        )

        try {
            if (!device.isOpen) {
                device.connect()
            }

            val profile = device.inspectController(probeOptions)
            validateDiscoveredAxes(profile.knownHexapodAxes)

            mutableConnectionState.value = PiHexapodConnectionState(
                phase = PiHexapodConnectionPhase.Connected,
                profile = profile
            )
        } catch (error: Throwable) {
            runCatching { device.close() }
            mutableConnectionState.value = PiHexapodConnectionState(
                phase = PiHexapodConnectionPhase.Failed,
                errorMessage = error.message ?: error::class.simpleName
            )
            throw error
        }
    }

    override suspend fun disconnect() {
        close()
    }

    override suspend fun identify(): String {
        ensureConnected()
        return connectionState.value.profile?.info?.idn
            ?: device.qIDN()
    }

    override suspend fun startup(reference: Boolean) {
        ensureConnected()
        PiTools.startup(
            device = device,
            axes = axes,
            enableServo = true,
            reference = reference
        )
    }

    /** 使用完整 startup 配置，包括 FRF/FNL/FPL 参考方式。 */
    suspend fun startup(options: PiStartupOptions) {
        ensureConnected()
        require(options.axes.all { it in axes }) {
            "startup options 包含当前 Hexapod 未配置的轴: ${options.axes - axes.toSet()}"
        }
        PiTools.startup(
            device = device,
            options = options
        )
    }

    override suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean
    ) {
        ensureConnected()

        val commandValues = unitConfig.toCommandValues(pose)
            .filterKeys { it in axes }

        check(commandValues.isNotEmpty()) {
            "目标位姿没有可发送到控制器的轴: $pose"
        }

        device.moveAbsolute(commandValues)

        if (wait) {
            waitOnTarget()
        }
    }

    override suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean
    ) {
        ensureConnected()

        val commandDeltas = unitConfig.toCommandDeltas(delta)
            .filterKeys { it in axes }
            .filterValues { it != 0.0 }

        if (commandDeltas.isEmpty()) return

        device.moveRelative(commandDeltas)

        if (wait) {
            waitOnTarget()
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()
        return unitConfig.fromCommandValues(device.qPOS(axes))
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        ensureConnected()
        PiTools.waitOnTarget(
            device = device,
            axes = axes,
            options = PiWaitOptions(
                timeoutMs = timeoutMs,
                pollDelayMs = 100L,
                stopOnTimeout = true,
                stopOnCancellation = true
            )
        )
    }

    override suspend fun stop() {
        ensureConnected()
        device.stopAll()
    }

    override suspend fun moveToSafePose() {
        moveTo(
            pose = safePose,
            wait = true
        )
    }

    /** 返回控制器命令单位下的行程范围。 */
    suspend fun queryCommandTravelRange(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        ensureConnected()
        return PiTools.queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    override fun close() {
        device.close()
        mutableConnectionState.value = PiHexapodConnectionState.Disconnected
    }

    private fun ensureConnected() {
        check(device.isOpen && connectionState.value.isConnected) {
            "PI GCS Hexapod 尚未连接"
        }
    }

    private fun validateDiscoveredAxes(discoveredAxes: List<PiAxis>) {
        if (discoveredAxes.isEmpty()) return

        val missingAxes = axes.filterNot { it in discoveredAxes }
        check(missingAxes.isEmpty()) {
            "PI 控制器轴列表与 Hexapod 配置不匹配，缺少轴: $missingAxes, " +
                "discovered=$discoveredAxes"
        }
    }
}
