package kr.co.hanium.dreamup.walksafe.inference

import android.media.Image
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate

interface AndroidFrameDetector {
    /** Output boxes remain in original sensor coordinates, regardless of model input rotation. */
    fun detectOriented(
        cameraImage: Image,
        timestampMs: Long,
        quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult = detect(cameraImage, timestampMs, onPartialResult)

    fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult
}

class NoopAndroidFrameDetector : AndroidFrameDetector {
    override fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = AndroidDetectionResult.empty()
}

data class AndroidDetectionResult(
    val detections: List<DetectionCandidate>,
    val timing: AndroidDetectorTiming = AndroidDetectorTiming(),
    val partial: Boolean = false,
) {
    companion object {
        fun empty(): AndroidDetectionResult = AndroidDetectionResult(emptyList())
    }
}

/** Calibration owns the optional raw copy; normal inference never allocates this snapshot. */
data class AndroidCalibrationDetectionResult(
    val result: AndroidDetectionResult,
    val rawOutput: FloatArray? = null,
)

data class AndroidDetectorTiming(
    val yuvDecodeMs: Long? = null,
    val modelKey: String? = null,
    val modelPreprocessMs: Long? = null,
    val modelInferenceMs: Long? = null,
    val modelParseMs: Long? = null,
    val cocoPreprocessMs: Long? = null,
    val cocoInferenceMs: Long? = null,
    val cocoParseMs: Long? = null,
    val customPreprocessMs: Long? = null,
    val customInferenceMs: Long? = null,
    val customParseMs: Long? = null,
    val totalMs: Long? = null,
    val completedModels: List<String> = emptyList(),
    val skippedModels: List<String> = emptyList(),
    val modelRuntime: AndroidDetectorRuntime? = null,
    val cocoRuntime: AndroidDetectorRuntime? = null,
    val customRuntime: AndroidDetectorRuntime? = null,
    val preprocessingStrategy: String? = null,
    val ownedTensorCopyMs: Long? = null,
)

data class AndroidDetectorRuntime(
    val requestedDelegate: String,
    val activeDelegate: String,
    val numThreads: Int,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
    // A GPU delegate being attached does not imply that every graph operation ran on the GPU.
    val gpuPrecisionLossAllowed: Boolean? = null,
    // "configured" records serialization parameters being supplied, not a native cache hit.
    val gpuSerializationCacheStatus: String? = null,
    val gpuSerializationCacheToken: String? = null,
    val gpuSerializationCacheFailureReason: String? = null,
)

fun AndroidDetectorTiming.totalModelMs(): Long? {
    val genericValues = listOfNotNull(
        modelPreprocessMs,
        modelInferenceMs,
        modelParseMs,
    )
    if (genericValues.isNotEmpty()) return genericValues.sum()

    val values = listOfNotNull(
        cocoPreprocessMs,
        cocoInferenceMs,
        cocoParseMs,
        customPreprocessMs,
        customInferenceMs,
        customParseMs,
    )
    if (values.isEmpty()) return null
    return values.sum()
}
