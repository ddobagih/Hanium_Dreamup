package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val UNNAMED_OBSTACLE_CLASS = "unnamed-obstacle"

/** Immutable mask in original unrotated CPU-image pixels, with [left,right)/[top,bottom) bounds. */
class BinaryImageMask private constructor(
    val originalWidth: Int,
    val originalHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    private val rowWords: LongArray,
    val area: Int,
) {
    private val wordsPerRow = ((width.toLong() + 63L) / 64L).toInt()

    fun contains(x: Int, y: Int): Boolean {
        if (x < left || x >= left + width || y < top || y >= top + height) return false
        val localX = x - left
        return rowWords[(y - top) * wordsPerRow + localX / 64] and (1L shl (localX % 64)) != 0L
    }

    /** Count actual foreground in an image rectangle, including holes; no full RGB scan. */
    fun areaInside(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val startX = max(left, x0); val endX = min(left + width, x1)
        val startY = max(top, y0); val endY = min(top + height, y1)
        if (startX >= endX || startY >= endY) return 0
        var count = 0
        for (y in startY until endY) for (word in startX / 64..(endX - 1) / 64) {
            val low = max(0, startX - word * 64)
            val high = min(64, endX - word * 64)
            val bounds = (-1L shl low) and (if (high == 64) -1L else (1L shl high) - 1L)
            count += java.lang.Long.bitCount(wordAt(y, word) and bounds)
        }
        return count
    }

    fun intersectionArea(other: BinaryImageMask): Int {
        if (originalWidth != other.originalWidth || originalHeight != other.originalHeight) return 0
        val x0 = max(left, other.left)
        val x1 = min(left + width, other.left + other.width)
        val y0 = max(top, other.top)
        val y1 = min(top + height, other.top + other.height)
        if (x1 <= x0 || y1 <= y0 || area == 0 || other.area == 0) return 0
        var count = 0
        for (y in y0 until y1) for (globalWord in x0 / 64..(x1 - 1) / 64) {
            count += java.lang.Long.bitCount(wordAt(y, globalWord) and other.wordAt(y, globalWord))
        }
        return count
    }

    fun iou(other: BinaryImageMask): Float {
        val intersection = intersectionArea(other)
        val union = area.toLong() + other.area - intersection
        return if (union > 0L) intersection / union.toFloat() else 0f
    }

    private fun wordAt(globalY: Int, globalWord: Int): Long {
        val offset = globalWord * 64 - left
        if (offset >= width || offset + 64 <= 0) return 0L
        val base = (globalY - top) * wordsPerRow
        if (offset < 0) return rowWords[base] shl -offset
        val index = offset / 64
        val shift = offset % 64
        val low = rowWords[base + index] ushr shift
        return if (shift == 0 || index + 1 >= wordsPerRow) low else low or (rowWords[base + index + 1] shl (64 - shift))
    }

    companion object {
        /** ROI row-major bits, least-significant-bit first. Storage is copied into immutable rows. */
        fun fromPackedRoi(originalWidth: Int, originalHeight: Int, left: Int, top: Int,
                          width: Int, height: Int, bytes: ByteArray): BinaryImageMask {
            require(originalWidth > 0 && originalHeight > 0 && left >= 0 && top >= 0 && width >= 0 && height >= 0)
            require(left.toLong() + width <= originalWidth && top.toLong() + height <= originalHeight)
            val pixels = width.toLong() * height
            require(pixels <= Int.MAX_VALUE && bytes.size.toLong() == (pixels + 7L) / 8L)
            val stride = (width.toLong() + 63L) / 64L
            require(stride * height <= Int.MAX_VALUE)
            val words = LongArray((stride * height).toInt())
            var area = 0
            for (y in 0 until height) for (x in 0 until width) {
                val bit = y * width + x
                if ((bytes[bit / 8].toInt() and 0xff) and (1 shl (bit % 8)) != 0) {
                    words[y * stride.toInt() + x / 64] = words[y * stride.toInt() + x / 64] or (1L shl (x % 64))
                    area++
                }
            }
            return BinaryImageMask(originalWidth, originalHeight, left, top, width, height, words, area)
        }
    }
}

/** Only whole-KNOWN depth may be supplied. Position and Z must describe the same measured support. */
data class SpatialAssociationEvidence(
    val axialDepthM: Float,
    val pointInAnchor: Vec3? = null,
    val pose: CameraPoseEvidence? = null,
    val depthObservationTimestampNs: Long,
    val confidence: Float,
    val source: DepthSource,
)

/** Camera timestamp is ns; frameTimestampMs is the copied frame observation time used by this tracker.
 * Geometry is normalized original CPU-image XYWH; pixel centers are (x+.5,y+.5). No display rotation.
 * A frame-local anchor/proposal number is never a persistent representative epoch.
 */
