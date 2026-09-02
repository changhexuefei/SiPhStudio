package org.jason.siph.domain.coupling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalDelta
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.positioner.PivotAwareOpticalPositionerPort
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 面向实际耦光任务的自适应 Runner。
 *
 * 算法：
 * 1. 初始点稳定功率采样；
 * 2. 方形螺旋粗扫寻找 first light；
 * 3. 多尺度 XYZ 坐标下降精调；
 * 4. 可选 U/V/W 角度坐标下降；
 * 5. 返回并停在全局最佳点。
 *
 * 与早期 Demo Runner 相比，增加了多次功率平均、采样上限、精调轮数上限、
 * 协程取消急停和每轮回到最佳点等保护。
 */
class AdaptiveCouplingRunner(
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,
    private val timeProvider: () -> Long = { 0L }
) : CouplingRunner {

    override suspend fun run(
        initialPose: OpticalPose,
        config: CouplingConfig,
        onSample: suspend (CouplingSample) -> Unit,
        shouldStop: suspend () -> Boolean
    ): CouplingResult {
        val startedAtMs = timeProvider()
        val context = SearchContext(
            config = config,
            samples = mutableListOf(),
            onSample = onSample,
            shouldStop = shouldStop
        )

        return try {
            positioner.moveTo(initialPose, wait = true)
            settle(config)

            var best = context.measure(
                pose = initialPose,
                stage = CouplingStage.Initial
            )

            var firstLightFound = best.powerDbm >= config.firstLightThresholdDbm

            if (!firstLightFound && !context.targetReached(best.powerDbm)) {
                for (offset in buildSquareSpiralOffsets(config.spiralStepUm, config.maxRadiusUm)) {
                    context.checkContinue()

                    val pose = applyPlaneOffset(
                        initialPose = initialPose,
                        firstOffsetUm = offset.first,
                        secondOffsetUm = offset.second,
                        plane = config.spiralPlane
                    )

                    positioner.moveTo(pose, wait = true)
                    settle(config)

                    val sample = context.measure(
                        pose = pose,
                        stage = CouplingStage.SpiralFirstLight
                    )

                    if (sample.powerDbm > best.powerDbm) {
                        best = sample
                    }

                    if (sample.powerDbm >= config.firstLightThresholdDbm) {
                        firstLightFound = true
                    }

                    if (firstLightFound || context.targetReached(best.powerDbm)) {
                        break
                    }
                }
            }

            if (!firstLightFound && best.powerDbm < config.firstLightThresholdDbm) {
                positioner.moveTo(best.pose, wait = true)
                settle(config)
                val final = context.measure(best.pose, CouplingStage.Final)

                return CouplingResult(
                    status = CouplingResultStatus.FirstLightNotFound,
                    bestPose = best.pose,
                    bestPowerDbm = best.powerDbm,
                    finalPose = best.pose,
                    finalPowerDbm = final.powerDbm,
                    samples = context.samples.toList(),
                    message = "未找到 first light，最佳功率 ${round3(best.powerDbm)} dBm",
                    startedAtMs = startedAtMs,
                    finishedAtMs = timeProvider()
                )
            }

            if (config.enableFineXyz && !context.targetReached(best.powerDbm)) {
                best = optimizeXyz(
                    startBest = best,
                    context = context
                )
            }

            if (config.enableIncidentAngleOptimization && !context.targetReached(best.powerDbm)) {
                best = optimizeAngles(
                    startBest = best,
                    context = context
                )
            }

            positioner.moveTo(best.pose, wait = true)
            settle(config)
            val final = context.measure(best.pose, CouplingStage.Final)

            val finalBest = if (final.powerDbm > best.powerDbm) final else best
            val status = if (finalBest.powerDbm >= config.targetPowerDbm) {
                CouplingResultStatus.Success
            } else {
                CouplingResultStatus.TargetNotReached
            }

            CouplingResult(
                status = status,
                bestPose = finalBest.pose,
                bestPowerDbm = finalBest.powerDbm,
                finalPose = best.pose,
                finalPowerDbm = final.powerDbm,
                samples = context.samples.toList(),
                message = when (status) {
                    CouplingResultStatus.Success ->
                        "耦光成功，最佳功率 ${round3(finalBest.powerDbm)} dBm"

                    else ->
                        "已找到光但未达到目标，最佳功率 ${round3(finalBest.powerDbm)} dBm"
                },
                startedAtMs = startedAtMs,
                finishedAtMs = timeProvider()
            )
        } catch (cancelled: CancellationException) {
            stopSafely()
            throw cancelled
        } catch (stopped: CouplingStoppedException) {
            stopSafely()
            CouplingResult.stopped(
                bestPose = context.bestSample?.pose ?: initialPose,
                bestPowerDbm = context.bestSample?.powerDbm ?: Double.NEGATIVE_INFINITY,
                samples = context.samples.toList(),
                message = stopped.message ?: "耦光已停止",
                startedAtMs = startedAtMs,
                finishedAtMs = timeProvider()
            )
        } catch (error: Throwable) {
            stopSafely()
            CouplingResult.failed(
                initialPose = context.bestSample?.pose ?: initialPose,
                samples = context.samples.toList(),
                message = error.message ?: "耦光执行异常",
                startedAtMs = startedAtMs,
                finishedAtMs = timeProvider()
            )
        }
    }

    private suspend fun optimizeXyz(
        startBest: CouplingSample,
        context: SearchContext
    ): CouplingSample {
        var best = startBest

        for (stepUm in context.config.fineStepsUm) {
            var pass = 0
            var improved = true

            while (improved && pass < context.config.maxFinePassesPerStep) {
                context.checkContinue()
                pass += 1
                improved = false

                val candidates = listOf(
                    best.pose.copy(xUm = best.pose.xUm + stepUm),
                    best.pose.copy(xUm = best.pose.xUm - stepUm),
                    best.pose.copy(yUm = best.pose.yUm + stepUm),
                    best.pose.copy(yUm = best.pose.yUm - stepUm),
                    best.pose.copy(zUm = best.pose.zUm + stepUm),
                    best.pose.copy(zUm = best.pose.zUm - stepUm)
                )

                for (pose in candidates) {
                    context.checkContinue()
                    positioner.moveTo(pose, wait = true)
                    settle(context.config)

                    val sample = context.measure(pose, CouplingStage.FineXyz)
                    if (sample.powerDbm > best.powerDbm + context.config.minImproveDb) {
                        best = sample
                        improved = true
                    }

                    if (context.targetReached(best.powerDbm)) {
                        return best
                    }
                }

                // 坏点测量后平台可能停在候选点，每轮必须回到当前最佳点。
                positioner.moveTo(best.pose, wait = true)
                settle(context.config)
            }
        }

        return best
    }

    private suspend fun optimizeAngles(
        startBest: CouplingSample,
        context: SearchContext
    ): CouplingSample {
        var best = startBest

        for (axis in AngleAxis.entries) {
            val stepDeg = when (axis) {
                AngleAxis.U -> context.config.uStepDeg
                AngleAxis.V -> context.config.vStepDeg
                AngleAxis.W -> context.config.wStepDeg
            }
            val stage = when (axis) {
                AngleAxis.U -> CouplingStage.OptimizeU
                AngleAxis.V -> CouplingStage.OptimizeV
                AngleAxis.W -> CouplingStage.OptimizeW
            }

            var searchedDeg = 0.0
            var improved = true

            while (improved && searchedDeg + 1e-12 < context.config.maxAngleRangeDeg) {
                context.checkContinue()
                improved = false

                for (direction in listOf(1.0, -1.0)) {
                    context.checkContinue()
                    val deltaDeg = stepDeg * direction
                    val samplePose = moveAngleCandidate(
                        basePose = best.pose,
                        axis = axis,
                        deltaDeg = deltaDeg,
                        config = context.config
                    )
                    settle(context.config)

                    val sample = context.measure(samplePose, stage)
                    if (sample.powerDbm > best.powerDbm + context.config.minImproveDb) {
                        best = sample
                        improved = true
                    }

                    if (context.targetReached(best.powerDbm)) {
                        return best
                    }
                }

                positioner.moveTo(best.pose, wait = true)
                settle(context.config)
                searchedDeg += stepDeg
            }
        }

        return best
    }

    private suspend fun moveAngleCandidate(
        basePose: OpticalPose,
        axis: AngleAxis,
        deltaDeg: Double,
        config: CouplingConfig
    ): OpticalPose {
        val pivotAware = positioner as? PivotAwareOpticalPositionerPort
        val pivot = config.virtualPivotPoint

        if (config.enableSoftwarePivotCompensation && pivot.enabled && pivotAware != null) {
            positioner.moveTo(basePose, wait = true)
            pivotAware.moveByAroundPivot(
                delta = when (axis) {
                    AngleAxis.U -> OpticalDelta(duDeg = deltaDeg)
                    AngleAxis.V -> OpticalDelta(dvDeg = deltaDeg)
                    AngleAxis.W -> OpticalDelta(dwDeg = deltaDeg)
                },
                pivot = pivot,
                wait = true
            )
            return positioner.currentPose()
        }

        val candidate = when (axis) {
            AngleAxis.U -> basePose.copy(uDeg = basePose.uDeg + deltaDeg)
            AngleAxis.V -> basePose.copy(vDeg = basePose.vDeg + deltaDeg)
            AngleAxis.W -> basePose.copy(wDeg = basePose.wDeg + deltaDeg)
        }
        positioner.moveTo(candidate, wait = true)
        return candidate
    }

    private suspend fun settle(config: CouplingConfig) {
        if (config.settleDelayMs > 0L) {
            delay(config.settleDelayMs)
        }
    }

    private suspend fun stopSafely() {
        withContext(NonCancellable) {
            runCatching { positioner.stop() }
        }
    }

    private inner class SearchContext(
        val config: CouplingConfig,
        val samples: MutableList<CouplingSample>,
        private val onSample: suspend (CouplingSample) -> Unit,
        private val shouldStop: suspend () -> Boolean
    ) {
        var bestSample: CouplingSample? = null
            private set

        suspend fun checkContinue() {
            currentCoroutineContext().ensureActive()
            if (shouldStop()) {
                throw CouplingStoppedException("用户停止耦光")
            }
            if (samples.size >= config.maxTotalSamples) {
                throw CouplingStoppedException(
                    "达到最大采样点数 ${config.maxTotalSamples}，任务已安全停止"
                )
            }
        }

        suspend fun measure(
            pose: OpticalPose,
            stage: CouplingStage
        ): CouplingSample {
            checkContinue()

            var total = 0.0
            repeat(config.powerAverageCount) { readIndex ->
                currentCoroutineContext().ensureActive()
                total += powerMeter.readPowerDbm(config.powerMeterChannel)
                if (readIndex < config.powerAverageCount - 1 && config.powerAverageDelayMs > 0L) {
                    delay(config.powerAverageDelayMs)
                }
            }

            val sample = CouplingSample(
                index = samples.size,
                pose = pose,
                powerDbm = total / config.powerAverageCount,
                stage = stage,
                timestampMs = timeProvider()
            )
            samples += sample

            if (bestSample == null || sample.powerDbm > bestSample!!.powerDbm) {
                bestSample = sample
            }

            onSample(sample)
            return sample
        }

        fun targetReached(powerDbm: Double): Boolean {
            return config.stopWhenTargetReached && powerDbm >= config.targetPowerDbm
        }
    }

    private enum class AngleAxis {
        U,
        V,
        W
    }
}

