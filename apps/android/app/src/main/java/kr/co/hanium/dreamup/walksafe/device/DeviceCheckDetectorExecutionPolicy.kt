package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult

/** Accepts an empty detection list, but only after a real final model invocation completed. */
object DeviceCheckDetectorExecutionPolicy {
    fun passes(result: AndroidDetectionResult): Boolean {
        if (result.partial) return false
        val timing = result.timing
        if (timing.completedModels.isEmpty()) return false
        if (timing.yuvDecodeMs == null || timing.yuvDecodeMs < 0L) return false
        if (timing.totalMs == null || timing.totalMs < 0L) return false
        return listOf(
            timing.modelInferenceMs,
            timing.cocoInferenceMs,
            timing.customInferenceMs,
        ).any { it != null && it >= 0L }
    }
}
