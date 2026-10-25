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

/**
 * Why a tracking output asks the detector scheduler for an earlier fresh detection. The request
 * never changes identities: tracked objects keep their detector source index, and only a new
 * detector batch can start new observations (FP-019: crossing/occlusion restarts observation).
 */
enum class VisualRedetectReason {
    /** An admitted object failed visual tracking (occluded, out of frame, weak or inconsistent motion). */
    TRACK_LOST,
    /** Two admitted objects converged on one image region; both are LOST and their positions uncertain. */
    OCCLUSION_OR_COMPETITION,
    /** A tracked object moved into the central lower walking region after this batch's priority was fixed. */
    WALKING_ZONE_ENTRY,
    /** Valid detector candidates were left untracked by the object budget of this batch. */
    UNTRACKED_CANDIDATES,
}

/** Identity of one measured image point; neither a detector batch nor an object track ID. */
class VisualMaterialPointId internal constructor()

/** One material point measured in this exact, unrotated CPU-image frame. */
data class VisualMaterialPoint internal constructor(
    val id: VisualMaterialPointId,
    val targetKey: VisualFrameKey,
    val imagePointNorm: Point2,
)

/** Issued only after the producer verifies unique ownership at one measured common image. */
internal class VisualMaterialPointHandoff(
    val previousSourceKey: VisualFrameKey,
    val sourceKey: VisualFrameKey,
    val commonFrameKey: VisualFrameKey,
    val previousSourceIndex: Int,
    val sourceIndex: Int,
    val previousDetection: DetectionCandidate,
    val detection: DetectionCandidate,
    val previousGeometryAtCommonFrame: DetectionCandidate,
    val previousTransformAtCommonFrame: VisualAffineTransform,
    val geometryAtCommonFrame: DetectionCandidate,
    val transformAtCommonFrame: VisualAffineTransform,
    val pointId: VisualMaterialPointId,
    val pointAtCommonFrame: Point2,
    val uprightQuarterTurns: Int,
)

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
    val materialPoint: VisualMaterialPoint? = null,
) {
    // Body storage preserves constructor/default/copy ABI. A copy loses the source-bound witness.
    private var boundMaterialPointHandoff: VisualMaterialPointHandoff? = null
    internal val materialPointHandoff: VisualMaterialPointHandoff? get() = boundMaterialPointHandoff

    internal fun bindMaterialPointHandoff(proof: VisualMaterialPointHandoff): VisualTrackingObservation {
        check(boundMaterialPointHandoff == null)
        check(status == VisualTrackingStatus.TRACKED && failure == null &&
            detectorSourceKey == proof.sourceKey && sourceIndex == proof.sourceIndex &&
            materialPoint?.id === proof.pointId)
        boundMaterialPointHandoff = proof
        return this
    }
}

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
    /** Scheduling hint only; it carries no geometry and cannot keep an unobserved position alive. */
    val redetectReasons: Set<VisualRedetectReason> = emptySet(),
) {
    val redetectRequested: Boolean
        get() = redetectReasons.isNotEmpty()
}

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
    /** Parser output is capped at 300; admission does not increase the optical-flow work budget. */
    val maxInputDetections: Int = 300,
    val backend: VisualTrackingBackend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
    val allowSimilarityTransform: Boolean = false,
) {
    init {
        require(maxHistoryFrames in 2..32 && maxHistoryAgeMs in 1L..900L)
        require(maxDetectionAgeMs in 1L..800L && maxFrameGapMs in 1L..120L)
        require(maxTrackedObjects in 1..5 && maxFeaturesPerObject in 6..40)
        require(maxPixelComparisonsPerCall in 1L..80_000_000L)
        require(maxExecutionNs in 1L..100_000_000L)
        require(maxInputDetections in 1..300)
    }
}
