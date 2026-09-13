package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming

/** Accepts an empty detection list, but only after a real final model invocation completed. */
object DeviceCheckDetectorExecutionPolicy {
    fun passes(result: AndroidDetectionResult): Boolean {
        if (result.partial) return false
        val timing = result.timing
        if (timing.completedModels.isEmpty()) return false
        if (timing.totalMs == null || timing.totalMs < 0L) return false
        if (timing.preprocessingStrategy == "FUSED_YUV_TO_TENSOR") {
            // Fused conversion is measured inside the corresponding model's preprocessing time.
            return hasFusedModelExecution(timing)
        }
        if (timing.yuvDecodeMs == null || timing.yuvDecodeMs < 0L) return false
        return listOf(
            timing.modelInferenceMs,
            timing.cocoInferenceMs,
            timing.customInferenceMs,
        ).any { it != null && it >= 0L }
    }

    private fun hasFusedModelExecution(timing: AndroidDetectorTiming): Boolean {
        val completedModels = HashSet<String>()
        return timing.completedModels.all { declaredModel ->
            // The runtime emits unified_walksafe; retain the earlier walksafe_unified alias.
            val model = if (declaredModel == "walksafe_unified") "unified_walksafe" else declaredModel
            if (!completedModels.add(model)) return@all false
            val durations = when (model) {
                "unified_walksafe" -> timing.modelPreprocessMs to timing.modelInferenceMs
                "coco_general" -> timing.cocoPreprocessMs to timing.cocoInferenceMs
                "custom_tactile" -> timing.customPreprocessMs to timing.customInferenceMs
                else -> return@all false
            }
            val (preprocessMs, inferenceMs) = durations
            preprocessMs != null && preprocessMs >= 0L && inferenceMs != null && inferenceMs >= 0L
        }
    }
}
