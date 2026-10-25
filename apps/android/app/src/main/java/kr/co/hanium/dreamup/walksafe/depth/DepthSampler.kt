package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class DepthSample(
    val depthM: Float,
    val confidence: Float?,
    val candidate: DepthCandidate,
)

private data class DepthCandidate(
    val x: Int,
    val y: Int,
    val row: Int,
    val column: Int,
    val rowPixels: IntArray,
) {
    fun isAdjacentTo(other: DepthCandidate): Boolean {
        if (abs(row - other.row) > 1) return false
        if (rowPixels.size == other.rowPixels.size) return abs(column - other.column) <= 1
        // Across unequal row densities, align by the closest actual sample position first.
        // Its immediate neighbors cover both diagonal directions without skipping grid cells.
        return includesNeighborColumnAt(other.x) || other.includesNeighborColumnAt(x)
    }

    private fun includesNeighborColumnAt(pixelX: Int): Boolean {
        val match = rowPixels.binarySearch(pixelX)
        if (match >= 0) return abs(column - match) <= 1
        val right = -match - 1
        if (right == 0) return column <= 1
        if (right == rowPixels.size) return column >= rowPixels.lastIndex - 1
        val left = right - 1
        val leftDistance = pixelX - rowPixels[left]
        val rightDistance = rowPixels[right] - pixelX
        // At an exact midpoint both equally close columns are valid alignments.
        return (leftDistance <= rightDistance && abs(column - left) <= 1) ||
            (rightDistance <= leftDistance && abs(column - right) <= 1)
    }
}

data class DepthImage16(
    val width: Int,
    val height: Int,
    val millimeters: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "depth image dimensions must be positive" }
        require(millimeters.size >= width * height) { "depth image data is smaller than width*height" }
    }

    fun depthMetersAt(x: Int, y: Int): Float? {
        if (x !in 0 until width || y !in 0 until height) return null
        val mm = millimeters[y * width + x]
        if (mm <= 0) return null
        return mm / 1000f
    }
}

data class ConfidenceImage8(
    val width: Int,
    val height: Int,
    val values: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "confidence image dimensions must be positive" }
        require(values.size >= width * height) { "confidence image data is smaller than width*height" }
    }

    fun confidenceAt(x: Int, y: Int): Float? {
        if (x !in 0 until width || y !in 0 until height) return null
        return (values[y * width + x].toInt() and 0xff) / 255f
    }
}

data class DepthSampleOptions(
    val minDepthM: Float = 0.2f,
    val maxDepthM: Float = 8.0f,
    val minConfidence: Float? = 0.35f,
    val targetSamples: Int = 160,
    val maxSamples: Int = 500,
    val minValidSamples: Int = 30,
    val outlierMadK: Float = 3.0f,
    val minOutlierBandM: Float = 0.25f,
    val polygonErosionRatio: Float = 0.02f,
)

/**
 * Samples metric depth inside a polygon already mapped to normalized depth-image coordinates.
 * Invalid/confidence-gated pixels and median-absolute-deviation outliers never enter the result.
 */
class DepthSampler {
    private val motionSampleOptions = DepthSampleOptions()
    /** Samples only the contact's five-by-five depth-pixel neighborhood, never the object median. */
    fun sampleContactDepth(
        depthImage: DepthImage16,
        confidenceImage: ConfidenceImage8?,
        polygonDepthNorm: List<Point2>,
        contactDepthNorm: Point2,
        minConfidence: Float?,
    ): ContactDepthEstimate? = samplePointNeighborhood(depthImage, confidenceImage, polygonDepthNorm,
        contactDepthNorm, minConfidence, CONTACT_MIN_DEPTH_M, CONTACT_MAX_DEPTH_M)

