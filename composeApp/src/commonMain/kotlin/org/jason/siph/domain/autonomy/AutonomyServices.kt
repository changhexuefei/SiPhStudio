package org.jason.siph.domain.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jason.siph.domain.optical.OpticalPowerMeterPort
import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.positioner.OpticalPositionerPort
import org.jason.siph.domain.positioner.plus
import kotlin.math.pow
import kotlin.math.sqrt

/** 不移动设备，只把当前安全位置训练成可复用测量位置。 */
data class MeasurementPositionTrainingRequest(
    val id: String,
    val name: String,
    val site: MeasurementSiteKey,
    val calibrationProfileId: String,
    val safetyProfileId: String? = null,
    val powerMeterChannel: Int = 1,
    val powerAverageCount: Int = 3,
    val powerAverageDelayMs: Long = 5L,
    val markVerified: Boolean = true,
    val notes: String? = null
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(calibrationProfileId.isNotBlank())
        require(powerMeterChannel > 0)
        require(powerAverageCount in 1..100)
        require(powerAverageDelayMs >= 0L)
    }
}

class MeasurementPositionTrainer(
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,
    private val positions: MeasurementPositionRepository,
    private val nowEpochMs: () -> Long
) {
    suspend fun train(request: MeasurementPositionTrainingRequest): TrainedMeasurementPosition {
        currentCoroutineContext().ensureActive()
        val pose = positioner.currentPose()
        val power = averagePower(
            channel = request.powerMeterChannel,
            count = request.powerAverageCount,
            delayMs = request.powerAverageDelayMs
        )
        val timestamp = nowEpochMs()
        val result = TrainedMeasurementPosition(
            id = request.id,
            name = request.name,
            site = request.site,
            pose = pose,
            referencePowerDbm = power,
            calibrationProfileId = request.calibrationProfileId,
            safetyProfileId = request.safetyProfileId,
            trainedAtEpochMs = timestamp,
            verifiedAtEpochMs = timestamp.takeIf { request.markVerified },
            verified = request.markVerified,
            notes = request.notes
        )
        positions.savePosition(result)
        return result
    }

    private suspend fun averagePower(channel: Int, count: Int, delayMs: Long): Double {
        var total = 0.0
        repeat(count) { index ->
            currentCoroutineContext().ensureActive()
            val value = powerMeter.readPowerDbm(channel)
            require(value.isFinite()) { "Power meter returned non-finite value: $value" }
            total += value
            if (index < count - 1 && delayMs > 0L) delay(delayMs)
        }
        return total / count
    }
}

data class CalibrationProfileVerificationRequest(
    val profileId: String,
    val verifiedBy: String,
    val maximumPoseErrorUm: Double = 1.0,
    val activateOnPass: Boolean = true
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(verifiedBy.isNotBlank()) { "verifiedBy must not be blank" }
        require(maximumPoseErrorUm.isFinite() && maximumPoseErrorUm >= 0.0) {
            "maximumPoseErrorUm must be non-negative and finite"
        }
    }
}

/**
 * 通过控制器身份和当前测量位姿验证校准配置，不执行任何自动运动。
 */
class CalibrationProfileVerifier(
    private val positioner: OpticalPositionerPort,
    private val profiles: CalibrationProfileRepository,
    private val verifications: CalibrationVerificationRepository,
    private val nowEpochMs: () -> Long
) {
    suspend fun verify(
        request: CalibrationProfileVerificationRequest
    ): CalibrationVerificationRecord {
        currentCoroutineContext().ensureActive()
        val profile = profiles.findProfile(request.profileId)
            ?: error("Calibration profile not found: ${request.profileId}")
        val controllerIdentity = positioner.identify().also {
            require(it.isNotBlank()) { "Positioner identity is blank" }
        }
        val actualPose = positioner.currentPose()
        val expectedPose = profile.measurementPose
        val poseError = expectedPose?.linearDistanceTo(actualPose) ?: Double.POSITIVE_INFINITY
        val identityMatches = profile.controllerIdentity
            ?.takeIf { it.isNotBlank() }
            ?.let { expected -> controllerIdentity.contains(expected, ignoreCase = true) }
            ?: true
        val passed = identityMatches && expectedPose != null && poseError <= request.maximumPoseErrorUm
        val message = buildString {
            if (!identityMatches) append("Controller identity mismatch. ")
            if (expectedPose == null) append("Measurement pose is not configured. ")
            if (expectedPose != null && poseError > request.maximumPoseErrorUm) {
                append("Pose error $poseError um exceeds ${request.maximumPoseErrorUm} um. ")
            }
            if (passed) append("Calibration profile verified")
        }.trim()
        val timestamp = nowEpochMs()
        val record = CalibrationVerificationRecord(
            profileId = profile.id,
            verifiedAtEpochMs = timestamp,
            controllerIdentity = controllerIdentity,
            fixtureId = profile.fixtureId,
            poseErrorUm = poseError.takeIf { it.isFinite() } ?: Double.MAX_VALUE,
            powerErrorDb = null,
            passed = passed,
            message = message
        )
        verifications.saveVerification(record)
        val updated = profile.copy(
            controllerIdentity = profile.controllerIdentity ?: controllerIdentity,
            verifiedAtEpochMs = timestamp,
            verifiedBy = request.verifiedBy,
            verified = passed
        )
        profiles.saveProfile(updated)
        if (passed && request.activateOnPass) profiles.activateProfile(updated.id)
        return record
    }
}

/**
 * 从最佳点小幅离开后反复回位并测量，用于验证全系统回位重复性。
 * 所有目标仍经过 OpticalPositionerPort 的安全包装。
 */
