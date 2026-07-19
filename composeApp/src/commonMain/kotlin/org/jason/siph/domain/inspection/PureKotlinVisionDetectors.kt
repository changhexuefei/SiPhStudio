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

/** Detects the brightest compact lobe and rejects lower-contrast target structures. */
class FiberTipIntensityDetector : VisionFeatureDetector {
    override val supportedKinds = setOf(VisionFeatureKind.FiberTip)

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection {
        require(request.kind == VisionFeatureKind.FiberTip)
        val roi = frame.normalizedRoi(request.regionOfInterest)
        val stats = frame.statistics(roi)
        var maximum = 0
        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                maximum = max(maximum, frame.grayAt(x, y))
            }
        }
        val threshold = max(
            stats.mean + max(12.0, stats.standardDeviation * 1.35),
            maximum * 0.72
        ).coerceAtMost(250.0)

        var weightSum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var brightCount = 0
        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                val gray = frame.grayAt(x, y)
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
        val expectedArea = max(12.0, roi.width * roi.height * 0.008)
        val areaScore = (brightCount / expectedArea).coerceIn(0.0, 1.0)
        val confidence = (0.50 * brightnessScore + 0.38 * compactness + 0.12 * areaScore)
            .coerceIn(0.0, 1.0)
        val found = confidence >= request.minimumConfidence

        return VisionFeatureDetection(
            kind = VisionFeatureKind.FiberTip,
            found = found,
            confidence = confidence,
            centerPx = VisionPointPx(centerX, centerY).takeIf { found },
            angleDeg = null,
            widthPx = major,
            heightPx = minor,
            scoreDetails = mapOf(
                "brightness" to brightnessScore,
                "compactness" to compactness,
                "area" to areaScore,
                "threshold" to threshold,
                "maximum" to maximum.toDouble()
            ),
            message = if (found) {
                "Fiber tip detected"
            } else {
                "Fiber-tip candidate confidence is below threshold"
            }
        )
    }
}

/** Detects repeated high-contrast grating edges and localizes their 2D edge centroid. */
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
            if (
                energy[index] >= edgeThreshold &&
                energy[index] >= energy[index - 1] &&
                energy[index] >= energy[index + 1]
            ) {
                if (peaks.isEmpty() || index - peaks.last() >= 2) peaks += index
            }
        }

        if (peaks.size < 4) return missing(request.kind, "Insufficient repeated grating edges")
        val spacings = peaks.zipWithNext { left, right -> (right - left).toDouble() }
        val spacingMean = spacings.average()
        val spacingDeviation = sqrt(spacings.sumOf { (it - spacingMean) * (it - spacingMean) } / spacings.size)
        val regularity = if (spacingMean <= 1e-9) 0.0 else {
            (1.0 - spacingDeviation / spacingMean).coerceIn(0.0, 1.0)
        }
        val edgeContrast = ((energy.maxOrNull() ?: mean) - mean).div(80.0).coerceIn(0.0, 1.0)
        val countScore = (peaks.size / 10.0).coerceIn(0.0, 1.0)

        var rowWeightSum = 0.0
        var weightedY = 0.0
        var weightedYSquared = 0.0
        for (y in roi.top until roi.bottomExclusive) {
            var rowWeight = 0.0
            peaks.forEach { peakIndex ->
                val boundaryX = roi.left + peakIndex + 1
                val gradient = abs(
                    frame.grayAt(boundaryX, y) - frame.grayAt(boundaryX - 1, y)
                ).toDouble()
                rowWeight += (gradient - 6.0).coerceAtLeast(0.0)
            }
            if (rowWeight > 0.0) {
                rowWeightSum += rowWeight
                weightedY += y * rowWeight
                weightedYSquared += y * y * rowWeight
            }
        }
        if (rowWeightSum <= 0.0) return missing(request.kind, "Grating edges have no 2D support")

        val firstBoundaryX = roi.left + peaks.first() + 1.0
        val lastBoundaryX = roi.left + peaks.last() + 1.0
        val centerX = (firstBoundaryX + lastBoundaryX) / 2.0
        val centerY = weightedY / rowWeightSum
        val yVariance = max(0.0, weightedYSquared / rowWeightSum - centerY * centerY)
        val height = sqrt(yVariance) * 4.0
        val supportScore = (height / max(4.0, roi.height * 0.12)).coerceIn(0.0, 1.0)
        val confidence = (
            0.40 * regularity +
                0.30 * edgeContrast +
                0.18 * countScore +
                0.12 * supportScore
            ).coerceIn(0.0, 1.0)
        val found = confidence >= request.minimumConfidence

        return VisionFeatureDetection(
            kind = request.kind,
            found = found,
            confidence = confidence,
            centerPx = VisionPointPx(centerX, centerY).takeIf { found },
            angleDeg = 0.0,
            widthPx = lastBoundaryX - firstBoundaryX,
            heightPx = height,
            scoreDetails = mapOf(
                "edgeCount" to peaks.size.toDouble(),
                "regularity" to regularity,
                "edgeContrast" to edgeContrast,
                "meanSpacingPx" to spacingMean,
                "support" to supportScore
            ),
            message = if (found) {
                "Grating edges detected"
            } else {
                "Grating edge confidence is below threshold"
            }
        )
    }
}

