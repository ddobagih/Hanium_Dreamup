package kr.hanium.dreamup.android.arcore.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Archived pure Kotlin ARCore depth architecture reference.
 *
 * Production Android code now lives under apps/android/. This file is kept only as an early architecture
 * reference and is not the runtime source of truth for the current APK, JSON TFLite config, or debug bbox
 * overlay. Android-specific adapters should wrap ARCore Frame/Image objects, copy the needed depth buffer
 * or keep it valid only for the sampling call, and expose the result through ArCoreFrameProvider/
 * DepthFrameSnapshot.
 */
interface ArCoreFrameProvider {
    /** Returns the latest depth frame, or null when depth is unsupported/unavailable for this AR frame. */
    fun latestDepthFrame(): DepthFrameSnapshot?
}

data class ImageSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }
}

data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

data class PixelPoint(
    val x: Int,
    val y: Int,
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): NormalizedRect = NormalizedRect(
        left = clamp01(left),
        top = clamp01(top),
        right = clamp01(right),
        bottom = clamp01(bottom),
    )

    fun inset(ratio: Float): NormalizedRect {
        val safeRatio = ratio.coerceIn(0f, 0.45f)
        val insetX = width * safeRatio
        val insetY = height * safeRatio
        return NormalizedRect(
            left = left + insetX,
            top = top + insetY,
            right = right - insetX,
            bottom = bottom - insetY,
        ).clamped()
    }

    companion object {
        fun fromXywh(x: Float, y: Float, width: Float, height: Float): NormalizedRect = NormalizedRect(
            left = x,
            top = y,
            right = x + width,
            bottom = y + height,
        ).clamped()
    }
}

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ObjectDetection(
    val trackId: String?,
    val className: String,
    val bbox: NormalizedRect,
    val confidence: Float,
    val observedAtMillis: Long,
)

/**
 * ARCore adapter output. depthMetersAt must return meters, not millimeters.
 * Invalid/unknown samples should return null.
 *
 * The lambda must not read from an already-closed ARCore Image. A production adapter should either copy
 * depth data into an immutable buffer before returning this snapshot, or perform sampling inside the
 * image lifecycle and then return a materialized TrackedObjectDepth.
 */
data class DepthFrameSnapshot(
    val depthSize: ImageSize,
    val observedAtMillis: Long,
    val depthMetersAt: (PixelPoint) -> Float?,
)

enum class RotationDegrees(val degrees: Int) {
    D0(0),
    D90(90),
    D180(180),
    D270(270);

    companion object {
        fun from(degrees: Int): RotationDegrees = when (((degrees % 360) + 360) % 360) {
            0 -> D0
            90 -> D90
            180 -> D180
            270 -> D270
            else -> error("rotation must be one of 0, 90, 180, 270")
        }
    }
}

/**
 * Maps detector normalized camera/display coordinates to depth-image pixels.
 *
 * Assumption: detector bbox coordinates are normalized to the same camera stream that the ARCore adapter
 * exposes. If the Android implementation uses display-rotated or cropped coordinates, prefer ARCore's
 * coordinate transform API in the adapter and keep this class as the pure fallback/testable mapper.
 */
class CoordinateMapper(
    private val depthSize: ImageSize,
    private val rotation: RotationDegrees = RotationDegrees.D0,
    private val mirrorX: Boolean = false,
) {
    fun normalizedPointToDepthPixel(point: NormalizedPoint): PixelPoint {
        val depthPoint = rotateAndMirror(point)
        return PixelPoint(
            x = (depthPoint.x * (depthSize.width - 1)).roundToInt().coerceIn(0, depthSize.width - 1),
            y = (depthPoint.y * (depthSize.height - 1)).roundToInt().coerceIn(0, depthSize.height - 1),
        )
    }

    fun detectionRectToDepthRect(rect: NormalizedRect): PixelRect {
        val points = listOf(
            normalizedPointToDepthPixel(NormalizedPoint(rect.left, rect.top)),
            normalizedPointToDepthPixel(NormalizedPoint(rect.right, rect.top)),
            normalizedPointToDepthPixel(NormalizedPoint(rect.right, rect.bottom)),
            normalizedPointToDepthPixel(NormalizedPoint(rect.left, rect.bottom)),
        )
        return PixelRect(
            left = points.minOf { it.x },
            top = points.minOf { it.y },
            right = points.maxOf { it.x },
            bottom = points.maxOf { it.y },
        )
    }

    fun samplePoints(
        rect: NormalizedRect,
        gridSize: Int = DEFAULT_GRID_SIZE,
        insetRatio: Float = DEFAULT_ROI_INSET_RATIO,
    ): List<PixelPoint> {
        val samplesPerAxis = gridSize.coerceIn(3, 9)
        val roi = rect.clamped().inset(insetRatio)
        val points = mutableListOf<PixelPoint>()

        for (row in 0 until samplesPerAxis) {
            val y = interpolate(roi.top, roi.bottom, row, samplesPerAxis)
            for (column in 0 until samplesPerAxis) {
                val x = interpolate(roi.left, roi.right, column, samplesPerAxis)
                points += normalizedPointToDepthPixel(NormalizedPoint(x, y))
            }
        }

        return points.distinct()
    }

    private fun rotateAndMirror(point: NormalizedPoint): NormalizedPoint {
        val x = if (mirrorX) 1f - clamp01(point.x) else clamp01(point.x)
        val y = clamp01(point.y)
        return when (rotation) {
            RotationDegrees.D0 -> NormalizedPoint(x, y)
            RotationDegrees.D90 -> NormalizedPoint(y, 1f - x)
            RotationDegrees.D180 -> NormalizedPoint(1f - x, 1f - y)
            RotationDegrees.D270 -> NormalizedPoint(1f - y, x)
        }
    }
}

