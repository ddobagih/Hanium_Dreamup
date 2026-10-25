package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** UI-thread state. Merging a visual region never removes its measurement or feedback candidate. */
internal class CameraObjectPresentation {
    data class Capture(val epoch: Int, val frameId: Long, val capturedAtMs: Long, val geometryId: String)

    data class Region(
        val bboxNorm: RectNorm,
        val trackId: String? = null,
        /** Only the distance supported by this region's captured metric depth; otherwise null. */
        val metricDistanceM: Double? = null,
        val confidence: Float = 1f,
    )

    private class PrimaryFrame(val capture: Capture, val regions: List<Region>) {
        // This edge belongs to these exact immutable frame snapshots, not to a tracking ID.
        var matchesFromPrevious: Map<Int, Int>? = null
        val uniqueTrackIndices = regions.mapIndexedNotNull { index, region ->
            region.trackId?.takeIf { it.isNotBlank() }?.let { it to index }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (id, indices) ->
            indices.singleOrNull()?.let { id to it }
        }.toMap()
    }
    private data class MergeCache(
        val capture: Capture,
        val regions: List<Region>,
        val primaryVersion: Long,
        val result: Map<Int, Int>,
    )
    private data class SourceMatchCache(val source: PrimaryFrame, val regions: List<Region>, val matches: Map<Int, Int>)
    /** Counts actual work, so direct tests can distinguish reuse from an equal recomputation. */
    data class ComputationStats(val mergePasses: Long, val geometricPasses: Long, val overlapComparisons: Long)
    private val history = ArrayDeque<PrimaryFrame>()
    private var primaryVersion = 0L
    private var mergeCache: MergeCache? = null
    private var sourceMatchCache: SourceMatchCache? = null
    private var mergePasses = 0L
    private var geometricPasses = 0L
    private var overlapComparisons = 0L
    val computationStats: ComputationStats
        get() = ComputationStats(mergePasses, geometricPasses, overlapComparisons)

    fun clear() {
        history.clear()
        mergeCache = null
        sourceMatchCache = null
        primaryVersion++
    }

    /** Record every completed primary frame, including empty frames; reject old or duplicate callbacks. */
    fun recordPrimary(capture: Capture, regions: List<Region>): Boolean {
        if (!validCapture(capture)) return false
        val previous = history.lastOrNull()?.capture
        if (previous != null) {
            if (capture.epoch < previous.epoch) return false
            if (capture.epoch == previous.epoch &&
                (capture.frameId <= previous.frameId || capture.capturedAtMs < previous.capturedAtMs)) return false
            if (capture.epoch != previous.epoch || capture.geometryId != previous.geometryId) clear()
        }
        history.addLast(PrimaryFrame(capture, regions.toList()))
        primaryVersion++
        while (history.size > MAX_HISTORY_FRAMES ||
            capture.capturedAtMs - history.first().capture.capturedAtMs > MAX_SOURCE_AGE_MS) {
            val retired = history.removeFirst()
            if (sourceMatchCache?.source === retired) sourceMatchCache = null
        }
        return true
    }

    /**
     * Unknown index -> current primary index. Pass all display candidates before applying the UI cap.
     * Reevaluate after every primary update, so lost/changed primary regions restore unknown visuals.
     */
    fun mergedUnknownIndices(unknownCapture: Capture, unknownRegions: List<Region>, nowMs: Long): Map<Int, Int> {
        val current = history.lastOrNull() ?: return emptyMap()
        if (!validCapture(unknownCapture) || !fresh(unknownCapture, nowMs) || !fresh(current.capture, nowMs) ||
            current.capture.epoch != unknownCapture.epoch ||
            current.capture.geometryId != unknownCapture.geometryId) return emptyMap()
        // Freshness is checked before reuse, including a clock that moves behind either capture.
        mergeCache?.takeIf {
            it.primaryVersion == primaryVersion && it.capture == unknownCapture && it.regions == unknownRegions
        }?.let { return it.result }
        mergePasses++
        val frames = history.toList()
        val sourceIndex = frames.indexOfFirst { it.capture == unknownCapture }
        if (sourceIndex < 0) return cacheMerge(unknownCapture, unknownRegions, emptyMap())
        val source = frames[sourceIndex]
        // Geometry ambiguity is not resolved by guessing about a candidate with missing depth.
        val sourceMatches = sourceMatchCache?.takeIf { it.source === source && it.regions == unknownRegions }?.matches
            ?: uniqueGeometricMatches(unknownRegions, source.regions).also {
                sourceMatchCache = SourceMatchCache(source, unknownRegions.toList(), it)
            }
        if (sourceMatches.isEmpty()) return cacheMerge(unknownCapture, unknownRegions, emptyMap())
        val followingFrames = frames.drop(sourceIndex + 1)
        val continuationMatches = (listOf(source) + followingFrames).zipWithNext { previous, next ->
            next.matchesFromPrevious ?: uniqueGeometricMatches(previous.regions, next.regions).also {
                next.matchesFromPrevious = it
            }
        }
        val merged = linkedMapOf<Int, Int>()
        for ((unknownIndex, primaryIndex) in sourceMatches) {
            val unknown = unknownRegions[unknownIndex]
            val anchor = source.regions[primaryIndex]
            if (!sameMetricSurface(unknown, anchor)) continue
            var continuingIndex = primaryIndex
            var previousFrame = source
            var continuous = true
            for ((step, frame) in followingFrames.withIndex()) {
                val id = previousFrame.regions[continuingIndex].trackId?.takeIf { it.isNotBlank() }
                val nextIndex = id?.let { frame.uniqueTrackIndices[it] }
                if (id == null || nextIndex == null ||
                    previousFrame.uniqueTrackIndices[id] != continuingIndex ||
                    continuationMatches[step][continuingIndex] != nextIndex ||
                    !sameMetricSurface(previousFrame.regions[continuingIndex], frame.regions[nextIndex]) ||
                    !sameMetricSurface(unknown, frame.regions[nextIndex])) {
                    continuous = false
                    break
                }
                continuingIndex = nextIndex
                previousFrame = frame
            }
            if (continuous) merged[unknownIndex] = continuingIndex
        }
        return cacheMerge(unknownCapture, unknownRegions, merged)
    }

