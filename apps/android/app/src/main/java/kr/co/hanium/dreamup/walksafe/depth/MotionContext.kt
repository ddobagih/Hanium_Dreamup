package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import kotlin.math.abs

data class MotionContext(
    val motionQuality: Float = 1f,
    val trackingQuality: Float = 1f,
    val freshnessQuality: Float = 1f,
    val routeAlignmentQuality: Float = 1f,
    val egoForwardSpeedMps: Float? = null,
    val shakeScore: Float = 0f,
    val cameraPoseEvidence: CameraPoseEvidence? = null,
    val userMotion: UserMotionEstimate = UserMotionEstimate(),
) {
    val safeMotionQuality: Float get() = minOf(motionQuality, routeAlignmentQuality).coerceIn(0.2f, 1f)

    fun reliableCameraPoseEvidence(): CameraPoseEvidence? = cameraPoseEvidence?.takeIf {
        motionQuality >= 0.8f && trackingQuality >= 0.8f && freshnessQuality >= 0.7f && shakeScore <= 0.2f
    }
}

/** Copied camera pose in one anchor reference, captured with the corresponding depth frame. */
data class CameraPoseEvidence(
    val referenceId: Long,
    val timestampMs: Long,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val forwardX: Float,
    val forwardY: Float,
    val forwardZ: Float,
    val imageProjection: CameraImageProjection? = null,
    /** World gravity-up rotated into this anchor; neither anchor Y nor image up is gravity. */
    val gravityUpInAnchor: Vec3? = null,
    val horizontalPlaneCandidates: List<CapturedHorizontalPlane> = emptyList(),
)

/** Finite same-capture surface evidence, not a semantic floor classification. */
data class CapturedHorizontalPlane(
    val referenceId: Long,
    val captureTimestampMs: Long,
    val polygonInAnchor: List<Vec3>,
)

data class CameraImageProjection(
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val rightX: Float,
    val rightY: Float,
    val rightZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float,
)

/** The detector center is in the unrotated CPU camera image; metric depth is camera-axis Z. */
fun CameraPoseEvidence.objectCenterInAnchor(center: Point2, zDistanceM: Float): Vec3? {
    val projection = imageProjection ?: return null
    if (projection.imageWidth <= 0 || projection.imageHeight <= 0 ||
        !zDistanceM.isFinite() || zDistanceM <= 0f ||
        !center.x.isFinite() || !center.y.isFinite() || center.x !in 0f..1f || center.y !in 0f..1f ||
        !projection.fx.isFinite() || projection.fx <= 0f || !projection.fy.isFinite() || projection.fy <= 0f ||
        !projection.cx.isFinite() || !projection.cy.isFinite()
    ) return null
    val position = Vec3(positionX, positionY, positionZ)
    val right = Vec3(projection.rightX, projection.rightY, projection.rightZ)
    val up = Vec3(projection.upX, projection.upY, projection.upZ)
    val forward = Vec3(forwardX, forwardY, forwardZ)
    if (listOf(position, right, up, forward).any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() } ||
        listOf(right, up, forward).any { abs(it.norm() - 1f) > 0.001f } ||
        abs(right.dot(up)) > 0.001f || abs(right.dot(forward)) > 0.001f || abs(up.dot(forward)) > 0.001f
    ) return null
    val x = (center.x * projection.imageWidth - projection.cx) / projection.fx * zDistanceM
    val y = -(center.y * projection.imageHeight - projection.cy) / projection.fy * zDistanceM
    return (position + right * x + up * y + forward * zDistanceM).takeIf {
        it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
    }
}

/** Separates relative closing from inferred object motion; uncertainty never means stationary. */
enum class ObjectMotion {
    USER_APPROACHING_STATIONARY,
    OBJECT_APPROACHING,
    UNKNOWN,
}

/** LEFT/RIGHT use the captured CPU camera's right axis, not compass or route bearing. */
enum class ObjectMovementDirection {
    UNKNOWN,
    STATIONARY,
    TOWARD_USER,
    AWAY_FROM_USER,
    LEFT,
    RIGHT,
    OTHER,
}

/**
 * Nominal closest approach of one measured point in one captured anchor, not body collision.
 * Time is signed seconds relative to [observedAtMs] in the AR Frame clock. A negative time is
 * past. Null time means exact zero relative velocity: distance is constant, no unique CPA time.
 * No measurement error/extent/user passage is supplied or inferred here.
 */