object RobustDepthStats {
    data class Policy(
        val minDepthMeters: Float = 0.15f,
        val maxDepthMeters: Float = 8.0f,
        val minValidSamples: Int = 5,
        val absoluteOutlierToleranceMeters: Float = 0.45f,
        val relativeOutlierToleranceRatio: Float = 0.35f,
        val spreadToleranceMeters: Float = 0.60f,
        val relativeSpreadToleranceRatio: Float = 0.45f,
    )

    data class Result(
        val medianMeters: Float,
        val sampleCount: Int,
        val requestedSampleCount: Int,
        val validSampleRatio: Float,
        val iqrMeters: Float,
        val confidence: Float,
    )

    fun summarize(
        rawDepthMeters: List<Float>,
        requestedSampleCount: Int,
        policy: Policy = Policy(),
    ): Result? {
        if (requestedSampleCount <= 0) return null

        val valid = rawDepthMeters
            .filter { it.isFiniteNumber() && it in policy.minDepthMeters..policy.maxDepthMeters }
            .sorted()
        if (valid.size < policy.minValidSamples) return null

        val initialMedian = median(valid)
        val outlierTolerance = max(
            policy.absoluteOutlierToleranceMeters,
            initialMedian * policy.relativeOutlierToleranceRatio,
        )
        val trimmed = valid.filter { abs(it - initialMedian) <= outlierTolerance }.sorted()
        val finalValues = if (trimmed.size >= policy.minValidSamples) trimmed else valid
        val finalMedian = median(finalValues)
        val iqr = percentile(finalValues, 0.75f) - percentile(finalValues, 0.25f)
        val validSampleRatio = (valid.size.toFloat() / requestedSampleCount.toFloat()).coerceIn(0f, 1f)
        val countScore = (finalValues.size.toFloat() / policy.minValidSamples.toFloat()).coerceIn(0f, 1f)
        val spreadTolerance = max(policy.spreadToleranceMeters, finalMedian * policy.relativeSpreadToleranceRatio)
        val spreadScore = (1f - iqr / spreadTolerance).coerceIn(0f, 1f)
        val confidence = (0.55f * validSampleRatio + 0.35f * spreadScore + 0.10f * countScore).coerceIn(0f, 1f)

        return Result(
            medianMeters = finalMedian,
            sampleCount = finalValues.size,
            requestedSampleCount = requestedSampleCount,
            validSampleRatio = validSampleRatio,
            iqrMeters = iqr,
            confidence = confidence,
        )
    }

    private fun median(sortedValues: List<Float>): Float {
        require(sortedValues.isNotEmpty()) { "median requires at least one value" }
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middle - 1] + sortedValues[middle]) / 2f
        } else {
            sortedValues[middle]
        }
    }

    private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
        require(sortedValues.isNotEmpty()) { "percentile requires at least one value" }
        val safePercentile = percentile.coerceIn(0f, 1f)
        val index = safePercentile * (sortedValues.size - 1)
        val lowerIndex = index.toInt()
        val upperIndex = min(lowerIndex + 1, sortedValues.lastIndex)
        val fraction = index - lowerIndex
        return sortedValues[lowerIndex] * (1f - fraction) + sortedValues[upperIndex] * fraction
    }
}

enum class DepthSource {
    ARCORE_DEPTH,
    UNAVAILABLE,
}

enum class ApproachState {
    APPROACHING,
    STABLE,
    RECEDING,
    UNKNOWN,
}