    private fun cacheMerge(capture: Capture, regions: List<Region>, result: Map<Int, Int>): Map<Int, Int> {
        // Snapshot the caller list so a mutation is observed by the next request.
        val cached = Collections.unmodifiableMap(LinkedHashMap(result))
        mergeCache = MergeCache(capture, regions.toList(), primaryVersion, cached)
        return cached
    }

    private fun uniqueGeometricMatches(left: List<Region>, right: List<Region>): Map<Int, Int> {
        geometricPasses++
        val soleRightIndex = IntArray(left.size) { -1 }
        val leftMatchCounts = IntArray(left.size)
        val rightMatchCounts = IntArray(right.size)
        for (index in left.indices) for (other in right.indices) {
            overlapComparisons++
            if (overlaps(left[index].bboxNorm, right[other].bboxNorm)) {
                soleRightIndex[index] = other
                leftMatchCounts[index]++
                rightMatchCounts[other]++
            }
        }
        val result = linkedMapOf<Int, Int>()
        for (index in left.indices) {
            val other = soleRightIndex[index]
            if (leftMatchCounts[index] == 1 && rightMatchCounts[other] == 1) result[index] = other
        }
        return result
    }

    private fun sameMetricSurface(left: Region, right: Region): Boolean {
        val a = left.metricDistanceM ?: return false
        val b = right.metricDistanceM ?: return false
        return left.confidence.isFinite() && left.confidence in MIN_CONFIDENCE..1f &&
            right.confidence.isFinite() && right.confidence in MIN_CONFIDENCE..1f &&
            a.isFinite() && b.isFinite() && a > 0.0 && b > 0.0 && abs(a - b) <= MAX_DEPTH_DIFFERENCE_M
    }

    private fun overlaps(left: RectNorm, right: RectNorm): Boolean {
        if (!validRect(left) || !validRect(right)) return false
        val width = max(0f, min(left.x + left.width, right.x + right.width) - max(left.x, right.x))
        val height = max(0f, min(left.y + left.height, right.y + right.height) - max(left.y, right.y))
        val intersection = width * height
        val union = left.area + right.area - intersection
        return union > 0f && intersection / union >= MIN_IOU
    }

    private fun validRect(rect: RectNorm): Boolean =
        rect.x.isFinite() && rect.y.isFinite() && rect.width.isFinite() && rect.height.isFinite() &&
            rect.x >= 0f && rect.y >= 0f && rect.width > 0f && rect.height > 0f &&
            rect.x + rect.width <= 1f && rect.y + rect.height <= 1f

    private fun validCapture(capture: Capture): Boolean = capture.epoch >= 0 && capture.frameId > 0L &&
        capture.capturedAtMs >= 0L && capture.geometryId.isNotBlank()

    private fun fresh(capture: Capture, nowMs: Long): Boolean = nowMs >= capture.capturedAtMs &&
        nowMs - capture.capturedAtMs <= MAX_SOURCE_AGE_MS

    private companion object {
        const val MAX_HISTORY_FRAMES = 64
        const val MAX_SOURCE_AGE_MS = 800L
        const val MIN_IOU = .70f
        const val MAX_DEPTH_DIFFERENCE_M = .30
        const val MIN_CONFIDENCE = .55f
    }
}
