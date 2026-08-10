package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.atan2

data class DetectionCandidate(
    val className: String,
    val detectionConfidence: Float,
    val bboxNorm: RectNorm,
    val polygonNorm: List<Point2> = emptyList(),
    val maskAreaNorm: Float? = null,
)

/**
 * Normalizes detector geometry for tracking and depth sampling. A bbox-derived polygon is only a
 * sampling fallback when segmentation is absent, not evidence that a segmentation mask existed.
 */
class MaskPolygonExtractor(
    private val minPolygonPoints: Int = 3,
    private val fallbackBboxErosionRatio: Float = 0.10f,
) {
    fun extract(candidate: DetectionCandidate): ObjectGeometry {
        val bbox = normalizeRect(candidate.bboxNorm)
        val polygon = sanitizePolygon(candidate.polygonNorm).takeIf { it.size >= minPolygonPoints }
            ?: bboxPolygon(bbox, erosionRatio = fallbackBboxErosionRatio)
        val center = polygonCentroid(polygon) ?: bbox.center
        val bottomContact = bottomContactPoint(polygon)
        val maskArea = candidate.maskAreaNorm ?: polygonAreaNorm(polygon).takeIf { it > 0f } ?: bbox.area
        val centerline = if (isTactileBlockClass(candidate.className)) tactileCenterline(polygon) else emptyList()
        val aspectRatio = if (bbox.height > 0f) bbox.width / bbox.height else null
        val orientation = centerline.takeIf { it.size >= 2 }?.let {
            val first = it.first()
            val last = it.last()
            atan2(last.y - first.y, last.x - first.x)
        }

        return ObjectGeometry(
            className = candidate.className,
            detectionConfidence = candidate.detectionConfidence.coerceIn(0f, 1f),
            bboxNorm = bbox,
            polygonNorm = polygon,
            maskAreaNorm = maskArea.coerceIn(0f, 1f),
            centerNorm = normalizePoint(center),
            bottomContactNorm = bottomContact?.let(::normalizePoint),
            screenZone = screenZone(center),
            centerlineNorm = centerline,
            orientationRad = orientation,
            aspectRatio = aspectRatio,
        )
    }

    private fun sanitizePolygon(points: List<Point2>): List<Point2> {
        return points.map(::normalizePoint).distinct()
    }

    private fun normalizeRect(rect: RectNorm): RectNorm {
        val left = rect.x.coerceIn(0f, 1f)
        val top = rect.y.coerceIn(0f, 1f)
        val right = (rect.x + rect.width).coerceIn(0f, 1f)
        val bottom = (rect.y + rect.height).coerceIn(0f, 1f)
        return RectNorm(left, top, (right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
    }

    private fun normalizePoint(point: Point2): Point2 = Point2(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))

    private fun bottomContactPoint(polygon: List<Point2>): Point2? {
        if (polygon.size < minPolygonPoints) return null
        val maxY = polygon.maxOf { it.y }
        val minY = polygon.minOf { it.y }
        if (maxY <= minY) return null
        val bandTop = maxY - (maxY - minY) * 0.10f
        val bottomBand = polygon.filter { it.y >= bandTop }
        if (bottomBand.isEmpty() || maxY >= 0.995f) return null
        val medianX = percentile(bottomBand.map { it.x }.sorted(), 0.5f) ?: return null
        return Point2(medianX, maxY)
    }

    private fun polygonCentroid(polygon: List<Point2>): Point2? {
        if (polygon.size < minPolygonPoints) return null
        val areaTimesTwo = signedAreaTimesTwo(polygon)
        if (areaTimesTwo == 0f) return null
        var cx = 0f
        var cy = 0f
        var previous = polygon.last()
        for (current in polygon) {
            val cross = previous.x * current.y - current.x * previous.y
            cx += (previous.x + current.x) * cross
            cy += (previous.y + current.y) * cross
            previous = current
        }
        val factor = 1f / (3f * areaTimesTwo)
        return Point2(cx * factor, cy * factor)
    }

    private fun polygonAreaNorm(polygon: List<Point2>): Float = kotlin.math.abs(signedAreaTimesTwo(polygon)) / 2f

    private fun signedAreaTimesTwo(polygon: List<Point2>): Float {
        var sum = 0f
        var previous = polygon.last()
        for (current in polygon) {
            sum += previous.x * current.y - current.x * previous.y
            previous = current
        }
        return sum
    }

    private fun tactileCenterline(polygon: List<Point2>): List<Point2> {
        if (polygon.size < minPolygonPoints) return emptyList()
        val top = polygon.minBy { it.y }
        val bottom = polygon.maxBy { it.y }
        val centerX = polygon.map { it.x }.average().toFloat().coerceIn(0f, 1f)
        return listOf(Point2(centerX, top.y), Point2(centerX, bottom.y)).map(::normalizePoint)
    }

    private fun screenZone(point: Point2): ScreenZone {
        return when {
            point.y < 0.33f -> ScreenZone.UPPER
            point.y > 0.70f -> ScreenZone.LOWER
            point.x < 0.33f -> ScreenZone.LEFT
            point.x > 0.67f -> ScreenZone.RIGHT
            else -> ScreenZone.CENTER
        }
    }
}