data class PointClosestApproach internal constructor(
    val referenceId: Long,
    val observedAtMs: Long,
    val source: DepthSource,
    /** Original Image clock/identity, never converted to the Frame or elapsed-realtime clock. */
    val depthObservationTimestampNs: Long,
    val materialPointId: VisualMaterialPointId,
    val motionWindowMs: Long,
    val motionConfidence: Float,
    val relativePositionInAnchorM: Vec3,
    val relativeVelocityInAnchorMps: Vec3,
    val signedTimeSeconds: Double?,
    val distanceM: Double,
    /** Original actual publication; absent in unleased direct motion estimates. */
    internal val publication: Publication? = null,
) {
    data class Publication internal constructor(
        val frameId: Long,
        val sourceObservedAtElapsedRealtimeMs: Long,
        val sourceValidUntilElapsedRealtimeMs: Long,
        val sourceMaxAgeMs: Long,
    )

    val relativeVelocityIsZero: Boolean get() = signedTimeSeconds == null
}

/**
 * Metric velocities in one AR anchor. Camera displacement compensates ego motion; it is not
 * substituted for the user's GNSS movement course. Missing evidence stays null, including speed.
 */
data class ObjectMotionEstimate(
    val referenceId: Long? = null,
    val observedAtMs: Long? = null,
    val elapsedMs: Long = 0L,
    val objectVelocityInAnchorMps: Vec3? = null,
    val relativeVelocityInAnchorMps: Vec3? = null,
    val cameraVelocityInAnchorMps: Vec3? = null,
    val objectDirectionInCamera: Vec3? = null,
    val objectSpeedMps: Float? = null,
    /** Positive means radial distance is decreasing, regardless of which actor moved. */
    val relativeClosingSpeedMps: Float? = null,
    val direction: ObjectMovementDirection = ObjectMovementDirection.UNKNOWN,
    val confidence: Float = 0f,
    /** Producer-captured paired basis; a copied clock/reference/velocity must not retag it. */
    private val capturedPointClosestApproach: PointClosestApproach? = null,
) {
    val pointClosestApproach: PointClosestApproach?
        get() = capturedPointClosestApproach?.takeIf {
            referenceId == it.referenceId && observedAtMs == it.observedAtMs &&
                elapsedMs == it.motionWindowMs && confidence == it.motionConfidence &&
                relativeVelocityInAnchorMps == it.relativeVelocityInAnchorMps &&
                isCurrentMeasuredMotion(it.observedAtMs)
        }
    val relativePositionInAnchorM: Vec3? get() = pointClosestApproach?.relativePositionInAnchorM

    /** Called only after the existing runtime has published its original capture/lease values. */
    internal fun bindPointClosestApproachPublication(
        frameId: Long, observedElapsedMs: Long?, validUntilElapsedMs: Long?, maxAgeMs: Long?,
    ): ObjectMotionEstimate {
        val cpa = pointClosestApproach ?: return this
        val binding = if (frameId > 0L && frameId / 1_000_000L == cpa.observedAtMs &&
            observedElapsedMs != null && observedElapsedMs >= 0L &&
            validUntilElapsedMs != null && validUntilElapsedMs >= observedElapsedMs &&
            maxAgeMs != null && maxAgeMs >= 0L
        ) PointClosestApproach.Publication(frameId, observedElapsedMs, validUntilElapsedMs, maxAgeMs) else null
        // An already published basis must never be reissued under a different receipt or lease.
        if (binding == null || (cpa.publication != null && cpa.publication != binding)) {
            return copy(capturedPointClosestApproach = null)
        }
        if (cpa.publication == binding) return this
        return copy(capturedPointClosestApproach = cpa.copy(publication = binding))
    }
}

fun routeBearingAlignmentQuality(headingDeg: Float?, routeBearingDeg: Float?): Float {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return 1f
    val bearing = routeBearingDeg?.takeIf { it.isFinite() } ?: return 1f
    val delta = angularDifferenceDeg(heading, bearing)
    return when {
        delta <= 30f -> 1f
        delta <= 75f -> 0.75f
        delta <= 120f -> 0.55f
        else -> 0.35f
    }
}

private fun angularDifferenceDeg(a: Float, b: Float): Float {
    val normalized = ((a - b + 540f) % 360f) - 180f
    return abs(normalized)
}
