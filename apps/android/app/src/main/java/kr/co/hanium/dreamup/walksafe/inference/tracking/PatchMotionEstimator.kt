package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class TrackingBudgetExceeded(val failure: VisualTrackingFailure) : RuntimeException()

internal class TrackingWorkBudget(
    private val config: VisualTrackingConfig,
    private val clockNanos: () -> Long,
    val startedAtNs: Long,
    val executionBudgetNs: Long = config.maxExecutionNs,
) {
    var pixelComparisons: Long = 0L
        private set

    fun charge(samples: Int = 0) {
        if (pixelComparisons + samples > config.maxPixelComparisonsPerCall) {
            throw TrackingBudgetExceeded(VisualTrackingFailure.WORK_BUDGET_EXCEEDED)
        }
        if (clockNanos() - startedAtNs >= executionBudgetNs) {
            throw TrackingBudgetExceeded(VisualTrackingFailure.TIME_BUDGET_EXCEEDED)
        }
        pixelComparisons += samples
    }
}

/** Fixed-source image templates prevent rounding/drift from accumulating across adjacent frames. */
internal class PatchMotionEstimator(private val maxFeatures: Int) : VisualMotionEstimator {
    override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature> {
        val rect = detection.bboxNorm
        val left = maxOf(PATCH_RADIUS + 2, ceil(rect.x * frame.width).toInt() + PATCH_RADIUS)
        val top = maxOf(PATCH_RADIUS + 2, ceil(rect.y * frame.height).toInt() + PATCH_RADIUS)
        val right = minOf(frame.width - PATCH_RADIUS - 3, floor((rect.x + rect.width) * frame.width).toInt() - PATCH_RADIUS)
        val bottom = minOf(frame.height - PATCH_RADIUS - 3, floor((rect.y + rect.height) * frame.height).toInt() - PATCH_RADIUS)
        if (right <= left || bottom <= top) return emptyList()
        val cells = mutableListOf<Corner>()
        for (cellY in 0 until 4) for (cellX in 0 until 4) {
            var best: Corner? = null
            val x0 = left + (right - left) * cellX / 4
            val x1 = left + (right - left) * (cellX + 1) / 4
            val y0 = top + (bottom - top) * cellY / 4
            val y1 = top + (bottom - top) * (cellY + 1) / 4
            for (y in y0..y1 step 2) {
                budget.charge()
                for (x in x0..x1 step 2) {
                    if (!patchInsidePolygon(x, y, frame, detection.polygonNorm)) continue
                    val score = cornerScore(frame, x, y)
                    if (score >= MIN_CORNER_SCORE && (best == null || score > best.score)) {
                        best = Corner(x, y, score)
                    }
                }
            }
            best?.let { cells += it }
        }
        val selected = mutableListOf<Corner>()
        // Preserve support in all quadrants before spending the remaining feature allowance.
        for (quadrantY in 0..1) for (quadrantX in 0..1) {
            cells.filter {
                (if (it.x < (left + right) / 2f) 0 else 1) == quadrantX &&
                    (if (it.y < (top + bottom) / 2f) 0 else 1) == quadrantY
            }.maxByOrNull { it.score }?.let { selected += it }
        }
        for (candidate in cells.sortedByDescending { it.score }) {
            if (selected.size >= maxFeatures) break
            if (selected.none { hypot(it.x - candidate.x, it.y - candidate.y) < 6f }) selected += candidate
        }
        return selected.take(maxFeatures).map { VisualMotionFeature(it.x, it.y, template(frame, it.x, it.y)) }
    }