class OpticalAlignmentVerifier(
    private val positioner: OpticalPositionerPort,
    private val powerMeter: OpticalPowerMeterPort,
    private val nowEpochMs: () -> Long
) {
    suspend fun verify(
        bestPose: OpticalPose,
        powerMeterChannel: Int,
        config: OpticalVerificationConfig
    ): OpticalAlignmentVerificationResult {
        val startedAt = nowEpochMs()
        val samples = mutableListOf<OpticalVerificationSample>()

        return try {
            positioner.moveTo(bestPose, wait = true)
            settle(config.settleDelayMs)
            val referencePower = averagePower(
                channel = powerMeterChannel,
                count = config.readsPerRepeat,
                delayMs = config.settleDelayMs.coerceAtMost(20L)
            )

            repeat(config.repeatCount) { index ->
                currentCoroutineContext().ensureActive()
                positioner.moveTo(bestPose + config.excursion, wait = true)
                settle(config.settleDelayMs)
                positioner.moveTo(bestPose, wait = true)
                settle(config.settleDelayMs)

                val actualPose = positioner.currentPose()
                val power = averagePower(
                    channel = powerMeterChannel,
                    count = config.readsPerRepeat,
                    delayMs = config.settleDelayMs.coerceAtMost(20L)
                )
                samples += OpticalVerificationSample(
                    index = index,
                    pose = actualPose,
                    powerDbm = power,
                    positionErrorUm = actualPose.linearDistanceTo(bestPose),
                    timestampEpochMs = nowEpochMs()
                )
            }

            val powers = samples.map { it.powerDbm }
            val mean = powers.average()
            val standardDeviation = sqrt(
                powers.sumOf { (it - mean).pow(2) } / powers.size.coerceAtLeast(1)
            )
            val peakToPeak = (powers.maxOrNull() ?: referencePower) -
                (powers.minOrNull() ?: referencePower)
            val maximumPositionError = samples.maxOfOrNull { it.positionErrorUm } ?: 0.0
            val failures = buildList {
                if (peakToPeak > config.maxPeakToPeakDb) {
                    add("Power peak-to-peak $peakToPeak dB exceeds ${config.maxPeakToPeakDb} dB")
                }
                if (standardDeviation > config.maxStandardDeviationDb) {
                    add("Power standard deviation $standardDeviation dB exceeds ${config.maxStandardDeviationDb} dB")
                }
                if (maximumPositionError > config.maxReturnPositionErrorUm) {
                    add("Return position error $maximumPositionError um exceeds ${config.maxReturnPositionErrorUm} um")
                }
            }

            OpticalAlignmentVerificationResult(
                bestPose = bestPose,
                referencePowerDbm = referencePower,
                meanPowerDbm = mean,
                standardDeviationDb = standardDeviation,
                peakToPeakDb = peakToPeak,
                maximumPositionErrorUm = maximumPositionError,
                samples = samples,
                passed = failures.isEmpty(),
                failures = failures,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = nowEpochMs()
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { positioner.stop() } }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { positioner.stop() } }
            throw error
        }
    }

    private suspend fun settle(delayMs: Long) {
        if (delayMs > 0L) delay(delayMs)
    }

    private suspend fun averagePower(channel: Int, count: Int, delayMs: Long): Double {
        var total = 0.0
        repeat(count) { index ->
            currentCoroutineContext().ensureActive()
            val value = powerMeter.readPowerDbm(channel)
            require(value.isFinite()) { "Power meter returned non-finite value: $value" }
            total += value
            if (index < count - 1 && delayMs > 0L) delay(delayMs)
        }
        return total / count
    }
}

class DriftEvaluator(
    private val nowEpochMs: () -> Long
) {
    fun assess(
        baseline: DriftBaseline,
        currentPose: OpticalPose,
        currentPowerDbm: Double,
        currentTemperatureC: Double?,
        policy: DriftPolicy
    ): DriftAssessment {
        require(currentPowerDbm.isFinite()) { "currentPowerDbm must be finite" }
        require(currentTemperatureC == null || currentTemperatureC.isFinite())

        val powerDrop = (baseline.referencePowerDbm - currentPowerDbm).coerceAtLeast(0.0)
        val positionShift = currentPose.linearDistanceTo(baseline.referencePose)
        val temperatureDelta = if (
            baseline.referenceTemperatureC != null && currentTemperatureC != null
        ) {
            kotlin.math.abs(currentTemperatureC - baseline.referenceTemperatureC)
        } else {
            null
        }

        val action = when {
            powerDrop >= policy.stopPowerDropDb -> DriftAction.StopWorkflow
            powerDrop >= policy.fullRecalibrationPowerDropDb -> DriftAction.FullRecalibration
            positionShift >= policy.fullRecalibrationPositionShiftUm -> DriftAction.FullRecalibration
            temperatureDelta != null && temperatureDelta >= policy.temperatureRecalibrationDeltaC ->
                DriftAction.FullRecalibration
            powerDrop >= policy.localRealignPowerDropDb -> DriftAction.LocalRealign
            else -> DriftAction.Continue
        }

        val reasons = buildList {
            if (powerDrop >= policy.warningPowerDropDb) {
                add("Power dropped by $powerDrop dB")
            }
            if (positionShift >= policy.warningPositionShiftUm) {
                add("Best position shifted by $positionShift um")
            }
            if (temperatureDelta != null && temperatureDelta >= policy.temperatureRecalibrationDeltaC) {
                add("Temperature changed by $temperatureDelta C")
            }
            if (isEmpty()) add("Drift is within configured limits")
        }

        return DriftAssessment(
            baselineId = baseline.id,
            currentPowerDbm = currentPowerDbm,
            powerDropDb = powerDrop,
            positionShiftUm = positionShift,
            temperatureDeltaC = temperatureDelta,
            action = action,
            reasons = reasons,
            assessedAtEpochMs = nowEpochMs()
        )
    }
}
