package org.jason.siph.domain.positioner

/**
 * 光学定位器连接状态。
 *
 * 这里是业务层状态，不绑定具体设备厂家。
 */
enum class OpticalPositionerConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Error
}

/**
 * 光学定位器运动状态。
 */
enum class OpticalPositionerMotionState {
    Unknown,
    Idle,
    Moving,
    OnTarget,
    Stopping,
    Stopped,
    Error
}

/**
 * 光学定位器安全状态。
 *
 * 用于后续和 MPI / FormFactor / 探针台协同。
 */
enum class OpticalPositionerSafetyState {
    Unknown,

    /**
     * 光学头处于安全位，允许探针台移动。
     */
    Safe,

    /**
     * 光学头接近芯片，可以耦光，但不允许探针台移动。
     */
    NearChip,

    /**
     * 存在碰撞风险。
     */
    CollisionRisk,

    /**
     * 设备错误或安全状态不可确认。
     */
    Error
}

/**
 * 光学定位器轴。
 *
 * 对应 PI 六轴：
 * X / Y / Z / U / V / W
 */
enum class OpticalAxis {
    X,
    Y,
    Z,
    U,
    V,
    W;

    val isLinear: Boolean
        get() = this == X || this == Y || this == Z

    val isAngular: Boolean
        get() = this == U || this == V || this == W
}

/**
 * 单个轴的行程范围。
 *
 * 线性轴单位：um
 * 角度轴单位：deg
 */
data class OpticalAxisLimit(
    val axis: OpticalAxis,
    val min: Double,
    val max: Double
) {
    fun contains(
        value: Double
    ): Boolean {
        return value in min..max
    }
}

/**
 * 光学定位器行程限制。
 *
 * 这里统一使用：
 * - X/Y/Z: um
 * - U/V/W: deg
 */
data class OpticalPositionerLimits(
    val limits: Map<OpticalAxis, OpticalAxisLimit> = emptyMap()
) {
    fun limitOf(
        axis: OpticalAxis
    ): OpticalAxisLimit? {
        return limits[axis]
    }

    fun isWithinLimits(
        pose: OpticalPose
    ): Boolean {
        return isAxisWithinLimit(OpticalAxis.X, pose.xUm) &&
                isAxisWithinLimit(OpticalAxis.Y, pose.yUm) &&
                isAxisWithinLimit(OpticalAxis.Z, pose.zUm) &&
                isAxisWithinLimit(OpticalAxis.U, pose.uDeg) &&
                isAxisWithinLimit(OpticalAxis.V, pose.vDeg) &&
                isAxisWithinLimit(OpticalAxis.W, pose.wDeg)
    }

    private fun isAxisWithinLimit(
        axis: OpticalAxis,
        value: Double
    ): Boolean {
        val limit = limits[axis] ?: return true
        return limit.contains(value)
    }
}

/**
 * 光学定位器完整状态。
 *
 * 这个状态可以给 ViewModel / UI 使用。
 */
data class OpticalPositionerState(
    val connectionState: OpticalPositionerConnectionState =
        OpticalPositionerConnectionState.Disconnected,

    val motionState: OpticalPositionerMotionState =
        OpticalPositionerMotionState.Unknown,

    val safetyState: OpticalPositionerSafetyState =
        OpticalPositionerSafetyState.Unknown,

    /**
     * 当前位姿。
     */
    val currentPose: OpticalPose = OpticalPose.ZERO,

    /**
     * 安全位姿。
     *
     * 探针台移动前，应该先移动到 safePose。
     */
    val safePose: OpticalPose = OpticalPose.ZERO,

    /**
     * 初始耦光位姿。
     *
     * 自动耦光时通常从这个位置附近开始螺旋搜索。
     */
    val initialPose: OpticalPose? = null,

    /**
     * 最近一次自动耦光得到的最佳位置。
     */
    val bestPose: OpticalPose? = null,

    /**
     * 行程限制。
     */
    val limits: OpticalPositionerLimits = OpticalPositionerLimits(),

    /**
     * 设备识别信息，例如 PI qIDN 返回值。
     */
    val idn: String? = null,

    /**
     * 最近一次错误。
     */
    val errorMessage: String? = null,

    /**
     * 最后更新时间。
     *
     * commonMain 中不直接调用 System.currentTimeMillis，
     * 由 ViewModel 或 jvmMain 层更新。
     */
    val updatedAtMs: Long = 0L
) {

    val isConnected: Boolean
        get() = connectionState == OpticalPositionerConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState == OpticalPositionerConnectionState.Connecting

    val isMoving: Boolean
        get() = motionState == OpticalPositionerMotionState.Moving ||
                motionState == OpticalPositionerMotionState.Stopping

    val isOnTarget: Boolean
        get() = motionState == OpticalPositionerMotionState.OnTarget

    val hasError: Boolean
        get() = connectionState == OpticalPositionerConnectionState.Error ||
                motionState == OpticalPositionerMotionState.Error ||
                safetyState == OpticalPositionerSafetyState.Error ||
                errorMessage != null

    val isSafeForProberMove: Boolean
        get() = isConnected &&
                !isMoving &&
                safetyState == OpticalPositionerSafetyState.Safe

    val canMove: Boolean
        get() = isConnected &&
                !isMoving &&
                !hasError

    val canJog: Boolean
        get() = canMove &&
                safetyState != OpticalPositionerSafetyState.CollisionRisk

    val canStartCoupling: Boolean
        get() = isConnected &&
                !isMoving &&
                safetyState != OpticalPositionerSafetyState.Error &&
                safetyState != OpticalPositionerSafetyState.CollisionRisk

    fun withConnectionState(
        state: OpticalPositionerConnectionState,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            connectionState = state,
            updatedAtMs = updatedAtMs
        )
    }

    fun withMotionState(
        state: OpticalPositionerMotionState,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            motionState = state,
            updatedAtMs = updatedAtMs
        )
    }

    fun withSafetyState(
        state: OpticalPositionerSafetyState,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            safetyState = state,
            updatedAtMs = updatedAtMs
        )
    }

    fun withPose(
        pose: OpticalPose,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            currentPose = pose,
            updatedAtMs = updatedAtMs
        )
    }

    fun withBestPose(
        pose: OpticalPose?,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            bestPose = pose,
            updatedAtMs = updatedAtMs
        )
    }

    fun withError(
        message: String,
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            connectionState = OpticalPositionerConnectionState.Error,
            motionState = OpticalPositionerMotionState.Error,
            safetyState = OpticalPositionerSafetyState.Error,
            errorMessage = message,
            updatedAtMs = updatedAtMs
        )
    }

    fun clearError(
        updatedAtMs: Long = this.updatedAtMs
    ): OpticalPositionerState {
        return copy(
            errorMessage = null,
            updatedAtMs = updatedAtMs
        )
    }
}