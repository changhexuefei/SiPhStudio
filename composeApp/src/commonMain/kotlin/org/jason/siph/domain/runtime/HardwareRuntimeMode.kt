package org.jason.siph.domain.runtime

/**
 * 应用运行模式。
 *
 * Demo 使用离线模拟设备和明确标记的 Demo 安全参数。
 * Real 面向真实设备；在加载并确认设备专用安全配置之前，运动互锁保持未就绪。
 */
enum class HardwareRuntimeMode(
    val text: String
) {
    Demo("Demo"),
    Real("Real");

    companion object {
        fun parse(value: String?): HardwareRuntimeMode {
            return when (value?.trim()?.lowercase()) {
                "real", "hardware", "production" -> Real
                else -> Demo
            }
        }
    }
}
