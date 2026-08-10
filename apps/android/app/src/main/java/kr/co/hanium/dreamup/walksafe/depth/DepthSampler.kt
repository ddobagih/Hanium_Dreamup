package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private data class DepthSample(
    val depthM: Float,
    val confidence: Float?,
)

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
        val samples = candidates.mapNotNull { (x, y) ->
            val depth = depthImage.depthMetersAt(x, y) ?: return@mapNotNull null
            if (depth < options.minDepthM || depth > options.maxDepthM) return@mapNotNull null
            val confidence = confidenceImage?.confidenceAt(x, y)
            if (options.minConfidence != null && confidenceImage != null && (confidence ?: 0f) < options.minConfidence) {
                return@mapNotNull null
            }
            DepthSample(depthM = depth, confidence = confidence)
        }

        if (samples.size < options.minValidSamples) {
            return emptyStats(validSampleCount = samples.size, totalSampleCount = candidates.size)
        }

        return robustStats(samples, candidates.size, options)
    }

    private fun candidatePixels(width: Int, height: Int, polygonNorm: List<Point2>, maxSamples: Int): List<Pair<Int, Int>> {
        val sampleLimit = if (maxSamples > 0) maxSamples else width * height
        val minX = polygonNorm.minOf { it.x }.coerceIn(0f, 1f)
        val maxX = polygonNorm.maxOf { it.x }.coerceIn(0f, 1f)
        val minY = polygonNorm.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = polygonNorm.maxOf { it.y }.coerceIn(0f, 1f)
        val left = floor(minX * (width - 1)).toInt().coerceIn(0, width - 1)
        val right = floor(maxX * (width - 1)).toInt().coerceIn(0, width - 1)
        val top = floor(minY * (height - 1)).toInt().coerceIn(0, height - 1)
        val bottom = floor(maxY * (height - 1)).toInt().coerceIn(0, height - 1)
        val boxPixels = max(1, (right - left + 1) * (bottom - top + 1))
        val stride = max(1, ceil(kotlin.math.sqrt(boxPixels / sampleLimit.toDouble())).toInt())
        val points = mutableListOf<Pair<Int, Int>>()

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val norm = Point2(x / max(1f, (width - 1).toFloat()), y / max(1f, (height - 1).toFloat()))
                if (pointInPolygon(norm, polygonNorm)) {
                    points += x to y
                }
                x += stride
            }
            y += stride
        }

        return points.take(sampleLimit)
    }

    private fun robustStats(samples: List<DepthSample>, totalSampleCount: Int, options: DepthSampleOptions): DepthStats {
        val sortedDepths = samples.map { it.depthM }.sorted()
        val med = percentile(sortedDepths, 0.5f) ?: return emptyStats(samples.size, totalSampleCount)
        val deviations = sortedDepths.map { abs(it - med) }.sorted()
        val mad = percentile(deviations, 0.5f) ?: 0f
        val band = max(options.minOutlierBandM, mad * options.outlierMadK)
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
        )
    }

    private fun emptyStats(validSampleCount: Int = 0, totalSampleCount: Int = 0): DepthStats {
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
        )
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
