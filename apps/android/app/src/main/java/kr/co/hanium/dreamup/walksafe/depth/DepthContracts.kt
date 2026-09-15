package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kotlin.math.ceil
import kotlin.math.max

/** Unless a name says otherwise, 2D geometry uses top-left-origin coordinates normalized to [0, 1]. */
data class Point2(val x: Float, val y: Float)

data class RectNorm(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val center: Point2 get() = Point2(x + width / 2f, y + height / 2f)
    val area: Float get() = max(0f, width) * max(0f, height)
}

enum class ScreenZone {
    LEFT,
    CENTER,
    RIGHT,
    LOWER,
    UPPER,
}

data class ObjectGeometry(
    val className: String,
    val detectionConfidence: Float,
    val bboxNorm: RectNorm,
    val polygonNorm: List<Point2>,
    val maskAreaNorm: Float,
    val centerNorm: Point2,
    /** Upright bottom contact, stored in sensor coordinates for depth/pose projection. */
    val bottomContactNorm: Point2?,
    /** Source-image zone. User-facing corridor decisions use [uprightScreenZone]. */
    val screenZone: ScreenZone = ScreenZone.CENTER,
    val centerlineNorm: List<Point2> = emptyList(),
    val orientationRad: Float? = null,
    val aspectRatio: Float? = null,
    /** Captured clockwise sensor-to-upright rotation; null means the display orientation is unknown. */
    val imageQuarterTurns: Int? = 0,
) {
    // Keep all stored geometry in the source image plane for depth/pose projection and association.
    val uprightCenterNorm: Point2? get() = sensorPointToUpright(centerNorm)

    val uprightScreenZone: ScreenZone?
        get() = uprightCenterNorm?.let { point ->
            when {
                point.y < 0.33f -> ScreenZone.UPPER
                point.y > 0.70f -> ScreenZone.LOWER
                point.x < 0.33f -> ScreenZone.LEFT
                point.x > 0.67f -> ScreenZone.RIGHT
                else -> ScreenZone.CENTER
            }
        }

    fun sensorPointToUpright(point: Point2): Point2? = sensorPointToUpright(point, imageQuarterTurns)
}

internal fun sensorPointToUpright(point: Point2, imageQuarterTurns: Int?): Point2? = when (imageQuarterTurns) {
    0 -> point
    1 -> Point2(1f - point.y, point.x)
    2 -> Point2(1f - point.x, 1f - point.y)
    3 -> Point2(point.y, 1f - point.x)
    else -> null
}

internal fun uprightPointToSensor(point: Point2, imageQuarterTurns: Int?): Point2? = when (imageQuarterTurns) {
    0 -> point
    1 -> Point2(point.y, 1f - point.x)
    2 -> Point2(1f - point.x, 1f - point.y)
    3 -> Point2(1f - point.y, point.x)
    else -> null
}

/** Only metric sources may become spoken distances; pseudo sources remain trend/display evidence. */
enum class DepthSource(
    val metric: Boolean,
    val sourceQuality: Float,
    val trustedForStepGuidance: Boolean,
) {
    ARCORE_RAW_DEPTH(metric = true, sourceQuality = 0.95f, trustedForStepGuidance = true),
    ARCORE_FULL_DEPTH(metric = true, sourceQuality = 0.80f, trustedForStepGuidance = true),
    WEBXR_DEPTH(metric = true, sourceQuality = 0.70f, trustedForStepGuidance = true),
    MONOCULAR_METRIC_DEPTH(metric = true, sourceQuality = 0.45f, trustedForStepGuidance = false),
    OBJECT_SIZE_PRIOR(metric = false, sourceQuality = 0.30f, trustedForStepGuidance = false),
    POLYGON_TREND_PSEUDO_DEPTH(metric = false, sourceQuality = 0.25f, trustedForStepGuidance = false),
    UNKNOWN(metric = false, sourceQuality = 0f, trustedForStepGuidance = false),
}

enum class Trend {
    APPROACHING,
    RECEDING,
    STABLE,
    UNKNOWN,
}

enum class MessageLevel {
    NONE,
    INFO,
    AWARE,
    CAUTION,
    WARNING,
    STOP,
}

data class DepthStats(
    val validSampleCount: Int,
    val validSampleRatio: Float,
    val medianM: Float?,
    val p10M: Float?,
    val p20M: Float?,
    val p80M: Float?,
    val iqrM: Float?,
    val madM: Float?,
    val confidenceMedian: Float?,
    val outlierRatio: Float,
)

