package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
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

    private data class PrimaryFrame(val capture: Capture, val regions: List<Region>)
    private val history = ArrayDeque<PrimaryFrame>()

    fun clear() = history.clear()

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
        while (history.size > MAX_HISTORY_FRAMES ||
            capture.capturedAtMs - history.first().capture.capturedAtMs > MAX_SOURCE_AGE_MS) history.removeFirst()
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
        val frames = history.toList()
        val sourceIndex = frames.indexOfFirst { it.capture == unknownCapture }
        if (sourceIndex < 0) return emptyMap()
        val source = frames[sourceIndex]
        // Geometry ambiguity is not resolved by guessing about a candidate with missing depth.
        val sourceMatches = uniqueGeometricMatches(unknownRegions, source.regions)
        if (sourceMatches.isEmpty()) return emptyMap()
        val followingFrames = frames.drop(sourceIndex + 1)
        val continuationMatches = (listOf(source) + followingFrames).zipWithNext { previous, next ->
            uniqueGeometricMatches(previous.regions, next.regions)
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
                val nextIndex = id?.let { trackId -> frame.regions.indices.singleOrNull {
                    frame.regions[it].trackId == trackId
                } }
                if (id == null || nextIndex == null ||
                    previousFrame.regions.count { it.trackId == id } != 1 ||
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
        return merged
    }

    private fun uniqueGeometricMatches(left: List<Region>, right: List<Region>): Map<Int, Int> {
        val matches = left.indices.map { index -> right.indices.filter { other ->
            overlaps(left[index].bboxNorm, right[other].bboxNorm)
        } }
        val result = linkedMapOf<Int, Int>()
        matches.forEachIndexed { index, candidates ->
            val other = candidates.singleOrNull() ?: return@forEachIndexed
            if (matches.count { other in it } == 1) result[index] = other
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
