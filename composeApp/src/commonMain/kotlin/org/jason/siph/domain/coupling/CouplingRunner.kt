package org.jason.siph.domain.coupling

import kotlinx.coroutines.delay
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 耦光 Runner 接口。
 *
 * 上层 ViewModel / UseCase 调用这个接口即可。
 * 具体实现可以是：
 * - 螺旋找光
 * - Raster Scan
 * - Nelder-Mead
 * - Fiber Array 多 lane 优化
 */
interface CouplingRunner {

    suspend fun run(
        initialPose: OpticalPose,
        config: CouplingConfig,
        onSample: suspend (CouplingSample) -> Unit = {},
        shouldStop: suspend () -> Boolean = { false }
    ): CouplingResult
}

/**
 * 默认螺旋耦光 Runner。
 *
 * 流程：
 * 1. 移动到 initialPose
 * 2. 读取初始功率
 * 3. 按螺旋路径找 first light
 * 4. 找到 first light 后，做 X/Y/Z 精调
 * 5. 可选做 U/V/W 简单角度优化
 * 6. 移动到 bestPose
 * 7. 读取 final power
 */
class SpiralCouplingRunner(
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,

    /**
     * 时间戳提供器。
     *
     * commonMain 不直接调用 System.currentTimeMillis。
     * jvmMain 可以传入 { System.currentTimeMillis() }。
     */
    private val timeProvider: () -> Long = { 0L }
) : CouplingRunner {

    override suspend fun run(
        initialPose: OpticalPose,
        config: CouplingConfig,
        onSample: suspend (CouplingSample) -> Unit,
        shouldStop: suspend () -> Boolean
    ): CouplingResult {
        val startedAtMs = timeProvider()
        val samples = mutableListOf<CouplingSample>()

        var index = 0
        var bestPose = initialPose
        var bestPowerDbm = Double.NEGATIVE_INFINITY
        var firstLightFound = false

        return try {
            positioner.moveTo(
                pose = initialPose,
                wait = true
            )

            settle(config)

            val initialPower = powerMeter.readPowerDbm(
                channel = config.powerMeterChannel
            )

            addSample(
                samples = samples,
                sample = CouplingSample(
                    index = index++,
                    pose = initialPose,
                    powerDbm = initialPower,
                    stage = CouplingStage.Initial,
                    timestampMs = timeProvider()
                ),
                onSample = onSample
            )

            bestPowerDbm = initialPower
            bestPose = initialPose

            if (initialPower >= config.firstLightThresholdDbm) {
                firstLightFound = true
            }

            if (!firstLightFound) {
                val offsets = buildSquareSpiralOffsets(
                    stepUm = config.spiralStepUm,
                    maxRadiusUm = config.maxRadiusUm
                )

                for (offset in offsets) {
                    if (shouldStop()) {
                        return CouplingResult.stopped(
                            bestPose = bestPose,
                            bestPowerDbm = bestPowerDbm,
                            samples = samples,
                            startedAtMs = startedAtMs,
                            finishedAtMs = timeProvider()
                        )
                    }

                    val pose = applySpiralOffset(
                        initialPose = initialPose,
                        offset = offset,
                        plane = config.spiralPlane
                    )

                    positioner.moveTo(
                        pose = pose,
                        wait = true
                    )

                    settle(config)

                    val power = powerMeter.readPowerDbm(
                        channel = config.powerMeterChannel
                    )

                    addSample(
                        samples = samples,
                        sample = CouplingSample(
                            index = index++,
                            pose = pose,
                            powerDbm = power,
                            stage = CouplingStage.SpiralFirstLight,
                            timestampMs = timeProvider()
                        ),
                        onSample = onSample
                    )

                    if (power > bestPowerDbm) {
                        bestPowerDbm = power
                        bestPose = pose
                    }

                    if (power >= config.firstLightThresholdDbm) {
                        firstLightFound = true
                        break
                    }
                }
            }

            if (!firstLightFound) {
                positioner.moveTo(
                    pose = bestPose,
                    wait = true
                )

                settle(config)

                val finalPower = powerMeter.readPowerDbm(
                    channel = config.powerMeterChannel
                )

                return CouplingResult(
                    status = CouplingResultStatus.FirstLightNotFound,
                    bestPose = bestPose,
                    bestPowerDbm = bestPowerDbm,
                    finalPose = bestPose,
                    finalPowerDbm = finalPower,
                    samples = samples,
                    message = "螺旋搜索未找到 first light，bestPower=${round3(bestPowerDbm)} dBm",
                    startedAtMs = startedAtMs,
                    finishedAtMs = timeProvider()
                )
            }

            if (config.enableFineXyz) {
                val fineState = fineOptimizeXyz(
                    startPose = bestPose,
                    startBestPowerDbm = bestPowerDbm,
                    startIndex = index,
                    config = config,
                    samples = samples,
                    onSample = onSample,
                    shouldStop = shouldStop
                )

                if (fineState.stopped) {
                    return CouplingResult.stopped(
                        bestPose = fineState.bestPose,
                        bestPowerDbm = fineState.bestPowerDbm,
                        samples = samples,
                        startedAtMs = startedAtMs,
                        finishedAtMs = timeProvider()
                    )
                }

                bestPose = fineState.bestPose
                bestPowerDbm = fineState.bestPowerDbm
                index = fineState.nextIndex
            }

            if (config.enableIncidentAngleOptimization) {
                val angleState = optimizeAnglesSimple(
                    startPose = bestPose,
                    startBestPowerDbm = bestPowerDbm,
                    startIndex = index,
                    config = config,
                    samples = samples,
                    onSample = onSample,
                    shouldStop = shouldStop
                )

                if (angleState.stopped) {
                    return CouplingResult.stopped(
                        bestPose = angleState.bestPose,
                        bestPowerDbm = angleState.bestPowerDbm,
                        samples = samples,
                        startedAtMs = startedAtMs,
                        finishedAtMs = timeProvider()
                    )
                }

                bestPose = angleState.bestPose
                bestPowerDbm = angleState.bestPowerDbm
                index = angleState.nextIndex
            }

            positioner.moveTo(
                pose = bestPose,
                wait = true
            )

            settle(config)

            val finalPower = powerMeter.readPowerDbm(
                channel = config.powerMeterChannel
            )

            val finalSample = CouplingSample(
                index = index,
                pose = bestPose,
                powerDbm = finalPower,
                stage = CouplingStage.Final,
                timestampMs = timeProvider()
            )

            addSample(
                samples = samples,
                sample = finalSample,
                onSample = onSample
            )

            val status = if (bestPowerDbm >= config.targetPowerDbm) {
                CouplingResultStatus.Success
            } else {
                CouplingResultStatus.TargetNotReached
            }

            CouplingResult(
                status = status,
                bestPose = bestPose,
                bestPowerDbm = bestPowerDbm,
                finalPose = bestPose,
                finalPowerDbm = finalPower,
                samples = samples,
                message = when (status) {
                    CouplingResultStatus.Success -> {
                        "耦光成功，bestPower=${round3(bestPowerDbm)} dBm"
                    }

                    CouplingResultStatus.TargetNotReached -> {
                        "找到 first light，但未达到目标功率，bestPower=${round3(bestPowerDbm)} dBm"
                    }

                    else -> null
                },
                startedAtMs = startedAtMs,
                finishedAtMs = timeProvider()
            )
        } catch (t: Throwable) {
            CouplingResult.failed(
                initialPose = initialPose,
                samples = samples,
                message = t.message ?: "耦光执行异常",
                startedAtMs = startedAtMs,
                finishedAtMs = timeProvider()
            )
        }
    }

    private suspend fun fineOptimizeXyz(
        startPose: OpticalPose,
        startBestPowerDbm: Double,
        startIndex: Int,
        config: CouplingConfig,
        samples: MutableList<CouplingSample>,
        onSample: suspend (CouplingSample) -> Unit,
        shouldStop: suspend () -> Boolean
    ): OptimizeState {
        var bestPose = startPose
        var bestPowerDbm = startBestPowerDbm
        var index = startIndex

        for (stepUm in config.fineStepsUm) {
            var improved = true

            while (improved) {
                improved = false

                val candidates = listOf(
                    bestPose.copy(xUm = bestPose.xUm + stepUm),
                    bestPose.copy(xUm = bestPose.xUm - stepUm),
                    bestPose.copy(yUm = bestPose.yUm + stepUm),
                    bestPose.copy(yUm = bestPose.yUm - stepUm),
                    bestPose.copy(zUm = bestPose.zUm + stepUm),
                    bestPose.copy(zUm = bestPose.zUm - stepUm)
                )

                for (pose in candidates) {
                    if (shouldStop()) {
                        return OptimizeState(
                            bestPose = bestPose,
                            bestPowerDbm = bestPowerDbm,
                            nextIndex = index,
                            stopped = true
                        )
                    }

                    positioner.moveTo(
                        pose = pose,
                        wait = true
                    )

                    settle(config)

                    val power = powerMeter.readPowerDbm(
                        channel = config.powerMeterChannel
                    )

                    addSample(
                        samples = samples,
                        sample = CouplingSample(
                            index = index++,
                            pose = pose,
                            powerDbm = power,
                            stage = CouplingStage.FineXyz,
                            timestampMs = timeProvider()
                        ),
                        onSample = onSample
                    )

                    if (power > bestPowerDbm + config.minImproveDb) {
                        bestPowerDbm = power
                        bestPose = pose
                        improved = true
                    }
                }
            }
        }

        return OptimizeState(
            bestPose = bestPose,
            bestPowerDbm = bestPowerDbm,
            nextIndex = index,
            stopped = false
        )
    }

    /**
     * 第一版简单角度优化。
     *
     * 注意：
     * 这里还没有启用虚拟枢轴补偿。
     * 后续如果支持 VirtualPivotPoint，需要改成 moveByAroundPivot。
     */
    private suspend fun optimizeAnglesSimple(
        startPose: OpticalPose,
        startBestPowerDbm: Double,
        startIndex: Int,
        config: CouplingConfig,
        samples: MutableList<CouplingSample>,
        onSample: suspend (CouplingSample) -> Unit,
        shouldStop: suspend () -> Boolean
    ): OptimizeState {
        var bestPose = startPose
        var bestPowerDbm = startBestPowerDbm
        var index = startIndex

        val angleAxes = listOf(
            AngleAxis.U,
            AngleAxis.V,
            AngleAxis.W
        )

        for (axis in angleAxes) {
            val step = when (axis) {
                AngleAxis.U -> config.uStepDeg
                AngleAxis.V -> config.vStepDeg
                AngleAxis.W -> config.wStepDeg
            }

            val stage = when (axis) {
                AngleAxis.U -> CouplingStage.OptimizeU
                AngleAxis.V -> CouplingStage.OptimizeV
                AngleAxis.W -> CouplingStage.OptimizeW
            }

            var improved = true
            var searchedRange = 0.0

            while (improved && searchedRange <= config.maxAngleRangeDeg) {
                improved = false

                val candidates = listOf(
                    bestPose.withAngleDelta(axis, step),
                    bestPose.withAngleDelta(axis, -step)
                )

                for (pose in candidates) {
                    if (shouldStop()) {
                        return OptimizeState(
                            bestPose = bestPose,
                            bestPowerDbm = bestPowerDbm,
                            nextIndex = index,
                            stopped = true
                        )
                    }

                    positioner.moveTo(
                        pose = pose,
                        wait = true
                    )

                    settle(config)

                    val power = powerMeter.readPowerDbm(
                        channel = config.powerMeterChannel
                    )

                    addSample(
                        samples = samples,
                        sample = CouplingSample(
                            index = index++,
                            pose = pose,
                            powerDbm = power,
                            stage = stage,
                            timestampMs = timeProvider()
                        ),
                        onSample = onSample
                    )

                    if (power > bestPowerDbm + config.minImproveDb) {
                        bestPowerDbm = power
                        bestPose = pose
                        improved = true
                    }
                }

                searchedRange += step
            }
        }

        return OptimizeState(
            bestPose = bestPose,
            bestPowerDbm = bestPowerDbm,
            nextIndex = index,
            stopped = false
        )
    }

    private suspend fun addSample(
        samples: MutableList<CouplingSample>,
        sample: CouplingSample,
        onSample: suspend (CouplingSample) -> Unit
    ) {
        samples += sample
        onSample(sample)
    }

    private suspend fun settle(
        config: CouplingConfig
    ) {
        if (config.settleDelayMs > 0L) {
            delay(config.settleDelayMs)
        }
    }

    private data class OptimizeState(
        val bestPose: OpticalPose,
        val bestPowerDbm: Double,
        val nextIndex: Int,
        val stopped: Boolean
    )

    enum class AngleAxis {
        U,
        V,
        W
    }
}

