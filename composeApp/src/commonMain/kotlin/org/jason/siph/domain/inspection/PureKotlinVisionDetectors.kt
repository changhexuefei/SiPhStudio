package org.jason.siph.domain.inspection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CompositeVisionFeatureDetector(
    private val fiberTipDetector: VisionFeatureDetector = FiberTipIntensityDetector(),
    private val gratingDetector: VisionFeatureDetector = GratingEdgeDetector(),
    private val facetDetector: VisionFeatureDetector = FacetLineDetector()
) : VisionFeatureDetector {
    override val supportedKinds: Set<VisionFeatureKind> = VisionFeatureKind.entries.toSet()

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection = when (request.kind) {
        VisionFeatureKind.FiberTip -> fiberTipDetector.detect(frame, request)
        VisionFeatureKind.Grating -> gratingDetector.detect(frame, request)
        VisionFeatureKind.Facet -> facetDetector.detect(frame, request)
    }
}

/** Detects the bright compact lobe produced by a fiber-tip illumination or reflection. */
class FiberTipIntensityDetector : VisionFeatureDetector {
    override val supportedKinds = setOf(VisionFeatureKind.FiberTip)

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection {
        require(request.kind == VisionFeatureKind.FiberTip)
        val roi = frame.normalizedRoi(request.regionOfInterest)
        val stats = frame.statistics(roi)
        val threshold = (stats.mean + max(12.0, stats.standardDeviation * 1.25)).coerceAtMost(250.0)

        var weightSum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var brightCount = 0
        var maximum = 0
        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                val gray = frame.grayAt(x, y)
                maximum = max(maximum, gray)
                if (gray >= threshold) {
                    val weight = gray - threshold + 1.0
                    weightSum += weight
                    weightedX += x * weight
                    weightedY += y * weight
                    brightCount++
                }
            }
        }

        if (weightSum <= 0.0 || brightCount < 5) {
            return missing(VisionFeatureKind.FiberTip, "No compact bright fiber-tip candidate")
        }

        val centerX = weightedX / weightSum
        val centerY = weightedY / weightSum
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                val gray = frame.grayAt(x, y)
                if (gray >= threshold) {
                    val weight = gray - threshold + 1.0
                    val dx = x - centerX
                    val dy = y - centerY
                    xx += dx * dx * weight
                    yy += dy * dy * weight
                    xy += dx * dy * weight
                }
            }
        }
        xx /= weightSum
        yy /= weightSum
        xy /= weightSum
        val trace = xx + yy
        val discriminant = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val major = sqrt(max(0.0, (trace + discriminant) / 2.0)) * 4.0
        val minor = sqrt(max(0.0, (trace - discriminant) / 2.0)) * 4.0
        val compactness = if (major <= 1e-9) 0.0 else (minor / major).coerceIn(0.0, 1.0)
        val brightnessScore = ((maximum - stats.mean) / 180.0).coerceIn(0.0, 1.0)
        val areaScore = (brightCount / max(12.0, roi.width * roi.height * 0.02)).coerceIn(0.0, 1.0)
        val confidence = (0.45 * brightnessScore + 0.35 * compactness + 0.20 * areaScore)
            .coerceIn(0.0, 1.0)

        return VisionFeatureDetection(
            kind = VisionFeatureKind.FiberTip,
            found = confidence >= request.minimumConfidence,
            confidence = confidence,
            centerPx = VisionPointPx(centerX, centerY).takeIf { confidence >= request.minimumConfidence },
            angleDeg = null,
            widthPx = major,
            heightPx = minor,
            scoreDetails = mapOf(
                "brightness" to brightnessScore,
                "compactness" to compactness,
                "area" to areaScore,
                "threshold" to threshold
            ),
            message = if (confidence >= request.minimumConfidence) {
                "Fiber tip detected"
            } else {
                "Fiber-tip candidate confidence is below threshold"
            }
        )
    }
}

/** Detects repeated high-contrast grating edges without depending on OpenCV. */
class GratingEdgeDetector : VisionFeatureDetector {
    override val supportedKinds = setOf(VisionFeatureKind.Grating)

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection {
        require(request.kind == VisionFeatureKind.Grating)
        val roi = frame.normalizedRoi(request.regionOfInterest)
        if (roi.width < 8 || roi.height < 4) return missing(request.kind, "Grating ROI is too small")

        val energy = DoubleArray(roi.width - 1)
        for (xOffset in 1 until roi.width) {
            val x = roi.left + xOffset
            var sum = 0.0
            for (y in roi.top until roi.bottomExclusive) {
                sum += abs(frame.grayAt(x, y) - frame.grayAt(x - 1, y)).toDouble()
            }
            energy[xOffset - 1] = sum / roi.height
        }
        val mean = energy.average()
        val deviation = sqrt(energy.sumOf { (it - mean) * (it - mean) } / energy.size)
        val edgeThreshold = mean + max(2.0, deviation * 0.55)
        val peaks = mutableListOf<Int>()
        for (index in 1 until energy.lastIndex) {
            if (energy[index] >= edgeThreshold && energy[index] >= energy[index - 1] && energy[index] >= energy[index + 1]) {
                if (peaks.isEmpty() || index - peaks.last() >= 2) peaks += index
            }
        }

        if (peaks.size < 4) return missing(request.kind, "Insufficient repeated grating edges")
        val spacings = peaks.zipWithNext { left, right -> (right - left).toDouble() }
        val spacingMean = spacings.average()
        val spacingDeviation = sqrt(spacings.sumOf { (it - spacingMean) * (it - spacingMean) } / spacings.size)
        val regularity = if (spacingMean <= 1e-9) 0.0 else (1.0 - spacingDeviation / spacingMean).coerceIn(0.0, 1.0)
        val edgeContrast = ((energy.maxOrNull() ?: mean) - mean).div(80.0).coerceIn(0.0, 1.0)
        val countScore = (peaks.size / 10.0).coerceIn(0.0, 1.0)
        val confidence = (0.45 * regularity + 0.35 * edgeContrast + 0.20 * countScore).coerceIn(0.0, 1.0)
        val centerX = roi.left + peaks.average() + 1.0
        val centerY = roi.top + roi.height / 2.0

        return VisionFeatureDetection(
            kind = request.kind,
            found = confidence >= request.minimumConfidence,
            confidence = confidence,
            centerPx = VisionPointPx(centerX, centerY).takeIf { confidence >= request.minimumConfidence },
            angleDeg = 0.0,
            widthPx = (peaks.last() - peaks.first()).toDouble(),
            heightPx = roi.height.toDouble(),
            scoreDetails = mapOf(
                "edgeCount" to peaks.size.toDouble(),
                "regularity" to regularity,
                "edgeContrast" to edgeContrast,
                "meanSpacingPx" to spacingMean
            ),
            message = if (confidence >= request.minimumConfidence) {
                "Grating edges detected"
            } else {
                "Grating edge confidence is below threshold"
            }
        )
    }
}

