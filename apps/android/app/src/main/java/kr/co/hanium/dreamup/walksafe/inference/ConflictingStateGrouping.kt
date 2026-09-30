package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm

/**
 * S10-17 (originals/10.txt:193-199): class-aware NMS keeps two states of one physical object
 * (normal and damaged tactile paving of the same shape, abandoned and moving e-scooter). A
 * class-agnostic NMS would also drop truly overlapping different objects (a person on a bicycle), so
 * only a same-family pair with a near-identical box is grouped as one object whose state is uncertain.
 * Both detections are kept; consumers decide what an uncertain state may not do.
 */
object ConflictingStateGrouping {
    /** Parser NMS overlap ([YoloRawOutputParser] default nmsIouThreshold). */
    const val MIN_IOU = 0.7f
    const val MIN_AREA_RATIO = 0.8f
    const val MAX_AREA_RATIO = 1.25f

    private val families: Map<String, String> = mapOf(
        "normal_tactile_block" to "tactile_block",
        "damaged_tactile_block" to "tactile_block",
        "linear_tactile_paving" to "linear_tactile_paving",
        "damaged_linear_tactile_paving" to "linear_tactile_paving",
        "dot_tactile_paving" to "dot_tactile_paving",
        "damaged_dot_tactile_paving" to "dot_tactile_paving",
        "abandoned_e_scooter" to "e_scooter_state",
        "moving_e_scooter" to "e_scooter_state",
    )

    fun conflictingStateFamily(className: String): String? = families[className]

    /** Two different state classes of one family whose boxes describe the same object. */
    fun isConflictingStatePair(firstClass: String, firstBox: RectNorm, secondClass: String, secondBox: RectNorm): Boolean {
        if (firstClass == secondClass) return false
        val family = conflictingStateFamily(firstClass) ?: return false
        if (conflictingStateFamily(secondClass) != family) return false
        val firstArea = firstBox.width * firstBox.height
        val secondArea = secondBox.width * secondBox.height
        if (!(firstArea > 0f) || !(secondArea > 0f) || !firstArea.isFinite() || !secondArea.isFinite()) return false
        val areaRatio = firstArea / secondArea
        if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_AREA_RATIO) return false
        return iou(firstBox, secondBox) >= MIN_IOU
    }

    /** Indices of detections that belong to a conflicting-state group (state uncertain). */
    fun stateUncertainIndices(detections: List<DetectionCandidate>): Set<Int> {
        val uncertain = HashSet<Int>()
        for (first in detections.indices) {
            for (second in first + 1 until detections.size) {
                val a = detections[first]
                val b = detections[second]
                if (isConflictingStatePair(a.className, a.bboxNorm, b.className, b.bboxNorm)) {
                    uncertain += first
                    uncertain += second
                }
            }
        }
        return uncertain
    }

    private fun iou(first: RectNorm, second: RectNorm): Float {
        val left = maxOf(first.x, second.x)
        val top = maxOf(first.y, second.y)
        val right = minOf(first.x + first.width, second.x + second.width)
        val bottom = minOf(first.y + first.height, second.y + second.height)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = first.width * first.height + second.width * second.height - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
