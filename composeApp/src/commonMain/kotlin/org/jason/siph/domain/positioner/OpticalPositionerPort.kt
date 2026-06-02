package org.jason.siph.domain.positioner


interface OpticalPositionerPort : AutoCloseable {

    suspend fun connect()

    suspend fun disconnect()

    suspend fun identify(): String

    suspend fun startup(
        reference: Boolean = false
    )

    suspend fun moveTo(
        pose: OpticalPose,
        wait: Boolean = true
    )

    suspend fun moveBy(
        delta: OpticalDelta,
        wait: Boolean = true
    )

    suspend fun currentPose(): OpticalPose

    suspend fun waitOnTarget(
        timeoutMs: Long = 10_000
    )

    suspend fun stop()

    suspend fun moveToSafePose()
}