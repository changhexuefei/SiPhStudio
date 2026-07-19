package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiControllerProbeOptions
import org.jason.pi.gcs.core.inspectController
import org.jason.pi.gcs.pitools.PiStartupOptions
import org.jason.pi.gcs.pitools.PiTools
import org.jason.pi.gcs.pitools.PiTravelRange
import org.jason.pi.gcs.pitools.PiWaitOptions

/**
 * 基于 PI GCS 的六轴定位器实现。
 *
 * 真实运动安全不依赖 UI：连接时读取 TMN?/TMX?，所有 MOV/MVR 在本端口内完成
 * 有限值、业务单位和控制器行程检查。上层软件限位仍可作为更严格的工艺保护层。
 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    private val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
    private val probeOptions: PiControllerProbeOptions = PiControllerProbeOptions()
) : PiHexapodPort {

    private val motionMutex = Mutex()
    private val mutableConnectionState = MutableStateFlow(
        PiHexapodConnectionState.Disconnected
    )

    private var businessTravelRange: PiTravelRange? = null

    val connectionState: StateFlow<PiHexapodConnectionState> =
        mutableConnectionState.asStateFlow()

    init {
        require(axes.isNotEmpty()) { "PI Hexapod axes 不能为空" }
        require(axes.size == axes.distinct().size) {
            "PI Hexapod axes 不能重复: $axes"
        }
    }

    override suspend fun connect() {
        motionMutex.withLock {
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

                val ranges = refreshBusinessTravelRangeLocked()
                ranges.requireWithinRangeStrict(
                    pose = safePose,
                    requiredAxes = axes,
                    label = "PI configured safe pose"
                )

                mutableConnectionState.value = PiHexapodConnectionState(
                    phase = PiHexapodConnectionPhase.Connected,
                    profile = profile
                )
            } catch (cancelled: CancellationException) {
                businessTravelRange = null
                runCatching { device.disconnect() }
                mutableConnectionState.value = PiHexapodConnectionState.Disconnected
                throw cancelled
            } catch (error: Throwable) {
                businessTravelRange = null
                runCatching { device.disconnect() }
                mutableConnectionState.value = PiHexapodConnectionState(
                    phase = PiHexapodConnectionPhase.Failed,
                    errorMessage = error.message ?: error::class.simpleName
                )
                throw error
            }
        }
    }

    override suspend fun disconnect() {
        motionMutex.withLock {
            try {
                device.disconnect()
            } finally {
                businessTravelRange = null
                mutableConnectionState.value = PiHexapodConnectionState.Disconnected
            }
        }
    }

    override suspend fun identify(): String {
        ensureConnected()
        return connectionState.value.profile?.info?.idn
            ?: device.qIDN()
    }

    override suspend fun startup(reference: Boolean) {
        motionMutex.withLock {
            ensureConnected()
            PiTools.startup(
                device = device,
                axes = axes,
                enableServo = true,
                reference = reference
            )
            validateStartupStateLocked()
        }
    }

    /** 使用完整 startup 配置，包括 FRF/FNL/FPL 参考方式。 */
    suspend fun startup(options: PiStartupOptions) {
        motionMutex.withLock {
            ensureConnected()
            require(options.axes.all { it in axes }) {
                "startup options 包含当前 Hexapod 未配置的轴: ${options.axes - axes.toSet()}"
            }
            PiTools.startup(
                device = device,
                options = options
            )
            validateStartupStateLocked()
        }
    }

    override suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean
    ) {
        motionMutex.withLock {
            ensureConnected()
            requireBusinessTravelRangeLocked().requireWithinRangeStrict(
                pose = pose,
                requiredAxes = axes,
                label = "PI absolute move target"
            )

            val commandValues = unitConfig.toCommandValues(pose)
                .filterKeys { it in axes }

            check(commandValues.isNotEmpty()) {
                "目标位姿没有可发送到控制器的轴: $pose"
            }
            commandValues.forEach { (axis, value) ->
                require(value.isFinite()) {
                    "PI 轴 ${axis.code} 单位转换后不是有效数值: $value"
                }
            }

            device.moveAbsolute(commandValues)

            if (wait) {
                waitOnTargetLocked(PiWaitOptions.Default.timeoutMs)
                validateActualPoseLocked("PI actual pose after absolute move")
            }
        }
    }

    override suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean
    ) {
        motionMutex.withLock {
            ensureConnected()
            val current = currentPoseLocked()
            requireBusinessTravelRangeLocked().requireMoveWithinRange(
                currentPose = current,
                delta = delta,
                requiredAxes = axes,
                label = "PI relative move target"
            )

            val commandDeltas = unitConfig.toCommandDeltas(delta)
                .filterKeys { it in axes }
                .filterValues { it != 0.0 }

            commandDeltas.forEach { (axis, value) ->
                require(value.isFinite()) {
                    "PI 轴 ${axis.code} 增量单位转换后不是有效数值: $value"
                }
            }

            if (commandDeltas.isEmpty()) return

            device.moveRelative(commandDeltas)

            if (wait) {
                waitOnTargetLocked(PiWaitOptions.Default.timeoutMs)
                validateActualPoseLocked("PI actual pose after relative move")
            }
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()
        return currentPoseLocked().also { pose ->
            requireBusinessTravelRangeLocked().requireWithinRangeStrict(
                pose = pose,
                requiredAxes = axes,
                label = "PI reported pose"
            )
        }
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        motionMutex.withLock {
            ensureConnected()
            waitOnTargetLocked(timeoutMs)
            validateActualPoseLocked("PI actual pose after wait")
        }
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

    /** 返回控制器命令单位下的最新行程范围。 */
    suspend fun queryCommandTravelRange(): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        ensureConnected()
        return PiTools.queryTravelRange(
            device = device,
            axes = axes
        ).toClosedRangeMap()
    }

    /** 返回业务单位下的已验证行程范围：X/Y/Z 为 um，U/V/W 为 deg。 */
    suspend fun queryBusinessTravelRange(
        refresh: Boolean = false
    ): Map<PiAxis, ClosedFloatingPointRange<Double>> {
        return motionMutex.withLock {
            ensureConnected()
            val range = if (refresh || businessTravelRange == null) {
                refreshBusinessTravelRangeLocked()
            } else {
                requireBusinessTravelRangeLocked()
            }
            range.toClosedRangeMap()
        }
    }

    /**
     * 同步兜底关闭。正常协程生命周期应调用 [disconnect]。
     */
    override fun close() {
        try {
            device.close()
        } finally {
            businessTravelRange = null
            mutableConnectionState.value = PiHexapodConnectionState.Disconnected
        }
    }

    private suspend fun validateStartupStateLocked() {
        refreshBusinessTravelRangeLocked()
        validateActualPoseLocked("PI actual pose after startup")
    }

    private suspend fun refreshBusinessTravelRangeLocked(): PiTravelRange {
        val commandRange = PiTools.queryTravelRange(
            device = device,
            axes = axes
        )
        val converted = PiTravelRange.fromCommandRangeMap(
            commandRanges = commandRange.toClosedRangeMap(),
            unitConfig = unitConfig
        )
        converted.requireRangesFor(
            requiredAxes = axes,
            label = "PI controller travel range"
        )
        businessTravelRange = converted
        return converted
    }

    private fun requireBusinessTravelRangeLocked(): PiTravelRange {
        return businessTravelRange
            ?: error("PI controller travel range is not loaded; reconnect before moving")
    }

    private suspend fun currentPoseLocked(): PiHexapodPose {
        return unitConfig.fromCommandValues(device.qPOS(axes))
    }

    private suspend fun validateActualPoseLocked(label: String) {
        val actual = currentPoseLocked()
        try {
            requireBusinessTravelRangeLocked().requireWithinRangeStrict(
                pose = actual,
                requiredAxes = axes,
                label = label
            )
        } catch (error: Throwable) {
            runCatching { device.stopAll() }
            throw error
        }
    }

    private suspend fun waitOnTargetLocked(timeoutMs: Long) {
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
