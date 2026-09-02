package org.jason.siph.di

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.autonomy.AutonomyRepositoryBundle
import org.jason.siph.domain.autonomy.CalibrationProfileRepository
import org.jason.siph.domain.autonomy.CalibrationProfileVerifier
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
import org.jason.siph.domain.inspection.CameraAcquisitionPort
import org.jason.siph.domain.inspection.CompositeVisionFeatureDetector
import org.jason.siph.domain.inspection.DefaultInspectionCalibrationRunner
import org.jason.siph.domain.inspection.InMemoryInspectionCalibrationRepository
import org.jason.siph.domain.inspection.InspectionCalibrationRepository
import org.jason.siph.domain.inspection.InspectionCalibrationRunner
import org.jason.siph.domain.inspection.InspectionVisionAlignmentAdapter
import org.jason.siph.domain.inspection.PivotCalibrationService
import org.jason.siph.domain.inspection.ProbeHeightTrainer
import org.jason.siph.domain.inspection.SimulatedCameraAcquisitionPort
import org.jason.siph.domain.inspection.SimulatedInspectionEnvironment
import org.jason.siph.domain.inspection.SimulatedZDisplacementSensorPort
import org.jason.siph.domain.inspection.UnavailableCameraAcquisitionPort
import org.jason.siph.domain.inspection.UnavailableZDisplacementSensorPort
import org.jason.siph.domain.inspection.VisionFeatureDetector
import org.jason.siph.domain.inspection.VisualPreAlignmentService
import org.jason.siph.domain.inspection.ZDisplacementSensorPort
import org.jason.siph.domain.inspection.ZSensorProbeTrackingAdapter
import org.jason.siph.domain.oo.DefaultOoMeasurementRunner
import org.jason.siph.domain.oo.InMemoryOoMeasurementRepository
import org.jason.siph.domain.oo.OoAlignmentPort
import org.jason.siph.domain.oo.OoMeasurementRepository
import org.jason.siph.domain.oo.OoMeasurementRunner
import org.jason.siph.domain.oo.OoOpticalPowerMeterPort
import org.jason.siph.domain.oo.SimulatedOoAlignmentPort
import org.jason.siph.domain.oo.SimulatedOoEnvironment
import org.jason.siph.domain.oo.SimulatedOoPowerMeter
import org.jason.siph.domain.oo.SimulatedTemperatureController
import org.jason.siph.domain.oo.SimulatedTunableLaser
import org.jason.siph.domain.oo.SimulatedWaferProber
import org.jason.siph.domain.oo.TemperatureControllerPort
import org.jason.siph.domain.oo.TunableLaserPort
import org.jason.siph.domain.oo.UnavailableOoAlignmentPort
import org.jason.siph.domain.oo.UnavailableOoPowerMeter
import org.jason.siph.domain.oo.UnavailableTemperatureController
import org.jason.siph.domain.oo.UnavailableTunableLaser
import org.jason.siph.domain.oo.UnavailableWaferProber
import org.jason.siph.domain.oo.WaferProberPort
import org.jason.siph.domain.oo.WaferTraversalPlanner
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
import org.jason.siph.ui.inspection.InspectionCalibrationStore
import org.jason.siph.ui.oo.OoMeasurementStore
import org.jason.siph.ui.safety.MotionSafetySettingsStore
import org.jason.siph.ui.state.CouplingToolStore
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.TimeSource

/** 可由 JVM/真实设备模块按能力逐项覆盖的端口和持久化服务集合。 */
data class RealHardwarePorts(
    val positioner: OpticalPositionerPort? = null,
    val powerMeter: OpticalPowerMeterPort? = null,
    val visionAlignment: VisionAlignmentPort? = null,
    val waferStage: WaferStagePort? = null,
    val probeTracking: ProbeTrackingPort? = null,
    val calibrationProfiles: CalibrationProfileRepository? = null,
    val autonomyRepositories: AutonomyRepositoryBundle? = null,
    val ooPowerMeter: OoOpticalPowerMeterPort? = null,
    val tunableLaser: TunableLaserPort? = null,
    val waferProber: WaferProberPort? = null,
    val temperatureController: TemperatureControllerPort? = null,
    val ooAlignment: OoAlignmentPort? = null,
    val ooMeasurements: OoMeasurementRepository? = null,
    val inspectionCamera: CameraAcquisitionPort? = null,
    val zDisplacementSensor: ZDisplacementSensorPort? = null,
    val inspectionCalibrations: InspectionCalibrationRepository? = null
)