data class TrackedObjectDepth(
    val trackId: String?,
    val className: String,
    val bbox: NormalizedRect,
    val distanceMeters: Float?,
    val depthSource: DepthSource,
    val depthConfidence: Float,
    val sampleCount: Int,
    val requestedSampleCount: Int,
    val validSampleRatio: Float,
    val iqrMeters: Float?,
    val approachState: ApproachState,
    val distanceDeltaMeters: Float?,
    val observedAtMillis: Long,
    val frameAgeMillis: Long,
)

class DepthSampler(
    private val gridSize: Int = DEFAULT_GRID_SIZE,
    private val roiInsetRatio: Float = DEFAULT_ROI_INSET_RATIO,
    private val maxFrameAgeMillis: Long = DEFAULT_MAX_FRAME_AGE_MILLIS,
    private val statsPolicy: RobustDepthStats.Policy = RobustDepthStats.Policy(),
) {
    fun sample(
        frame: DepthFrameSnapshot,
        detection: ObjectDetection,
        mapper: CoordinateMapper,
        previousDepth: TrackedObjectDepth? = null,
        nowMillis: Long = detection.observedAtMillis,
    ): TrackedObjectDepth {
        val frameAgeMillis = max(0L, nowMillis - frame.observedAtMillis)
        if (frameAgeMillis > maxFrameAgeMillis) {
            return unavailable(detection, previousDepth, nowMillis, frameAgeMillis)
        }

        val points = mapper.samplePoints(
            rect = detection.bbox,
            gridSize = gridSize,
            insetRatio = roiInsetRatio,
        )
        val rawDepths = points.mapNotNull { frame.depthMetersAt(it) }
        val stats = RobustDepthStats.summarize(
            rawDepthMeters = rawDepths,
            requestedSampleCount = points.size,
            policy = statsPolicy,
        ) ?: return unavailable(detection, previousDepth, nowMillis, frameAgeMillis)

        val deltaMeters = previousDepth?.distanceMeters?.let { previous -> previous - stats.medianMeters }
        return TrackedObjectDepth(
            trackId = detection.trackId,
            className = detection.className,
            bbox = detection.bbox,
            distanceMeters = stats.medianMeters,
            depthSource = DepthSource.ARCORE_DEPTH,
            depthConfidence = (stats.confidence * detection.confidence.coerceIn(0f, 1f)).coerceIn(0f, 1f),
            sampleCount = stats.sampleCount,
            requestedSampleCount = stats.requestedSampleCount,
            validSampleRatio = stats.validSampleRatio,
            iqrMeters = stats.iqrMeters,
            approachState = resolveApproachState(
                previousDepth = previousDepth,
                currentDistanceMeters = stats.medianMeters,
                currentObservedAtMillis = frame.observedAtMillis,
            ),
            distanceDeltaMeters = deltaMeters,
            observedAtMillis = frame.observedAtMillis,
            frameAgeMillis = frameAgeMillis,
        )
    }

    private fun unavailable(
        detection: ObjectDetection,
        previousDepth: TrackedObjectDepth?,
        nowMillis: Long,
        frameAgeMillis: Long,
    ): TrackedObjectDepth = TrackedObjectDepth(
        trackId = detection.trackId,
        className = detection.className,
        bbox = detection.bbox,
        distanceMeters = null,
        depthSource = DepthSource.UNAVAILABLE,
        depthConfidence = 0f,
        sampleCount = 0,
        requestedSampleCount = 0,
        validSampleRatio = 0f,
        iqrMeters = null,
        approachState = ApproachState.UNKNOWN,
        distanceDeltaMeters = null,
        observedAtMillis = nowMillis,
        frameAgeMillis = frameAgeMillis,
    )

    private fun resolveApproachState(
        previousDepth: TrackedObjectDepth?,
        currentDistanceMeters: Float,
        currentObservedAtMillis: Long,
    ): ApproachState {
        val previousDistance = previousDepth?.distanceMeters ?: return ApproachState.UNKNOWN
        val deltaSeconds = (currentObservedAtMillis - previousDepth.observedAtMillis) / 1000f
        if (deltaSeconds < 0.1f) return ApproachState.UNKNOWN

        val approachVelocityMetersPerSecond = (previousDistance - currentDistanceMeters) / deltaSeconds
        return when {
            approachVelocityMetersPerSecond >= 0.25f -> ApproachState.APPROACHING
            approachVelocityMetersPerSecond <= -0.25f -> ApproachState.RECEDING
            abs(approachVelocityMetersPerSecond) <= 0.15f -> ApproachState.STABLE
            else -> ApproachState.UNKNOWN
        }
    }
}

enum class MessageAction {
    NONE,
    HAPTIC_ONLY,
    SPEAK,
}

enum class MessagePriority {
    LOW,
    MEDIUM,
    HIGH,
}

data class DepthMessageDecision(
    val action: MessageAction,
    val priority: MessagePriority,
    val message: String?,
    val reason: String,
)

