package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.autonomy.CalibrationProfileRepository
import org.jason.siph.domain.autonomy.InMemoryCalibrationProfileRepository
import org.jason.siph.domain.autonomy.ProbeTrackingPort
import org.jason.siph.domain.autonomy.UnavailableProbeTrackingPort
import org.jason.siph.domain.autonomy.UnavailableVisionAlignmentPort
import org.jason.siph.domain.autonomy.UnavailableWaferStagePort
import org.jason.siph.domain.autonomy.VisionAlignmentPort
import org.jason.siph.domain.autonomy.WaferStagePort
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
import org.jason.siph.ui.autonomy.AutonomousWorkflowStore
import org.jason.siph.ui.safety.MotionSafetySettingsStore
import org.jason.siph.ui.state.CouplingToolStore
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.TimeSource

/** 可由真实设备模块覆盖的端口集合。 */
data class RealHardwarePorts(
    val positioner: OpticalPositionerPort,
    val powerMeter: OpticalPowerMeterPort,
    val visionAlignment: VisionAlignmentPort? = null,
    val waferStage: WaferStagePort? = null,
    val probeTracking: ProbeTrackingPort? = null,
    val calibrationProfiles: CalibrationProfileRepository? = null
)

/**
 * SiPh Studio 的公共 Koin 模块。
 *
 * Real 模式没有传入对应端口时，使用明确失败的未配置实现，绝不回退到 Demo。
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

        single<VisionAlignmentPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> UnavailableVisionAlignmentPort()
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.visionAlignment ?: UnavailableVisionAlignmentPort()
            }
        }

        single<WaferStagePort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> UnavailableWaferStagePort()
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.waferStage ?: UnavailableWaferStagePort()
            }
        }

        single<ProbeTrackingPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> UnavailableProbeTrackingPort()
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.probeTracking ?: UnavailableProbeTrackingPort()
            }
        }

        single<CalibrationProfileRepository> {
            realHardwarePorts?.calibrationProfiles
                ?: InMemoryCalibrationProfileRepository()
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

        single {
            AutonomousWorkflowStore(
                scope = scope,
                vision = get(),
                waferStage = get(),
                probeTracking = get(),
                profiles = get()
            )
        }
    }
}