data class MaskAssociationEvidence(
    val frameTimestampMs: Long,
    val cameraTimestampNs: Long,
    val mask: BinaryImageMask,
    val spatial: SpatialAssociationEvidence? = null,
    val representativeEpoch: String? = null,
) {
    internal fun validAt(timestampMs: Long): Boolean = frameTimestampMs == timestampMs &&
        timestampMs >= 0L && cameraTimestampNs > 0L && mask.area > 0

    internal fun validSpatial(): SpatialAssociationEvidence? = spatial?.takeIf {
        it.axialDepthM.isFinite() && it.axialDepthM > 0f && it.confidence.isFinite() && it.confidence >= .55f &&
            it.source.trustedForStepGuidance && it.depthObservationTimestampNs > 0L &&
            cameraTimestampNs - it.depthObservationTimestampNs in 0L..250_000_000L &&
            (it.pose?.let { pose -> pose.referenceId >= 0L && pose.timestampMs == frameTimestampMs &&
                pose.imageProjection?.imageWidth == mask.originalWidth && pose.imageProjection?.imageHeight == mask.originalHeight &&
                pose.objectCenterInAnchor(Point2(.5f,.5f),1f) != null } != false) &&
            (it.pointInAnchor?.let { point -> point.x.isFinite() && point.y.isFinite() && point.z.isFinite() } != false)
    }
}

enum class MaskAssociationStatus { MATCHED, NEW, AMBIGUOUS }

data class MaskTrackAssignment(
    val sourceDetectionIndex: Int,
    val track: TrackState?,
    val status: MaskAssociationStatus,
    val reason: String,
)

/** Fixed conservative correspondence policy. Optional sensor evidence only adds a bounded tie-break. */
internal object MaskCorrespondencePolicy {
    private const val MIN_MASK_IOU = .15f
    private const val MIN_SCORE_MARGIN = .10f
    data class Match(val trackIndex: Int, val geometryIndex: Int)
    data class Result(val matches: List<Match>, val ambiguous: Set<Int>)
    private data class Edge(val track: Int, val geometry: Int, val score: Float)

    fun associate(previous: List<Pair<Int, MaskAssociationEvidence>>,
                  current: List<Pair<Int, MaskAssociationEvidence>>,
                  candidateAllowed: (Int, Int) -> Boolean = { _, _ -> true }): Result {
        val edges = previous.flatMap { (track, old) -> current.mapNotNull { (geometry, now) ->
            if (!candidateAllowed(track, geometry)) return@mapNotNull null
            val gap = now.frameTimestampMs - old.frameTimestampMs
            if (gap !in 1L..1_500L || now.cameraTimestampNs <= old.cameraTimestampNs) return@mapNotNull null
            val iou = old.mask.iou(now.mask)
            if (iou < MIN_MASK_IOU) return@mapNotNull null
            Edge(track, geometry, iou + optionalSpatialBonus(old, now, gap / 1000f))
        } }
        val byTrack = edges.groupBy { it.track }
        val byGeometry = edges.groupBy { it.geometry }
        fun confidentBest(candidates: List<Edge>): Edge? {
            val sorted = candidates.sortedWith(compareByDescending<Edge> { it.score }.thenBy { it.track }.thenBy { it.geometry })
            if (sorted.size > 1 && sorted[0].score - sorted[1].score < MIN_SCORE_MARGIN) return null
            return sorted.firstOrNull()
        }
        val matches = edges.filter { edge -> confidentBest(byTrack.getValue(edge.track)) == edge &&
            confidentBest(byGeometry.getValue(edge.geometry)) == edge }.map { Match(it.track, it.geometry) }
        return Result(matches, byGeometry.keys - matches.map { it.geometryIndex }.toSet())
    }

    private fun optionalSpatialBonus(old: MaskAssociationEvidence, now: MaskAssociationEvidence, seconds: Float): Float {
        if (old.representativeEpoch != now.representativeEpoch) return 0f
        val a = old.validSpatial() ?: return 0f
        val b = now.validSpatial() ?: return 0f
        if (a.source != b.source || b.depthObservationTimestampNs <= a.depthObservationTimestampNs ||
            (a.pose != null && b.pose != null && a.pose.referenceId != b.pose.referenceId)) return 0f
        var bonus = .10f * (1f - abs(a.axialDepthM - b.axialDepthM) / (.25f + 5f * seconds)).coerceIn(0f, 1f)
        val pa = a.pose ?: return bonus
        val pb = b.pose ?: return bonus
        val xa = a.pointInAnchor ?: return bonus
        val xb = b.pointInAnchor ?: return bonus
        fun validPose(p: CameraPoseEvidence, at: Long) = p.referenceId >= 0 && p.timestampMs == at &&
            p.objectCenterInAnchor(Point2(.5f,.5f),1f) != null
        fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
        if (!validPose(pa, old.frameTimestampMs) || !validPose(pb, now.frameTimestampMs) ||
            pa.referenceId != pb.referenceId || !finite(xa) || !finite(xb)) return bonus
        bonus += .10f * (1f - (xb - xa).norm() / (.25f + 5f * seconds)).coerceIn(0f, 1f)
        return bonus
    }
}
