package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import org.jason.siph.domain.autonomy.AutonomyRepositoryBundle
import org.jason.siph.domain.autonomy.CalibrationProfileRepository
import org.jason.siph.domain.autonomy.CalibrationVerificationRepository
import org.jason.siph.domain.autonomy.DefaultSiPhWorkflowRunner
import org.jason.siph.domain.autonomy.DriftBaselineRepository
import org.jason.siph.domain.autonomy.DriftEvaluator
import org.jason.siph.domain.autonomy.InMemoryAutonomyRepository
import org.jason.siph.domain.autonomy.MeasurementPositionRepository
import org.jason.siph.domain.autonomy.MeasurementPositionTrainer
import org.jason.siph.domain.autonomy.MeasurementRecordRepository
import org.jason.siph.domain.autonomy.OpticalAlignmentVerifier
import org.jason.siph.domain.autonomy.ProbeTrackingPort
import org.jason.siph.domain.autonomy.SiPhWorkflowRunner
import org.jason.siph.domain.autonomy.UnavailableProbeTrackingPort
import org.jason.siph.domain.autonomy.UnavailableVisionAlignmentPort
import org.jason.siph.domain.autonomy.UnavailableWaferStagePort
import org.jason.siph.domain.autonomy.VisionAlignmentPort
import org.jason.siph.domain.autonomy.WaferDefinitionRepository
import org.jason.siph.domain.autonomy.WaferStagePort
import org.jason.siph.domain.autonomy.WorkflowCheckpointRepository
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

/** 可由 JVM/真实设备模块按能力逐项覆盖的端口和持久化服务集合。 */
data class RealHardwarePorts(
    val positioner: OpticalPositionerPort? = null,
    val powerMeter: OpticalPowerMeterPort? = null,
    val visionAlignment: VisionAlignmentPort? = null,
    val waferStage: WaferStagePort? = null,
    val probeTracking: ProbeTrackingPort? = null,
    val calibrationProfiles: CalibrationProfileRepository? = null,
    val autonomyRepositories: AutonomyRepositoryBundle? = null
)

/**
 * SiPh Studio 的公共 Koin 模块。
 *
 * Real 模式没有传入对应硬件端口时，使用明确失败的未配置实现，绝不回退到 Demo。
 * 工作流数据仓储与硬件模式无关，Desktop 可在 Demo/Real 下共同使用 JVM JSON 实现。
 */
fun createSiPhAppModule(
    scope: CoroutineScope,
    runtimeMode: HardwareRuntimeMode,
    realHardwarePorts: RealHardwarePorts? = null
): Module {
    val monotonicOrigin = TimeSource.Monotonic.markNow()
    val monotonicClock = { monotonicOrigin.elapsedNow().inWholeMilliseconds }
    val epochClock = { Clock.System.now().toEpochMilliseconds() }
    val autonomyRepository = realHardwarePorts?.autonomyRepositories
        ?: InMemoryAutonomyRepository()

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
                HardwareRuntimeMode.Demo -> DemoOpticalPositioner(safePose = OpticalPose.ZERO)
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.positioner ?: UnavailableRealPositioner()
            }

            SafetyCheckedOpticalPositioner(
                delegate = rawPositioner,
                planner = get(),
                safePoseProvider = if (runtimeMode == HardwareRuntimeMode.Demo) {
                    { OpticalPose.ZERO }
                } else {
                    null
                }
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

        single<AutonomyRepositoryBundle> { autonomyRepository }
        single<CalibrationProfileRepository> {
            realHardwarePorts?.calibrationProfiles ?: get<AutonomyRepositoryBundle>()
        }
        single<MeasurementPositionRepository> { get<AutonomyRepositoryBundle>() }
        single<WaferDefinitionRepository> { get<AutonomyRepositoryBundle>() }
        single<CalibrationVerificationRepository> { get<AutonomyRepositoryBundle>() }
        single<DriftBaselineRepository> { get<AutonomyRepositoryBundle>() }
        single<WorkflowCheckpointRepository> { get<AutonomyRepositoryBundle>() }
        single<MeasurementRecordRepository> { get<AutonomyRepositoryBundle>() }

        single<CouplingRunner> {
            AdaptiveCouplingRunner(
                positioner = get(),
                powerMeter = get(),
                timeProvider = monotonicClock
            )
        }

        single {
            MeasurementPositionTrainer(
                positioner = get(),
                powerMeter = get(),
                positions = get(),
                nowEpochMs = epochClock
            )
        }

        single {
            OpticalAlignmentVerifier(
                positioner = get(),
                powerMeter = get(),
                nowEpochMs = epochClock
            )
        }

        single { DriftEvaluator(nowEpochMs = epochClock) }

        single<SiPhWorkflowRunner> {
            DefaultSiPhWorkflowRunner(
                positioner = get(),
                powerMeter = get(),
                couplingRunner = get(),
                calibrationProfiles = get(),
                positions = get(),
                baselines = get(),
                checkpoints = get(),
                records = get(),
                verifier = get(),
                driftEvaluator = get(),
                runtimeModeProvider = { runtimeMode.name },
                nowEpochMs = epochClock
            )
        }

        single {
            CouplingToolStore(
                scope = scope,
                positioner = get(),
                powerMeter = get(),
                runner = get(),
                nowMs = monotonicClock
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