    override fun advance(
        source: GrayTrackingFrame,
        previous: GrayTrackingFrame,
        target: GrayTrackingFrame,
        detection: DetectionCandidate,
        features: List<VisualMotionFeature>,
        originalFeatureCount: Int,
        budget: TrackingWorkBudget,
    ): VisualMotionEstimate {
        val matches = mutableListOf<Match>()
        var lastFailure = VisualTrackingFailure.TOO_FEW_FEATURES
        for (feature in features) {
            val forward = search(feature.template, target, feature.currentX.roundToInt(), feature.currentY.roundToInt(), budget)
            if (forward.failure != null) {
                lastFailure = forward.failure
                continue
            }
            val backward = search(
                template(target, forward.x.roundToInt(), forward.y.roundToInt()),
                source, feature.sourceX, feature.sourceY, budget,
            )
            if (backward.failure != null || hypot(backward.x - feature.sourceX, backward.y - feature.sourceY) > 1.25f) {
                lastFailure = backward.failure ?: VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH
                continue
            }
            matches += Match(feature.copy(currentX = forward.x, currentY = forward.y), forward.cost)
        }
        val minimumSurvivors = maxOf(6, ceil(originalFeatureCount * 0.65f).toInt())
        if (matches.size < minimumSurvivors) return failed(lastFailure)
        val dx = median(matches.map { it.feature.currentX - it.feature.sourceX })
        val dy = median(matches.map { it.feature.currentY - it.feature.sourceY })
        val inliers = matches.filter {
            hypot(it.feature.currentX - it.feature.sourceX - dx, it.feature.currentY - it.feature.sourceY - dy) <= 1.25f
        }
        if (inliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        val kept = inliers.map { it.feature }
        if (!hasCoverage(kept, detection, source)) return failed(VisualTrackingFailure.INSUFFICIENT_COVERAGE)
        if (!translationShapeValid(kept, detection, source)) return failed(VisualTrackingFailure.UNSUPPORTED_SCALE_OR_ROTATION)
        val residual = median(kept.map { hypot(it.currentX - it.sourceX - dx, it.currentY - it.sourceY - dy) })
        val quality = (inliers.size.toFloat() / originalFeatureCount) *
            (1f - median(inliers.map { it.cost }) / MAX_PATCH_COST).coerceIn(0f, 1f) *
            (1f - residual / 2f).coerceIn(0f, 1f)
        return VisualMotionEstimate(kept, VisualAffineTransform(tx = dx / source.width, ty = dy / source.height), quality, residual)
    }

    private fun search(template: FloatArray, frame: GrayTrackingFrame, cx: Int, cy: Int, budget: TrackingWorkBudget): Search {
        val side = SEARCH_RADIUS * 2 + 1
        val costs = FloatArray(side * side) { Float.POSITIVE_INFINITY }
        var best = Float.POSITIVE_INFINITY
        var bestX = cx
        var bestY = cy
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
            val x = cx + dx
            val y = cy + dy
            if (x < PATCH_RADIUS || y < PATCH_RADIUS || x >= frame.width - PATCH_RADIUS || y >= frame.height - PATCH_RADIUS) continue
            budget.charge(PATCH_SAMPLES)
            val mean = frame.patchMean(x, y, PATCH_RADIUS)
            var difference = 0f
            var index = 0
            for (py in -PATCH_RADIUS..PATCH_RADIUS) for (px in -PATCH_RADIUS..PATCH_RADIUS) {
                difference += abs(template[index++] - (frame.intensity(x + px, y + py) - mean))
            }
            val cost = difference / PATCH_SAMPLES
            costs[(dy + SEARCH_RADIUS) * side + dx + SEARCH_RADIUS] = cost
            if (cost < best) {
                best = cost
                bestX = x
                bestY = y
            }
        }
        if (!best.isFinite() || best > MAX_PATCH_COST) return Search(failure = VisualTrackingFailure.PHOTOMETRIC_MISMATCH)
        val bx = bestX - cx + SEARCH_RADIUS
        val by = bestY - cy + SEARCH_RADIUS
        if (bx == 0 || by == 0 || bx == side - 1 || by == side - 1) return Search(failure = VisualTrackingFailure.SEARCH_BOUNDARY)
        var second = Float.POSITIVE_INFINITY
        for (y in 0 until side) for (x in 0 until side) {
            if (abs(x - bx) > 1 || abs(y - by) > 1) second = minOf(second, costs[y * side + x])
        }
        if (!second.isFinite() || second - best < maxOf(2f, best * 0.20f)) return Search(failure = VisualTrackingFailure.AMBIGUOUS_PATCH)
        val subX = refine(costs[by * side + bx - 1], best, costs[by * side + bx + 1])
        val subY = refine(costs[(by - 1) * side + bx], best, costs[(by + 1) * side + bx])
        return Search(bestX + subX, bestY + subY, best)
    }

    private fun template(frame: GrayTrackingFrame, x: Int, y: Int): FloatArray {
        val mean = frame.patchMean(x, y, PATCH_RADIUS)
        return FloatArray(PATCH_SAMPLES) { index ->
            frame.intensity(x + index % PATCH_SIDE - PATCH_RADIUS, y + index / PATCH_SIDE - PATCH_RADIUS) - mean
        }
    }