/**
 * 螺旋搜索偏移。
 *
 * dx/dy 表示当前螺旋平面里的两个轴。
 * 具体映射到 X/Y、Y/Z、X/Z，由 CouplingSpiralPlane 决定。
 */
private data class SpiralOffset(
    val dxUm: Double,
    val dyUm: Double
)

private fun buildSquareSpiralOffsets(
    stepUm: Double,
    maxRadiusUm: Double
): List<SpiralOffset> {
    require(stepUm > 0.0) {
        "spiralStepUm 必须大于 0"
    }
    require(maxRadiusUm > 0.0) {
        "maxRadiusUm 必须大于 0"
    }

    val maxLayer = ceil(maxRadiusUm / stepUm).toInt()
    val offsets = mutableListOf<SpiralOffset>()

    for (layer in 1..maxLayer) {
        val min = -layer
        val max = layer

        for (x in min..max) {
            offsets += SpiralOffset(
                dxUm = x * stepUm,
                dyUm = max * stepUm
            )
        }

        for (y in (max - 1) downTo min) {
            offsets += SpiralOffset(
                dxUm = max * stepUm,
                dyUm = y * stepUm
            )
        }

        for (x in (max - 1) downTo min) {
            offsets += SpiralOffset(
                dxUm = x * stepUm,
                dyUm = min * stepUm
            )
        }

        for (y in (min + 1)..(max - 1)) {
            offsets += SpiralOffset(
                dxUm = min * stepUm,
                dyUm = y * stepUm
            )
        }
    }

    return offsets.filter { offset ->
        hypot(offset.dxUm, offset.dyUm) <= maxRadiusUm + 1e-9
    }
}