data class MessagePolicyConfig(
    val minDepthConfidence: Float = 0.50f,
    val nearDistanceMeters: Float = 1.20f,
    val cautionDistanceMeters: Float = 2.50f,
    val approachingDistanceMeters: Float = 3.00f,
    val globalCooldownMillis: Long = 700L,
    val perTrackCooldownMillis: Long = 2_500L,
)

/**
 * Converts depth observations into user-facing actions.
 *
 * Product rule: depth alone does not create a report. Reports and public-submission flows stay outside
 * this policy. This class only gates warning speech/haptics for already tracked objects.
 */
class MessagePolicy(
    private val config: MessagePolicyConfig = MessagePolicyConfig(),
) {
    private var lastGlobalSpeakAtMillis: Long? = null
    private val lastSpeakAtByTrack = mutableMapOf<String, Long>()

    fun evaluate(depth: TrackedObjectDepth, nowMillis: Long): DepthMessageDecision {
        val distanceMeters = depth.distanceMeters ?: return none("depth unavailable")
        if (depth.depthConfidence < config.minDepthConfidence) {
            return none("depth confidence below threshold")
        }

        val isNear = distanceMeters <= config.nearDistanceMeters
        val isCaution = distanceMeters <= config.cautionDistanceMeters
        val isApproaching = depth.approachState == ApproachState.APPROACHING &&
            distanceMeters <= config.approachingDistanceMeters
        if (!isNear && !isApproaching && !isCaution) {
            return none("outside warning distance")
        }

        val priority = when {
            isNear -> MessagePriority.HIGH
            isApproaching -> MessagePriority.MEDIUM
            isCaution -> MessagePriority.MEDIUM
            else -> MessagePriority.LOW
        }
        if (!isNear && !isApproaching && isCaution) {
            return DepthMessageDecision(
                action = MessageAction.HAPTIC_ONLY,
                priority = priority,
                message = null,
                reason = "caution distance without approach",
            )
        }
        val trackKey = depth.trackId ?: "${depth.className}:${depth.bbox.left}:${depth.bbox.top}"
        val lastTrackSpeakAt = lastSpeakAtByTrack[trackKey]
        if (lastTrackSpeakAt != null && nowMillis - lastTrackSpeakAt < config.perTrackCooldownMillis) {
            return DepthMessageDecision(
                action = MessageAction.HAPTIC_ONLY,
                priority = priority,
                message = null,
                reason = "track cooldown active",
            )
        }

        val lastGlobalSpeakAt = lastGlobalSpeakAtMillis
        if (lastGlobalSpeakAt != null && nowMillis - lastGlobalSpeakAt < config.globalCooldownMillis) {
            return DepthMessageDecision(
                action = MessageAction.HAPTIC_ONLY,
                priority = priority,
                message = null,
                reason = "global cooldown active",
            )
        }

        lastSpeakAtByTrack[trackKey] = nowMillis
        lastGlobalSpeakAtMillis = nowMillis
        return DepthMessageDecision(
            action = MessageAction.SPEAK,
            priority = priority,
            message = buildMessage(depth, distanceMeters),
            reason = if (isNear) "near object" else "approaching object",
        )
    }

    private fun buildMessage(depth: TrackedObjectDepth, distanceMeters: Float): String {
        val distanceText = formatDistance(distanceMeters)
        return if (depth.approachState == ApproachState.APPROACHING) {
            "${depth.className} 접근 중, $distanceText"
        } else {
            "${depth.className} $distanceText"
        }
    }

    private fun formatDistance(distanceMeters: Float): String {
        val roundedTenths = (distanceMeters * 10f).roundToInt() / 10f
        if (roundedTenths < 1f) return "1미터 이내"
        val asInt = roundedTenths.toInt()
        val text = if (abs(roundedTenths - asInt) < 0.01f) asInt.toString() else roundedTenths.toString()
        return "약 ${text}미터 앞"
    }

    private fun none(reason: String): DepthMessageDecision = DepthMessageDecision(
        action = MessageAction.NONE,
        priority = MessagePriority.LOW,
        message = null,
        reason = reason,
    )
}

private const val DEFAULT_GRID_SIZE = 5
private const val DEFAULT_ROI_INSET_RATIO = 0.20f
private const val DEFAULT_MAX_FRAME_AGE_MILLIS = 900L

private fun interpolate(start: Float, end: Float, index: Int, sampleCount: Int): Float {
    if (sampleCount <= 1) return (start + end) / 2f
    return start + ((end - start) * index.toFloat() / (sampleCount - 1).toFloat())
}

private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

private fun Float.isFiniteNumber(): Boolean = !isNaN() && !isInfinite()
