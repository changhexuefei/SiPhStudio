package org.jason.siph.domain.safety

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.positioner.PivotAwareOpticalPositionerPort
import org.jason.siph.domain.positioner.VirtualPivotPoint
import org.jason.siph.domain.positioner.plus

/**
 * 为任意光学定位器增加统一的软件安全检查。
 *
 * Store、手动 Jog 和自动耦光 Runner 应共享同一个实例，确保所有运动都经过相同规则。
 */
class SafetyCheckedOpticalPositioner(
    private val delegate: OpticalPositionerPort,
    val planner: MotionSafetyPlanner,
    private val safePoseProvider: (() -> OpticalPose)? = null
) : PivotAwareOpticalPositionerPort {

    private val motionMutex = Mutex()

    /** 连接本身允许建立通信；任何可能启用运动的 startup/move 都需要互锁就绪。 */
    override suspend fun connect() = delegate.connect()

    override suspend fun disconnect() = delegate.disconnect()

    override suspend fun identify(): String = delegate.identify()

    override suspend fun startup(reference: Boolean) {
        // 必须在调用底层 startup 之前检查，防止绕过 UI 后启用伺服或开始参考动作。
        planner.requireConfigured()
        delegate.startup(reference)
        planner.requireValid(delegate.currentPose())
    }

    override suspend fun moveTo(pose: OpticalPose, wait: Boolean) {
        motionMutex.withLock {
            moveToLocked(pose, wait)
        }
    }

    override suspend fun moveBy(delta: OpticalDelta, wait: Boolean) {
        motionMutex.withLock {
            planner.requireConfigured()
            val current = delegate.currentPose()
            moveToLocked(current + delta, wait)
        }
    }

    override suspend fun moveByAroundPivot(
        delta: OpticalDelta,
        pivot: VirtualPivotPoint,
        wait: Boolean
    ) {
        motionMutex.withLock {
            planner.requireConfigured()
            val pivotDelegate = delegate as? PivotAwareOpticalPositionerPort
                ?: error("The wrapped positioner does not support pivot-aware motion")

            val current = delegate.currentPose()
            val estimatedTarget = current + delta
            planner.requireValid(estimatedTarget)

            // 大角度/大横向枢轴运动无法安全地自动插入 Z 抬升路径，必须由上层拆分。
            check(!planner.requiresProtectedTransfer(current, estimatedTarget)) {
                "Pivot move exceeds protected-transfer threshold; split it into smaller moves"
            }

            pivotDelegate.moveByAroundPivot(delta, pivot, wait)
            if (wait) {
                validateActualPoseOrStop()
            }
        }
    }

    override suspend fun currentPose(): OpticalPose {
        planner.requireConfigured()
        val pose = delegate.currentPose()
        planner.requireValid(pose)
        return pose
    }

    override suspend fun waitOnTarget(timeoutMs: Long) {
        planner.requireConfigured()
        delegate.waitOnTarget(timeoutMs)
        validateActualPoseOrStop()
    }

    /** Stop 始终允许，不依赖互锁状态。 */
    override suspend fun stop() = delegate.stop()

    override suspend fun moveToSafePose() {
        planner.requireConfigured()
        val configuredSafePose = safePoseProvider?.invoke()
        if (configuredSafePose != null) {
            moveTo(configuredSafePose, wait = true)
            return
        }

        motionMutex.withLock {
            delegate.moveToSafePose()
            validateActualPoseOrStop()
        }
    }

    private suspend fun moveToLocked(
        target: OpticalPose,
        wait: Boolean
    ) {
        planner.requireConfigured()
        val current = delegate.currentPose()
        val waypoints = planner.planMove(current, target)

        waypoints.forEachIndexed { index, waypoint ->
            val isFinal = index == waypoints.lastIndex
            // 中间安全路径必须等待到位；只有单段或最后一段保留调用方 wait 语义。
            delegate.moveTo(
                pose = waypoint,
                wait = if (isFinal) wait else true
            )
        }

        if (wait) {
            validateActualPoseOrStop()
        }
    }

    private suspend fun validateActualPoseOrStop() {
        val actual = delegate.currentPose()
        val violations = planner.validate(actual)
        if (violations.isNotEmpty()) {
            runCatching { delegate.stop() }
            throw MotionSafetyException(
                violations = violations,
                message = violations.joinToString(
                    prefix = "Positioner reported an unsafe actual pose: ",
                    separator = "; "
                ) { it.message }
            )
        }
    }
}
