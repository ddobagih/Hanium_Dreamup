package kr.co.hanium.dreamup.walksafe.runtime

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileEvidenceFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import java.util.Locale

private fun DetectionCandidate.isTactileDetection(): Boolean {
    return className.lowercase(Locale.US).contains("tactile") || className.contains("점자")
}

internal data class DetectionFrameEvidence(
    val frameId: Long,
    val frameTimestampMs: Long,
    val depthSnapshot: DepthFrameSnapshot,
    val depthMapper: FrozenImageToTextureCoordinateMapper?,
    val tactileContext: TactileProjectionContext,
    val motionContext: MotionContext,
    val navigationActive: Boolean,
    val tmapOnRoute: Boolean,
    val visualFrameKey: kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey? = null,
    val imageQuarterTurns: Int = 0,
    /** S10-2: display geometry (visualGeometryVersion) the detection was requested under. */
    val geometryVersion: Long? = null,
) {
    fun tactileFrameIdentity(): AndroidTactileEvidenceFrameIdentity = AndroidTactileEvidenceFrameIdentity(
        frameId = frameId,
        frameTimestampMs = frameTimestampMs,
        depthFrameId = depthSnapshot.frameTimestampNs,
        depthMapperFrameId = depthMapper?.frameId,
    )
}

