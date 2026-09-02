package org.jason.siph.ui.state

import kotlinx.coroutines.CoroutineScope
import org.jason.siph.domain.coupling.AdaptiveCouplingRunner
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.safety.MotionSafetyConfig
import org.jason.siph.domain.safety.MotionSafetyPlanner
import org.jason.siph.domain.safety.SafetyCheckedOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPositioner
import org.jason.siph.domain.simulation.DemoOpticalPowerMeter
import kotlin.time.TimeSource

/**
 * 创建启用统一运动安全层的离线 Demo Store。
 *
 * 真实 PI 设备接入时应复用相同的 [SafetyCheckedOpticalPositioner]，
 * 但必须用设备和夹具确认后的软限位替换 [MotionSafetyConfig.demoDefault]。
 */
fun createSafeDemoCouplingToolStore(
    scope: CoroutineScope,
    safetyConfig: MotionSafetyConfig = MotionSafetyConfig.demoDefault()
): CouplingToolStore {
    val clockOrigin = TimeSource.Monotonic.markNow()
    val clock = { clockOrigin.elapsedNow().inWholeMilliseconds }

    val rawPositioner = DemoOpticalPositioner(
        safePose = OpticalPose.ZERO
    )
    val safePositioner = SafetyCheckedOpticalPositioner(
        delegate = rawPositioner,
        planner = MotionSafetyPlanner(safetyConfig),
        safePoseProvider = { OpticalPose.ZERO }
    )
    val powerMeter = DemoOpticalPowerMeter(
        poseProvider = { safePositioner.currentPose() }
    )
    val runner = AdaptiveCouplingRunner(
        positioner = safePositioner,
        powerMeter = powerMeter,
        timeProvider = clock
    )

    return CouplingToolStore(
        scope = scope,
        positioner = safePositioner,
        powerMeter = powerMeter,
        runner = runner,
        nowMs = clock
    )
}
