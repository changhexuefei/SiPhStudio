package org.jason.pi.gcs.core

/**
 * PI 轴参考命令。
 *
 * 具体控制器支持哪一种模式必须以对应控制器和平台手册为准。
 */
enum class PiReferenceCommand(
    val gcsCode: String
) {

    /** Reference using the controller-defined reference switch or sensor. */
    FRF("FRF"),

    /** Move to the negative limit and use it as reference. */
    FNL("FNL"),

    /** Move to the positive limit and use it as reference. */
    FPL("FPL")
}
