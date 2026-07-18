package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.coupling.AdaptiveCouplingRunner
import org.jason.siph.domain.coupling.CouplingRunner
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.domain.runtime.UnavailableRealPositioner
import org.jason.siph.domain.runtime.UnavailableRealPowerMeter
import org.jason.siph.domain.safety.MotionSafetyConfig
import org.jason.siph.domain.safety.MotionSafetyPlanner
import org.jason.siph.domain.safety.SafetyCheckedOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPowerMeter
import org.jason.siph.ui.safety.MotionSafetySettingsStore
import org.jason.siph.ui.state.CouplingToolStore
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.TimeSource

/** 可由真实设备模块覆盖的端口集合。 */
data class RealHardwarePorts(
    val positioner: OpticalPositionerPort,
    val powerMeter: OpticalPowerMeterPort
)

/**
 * SiPh Studio 的公共 Koin 模块。
 *
 * Real 模式没有传入 [realHardwarePorts] 时，使用明确失败的占位实现，绝不回退到 Demo。
 */
fun createSiPhAppModule(
    scope: CoroutineScope,
    runtimeMode: HardwareRuntimeMode,
    realHardwarePorts: RealHardwarePorts? = null
): Module {
    val clockOrigin = TimeSource.Monotonic.markNow()
    val clock = { clockOrigin.elapsedNow().inWholeMilliseconds }

    return module {
        single { runtimeMode }

        single {
            MotionSafetyPlanner(
                initialConfig = if (runtimeMode == HardwareRuntimeMode.Demo) {
                    MotionSafetyConfig.demoDefault()
                } else {
                    null
                }
            )
        }

        single {
            MotionSafetySettingsStore(
                runtimeMode = get(),
                planner = get()
            )
        }

        single<OpticalPositionerPort> {
            val rawPositioner = when (runtimeMode) {
                HardwareRuntimeMode.Demo -> DemoOpticalPositioner(
                    safePose = OpticalPose.ZERO
                )

                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.positioner ?: UnavailableRealPositioner()
            }

            SafetyCheckedOpticalPositioner(
                delegate = rawPositioner,
                planner = get(),
                safePoseProvider = { OpticalPose.ZERO }
            )
        }

        single<OpticalPowerMeterPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> DemoOpticalPowerMeter(
                    poseProvider = { get<OpticalPositionerPort>().currentPose() }
                )

                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.powerMeter ?: UnavailableRealPowerMeter()
            }
        }

        single<CouplingRunner> {
            AdaptiveCouplingRunner(
                positioner = get(),
                powerMeter = get(),
                timeProvider = clock
            )
        }

        single {
            CouplingToolStore(
                scope = scope,
                positioner = get(),
                powerMeter = get(),
                runner = get(),
                nowMs = clock
            )
        }
    }
}