    private fun samplePointNeighborhood(
        depthImage: DepthImage16,
        confidenceImage: ConfidenceImage8?,
        polygonDepthNorm: List<Point2>,
        contactDepthNorm: Point2,
        minConfidence: Float?,
        minDepthM: Float,
        maxDepthM: Float,
    ): ContactDepthEstimate? {
        if (polygonDepthNorm.size < 3 || !contactDepthNorm.x.isFinite() || !contactDepthNorm.y.isFinite() ||
            contactDepthNorm.x !in 0f..1f || contactDepthNorm.y !in 0f..1f ||
            (confidenceImage != null &&
                (confidenceImage.width != depthImage.width || confidenceImage.height != depthImage.height))
        ) return null

        val centerX = (contactDepthNorm.x * (depthImage.width - 1)).roundToInt()
        val centerY = (contactDepthNorm.y * (depthImage.height - 1)).roundToInt()
        val depths = mutableListOf<Float>()
        var eligiblePixels = 0
        for (y in max(0, centerY - CONTACT_RADIUS_PX)..min(depthImage.height - 1, centerY + CONTACT_RADIUS_PX)) {
            for (x in max(0, centerX - CONTACT_RADIUS_PX)..min(depthImage.width - 1, centerX + CONTACT_RADIUS_PX)) {
                val point = Point2(x / max(1f, (depthImage.width - 1).toFloat()),
                    y / max(1f, (depthImage.height - 1).toFloat()))
                if (!pointInPolygon(point, polygonDepthNorm)) continue
                eligiblePixels += 1
                val depth = depthImage.depthMetersAt(x, y) ?: continue
                if (depth !in minDepthM..maxDepthM) continue
                if (minConfidence != null && (confidenceImage?.confidenceAt(x, y) ?: 0f) < minConfidence) continue
                depths += depth
            }
        }
        if (depths.size < MIN_CONTACT_SAMPLES || eligiblePixels == 0 ||
            depths.size.toFloat() / eligiblePixels < MIN_CONTACT_VALID_RATIO
        ) return null
        val sorted = depths.sorted()
        val spread = sorted.last() - sorted.first()
        if (spread > MAX_CONTACT_SPREAD_M) return null
        return ContactDepthEstimate(
            distanceM = percentile(sorted, 0.5f) ?: return null,
            spreadM = spread,
            validSampleCount = depths.size,
        )
    }

    /** A motion point needs its own valid center pixel as well as local surface support. */
    internal fun sampleMaterialPointDepth(
        depthImage: DepthImage16,
        confidenceImage: ConfidenceImage8?,
        polygonDepthNorm: List<Point2>,
        pointDepthNorm: Point2,
        minConfidence: Float?,
    ): Float? {
        if (!pointDepthNorm.x.isFinite() || !pointDepthNorm.y.isFinite() ||
            pointDepthNorm.x !in 0f..1f || pointDepthNorm.y !in 0f..1f ||
            (confidenceImage != null && (confidenceImage.width != depthImage.width || confidenceImage.height != depthImage.height)) ||
            polygonDepthNorm.size < 3 || !pointInPolygon(pointDepthNorm, polygonDepthNorm)
        ) return null
        val x = (pointDepthNorm.x * (depthImage.width - 1)).roundToInt()
        val y = (pointDepthNorm.y * (depthImage.height - 1)).roundToInt()
        val pixel = Point2(x / max(1f, (depthImage.width - 1).toFloat()),
            y / max(1f, (depthImage.height - 1).toFloat()))
        if (!pointInPolygon(pixel, polygonDepthNorm)) return null
        val depth = depthImage.depthMetersAt(x, y)?.takeIf { it in motionSampleOptions.minDepthM..motionSampleOptions.maxDepthM } ?: return null
        if (minConfidence != null && (confidenceImage?.confidenceAt(x, y) ?: 0f) < minConfidence) return null
        samplePointNeighborhood(depthImage, confidenceImage, polygonDepthNorm, pointDepthNorm, minConfidence,
            motionSampleOptions.minDepthM, motionSampleOptions.maxDepthM) ?: return null
        // Neighbors validate support but cannot substitute their median for the actual point.
        return depth
    }

