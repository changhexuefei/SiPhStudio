package org.jason.pi.gcs.hexapod

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.pitools.PiStartupOptions
import org.jason.pi.gcs.pitools.PiStartupReferenceMode
import org.jason.pi.gcs.pitools.PiTools
import org.jason.pi.gcs.pitools.PiTravelRange
import org.jason.pi.gcs.pitools.PiWaitOptions

/**
 * 基于 PI GCS 的六轴控制实现。
 *
 * 业务层单位：
 * - X/Y/Z: μm
 * - U/V/W: deg
 *
 * GCS 命令单位由 PiHexapodUnitConfig 决定：
 * - 常见情况 X/Y/Z 为 mm
 * - U/V/W 为 deg
 */
class PiGcsHexapodPort(
    private val device: GcsDevice,
    private val safePose: PiHexapodPose,
    private val unitConfig: PiHexapodUnitConfig = PiHexapodUnitConfig(),
    private val axes: List<PiAxis> = PiAxis.HEXAPOD_AXES,
    private val defaultWaitOptions: PiWaitOptions = PiWaitOptions.Default,
    private val validateTravelRangeBeforeMove: Boolean = true
) : PiHexapodPort {

    private val operationMutex = Mutex()

    private var connected: Boolean = false

    /**
     * 控制器命令单位下的行程范围。
     *
     * 例如：
     * - X/Y/Z: mm
     * - U/V/W: deg
     */
    private var commandTravelRange: PiTravelRange? = null

    init {
        require(axes.isNotEmpty()) {
            "PI Hexapod axes 不能为空"
        }

        require(axes.distinct().size == axes.size) {
            "PI Hexapod axes 存在重复: $axes"
        }
    }

    override suspend fun connect() {
        operationMutex.withLock {
            if (connected) {
                return
            }

            device.connect()
            connected = true
        }
    }

    override suspend fun disconnect() {
        operationMutex.withLock {
            closeInternal()
        }
    }

    override suspend fun identify(): String {
        ensureConnected()
        return device.qIDN()
    }

    override suspend fun startup(
        reference: Boolean
    ) {
        startup(
            options = PiStartupOptions.DefaultForSiPh.copy(
                axes = axes,
                referenceMode = if (reference) {
                    PiStartupReferenceMode.ReferenceAll
                } else {
                    PiStartupReferenceMode.None
                }
            )
        )
    }

    /**
     * 新版 startup。
     *
     * 推荐后续业务层优先调用这个方法，
     * 这样可以控制是否 STP、是否清错误、是否 Reference、等待参数等。
     */
    suspend fun startup(
        options: PiStartupOptions = PiStartupOptions.DefaultForSiPh.copy(
            axes = axes
        )
    ) {
        ensureConnected()

        operationMutex.withLock {
            PiTools.startup(
                device = device,
                options = options
            )

            refreshCommandTravelRangeIfPossible()
        }
    }

    override suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean
    ) {
        ensureConnected()

        operationMutex.withLock {
            val commandValues = unitConfig
                .toCommandValues(pose)
                .filterKeys { it in axes }

            if (commandValues.isEmpty()) {
                return
            }

            if (validateTravelRangeBeforeMove) {
                validateCommandValuesWithinRange(commandValues)
            }

            device.moveAbsolute(commandValues)

            if (wait) {
                waitOnTargetLocked(
                    axesToWait = commandValues.keys.toList(),
                    waitOptions = defaultWaitOptions
                )
            }
        }
    }

    override suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean
    ) {
        ensureConnected()

        operationMutex.withLock {
            val commandDeltas = unitConfig
                .toCommandDeltas(delta)
                .filterKeys { it in axes }
                .filterValues { it != 0.0 }

            if (commandDeltas.isEmpty()) {
                return
            }

            if (validateTravelRangeBeforeMove) {
                val currentCommandPose = device.qPOS(axes)
                val targetCommandValues = currentCommandPose.toMutableMap()

                commandDeltas.forEach { (axis, commandDelta) ->
                    targetCommandValues[axis] =
                        targetCommandValues.getValue(axis) + commandDelta
                }

                validateCommandValuesWithinRange(targetCommandValues)
            }

            device.moveRelative(commandDeltas)

            if (wait) {
                waitOnTargetLocked(
                    axesToWait = commandDeltas.keys.toList(),
                    waitOptions = defaultWaitOptions
                )
            }
        }
    }

    override suspend fun currentPose(): PiHexapodPose {
        ensureConnected()

        return operationMutex.withLock {
            val commandValues = device.qPOS(axes)
            unitConfig.fromCommandValues(commandValues)
        }
    }

    override suspend fun waitOnTarget(
        timeoutMs: Long
    ) {
        ensureConnected()

        operationMutex.withLock {
            waitOnTargetLocked(
                axesToWait = axes,
                waitOptions = defaultWaitOptions.copy(
                    timeoutMs = timeoutMs
                )
            )
        }
    }

    /**
     * 使用 PiWaitOptions 等待到位。
     */
    suspend fun waitOnTarget(
        waitOptions: PiWaitOptions = defaultWaitOptions
    ) {
        ensureConnected()

        operationMutex.withLock {
            waitOnTargetLocked(
                axesToWait = axes,
                waitOptions = waitOptions
            )
        }
    }

    override suspend fun stop() {
        ensureConnected()

        operationMutex.withLock {
            device.stopAll()
        }
    }

    override suspend fun moveToSafePose() {
        moveTo(
            pose = safePose,
            wait = true
        )
    }

    /**
     * 刷新控制器命令单位下的行程范围。
     *
     * 返回值单位：
     * - X/Y/Z: GCS 命令单位，常见为 mm
     * - U/V/W: deg
     */
    suspend fun refreshCommandTravelRange(): PiTravelRange {
        ensureConnected()

        return operationMutex.withLock {
            val range = PiTools.queryTravelRange(
                device = device,
                axes = axes
            )

            commandTravelRange = range
            range
        }
    }

    /**
     * 查询控制器命令单位下的行程范围。
     */
    suspend fun queryCommandTravelRange(): PiTravelRange {
        ensureConnected()

        return operationMutex.withLock {
            PiTools.queryTravelRange(
                device = device,
                axes = axes
            )
        }
    }

    /**
     * 查询业务单位下的行程范围。
     *
     * 返回值单位：
     * - X/Y/Z: μm
     * - U/V/W: deg
     */
    suspend fun queryBusinessTravelRange(): PiTravelRange {
        val commandRange = queryCommandTravelRange()

        val businessMap = unitConfig.fromCommandTravelRanges(
            commandRange.toClosedRangeMap()
        )

        return PiTravelRange.fromClosedRangeMap(businessMap)
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        connected = false
        commandTravelRange = null
        device.close()
    }

    private suspend fun refreshCommandTravelRangeIfPossible() {
        runCatching {
            commandTravelRange = PiTools.queryTravelRange(
                device = device,
                axes = axes
            )
        }
    }

    private suspend fun waitOnTargetLocked(
        axesToWait: List<PiAxis>,
        waitOptions: PiWaitOptions
    ) {
        if (axesToWait.isEmpty()) {
            return
        }

        PiTools.waitOnTarget(
            device = device,
            axes = axesToWait,
            options = waitOptions
        )
    }

    private fun validateCommandValuesWithinRange(
        commandValues: Map<PiAxis, Double>
    ) {
        val range = commandTravelRange ?: return

        val outOfRange = range.outOfRangeAxes(commandValues)

        require(outOfRange.isEmpty()) {
            outOfRange.joinToString(separator = "\n") { it.message }
        }
    }

    private fun ensureConnected() {
        check(connected) {
            "PI GCS Hexapod 尚未连接"
        }
    }
}