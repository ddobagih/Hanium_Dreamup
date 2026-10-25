package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm

/** Adds missing people only. A person can be a rider, so overlap never relabels/removes a vehicle. */
internal class PersonSupplementMerger(private val duplicateIou: Float = 0.7f) {
    init {
        require(duplicateIou.isFinite() && duplicateIou in 0f..1f)
    }

    fun merge(primary: List<DetectionCandidate>, supplement: List<DetectionCandidate>): List<DetectionCandidate> {
        val merged = primary.toMutableList()
        supplement.asSequence().filter { it.className == "person" && valid(it) }
            .sortedByDescending { it.detectionConfidence }.forEach { person ->
                if (merged.none { it.className == "person" && iou(it.bboxNorm, person.bboxNorm) > duplicateIou }) {
                    merged.add(person)
                }
            }
        return merged
    }

    private fun valid(candidate: DetectionCandidate): Boolean = with(candidate) {
        detectionConfidence.isFinite() && detectionConfidence > 0f && detectionConfidence <= 1f &&
            with(bboxNorm) { listOf(x, y, width, height).all(Float::isFinite) && width > 0f && height > 0f }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val intersection = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return intersection / (a.area + b.area - intersection).coerceAtLeast(1e-12f)
    }
}