    fun sampleObjectDepth(
        depthImage: DepthImage16,
        confidenceImage: ConfidenceImage8?,
        polygonDepthNorm: List<Point2>,
        options: DepthSampleOptions = DepthSampleOptions(),
    ): DepthStats {
        if (polygonDepthNorm.size < 3) {
            return emptyStats()
        }

        val polygon = erodePolygonTowardCentroid(polygonDepthNorm, options.polygonErosionRatio)
            .takeIf { it.size >= 3 }
            ?: polygonDepthNorm
        val candidates = candidatePixels(depthImage.width, depthImage.height, polygon, options.maxSamples)
            .filter { candidate ->
                // Centroid contraction can leave a concave mask; never sample outside the source.
                pointInPolygon(Point2(candidate.x / max(1f, (depthImage.width - 1).toFloat()),
                    candidate.y / max(1f, (depthImage.height - 1).toFloat())), polygonDepthNorm)
            }
        var invalidZero = 0
        var tooNear = 0
        var tooFar = 0
        var lowConfidence = 0
        val samples = candidates.mapNotNull { candidate ->
            // Candidates lie inside the image, so a missing value here is a zero/invalid pixel.
            val depth = depthImage.depthMetersAt(candidate.x, candidate.y)
            if (depth == null) {
                invalidZero += 1
                return@mapNotNull null
            }
            // Low confidence is classified before range, so near low-confidence noise is never
            // counted as too-near evidence. The kept sample set is unchanged by this order.
            val confidence = confidenceImage?.confidenceAt(candidate.x, candidate.y)
            if (options.minConfidence != null && confidenceImage != null && (confidence ?: 0f) < options.minConfidence) {
                lowConfidence += 1
                return@mapNotNull null
            }
            if (depth < options.minDepthM) {
                tooNear += 1
                return@mapNotNull null
            }
            if (depth > options.maxDepthM) {
                tooFar += 1
                return@mapNotNull null
            }
            DepthSample(depthM = depth, confidence = confidence, candidate = candidate)
        }
        val rejected = DepthSampleRejections(invalidZero, tooNear, tooFar, lowConfidence)

        return robustStats(samples, candidates.size, options, candidates).copy(rejectedSamples = rejected)
    }

    private fun candidatePixels(width: Int, height: Int, polygonNorm: List<Point2>, maxSamples: Int): List<DepthCandidate> {
        val sampleLimit = if (maxSamples > 0) maxSamples.toLong() else width.toLong() * height.toLong()
        val minX = polygonNorm.minOf { it.x }.coerceIn(0f, 1f)
        val maxX = polygonNorm.maxOf { it.x }.coerceIn(0f, 1f)
        val minY = polygonNorm.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = polygonNorm.maxOf { it.y }.coerceIn(0f, 1f)
        val left = floor(minX * (width - 1)).toInt().coerceIn(0, width - 1)
        val right = floor(maxX * (width - 1)).toInt().coerceIn(0, width - 1)
        val top = floor(minY * (height - 1)).toInt().coerceIn(0, height - 1)
        val bottom = floor(maxY * (height - 1)).toInt().coerceIn(0, height - 1)
        val boxWidth = right.toLong() - left.toLong() + 1L
        val boxHeight = bottom.toLong() - top.toLong() + 1L
        val sampleCount = min(sampleLimit, boxWidth * boxHeight)
        if (sampleCount == 0L) return emptyList()

        val minimumRows = sampleCount / boxWidth + (if (sampleCount % boxWidth != 0L) 1L else 0L)
        val maximumRows = min(sampleCount, boxHeight)
        val approximateRows = floor(
            kotlin.math.sqrt(sampleCount.toDouble() * boxHeight.toDouble() / boxWidth.toDouble()) + 0.5,
        ).toLong()
        val rowCount = approximateRows.coerceIn(minimumRows, maximumRows)
        val baseColumns = sampleCount / rowCount
        val remainder = sampleCount % rowCount
        val points = mutableListOf<DepthCandidate>()

        // Visit the full two-dimensional extent without a ceil-stride budget cliff.
        // Polygon rejection does not refill the budget or relax later quality gates.
        for (row in 0L until rowCount) {
            val columnCount = baseColumns + (if (row < remainder) 1L else 0L)
            val y = top + (((2L * row + 1L) * boxHeight - 1L) / (2L * rowCount)).toInt()
            val rowPixels = IntArray(columnCount.toInt()) { column ->
                left + (((2L * column + 1L) * boxWidth - 1L) / (2L * columnCount)).toInt()
            }
            for (column in rowPixels.indices) {
                val x = rowPixels[column]
                val norm = Point2(x / max(1f, (width - 1).toFloat()), y / max(1f, (height - 1).toFloat()))
                if (pointInPolygon(norm, polygonNorm)) {
                    points += DepthCandidate(
                        x = x,
                        y = y,
                        row = row.toInt(),
                        column = column,
                        rowPixels = rowPixels,
                    )
                }
            }
        }

        return points
    }