/** Detects the longest bright connected line and rejects compact fiber-tip blobs. */
class FacetLineDetector : VisionFeatureDetector {
    override val supportedKinds = setOf(VisionFeatureKind.Facet)

    override suspend fun detect(
        frame: CameraFrame,
        request: VisionFeatureRequest
    ): VisionFeatureDetection {
        require(request.kind == VisionFeatureKind.Facet)
        val roi = frame.normalizedRoi(request.regionOfInterest)
        val stats = frame.statistics(roi)
        val threshold = stats.mean + max(16.0, stats.standardDeviation * 1.10)
        val components = brightComponents(frame, roi, threshold)
        val candidate = components
            .filter { it.points.size >= 8 && it.width >= 8 }
            .maxWithOrNull(
                compareBy<BrightComponent> { it.width }
                    .thenBy { it.points.size }
            ) ?: return missing(request.kind, "No elongated facet edge candidate")

        val points = candidate.points
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
        val rSquared = if (total <= 1e-9) 0.0 else {
            (1.0 - residual / total).coerceIn(0.0, 1.0)
        }
        val contrast = (
            (points.maxOf { frame.grayAt(it.x.toInt(), it.y.toInt()) } - stats.mean) / 180.0
            ).coerceIn(0.0, 1.0)
        val spanScore = (candidate.width / max(12.0, roi.width * 0.35)).coerceIn(0.0, 1.0)
        val elongation = (
            candidate.width.toDouble() / max(1.0, candidate.height.toDouble() * 2.0)
            ).coerceIn(0.0, 1.0)
        val confidence = (
            0.48 * rSquared +
                0.22 * contrast +
                0.18 * spanScore +
                0.12 * elongation
            ).coerceIn(0.0, 1.0)
        val angle = atan(slope) * 180.0 / PI
        val found = confidence >= request.minimumConfidence

        return VisionFeatureDetection(
            kind = request.kind,
            found = found,
            confidence = confidence,
            centerPx = VisionPointPx(meanX, meanY).takeIf { found },
            angleDeg = angle,
            widthPx = candidate.width.toDouble(),
            heightPx = sqrt(residual / points.size.coerceAtLeast(1)) * 2.0,
            scoreDetails = mapOf(
                "rSquared" to rSquared,
                "contrast" to contrast,
                "span" to spanScore,
                "elongation" to elongation,
                "componentPoints" to points.size.toDouble(),
                "threshold" to threshold
            ),
            message = if (found) {
                "Facet line detected"
            } else {
                "Facet line confidence is below threshold"
            }
        )
    }
}

private data class BrightComponent(
    val points: List<VisionPointPx>,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
}

private fun brightComponents(
    frame: CameraFrame,
    roi: VisionRectPx,
    threshold: Double
): List<BrightComponent> {
    val visited = BooleanArray(roi.width * roi.height)
    val components = mutableListOf<BrightComponent>()

    fun localIndex(x: Int, y: Int): Int = (y - roi.top) * roi.width + (x - roi.left)

    for (startY in roi.top until roi.bottomExclusive) {
        for (startX in roi.left until roi.rightExclusive) {
            val startIndex = localIndex(startX, startY)
            if (visited[startIndex] || frame.grayAt(startX, startY) < threshold) continue

            val queue = ArrayDeque<Int>()
            val points = mutableListOf<VisionPointPx>()
            var left = startX
            var right = startX
            var top = startY
            var bottom = startY
            visited[startIndex] = true
            queue.addLast(startIndex)

            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val localY = index / roi.width
                val localX = index % roi.width
                val x = roi.left + localX
                val y = roi.top + localY
                points += VisionPointPx(x.toDouble(), y.toDouble())
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        val nextY = y + dy
                        if (
                            nextX !in roi.left until roi.rightExclusive ||
                            nextY !in roi.top until roi.bottomExclusive
                        ) continue
                        val nextIndex = localIndex(nextX, nextY)
                        if (
                            !visited[nextIndex] &&
                            frame.grayAt(nextX, nextY) >= threshold
                        ) {
                            visited[nextIndex] = true
                            queue.addLast(nextIndex)
                        }
                    }
                }
            }
            components += BrightComponent(points, left, top, right, bottom)
        }
    }
    return components
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
