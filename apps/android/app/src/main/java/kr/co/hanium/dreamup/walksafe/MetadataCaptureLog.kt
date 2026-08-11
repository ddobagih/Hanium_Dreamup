package kr.co.hanium.dreamup.walksafe

import java.util.Locale

class MetadataCaptureLog(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = ArrayDeque<MetadataCaptureLogEntry>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun append(entry: MetadataCaptureLogEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun recentEntries(limit: Int = maxEntries): List<MetadataCaptureLogEntry> {
        if (limit <= 0) return emptyList()
        return entries.takeLast(limit)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun summaryText(limit: Int = SUMMARY_LIMIT): String {
        val recent = recentEntries(limit)
        if (recent.isEmpty()) return "capture log: empty"
        return recent.joinToString(separator = "\n") { it.summaryText() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 30
        private const val SUMMARY_LIMIT = 3
    }
}

data class MetadataCaptureLogEntry(
    val frameTimestampMs: Long,
    val detectorFrameTimestampMs: Long?,
    val detectorAgeMs: Long?,
    val detectorSourceAgeMs: Long? = null,
    val detectorCompletedAgeMs: Long? = null,
    val detectorFrameDeltaMs: Long? = null,
    val detectDurationMs: Long? = null,
    val detectorYuvDecodeMs: Long? = null,
    val detectorModelKey: String? = null,
    val detectorLoadedModelKey: String? = null,
    val detectorModelFallbackUsed: Boolean? = null,
    val detectorModelLoadReason: String? = null,
    val detectorModelPreprocessMs: Long? = null,
    val detectorModelInferenceMs: Long? = null,
    val detectorModelParseMs: Long? = null,
    val detectorCocoPreprocessMs: Long? = null,
    val detectorCocoInferenceMs: Long? = null,
    val detectorCocoParseMs: Long? = null,
    val detectorCustomPreprocessMs: Long? = null,
    val detectorCustomInferenceMs: Long? = null,
    val detectorCustomParseMs: Long? = null,
    val detectorCompletedModels: List<String> = emptyList(),
    val detectorSkippedModels: List<String> = emptyList(),
    val detectorPartial: Boolean = false,
    val detectionCount: Int,
    val detectionsUsedForDepth: Boolean,
    val overlaySelection: String? = null,
    val overlayDetectionCount: Int? = null,
    val overlayTactileDetectionCount: Int? = null,
    val overlayBoxCount: Int? = null,
    val overlayTactileBoxCount: Int? = null,
    val overlayHeldTactileBoxCount: Int? = null,
    val overlaySmoothedTactileBoxCount: Int? = null,
    val overlayHoldApplied: Boolean? = null,
    val overlaySnapshotPartial: Boolean? = null,
    val overlaySourceAgeMs: Long? = null,
    val overlayFrameDeltaMs: Long? = null,
    val overlayStaleReason: String? = null,
    val staleReason: String?,
    val topDetectionClassName: String?,
    val topDetectionConfidence: Float?,
    val topDetectionBbox: MetadataRect?,
    val bestDepthClassName: String?,
    val bestDepthTrackId: String? = null,
    val bestDepthSource: String?,
    val bestDepthDetectionConfidence: Float? = null,
    val bestDepthConfidenceScore: Float? = null,
    val bestDepthMedianM: Float?,
    val bestDepthP20M: Float? = null,
    val bestDepthRiskDistanceM: Float? = null,
    val bestDepthIqrM: Float? = null,
    val bestDepthValidSampleCount: Int?,
    val bestDepthValidSampleRatio: Float?,
    val bestDepthBbox: MetadataRect?,
    val previewWidth: Int? = null,
    val previewHeight: Int? = null,
    val cameraImageWidth: Int? = null,
    val cameraImageHeight: Int? = null,
    val displayRotation: Int? = null,
    val depthWidth: Int? = null,
    val depthHeight: Int? = null,
    val overlayTransformPath: String? = null,
    val depthTransformPath: String? = null,
    val transformPath: String? = null,
    val fallbackReason: String? = null,
) {
    fun summaryText(): String {
        val detectorText = buildString {
            append("frameTs=")
            append(frameTimestampMs)
            append(" detFrameTs=")
            append(detectorFrameTimestampMs ?: "-")
            append(" age=")
            append(detectorAgeMs?.let { "${it}ms" } ?: "-")
            detectorSourceAgeMs?.let { append(" sourceAge=${it}ms") }
            detectorFrameDeltaMs?.let { append(" frameDelta=${it}ms") }
            detectDurationMs?.let { append(" detect=${it}ms") }
            detectorModelKey?.let { append(" model=$it") }
            detectorLoadedModelKey?.let { append(" loadedModel=$it") }
            detectorModelLoadReason?.let { append(" loadReason=$it") }
            detectorModelFallbackUsed?.takeIf { it }?.let { append(" modelFallback") }
            detectorModelInferenceMs?.let { append(" modelInfer=${it}ms") }
            if (detectorCompletedModels.isNotEmpty()) {
                append(" models=")
                append(detectorCompletedModels.joinToString("+"))
            }
            append(if (detectionsUsedForDepth) " used" else " suppressed")
            staleReason?.let { append(" stale=$it") }
            append(" count=")
            append(detectionCount)
        }
        val overlayText = buildString {
            overlaySelection?.let { append("selection=$it ") }
            overlayDetectionCount?.let { append("det=$it ") }
            overlayTactileDetectionCount?.let { append("tactileDet=$it ") }
            overlayBoxCount?.let { append("boxes=$it ") }
            overlayTactileBoxCount?.let { append("tactileBoxes=$it ") }
            overlayHeldTactileBoxCount?.let { append("heldTactile=$it ") }
            overlaySmoothedTactileBoxCount?.let { append("smoothTactile=$it ") }
            overlaySourceAgeMs?.let { append("sourceAge=${it}ms ") }
            overlayFrameDeltaMs?.let { append("frameDelta=${it}ms ") }
            overlayHoldApplied?.takeIf { it }?.let { append("holdApplied ") }
            overlaySnapshotPartial?.takeIf { it }?.let { append("partial ") }
            overlayStaleReason?.let { append("stale=$it") }
        }.trim().ifEmpty { "overlay=-" }
        val topText = if (topDetectionClassName == null) {
            "top=none"
        } else {
            "top=$topDetectionClassName ${percent(topDetectionConfidence ?: 0f)} ${topDetectionBbox?.summaryText() ?: "bbox=-"}"
        }
        val depthText = if (bestDepthClassName == null) {
            "depth=none"
        } else {
            "depth=$bestDepthClassName ${bestDepthSource ?: "UNKNOWN"} " +
                "risk=${meters(bestDepthRiskDistanceM)} median=${meters(bestDepthMedianM)} " +
                "score=${percent(bestDepthConfidenceScore ?: 0f)} samples=${bestDepthValidSampleCount ?: 0} " +
                "ratio=${percent(bestDepthValidSampleRatio ?: 0f)} ${bestDepthBbox?.summaryText() ?: "bbox=-"}"
        }
        val geometryText = buildString {
            val previewSize = sizeText(previewWidth, previewHeight)
            val cameraSize = sizeText(cameraImageWidth, cameraImageHeight)
            val depthSize = sizeText(depthWidth, depthHeight)
            if (previewSize != null) append("preview=$previewSize ")
            if (cameraSize != null) append("camera=$cameraSize ")
            if (depthSize != null) append("depthSize=$depthSize ")
            displayRotation?.let { append("rotation=$it ") }
            overlayTransformPath?.let { append("overlayTransform=$it ") }
            depthTransformPath?.let { append("depthTransform=$it ") }
            transformPath?.let { append("transform=$it ") }
            fallbackReason?.let { append("fallback=$it") }
        }.trim().ifEmpty { "geometry=-" }
        return "capture: $detectorText · $overlayText · $topText · $depthText · $geometryText"
    }

    private companion object {
        fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value.coerceIn(0f, 1f) * 100f)

        fun meters(value: Float?): String = value?.let { String.format(Locale.US, "%.2fm", it) } ?: "-"

        fun sizeText(width: Int?, height: Int?): String? {
            if (width == null || height == null) return null
            if (width <= 0 || height <= 0) return null
            return "${width}x$height"
        }
    }
}

data class MetadataRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun summaryText(): String {
        return "bbox=(${decimal(x)},${decimal(y)},${decimal(width)},${decimal(height)})"
    }

    private companion object {
        fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)
    }
}