    private fun robustStats(samples: List<DepthSample>, totalSampleCount: Int, options: DepthSampleOptions,
                            candidates: List<DepthCandidate> = emptyList()): DepthStats {
        val sortedDepths = samples.map { it.depthM }.sorted()
        val med = percentile(sortedDepths, 0.5f) ?: return emptyStats(samples.size, totalSampleCount)
        val deviations = sortedDepths.map { abs(it - med) }.sorted()
        val mad = percentile(deviations, 0.5f) ?: 0f
        val band = max(options.minOutlierBandM, mad * options.outlierMadK)
        // A supported nearer population is not an isolated outlier. Without spatial membership
        // evidence neither the majority background nor the minimum is a defensible object depth.
        // Reuse the existing support gate rather than inventing a foreground operating threshold.
        val originalRiskPercentile = percentile(sortedDepths, 0.20f) ?: med
        val nearSamples = samples.filter { it.depthM < med - band }
        if (originalRiskPercentile < med - band ||
            nearSamples.size >= options.minValidSamples || nearSamples.indices.any { index ->
                (index + 1 until nearSamples.size).any { otherIndex ->
                    nearSamples[index].candidate.isAdjacentTo(nearSamples[otherIndex].candidate)
                }
            }
        ) {
            return emptyStats(validSampleCount = samples.size, totalSampleCount = totalSampleCount,
                foregroundAmbiguous = true)
        }
        if (samples.size < options.minValidSamples) {
            return emptyStats(validSampleCount = samples.size, totalSampleCount = totalSampleCount)
        }
        val kept = samples.filter { abs(it.depthM - med) <= band }
        if (kept.size < options.minValidSamples) {
            return emptyStats(validSampleCount = kept.size, totalSampleCount = totalSampleCount)
        }
        val keptSorted = kept.map { it.depthM }.sorted()
        val p10 = percentile(keptSorted, 0.10f)
        val p20 = percentile(keptSorted, 0.20f)
        val p80 = percentile(keptSorted, 0.80f)
        val p25 = percentile(keptSorted, 0.25f)
        val p75 = percentile(keptSorted, 0.75f)
        val iqr = if (p25 != null && p75 != null) p75 - p25 else null
        val confidenceMedian = percentile(kept.mapNotNull { it.confidence }.sorted(), 0.5f)
        val outlierRatio = if (samples.isEmpty()) 1f else (samples.size - kept.size).toFloat() / samples.size

        return DepthStats(
            validSampleCount = kept.size,
            validSampleRatio = if (totalSampleCount == 0) 0f else kept.size.toFloat() / totalSampleCount,
            medianM = percentile(keptSorted, 0.5f),
            p10M = p10,
            p20M = p20,
            p80M = p80,
            iqrM = iqr,
            madM = mad,
            confidenceMedian = confidenceMedian,
            outlierRatio = outlierRatio.coerceIn(0f, 1f),
            spatialCoverage = longAxisCoverage(candidates, kept),
        )
    }

    /**
     * S11-6: how many long-axis thirds of the in-region candidates hold a kept sample, divided by
     * the thirds that hold any candidate (a concave mask can leave a third empty). Measured along
     * the longer candidate extent in depth pixels, so a thin object is not judged by its width.
     */
    private fun longAxisCoverage(candidates: List<DepthCandidate>, kept: List<DepthSample>): Float? {
        if (candidates.isEmpty()) return null
        val xExtent = candidates.maxOf { it.x } - candidates.minOf { it.x } + 1
        val yExtent = candidates.maxOf { it.y } - candidates.minOf { it.y } + 1
        val horizontal = xExtent >= yExtent
        val longCoverage = axisCoverage(candidates, kept, alongX = horizontal)
        // S11-6: a wide object (short extent at least half the long one) is also judged across its
        // short axis, so depth crowded into one band is not full coverage; a thin object keeps
        // the long-axis judgement only.
        val wide = 2 * minOf(xExtent, yExtent) >= maxOf(xExtent, yExtent)
        if (!wide) return longCoverage
        return minOf(longCoverage, axisCoverage(candidates, kept, alongX = !horizontal))
    }