data class DepthConfidenceBreakdown(
    val sourceQuality: Float,
    val sampleQuality: Float,
    val depthQuality: Float,
    val detectionQuality: Float,
    val trackingQuality: Float,
    val motionQuality: Float,
    val freshnessQuality: Float,
    val corridorQuality: Float,
    val hardGate: Float = 1f,
) {
    val finalScore: Float
        get() = (sourceQuality * weightedBase() * hardGate.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    private fun weightedBase(): Float {
        return (
            sampleQuality * 0.20f +
                depthQuality * 0.20f +
                detectionQuality * 0.16f +
                trackingQuality * 0.16f +
                motionQuality * 0.12f +
                freshnessQuality * 0.10f +
                corridorQuality * 0.06f
            ).coerceIn(0f, 1f)
    }
}

enum class DepthAvailability { MEASURED, PREDICTED, TEMPORARILY_UNAVAILABLE, UNAVAILABLE }

/** A short extrapolation of accepted independent depth, never a metric measurement or voice input. */
data class PredictedDepthEstimate(
    val distanceM: Float,
    val observedAtMs: Long,
    val predictionAgeMs: Long,
    /** Conservative model allowance, not a calibrated sensor accuracy or statistical confidence. */
    val errorBoundM: Float,
    /** Fixed from the last real observation; repeated rendering never extends this deadline. */
    val horizonMs: Long,
    /** Physical range upper allowance only when current pose and observed 3D motion support it. */
    val rangeUpperBoundM: Float? = null,
)

data class TrackedObjectDepth(
    val frameId: Long,
    val timestampMs: Long,
    val trackId: String,
    val className: String,
    val detectionConfidence: Float,
    val bboxNorm: RectNorm,
    val polygonNorm: List<Point2>,
    val maskAreaNorm: Float,
    val centerNorm: Point2,
    val bottomContactNorm: Point2?,
    val source: DepthSource,
    val zDistanceM: Float?,
    val rayDistanceM: Float?,
    val groundDistanceM: Float?,
    val riskDistanceM: Float?,
    val validSampleCount: Int,
    val validSampleRatio: Float,
    val depthMedianM: Float?,
    val depthP20M: Float?,
    val depthIqrM: Float?,
    val trend: Trend,
    val approachScore: Float,
    val approachSpeedMps: Float?,
    val timeToCollisionMs: Long?,
    val confidence: DepthConfidenceBreakdown,
    val userFacing: UserFacingDepth,
    val trackAgeFrames: Int = 0,
    val trackStableMs: Long = 0L,
    val objectMotion: ObjectMotion = ObjectMotion.UNKNOWN,
    val motionEstimate: ObjectMotionEstimate = ObjectMotionEstimate(),
    val userMotion: UserMotionEstimate = UserMotionEstimate(),
    /** Unnamed masks must pass the captured portrait corridor/near-obstacle selection. */
    val walkingObstacleCandidate: Boolean = true,
    /** Captured sensor-to-upright rotation; null/invalid orientation cannot guide tactile steering. */
    val imageQuarterTurns: Int? = 0,
    val depthAvailability: DepthAvailability = if (source.metric && riskDistanceM != null) {
        DepthAvailability.MEASURED
    } else DepthAvailability.UNAVAILABLE,
    /** Original independent depth capture in the camera's millisecond clock, when known. */
    val depthObservedAtMs: Long? = null,
    val prediction: PredictedDepthEstimate? = null,
) {
    val uprightBottomContactNorm: Point2?
        get() = bottomContactNorm?.let { sensorPointToUpright(it, imageQuarterTurns) }
}

data class UserFacingDepth(
    val stepsAhead: Int?,
    val messageLevel: MessageLevel,
    val message: String?,
)

fun distanceMetersToSteps(distanceM: Float?, userStepLengthM: Float): Int? {
    if (distanceM == null || distanceM <= 0f || !distanceM.isFinite()) return null
    val safeStep = if (userStepLengthM in 0.3f..1.2f) userStepLengthM else 0.65f
    return max(1, ceil((distanceM / safeStep).toDouble()).toInt())
}

fun isTactileBlockClass(className: String): Boolean {
    return className.lowercase().contains("tactile") || className.contains("점자")
}
