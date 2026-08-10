package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class YoloEndToEndDetection(
    val bbox: RectNorm,
    val score: Float,
    val classId: Int,
)

/**
 * Parses exported end-to-end YOLO rows `[x1, y1, x2, y2, score, classId]`. Boxes are returned in
 * normalized model-input space; suppression is assumed to have happened inside the exported graph.
 */
class YoloEndToEndOutputParser(
    private val inputSize: Int,
    private val classNameForId: (Int) -> String?,
    private val thresholdForClass: (String) -> Float,
    private val allowClass: (String) -> Boolean = { true },
) {
    fun parse(flatOutput: FloatArray, rows: Int = DEFAULT_ROWS, columns: Int = DEFAULT_COLUMNS): List<DetectionCandidate> {
        require(inputSize > 0) { "inputSize must be positive" }
        require(columns == DEFAULT_COLUMNS) { "YOLO end-to-end output must have 6 columns" }
        require(flatOutput.size >= rows * columns) { "output is smaller than rows*columns" }

        return (0 until rows).mapNotNull { row ->
            val offset = row * columns
            val score = flatOutput[offset + 4]
            if (!score.isFinite() || score !in 0f..1f || score == 0f) return@mapNotNull null
            val rawClassId = flatOutput[offset + 5]
            if (!rawClassId.isFinite()) return@mapNotNull null
            val classId = rawClassId.roundToInt()
            if (abs(rawClassId - classId) > CLASS_ID_TOLERANCE) return@mapNotNull null
            val className = classNameForId(classId) ?: return@mapNotNull null
            if (!allowClass(className) || score < thresholdForClass(className)) return@mapNotNull null
            val bbox = parseBbox(
                x1 = flatOutput[offset],
                y1 = flatOutput[offset + 1],
                x2 = flatOutput[offset + 2],
                y2 = flatOutput[offset + 3],
            ) ?: return@mapNotNull null
            if (bbox.width <= 0f || bbox.height <= 0f) return@mapNotNull null
            DetectionCandidate(
                className = className,
                detectionConfidence = score,
                bboxNorm = bbox,
            )
        }
    }

    fun parseRaw(flatOutput: FloatArray, rows: Int = DEFAULT_ROWS, columns: Int = DEFAULT_COLUMNS): List<YoloEndToEndDetection> {
        require(inputSize > 0) { "inputSize must be positive" }
        require(columns == DEFAULT_COLUMNS) { "YOLO end-to-end output must have 6 columns" }
        require(flatOutput.size >= rows * columns) { "output is smaller than rows*columns" }

        return (0 until rows).mapNotNull { row ->
            val offset = row * columns
            val score = flatOutput[offset + 4]
            if (!score.isFinite() || score !in 0f..1f || score == 0f) return@mapNotNull null
            val bbox = parseBbox(
                x1 = flatOutput[offset],
                y1 = flatOutput[offset + 1],
                x2 = flatOutput[offset + 2],
                y2 = flatOutput[offset + 3],
            ) ?: return@mapNotNull null
            if (bbox.width <= 0f || bbox.height <= 0f) return@mapNotNull null
            val rawClassId = flatOutput[offset + 5]
            if (!rawClassId.isFinite()) return@mapNotNull null
            val classId = rawClassId.roundToInt()
            if (abs(rawClassId - classId) > CLASS_ID_TOLERANCE) return@mapNotNull null
            YoloEndToEndDetection(
                bbox = bbox,
                score = score,
                classId = classId,
            )
        }
    }

    private fun parseBbox(x1: Float, y1: Float, x2: Float, y2: Float): RectNorm? {
        if (!listOf(x1, y1, x2, y2).all(Float::isFinite)) return null
        val looksNormalized = listOf(x1, y1, x2, y2).all { it in NORMALIZED_MIN_TOLERANCE..NORMALIZED_MAX_TOLERANCE }
        val scale = if (looksNormalized) 1f else inputSize.toFloat()
        val left = min(x1, x2) / scale
        val top = min(y1, y2) / scale
        val right = max(x1, x2) / scale
        val bottom = max(y1, y2) / scale
        val clampedLeft = left.coerceIn(0f, 1f)
        val clampedTop = top.coerceIn(0f, 1f)
        val clampedRight = right.coerceIn(0f, 1f)
        val clampedBottom = bottom.coerceIn(0f, 1f)
        return RectNorm(
            x = clampedLeft,
            y = clampedTop,
            width = (clampedRight - clampedLeft).coerceAtLeast(0f),
            height = (clampedBottom - clampedTop).coerceAtLeast(0f),
        )
    }

    private companion object {
        const val DEFAULT_ROWS = 300
        const val DEFAULT_COLUMNS = 6
        const val CLASS_ID_TOLERANCE = 1e-4f
        const val NORMALIZED_MIN_TOLERANCE = -0.05f
        const val NORMALIZED_MAX_TOLERANCE = 1.05f
    }
}
