package org.jason.pi.gcs.pitools

/**
 * PI 等待配置。
 *
 * 除等待时间外，还定义超时或协程取消时是否主动发送 STP。
 */
data class PiWaitOptions(
    val preDelayMs: Long = 0L,
    val timeoutMs: Long = 10_000L,
    val pollDelayMs: Long = 100L,
    val postDelayMs: Long = 0L,
    val stopOnTimeout: Boolean = true,
    val stopOnCancellation: Boolean = true
) {

    init {
        require(preDelayMs >= 0L) {
            "preDelayMs 不能小于 0，当前值: $preDelayMs"
        }
        require(timeoutMs > 0L) {
            "timeoutMs 必须大于 0，当前值: $timeoutMs"
        }
        require(pollDelayMs > 0L) {
            "pollDelayMs 必须大于 0，当前值: $pollDelayMs"
        }
        require(postDelayMs >= 0L) {
            "postDelayMs 不能小于 0，当前值: $postDelayMs"
        }
    }

    companion object {

        val Default: PiWaitOptions = PiWaitOptions(
            timeoutMs = 10_000L,
            pollDelayMs = 100L,
            stopOnTimeout = true,
            stopOnCancellation = true
        )

        val LongMove: PiWaitOptions = PiWaitOptions(
            preDelayMs = 100L,
            timeoutMs = 60_000L,
            pollDelayMs = 200L,
            postDelayMs = 200L,
            stopOnTimeout = true,
            stopOnCancellation = true
        )

        val FineCoupling: PiWaitOptions = PiWaitOptions(
            timeoutMs = 5_000L,
            pollDelayMs = 50L,
            postDelayMs = 30L,
            stopOnTimeout = true,
            stopOnCancellation = true
        )

        val Simulation: PiWaitOptions = PiWaitOptions(
            timeoutMs = 2_000L,
            pollDelayMs = 20L,
            stopOnTimeout = false,
            stopOnCancellation = false
        )
    }
}
