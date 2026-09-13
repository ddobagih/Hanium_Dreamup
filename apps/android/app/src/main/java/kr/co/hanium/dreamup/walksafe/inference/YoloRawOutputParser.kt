package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm

/**
 * Ultralytics 8.4.48 YOLO11 detection export without NMS: [1, 4 + classes, anchors].
 * Channels 0..3 are center-x, center-y, width and height in explicitly configured units; remaining
 * channels are sigmoid class probabilities, with no objectness channel. NMS is class-aware.
 */
class YoloRawOutputParser(
    private val inputSize: Int,
    private val classCount: Int,
    private val classNameForId: (Int) -> String?,
    private val thresholdForClass: (String) -> Float,
    private val allowClass: (String) -> Boolean = { true },
    private val nmsIouThreshold: Float = 0.7f,
    private val maxDetections: Int = 300,
    private val normalizedCoordinates: Boolean = false,
) {
    init {
        require(inputSize in 32..2048 && inputSize % 32 == 0)
        require(classCount > 0)
        require(nmsIouThreshold.isFinite() && nmsIouThreshold in 0f..1f)
        require(maxDetections in 1..300)
    }

    private val anchors = listOf(8, 16, 32).sumOf { stride -> (inputSize / stride) * (inputSize / stride) }

    private fun readCandidates(values: FloatArray, acceptScore: (String, Float) -> Boolean): ArrayList<RawDetection> {
        require(values.size == (4 + classCount) * anchors) { "raw YOLO output shape mismatch" }
        val candidates = ArrayList<RawDetection>()
        for (anchor in 0 until anchors) {
            var classId = -1
            var score = 0f
            var invalidScore = false
            for (classIndex in 0 until classCount) {
                val probability = values[(4 + classIndex) * anchors + anchor]
                if (!probability.isFinite() || probability !in 0f..1f) {
                    invalidScore = true
                    break
                }
                if (probability > score) {
                    score = probability
                    classId = classIndex
                }
            }
            if (invalidScore || classId < 0) continue
            val className = classNameForId(classId) ?: continue
            if (!allowClass(className) || !acceptScore(className, score)) continue
            val centerX = values[anchor]
            val centerY = values[anchors + anchor]
            val width = values[2 * anchors + anchor]
            val height = values[3 * anchors + anchor]
            if (!centerX.isFinite() || !centerY.isFinite() || !width.isFinite() || !height.isFinite() ||
                width <= 0f || height <= 0f) continue
            val scale = if (normalizedCoordinates) 1f else inputSize.toFloat()
            val box = RectNorm((centerX - width / 2f) / scale, (centerY - height / 2f) / scale,
                width / scale, height / scale)
            if (!box.x.isFinite() || !box.y.isFinite() || !box.width.isFinite() || !box.height.isFinite() ||
                !box.area.isFinite() || !(box.x + box.width).isFinite() || !(box.y + box.height).isFinite()) continue
            if (box.x >= 1f || box.y >= 1f || box.x + box.width <= 0f || box.y + box.height <= 0f) continue
            candidates.add(RawDetection(anchor, classId, className, score, box))
        }
        return candidates
    }

    /** Operational calibration coverage only: an allowed, valid-box top class within +/- window. */
    fun hasNearThresholdCandidate(values: FloatArray, window: Float): Boolean {
        require(window.isFinite() && window in 0f..1f)
        return readCandidates(values) { className, score ->
            kotlin.math.abs(score - thresholdForClass(className)) <= window
        }.isNotEmpty()
    }

    fun parse(values: FloatArray): List<DetectionCandidate> {
        val candidates = readCandidates(values) { className, score -> score >= thresholdForClass(className) }
        // Anchor index makes equal-confidence suppression deterministic across delegates.
        candidates.sortWith(compareByDescending<RawDetection> { it.score }.thenBy { it.anchor })
        val selected = ArrayList<RawDetection>(maxDetections)
        for (candidate in candidates) {
            if (selected.any { it.classId == candidate.classId && iou(it.box, candidate.box) > nmsIouThreshold }) continue
            selected.add(candidate)
            if (selected.size == maxDetections) break
        }
        return selected.mapNotNull { detection ->
            // Suppress in un-clipped model coordinates, then clip before undoing letterboxing.
            val box = detection.box
            val left = box.x.coerceIn(0f, 1f)
            val top = box.y.coerceIn(0f, 1f)
            val right = (box.x + box.width).coerceIn(0f, 1f)
            val bottom = (box.y + box.height).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) return@mapNotNull null
            DetectionCandidate(detection.className, detection.score, RectNorm(left, top, right - left, bottom - top))
        }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val overlap = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return overlap / maxOf(a.area + b.area - overlap, 1e-12f)
    }

    private data class RawDetection(val anchor: Int, val classId: Int, val className: String, val score: Float, val box: RectNorm)
}
