package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2

enum class VisualTrackingStatus { TRACKED, LOST }
enum class VisualTrackingBackend { OPENCV_PYRAMIDAL_LK, PATCH_DIAGNOSTIC }

enum class VisualTrackingFailure {
    SOURCE_NOT_IN_HISTORY, TARGET_NOT_IN_HISTORY, TARGET_NOT_CURRENT, FRAME_IDENTITY_CONFLICT,
    EPOCH_OR_GEOMETRY_CHANGED, FRAME_ORDER_INVALID, FRAME_GAP, SOURCE_EXPIRED,
    DETECTION_IDENTITY_CONFLICT, DETECTION_REVISION_OLD, DETECTOR_SOURCE_OUT_OF_ORDER,
    INVALID_GEOMETRY, INPUT_BUDGET_EXCEEDED, OBJECT_BUDGET_EXCEEDED, WORK_BUDGET_EXCEEDED, TIME_BUDGET_EXCEEDED, TOO_FEW_FEATURES,
    AMBIGUOUS_PATCH, SEARCH_BOUNDARY, PHOTOMETRIC_MISMATCH, FORWARD_BACKWARD_MISMATCH,
    INSUFFICIENT_COVERAGE, INCONSISTENT_MOTION, UNSUPPORTED_SCALE_OR_ROTATION,
    OUT_OF_FRAME, OBJECT_COMPETITION, NATIVE_INITIALIZATION_FAILED, NATIVE_TRACKING_FAILED,
}

data class VisualTrackingObservation(
    /** Index into the authoritative detector batch, including entries not admitted for tracking. */
    val sourceIndex: Int,
    val detectorSourceKey: VisualFrameKey,
    val trackedTargetKey: VisualFrameKey,
    val status: VisualTrackingStatus,
    /** Null for LOST. Original class and detector confidence are preserved for TRACKED. */
    val geometry: DetectionCandidate?,
    /** Image matching quality, not a calibrated probability or a new detector confidence. */
    val trackingQuality: Float,
    val failure: VisualTrackingFailure?,
    val translationNorm: Point2?,
    val originalFeatureCount: Int,
    val survivingFeatureCount: Int,
    val residualPx: Float?,
    val polygonConstrained: Boolean,
    val transformNorm: VisualAffineTransform? = null,
)

data class VisualTrackingMetrics(
    val durationNs: Long,
    val processedEdges: Int,
    val pixelComparisons: Long,
    val cacheHit: Boolean,
    val retainedFrames: Int,
    val retainedImageBytes: Int,
    /** A native call cannot be preempted; complete, validated results may outlive its work budget. */
    val executionBudgetOverrun: Boolean = false,
    val executionBudgetNs: Long = 5_000_000L,
)

data class VisualTrackingResult(
    val detectorSourceKey: VisualFrameKey,
    val trackedTargetKey: VisualFrameKey,
    val observations: List<VisualTrackingObservation>,
    val metrics: VisualTrackingMetrics,
    /** Oversized batches are rejected in O(1), without allocating one LOST entry per input. */
    val batchFailure: VisualTrackingFailure? = null,
    val rejectedInputCount: Int = 0,
)

data class TrackingFrameAdmission(
    val accepted: Boolean,
    val reset: Boolean,
    val failure: VisualTrackingFailure? = null,
)

data class VisualTrackingConfig(
    val maxHistoryFrames: Int = 32,
    val maxHistoryAgeMs: Long = 900L,
    val maxDetectionAgeMs: Long = 800L,
    val maxFrameGapMs: Long = 120L,
    val maxTrackedObjects: Int = 3,
    val maxFeaturesPerObject: Int = 40,
    val maxPixelComparisonsPerCall: Long = 80_000_000L,
    val maxExecutionNs: Long = 5_000_000L,
    val maxInputDetections: Int = 128,
    val backend: VisualTrackingBackend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
    val allowSimilarityTransform: Boolean = false,
) {
    init {
        require(maxHistoryFrames in 2..32 && maxHistoryAgeMs in 1L..900L)
        require(maxDetectionAgeMs in 1L..800L && maxFrameGapMs in 1L..120L)
        require(maxTrackedObjects in 1..5 && maxFeaturesPerObject in 6..40)
        require(maxPixelComparisonsPerCall in 1L..80_000_000L)
        require(maxExecutionNs in 1L..100_000_000L)
        require(maxInputDetections in 1..128)
    }
}
