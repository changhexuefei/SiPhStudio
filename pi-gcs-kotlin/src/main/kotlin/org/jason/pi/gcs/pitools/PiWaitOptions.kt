package org.jason.pi.gcs.pitools

/**
 * PI 等待配置。
 *
 * 用于：
 * - waitOnTarget()
 * - moveAndWait()
 * - startup()
 * - reference 后等待
 *
 * 单位：
 * - timeoutMs: ms
 * - pollDelayMs: ms
 * - preDelayMs: ms
 * - postDelayMs: ms
 */
data class PiWaitOptions(

    /**
     * 开始轮询前的等待时间。
     *
     * 有些控制器刚收到 MOV/MVR 后，ONT? 状态不会立刻变化。
     * 如果你发现刚移动就误判为 on target，可以设置一个 preDelay。
     */
    val preDelayMs: Long = 0L,

    /**
     * 等待到位最大超时时间。
     */
    val timeoutMs: Long = 10_000L,

    /**
     * ONT? 轮询间隔。
     */
    val pollDelayMs: Long = 100L,

    /**
     * 到位后额外等待时间。
     *
     * 对硅光耦光有用，因为机械到位后，光功率读数可能还需要一点稳定时间。
     */
    val postDelayMs: Long = 0L
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

        /**
         * 硅光耦光默认等待配置。
         */
        val Default: PiWaitOptions = PiWaitOptions(
            preDelayMs = 0L,
            timeoutMs = 10_000L,
            pollDelayMs = 100L,
            postDelayMs = 0L
        )

        /**
         * 慢速 / 大行程移动等待配置。
         */
        val LongMove: PiWaitOptions = PiWaitOptions(
            preDelayMs = 100L,
            timeoutMs = 60_000L,
            pollDelayMs = 200L,
            postDelayMs = 200L
        )

        /**
         * 精细耦光小步进等待配置。
         */
        val FineCoupling: PiWaitOptions = PiWaitOptions(
            preDelayMs = 0L,
            timeoutMs = 5_000L,
            pollDelayMs = 50L,
            postDelayMs = 30L
        )

        /**
         * 仿真 / Demo 模式等待配置。
         */
        val Simulation: PiWaitOptions = PiWaitOptions(
            preDelayMs = 0L,
            timeoutMs = 2_000L,
            pollDelayMs = 20L,
            postDelayMs = 0L
        )
    }
}