package org.jason.siph.domain.positioner


/**
 * 光学定位器业务接口。
 *
 * 这个接口不绑定具体厂家。
 *
 * 实现可以是：
 * - PI 六轴
 * - FormFactor Photonics Controller
 * - Demo Positioner
 * - 其他光学定位平台
 */
interface OpticalPositionerPort {

    /**
     * 连接光学定位器。
     */
    suspend fun connect()

    /**
     * 断开光学定位器。
     */
    suspend fun disconnect()

    /**
     * 查询设备识别信息。
     *
     * 例如 PI 的 *IDN? / qIDN 返回值。
     */
    suspend fun identify(): String

    /**
     * 启动 / 初始化。
     *
     * reference 是否执行回零 / 参考操作，需要根据具体设备决定。
     */
    suspend fun startup(
        reference: Boolean = false
    )

    /**
     * 移动到绝对位置。
     */
    suspend fun moveTo(
        pose: OpticalPose,
        wait: Boolean = true
    )

    /**
     * 相对当前位置移动。
     */
    suspend fun moveBy(
        delta: OpticalDelta,
        wait: Boolean = true
    )

    /**
     * 读取当前位置。
     */
    suspend fun currentPose(): OpticalPose

    /**
     * 等待运动到位。
     */
    suspend fun waitOnTarget(
        timeoutMs: Long = 10_000
    )

    /**
     * 停止运动。
     */
    suspend fun stop()

    /**
     * 移动到安全位。
     *
     * 探针台移动前，应先调用这个方法。
     */
    suspend fun moveToSafePose()
}