    private fun axisCoverage(candidates: List<DepthCandidate>, kept: List<DepthSample>, alongX: Boolean): Float {
        fun position(candidate: DepthCandidate) = if (alongX) candidate.x else candidate.y
        val first = candidates.minOf(::position)
        val extent = candidates.maxOf(::position) - first + 1
        fun bin(candidate: DepthCandidate) =
            ((position(candidate) - first).toLong() * LONG_AXIS_COVERAGE_BINS / extent).toInt()
                .coerceIn(0, LONG_AXIS_COVERAGE_BINS - 1)
        val eligible = candidates.map(::bin).toSet()
        val occupied = kept.map { bin(it.candidate) }.toSet()
        return occupied.size.toFloat() / eligible.size
    }

    private fun emptyStats(validSampleCount: Int = 0, totalSampleCount: Int = 0,
                           foregroundAmbiguous: Boolean = false): DepthStats {
        return DepthStats(
            validSampleCount = validSampleCount,
            validSampleRatio = if (totalSampleCount == 0) 0f else validSampleCount.toFloat() / totalSampleCount,
            medianM = null,
            p10M = null,
            p20M = null,
            p80M = null,
            iqrM = null,
            madM = null,
            confidenceMedian = null,
            outlierRatio = 1f,
            foregroundAmbiguous = foregroundAmbiguous,
        )
    }

    private companion object {
        const val LONG_AXIS_COVERAGE_BINS = 3
        const val CONTACT_RADIUS_PX = 2
        const val MIN_CONTACT_SAMPLES = 5
        const val MIN_CONTACT_VALID_RATIO = 0.6f
        const val CONTACT_MIN_DEPTH_M = 0.5f
        const val CONTACT_MAX_DEPTH_M = 5f
        const val MAX_CONTACT_SPREAD_M = 0.35f
    }
}

fun bboxPolygon(rect: RectNorm, erosionRatio: Float = 0.08f): List<Point2> {
    val ex = (rect.width * erosionRatio).coerceAtLeast(0f)
    val ey = (rect.height * erosionRatio).coerceAtLeast(0f)
    val left = (rect.x + ex).coerceIn(0f, 1f)
    val top = (rect.y + ey).coerceIn(0f, 1f)
    val right = (rect.x + rect.width - ex).coerceIn(0f, 1f)
    val bottom = (rect.y + rect.height - ey).coerceIn(0f, 1f)
    return listOf(Point2(left, top), Point2(right, top), Point2(right, bottom), Point2(left, bottom))
}

fun erodePolygonTowardCentroid(polygon: List<Point2>, erosionRatio: Float): List<Point2> {
    if (polygon.size < 3 || erosionRatio <= 0f) return polygon
    val center = Point2(
        polygon.map { it.x }.average().toFloat(),
        polygon.map { it.y }.average().toFloat(),
    )
    val keep = (1f - erosionRatio.coerceIn(0f, 0.45f))
    return polygon.map { point ->
        Point2(
            (center.x + (point.x - center.x) * keep).coerceIn(0f, 1f),
            (center.y + (point.y - center.y) * keep).coerceIn(0f, 1f),
        )
    }
}

fun pointInPolygon(point: Point2, polygon: List<Point2>): Boolean {
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val denominator = previous.y - current.y
            val xAtY = (previous.x - current.x) * (point.y - current.y) / (if (denominator != 0f) denominator else 1e-6f) + current.x
            if (point.x < xAtY) inside = !inside
        }
        previous = current
    }
    return inside
}

fun percentile(sortedValues: List<Float>, q: Float): Float? {
    if (sortedValues.isEmpty()) return null
    val clamped = q.coerceIn(0f, 1f)
    val index = clamped * (sortedValues.size - 1)
    val lower = floor(index).toInt()
    val upper = min(sortedValues.lastIndex, lower + 1)
    val weight = index - lower
    return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight
}
