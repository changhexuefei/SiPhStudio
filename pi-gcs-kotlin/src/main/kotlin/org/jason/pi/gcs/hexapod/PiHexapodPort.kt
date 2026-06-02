package org.jason.pi.gcs.hexapod

/**
 * PI 六轴业务接口。
 *
 * 上层 SiPhTools-Kotlin 不建议直接调用 GcsDevice。
 * 应该通过这个接口控制六轴。
 */
interface PiHexapodPort : AutoCloseable {

    suspend fun connect()

    suspend fun disconnect()

    suspend fun identify(): String

    suspend fun startup(
        reference: Boolean = false
    )

    suspend fun moveTo(
        pose: PiHexapodPose,
        wait: Boolean = true
    )

    suspend fun moveBy(
        delta: PiHexapodDelta,
        wait: Boolean = true
    )

    suspend fun currentPose(): PiHexapodPose

    suspend fun waitOnTarget(
        timeoutMs: Long = 10_000
    )

    suspend fun stop()

    suspend fun moveToSafePose()
}