/**
 * SiPh Studio 的公共 Koin 模块。
 *
 * Real 模式没有传入对应硬件端口时，使用明确失败的未配置实现，绝不回退到 Demo。
 * 第一、第二和第三阶段使用独立能力接口，但复用同一安全位置器与温控器。
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
    val ooRepository = realHardwarePorts?.ooMeasurements
        ?: InMemoryOoMeasurementRepository()
    val inspectionRepository = realHardwarePorts?.inspectionCalibrations
        ?: InMemoryInspectionCalibrationRepository()

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

        if (runtimeMode == HardwareRuntimeMode.Demo) {
            single { SimulatedOoEnvironment() }
        }

        single<OoMeasurementRepository> { ooRepository }
        single<TunableLaserPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedTunableLaser(get())
                HardwareRuntimeMode.Real -> realHardwarePorts?.tunableLaser ?: UnavailableTunableLaser()
            }
        }
        single<OoOpticalPowerMeterPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedOoPowerMeter(get())
                HardwareRuntimeMode.Real -> realHardwarePorts?.ooPowerMeter ?: UnavailableOoPowerMeter()
            }
        }
        single<WaferProberPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedWaferProber(get())
                HardwareRuntimeMode.Real -> realHardwarePorts?.waferProber ?: UnavailableWaferProber()
            }
        }
        single<TemperatureControllerPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedTemperatureController(get())
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.temperatureController ?: UnavailableTemperatureController()
            }
        }
        single<OoAlignmentPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedOoAlignmentPort(get())
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.ooAlignment ?: UnavailableOoAlignmentPort()
            }
        }

        if (runtimeMode == HardwareRuntimeMode.Demo) {
            single {
                SimulatedInspectionEnvironment(
                    poseProvider = { get<OpticalPositionerPort>().currentPose() },
                    temperatureProvider = { get<TemperatureControllerPort>().readSnapshot().processValueC }
                )
            }
        }
        single<InspectionCalibrationRepository> { inspectionRepository }
        single<VisionFeatureDetector> { CompositeVisionFeatureDetector() }
        single<CameraAcquisitionPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedCameraAcquisitionPort(
                    environment = get(),
                    nowEpochMs = epochClock
                )
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.inspectionCamera ?: UnavailableCameraAcquisitionPort()
            }
        }
        single<ZDisplacementSensorPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> SimulatedZDisplacementSensorPort(
                    environment = get(),
                    nowEpochMs = epochClock
                )
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.zDisplacementSensor ?: UnavailableZDisplacementSensorPort()
            }
        }

        single<VisionAlignmentPort> {
            when (runtimeMode) {
                HardwareRuntimeMode.Demo -> InspectionVisionAlignmentAdapter(
                    camera = get(),
                    detector = get(),
                    calibrations = get()
                )
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
                HardwareRuntimeMode.Demo -> ZSensorProbeTrackingAdapter(get())
                HardwareRuntimeMode.Real ->
                    realHardwarePorts?.probeTracking ?: UnavailableProbeTrackingPort()
            }
        }

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
            CalibrationProfileVerifier(
                positioner = get(),
                profiles = get(),
                verifications = get(),
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
                profiles = get(),
                positions = get(),
                wafers = get(),
                checkpoints = get(),
                records = get(),
                trainer = get(),
                calibrationVerifier = get(),
                workflowRunner = get(),
                nowEpochMs = epochClock
            )
        }

        single { WaferTraversalPlanner() }
        single<OoMeasurementRunner> {
            DefaultOoMeasurementRunner(
                laser = get(),
                powerMeter = get(),
                prober = get(),
                temperatureController = get(),
                alignment = get(),
                repository = get(),
                traversalPlanner = get(),
                nowEpochMs = epochClock
            )
        }
        single {
            OoMeasurementStore(
                scope = scope,
                runner = get(),
                repository = get(),
                wafers = get(),
                laser = get(),
                powerMeter = get(),
                prober = get(),
                temperature = get(),
                nowEpochMs = epochClock
            )
        }

        single {
            VisualPreAlignmentService(
                camera = get(),
                detector = get(),
                positioner = get(),
                repository = get()
            )
        }
        single {
            ProbeHeightTrainer(
                positioner = get(),
                sensor = get(),
                repository = get(),
                nowEpochMs = epochClock
            )
        }
        single {
            PivotCalibrationService(
                camera = get(),
                detector = get(),
                positioner = get(),
                repository = get(),
                nowEpochMs = epochClock
            )
        }
        single<InspectionCalibrationRunner> {
            DefaultInspectionCalibrationRunner(
                camera = get(),
                zSensor = get(),
                temperatureController = get(),
                positioner = get(),
                preAlignment = get(),
                heightTrainer = get(),
                pivotCalibration = get(),
                repository = get(),
                nowEpochMs = epochClock
            )
        }
        single {
            InspectionCalibrationStore(
                scope = scope,
                runner = get(),
                repository = get(),
                camera = get(),
                zSensor = get(),
                nowEpochMs = epochClock
            )
        }
    }
}