internal data class DetectionSnapshot(
    val detections: List<DetectionCandidate>,
    val identity: AndroidDetectionSnapshotFrameIdentity,
    val capturedAtMs: Long?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val detectDurationMs: Long?,
    val detectorTiming: AndroidDetectorTiming?,
    val reportImage: ByteArray? = null,
    val partial: Boolean,
    val frameEvidence: DetectionFrameEvidence?,
    val capturedAtElapsedRealtimeMs: Long? = null,
    val reportCaptureFacts: ReportFrameCaptureFacts? = null,
) {
    val captureFrameId: Long? get() = identity.captureFrameId

    val frameTimestampMs: Long? get() = identity.frameTimestampMs

    fun freshDetections(
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxSourceAgeMs: Long,
        maxFrameDeltaMs: Long,
    ): List<DetectionCandidate> {
        return if (staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs) == null) {
            detections
        } else {
            emptyList()
        }
    }

    fun sourceAgeMs(nowMs: Long): Long? = capturedAtMs?.let { nowMs - it }

    fun completedAgeMs(nowMs: Long): Long? = completedAtMs?.let { nowMs - it }

    fun hasTactileDetection(): Boolean {
        return detections.any { it.isTactileDetection() }
    }

    fun tactileDetections(): List<DetectionCandidate> = detections.filter { it.isTactileDetection() }

    fun hasImageSize(): Boolean {
        return imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0
    }

    fun frameDeltaMs(currentFrameTimestampMs: Long): Long? {
        val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs) ?: return null
        return frameDelta.takeIf { it >= 0L }
    }

    fun ageMs(nowMs: Long): Long? = sourceAgeMs(nowMs)

    fun staleReason(
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxSourceAgeMs: Long,
        maxFrameDeltaMs: Long,
    ): String? {
        // S10-1: no completed inference yet (no frame, or only unavailable/failed calls) is not a fresh empty scene.
        if (completedAtMs == null) return DETECTION_NOT_COMPLETED_STALE_REASON
        val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs)
        if (frameDelta != null) {
            if (frameDelta < -FRAME_TIMESTAMP_TOLERANCE_MS) return "frame_delta_negative"
            if (frameDelta > maxFrameDeltaMs) return "frame_delta>${maxFrameDeltaMs}ms"
        }
        val sourceAge = sourceAgeMs(nowMs) ?: return null
        return if (sourceAge > maxSourceAgeMs) "source_age>${maxSourceAgeMs}ms" else null
    }

    fun debugStatusText(
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxSourceAgeMs: Long,
        maxFrameDeltaMs: Long,
    ): String {
        if (completedAtMs == null) {
            return "detector: 아직 완료된 TFLite 결과 없음"
        }
        val sourceAgeMs = sourceAgeMs(nowMs) ?: 0L
        val completedAgeMs = completedAgeMs(nowMs) ?: 0L
        val frameDeltaMs = frameDeltaMs(currentFrameTimestampMs)
        val stale = staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs)?.let { " · stale=$it depth입력 제외" } ?: ""
        val partialText = if (partial) "partial" else "complete"
        val top = detections.maxByOrNull { it.detectionConfidence }
        val topText = if (top == null) {
            "top=none"
        } else {
            val bbox = top.bboxNorm
            "top=${top.className} ${formatPercent(top.detectionConfidence)} " +
                "c=(${formatDecimal(bbox.center.x)},${formatDecimal(bbox.center.y)}) " +
                "wh=(${formatDecimal(bbox.width)},${formatDecimal(bbox.height)})"
        }
        val duration = detectDurationMs?.let { " · detect=${it}ms" } ?: ""
        val models = detectorTiming?.completedModels?.takeIf { it.isNotEmpty() }?.joinToString("+")
            ?.let { " · models=$it" } ?: ""
        val imageSize = if (imageWidth != null && imageHeight != null) " · image=${imageWidth}x$imageHeight" else ""
        val frameDeltaText = frameDeltaMs?.let { " frameDelta=${it}ms" } ?: ""
        return "detector: ${detections.size}개 $partialText · sourceAge=${sourceAgeMs}ms completedAge=${completedAgeMs}ms$frameDeltaText$duration$models$stale · frameTs=${frameTimestampMs ?: "-"}$imageSize · $topText"
    }

    companion object {
        fun published(
            result: AndroidDetectionResult,
            identity: AndroidDetectionSnapshotFrameIdentity,
            capturedAtMs: Long,
            startedAtMs: Long,
            completedAtMs: Long,
            imageWidth: Int,
            imageHeight: Int,
            reportImageJpeg: ByteArray?,
            frameEvidence: DetectionFrameEvidence,
            capturedAtElapsedRealtimeMs: Long? = null,
            reportCaptureFacts: ReportFrameCaptureFacts? = null,
        ): DetectionSnapshot = DetectionSnapshot(
            detections = result.detections,
            identity = identity,
            capturedAtMs = capturedAtMs,
            startedAtMs = startedAtMs,
            completedAtMs = completedAtMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            reportImage = reportImageJpeg,
            detectDurationMs = result.timing.totalMs ?: (completedAtMs - startedAtMs),
            detectorTiming = result.timing,
            partial = result.partial,
            frameEvidence = frameEvidence,
            capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
            // Missing or mismatched capture provenance cannot authorize a report.
            reportCaptureFacts = reportCaptureFacts?.takeIf {
                it.capturedAtElapsedRealtimeMs == capturedAtElapsedRealtimeMs
            },
        )

        fun empty(): DetectionSnapshot = DetectionSnapshot(
            detections = emptyList(),
            identity = AndroidDetectionSnapshotFrameIdentity(
                captureFrameId = null,
                frameTimestampMs = null,
            ),
            capturedAtMs = null,
            startedAtMs = null,
            completedAtMs = null,
            imageWidth = null,
            imageHeight = null,
            detectDurationMs = null,
            detectorTiming = null,
            reportImage = null,
            partial = false,
            frameEvidence = null,
        )

        const val DETECTION_NOT_COMPLETED_STALE_REASON = "not_completed"

        private const val FRAME_TIMESTAMP_TOLERANCE_MS = 100L

        private fun formatPercent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

        private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)
    }

    private fun rawFrameDeltaMs(currentFrameTimestampMs: Long): Long? {
        val detectorFrameTimestamp = frameTimestampMs ?: return null
        return currentFrameTimestampMs - detectorFrameTimestamp
    }
}