private class CouplingStoppedException(message: String) : RuntimeException(message)

private fun buildSquareSpiralOffsets(
    stepUm: Double,
    maxRadiusUm: Double
): Sequence<Pair<Double, Double>> = sequence {
    val maxLayer = ceil(maxRadiusUm / stepUm).toInt()

    for (layer in 1..maxLayer) {
        val min = -layer
        val max = layer

        suspend fun SequenceScope<Pair<Double, Double>>.emit(x: Int, y: Int) {
            val dx = x * stepUm
            val dy = y * stepUm
            if (hypot(dx, dy) <= maxRadiusUm + 1e-9) {
                yield(dx to dy)
            }
        }

        for (x in min..max) emit(x, max)
        for (y in (max - 1) downTo min) emit(max, y)
        for (x in (max - 1) downTo min) emit(x, min)
        for (y in (min + 1) until max) emit(min, y)
    }
}

private fun applyPlaneOffset(
    initialPose: OpticalPose,
    firstOffsetUm: Double,
    secondOffsetUm: Double,
    plane: CouplingSpiralPlane
): OpticalPose {
    return when (plane) {
        CouplingSpiralPlane.XY -> initialPose.copy(
            xUm = initialPose.xUm + firstOffsetUm,
            yUm = initialPose.yUm + secondOffsetUm
        )

        CouplingSpiralPlane.YZ -> initialPose.copy(
            yUm = initialPose.yUm + firstOffsetUm,
            zUm = initialPose.zUm + secondOffsetUm
        )

        CouplingSpiralPlane.XZ -> initialPose.copy(
            xUm = initialPose.xUm + firstOffsetUm,
            zUm = initialPose.zUm + secondOffsetUm
        )
    }
}

private fun round3(value: Double): Double {
    return kotlin.math.round(value * 1000.0) / 1000.0
}