/** Detects a bright facet edge by least-squares line fitting and residual scoring. */
class FacetLineDetector : VisionFeatureDetector {
    override val supportedKinds = setOf(VisionFeatureKind.Facet)

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection {
        require(request.kind == VisionFeatureKind.Facet)
        val roi = frame.normalizedRoi(request.regionOfInterest)
        val stats = frame.statistics(roi)
        val threshold = stats.mean + max(10.0, stats.standardDeviation)
        val points = buildList {
            for (y in roi.top until roi.bottomExclusive) {
                for (x in roi.left until roi.rightExclusive) {
                    if (frame.grayAt(x, y) >= threshold) add(VisionPointPx(x.toDouble(), y.toDouble()))
                }
            }
        }
        if (points.size < 8) return missing(request.kind, "No facet edge candidate")

        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        val varianceX = points.sumOf { (it.x - meanX) * (it.x - meanX) }
        if (varianceX <= 1e-9) return missing(request.kind, "Facet candidate is degenerate")
        val covariance = points.sumOf { (it.x - meanX) * (it.y - meanY) }
        val slope = covariance / varianceX
        val intercept = meanY - slope * meanX
        val total = points.sumOf { (it.y - meanY) * (it.y - meanY) }
        val residual = points.sumOf {
            val predicted = slope * it.x + intercept
            (it.y - predicted) * (it.y - predicted)
        }
        val rSquared = if (total <= 1e-9) 0.0 else (1.0 - residual / total).coerceIn(0.0, 1.0)
        val contrast = ((points.maxOf { frame.grayAt(it.x.toInt(), it.y.toInt()) } - stats.mean) / 180.0)
            .coerceIn(0.0, 1.0)
        val coverage = (points.size / max(16.0, roi.width * 0.8)).coerceIn(0.0, 1.0)
        val confidence = (0.55 * rSquared + 0.30 * contrast + 0.15 * coverage).coerceIn(0.0, 1.0)
        val angle = atan(slope) * 180.0 / PI

        return VisionFeatureDetection(
            kind = request.kind,
            found = confidence >= request.minimumConfidence,
            confidence = confidence,
            centerPx = VisionPointPx(meanX, meanY).takeIf { confidence >= request.minimumConfidence },
            angleDeg = angle,
            widthPx = roi.width.toDouble(),
            heightPx = sqrt(residual / points.size.coerceAtLeast(1)) * 2.0,
            scoreDetails = mapOf(
                "rSquared" to rSquared,
                "contrast" to contrast,
                "coverage" to coverage,
                "threshold" to threshold
            ),
            message = if (confidence >= request.minimumConfidence) {
                "Facet line detected"
            } else {
                "Facet line confidence is below threshold"
            }
        )
    }
}

private data class GrayStatistics(
    val mean: Double,
    val standardDeviation: Double
)

private fun CameraFrame.statistics(roi: VisionRectPx): GrayStatistics {
    var count = 0
    var sum = 0.0
    var sumSquares = 0.0
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            val value = grayAt(x, y).toDouble()
            count++
            sum += value
            sumSquares += value * value
        }
    }
    val mean = sum / count.coerceAtLeast(1)
    val variance = max(0.0, sumSquares / count.coerceAtLeast(1) - mean * mean)
    return GrayStatistics(mean, sqrt(variance))
}

private fun CameraFrame.normalizedRoi(request: VisionRectPx?): VisionRectPx {
    if (request == null) return VisionRectPx(0, 0, widthPx, heightPx)
    val left = request.left.coerceIn(0, widthPx - 1)
    val top = request.top.coerceIn(0, heightPx - 1)
    val right = min(widthPx, request.rightExclusive)
    val bottom = min(heightPx, request.bottomExclusive)
    require(right > left && bottom > top) { "Vision ROI does not intersect the frame" }
    return VisionRectPx(left, top, right - left, bottom - top)
}

private fun missing(kind: VisionFeatureKind, message: String) = VisionFeatureDetection(
    kind = kind,
    found = false,
    confidence = 0.0,
    centerPx = null,
    message = message
)