private fun applySpiralOffset(
    initialPose: OpticalPose,
    offset: SpiralOffset,
    plane: CouplingSpiralPlane
): OpticalPose {
    return when (plane) {
        CouplingSpiralPlane.XY -> {
            initialPose.copy(
                xUm = initialPose.xUm + offset.dxUm,
                yUm = initialPose.yUm + offset.dyUm
            )
        }

        CouplingSpiralPlane.YZ -> {
            initialPose.copy(
                yUm = initialPose.yUm + offset.dxUm,
                zUm = initialPose.zUm + offset.dyUm
            )
        }

        CouplingSpiralPlane.XZ -> {
            initialPose.copy(
                xUm = initialPose.xUm + offset.dxUm,
                zUm = initialPose.zUm + offset.dyUm
            )
        }
    }
}

private fun OpticalPose.withAngleDelta(
    axis: SpiralCouplingRunner.AngleAxis,
    deltaDeg: Double
): OpticalPose {
    return when (axis) {
        SpiralCouplingRunner.AngleAxis.U -> {
            copy(uDeg = uDeg + deltaDeg)
        }

        SpiralCouplingRunner.AngleAxis.V -> {
            copy(vDeg = vDeg + deltaDeg)
        }

        SpiralCouplingRunner.AngleAxis.W -> {
            copy(wDeg = wDeg + deltaDeg)
        }
    }
}

private fun round3(
    value: Double
): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}