    private fun cornerScore(frame: GrayTrackingFrame, x: Int, y: Int): Float {
        var xx = 0f
        var xy = 0f
        var yy = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val gx = frame.intensity(x + dx + 1, y + dy) - frame.intensity(x + dx - 1, y + dy)
            val gy = frame.intensity(x + dx, y + dy + 1) - frame.intensity(x + dx, y + dy - 1)
            xx += gx * gx
            xy += gx * gy
            yy += gy * gy
        }
        return (xx + yy - sqrt((xx - yy) * (xx - yy) + 4f * xy * xy)) / 50f
    }

    private fun patchInsidePolygon(x: Int, y: Int, frame: GrayTrackingFrame, polygon: List<Point2>): Boolean {
        if (polygon.isEmpty()) return true
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
            if (!pointInside((x + dx).toFloat() / frame.width, (y + dy).toFloat() / frame.height, polygon)) return false
        }
        return true
    }

    private fun pointInside(x: Float, y: Float, polygon: List<Point2>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            if ((current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x) inside = !inside
            previous = current
        }
        return inside
    }

    private fun hasCoverage(features: List<VisualMotionFeature>, detection: DetectionCandidate, frame: GrayTrackingFrame): Boolean {
        val expectedWidth = detection.bboxNorm.width * frame.width
        val expectedHeight = detection.bboxNorm.height * frame.height
        val sourceWidth = features.maxOf { it.sourceX } - features.minOf { it.sourceX }
        val sourceHeight = features.maxOf { it.sourceY } - features.minOf { it.sourceY }
        val targetWidth = features.maxOf { it.currentX } - features.minOf { it.currentX }
        val targetHeight = features.maxOf { it.currentY } - features.minOf { it.currentY }
        return minOf(sourceWidth.toFloat(), targetWidth) >= expectedWidth * 0.40f &&
            minOf(sourceHeight.toFloat(), targetHeight) >= expectedHeight * 0.40f
    }

    private fun translationShapeValid(features: List<VisualMotionFeature>, detection: DetectionCandidate, frame: GrayTrackingFrame): Boolean {
        val minDistance = minOf(detection.bboxNorm.width * frame.width, detection.bboxNorm.height * frame.height) * 0.35f
        val scales = mutableListOf<Float>()
        val angles = mutableListOf<Float>()
        for (i in features.indices) for (j in i + 1 until features.size) {
            val sx = (features[j].sourceX - features[i].sourceX).toFloat()
            val sy = (features[j].sourceY - features[i].sourceY).toFloat()
            val distance = hypot(sx, sy)
            if (distance < minDistance) continue
            val tx = features[j].currentX - features[i].currentX
            val ty = features[j].currentY - features[i].currentY
            scales += hypot(tx, ty) / distance
            angles += abs(atan2(sx * ty - sy * tx, sx * tx + sy * ty))
        }
        return scales.size >= 3 && median(scales) in 0.96f..1.04f && median(angles) <= 0.04f
    }

    private fun failed(failure: VisualTrackingFailure) = VisualMotionEstimate(emptyList(), VisualAffineTransform(), 0f, 0f, failure)
    private fun refine(left: Float, center: Float, right: Float): Float {
        val curvature = left - 2f * center + right
        return if (!curvature.isFinite() || curvature <= 0.001f) 0f else ((left - right) / (2f * curvature)).coerceIn(-0.5f, 0.5f)
    }
    private fun median(values: List<Float>): Float = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2f }
    private fun hypot(x: Int, y: Int): Float = hypot(x.toFloat(), y.toFloat())
    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)
    private data class Corner(val x: Int, val y: Int, val score: Float)
    private data class Match(val feature: VisualMotionFeature, val cost: Float)
    private data class Search(val x: Float = 0f, val y: Float = 0f, val cost: Float = 0f, val failure: VisualTrackingFailure? = null)

    private companion object {
        const val PATCH_RADIUS = 3
        const val PATCH_SIDE = 7
        const val PATCH_SAMPLES = 49
        const val SEARCH_RADIUS = 8
        const val MIN_CORNER_SCORE = 30f
        const val MAX_PATCH_COST = 28f
    }
}
