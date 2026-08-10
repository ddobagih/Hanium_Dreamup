package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Positive speed means the tracked object is closing in on the user. */
data class ApproachSpeed(
    val metersPerSecond: Float?,
    val confidence: Float,
    val trend: Trend,
    val distanceDeltaM: Float?,
    val elapsedMs: Long,
) {
    companion object {
        fun unknown(): ApproachSpeed = ApproachSpeed(
            metersPerSecond = null,
            confidence = 0f,
            trend = Trend.UNKNOWN,
            distanceDeltaM = null,
            elapsedMs = 0L,
        )

        fun fromDistanceHistory(
            history: List<DistanceObservation>,
            minConfidence: Float = 0.35f,
            minElapsedMs: Long = 100L,
            approachingMps: Float = 0.25f,
            stableMps: Float = 0.15f,
        ): ApproachSpeed {
            val usable = history
                .filter { it.source.metric && it.confidence >= minConfidence && it.distanceM > 0f && it.distanceM.isFinite() }
                .sortedBy { it.timestampMs }
            val first = usable.firstOrNull() ?: return unknown()
            val latest = usable.lastOrNull() ?: return unknown()
            val elapsedMs = latest.timestampMs - first.timestampMs
            if (usable.size < 2 || elapsedMs < minElapsedMs) return unknown()

            val deltaM = first.distanceM - latest.distanceM
            val speedMps = deltaM / (elapsedMs / 1000f)
            val trend = when {
                speedMps >= approachingMps -> Trend.APPROACHING
                speedMps <= -approachingMps -> Trend.RECEDING
                abs(speedMps) <= stableMps -> Trend.STABLE
                else -> Trend.UNKNOWN
            }

            return ApproachSpeed(
                metersPerSecond = speedMps,
                confidence = usable.minOf { it.confidence }.coerceIn(0f, 1f),
                trend = trend,
                distanceDeltaM = deltaM,
                elapsedMs = elapsedMs,
            )
        }
    }
}

enum class TtcSource {
    METRIC_DISTANCE,
    BBOX_SCALE,
    NONE,
}

data class TtcEstimate(
    val milliseconds: Long?,
    val confidence: Float,
    val source: TtcSource,
    val reason: String,
)

object TTC {
    fun fromMetricDistance(
        currentDistanceM: Float?,
        approachSpeed: ApproachSpeed,
        maxTtcMs: Long = 60_000L,
    ): TtcEstimate {
        val distance = currentDistanceM?.takeIf { it > 0f && it.isFinite() }
        val speed = approachSpeed.metersPerSecond?.takeIf { it > 0f && it.isFinite() }
        if (distance == null || speed == null) {
            return TtcEstimate(null, 0f, TtcSource.NONE, "metric distance or closing speed unavailable")
        }
        val ttcMs = ((distance / speed) * 1000f).roundToLong().coerceAtLeast(0L)
        if (ttcMs > maxTtcMs) {
            return TtcEstimate(null, approachSpeed.confidence, TtcSource.METRIC_DISTANCE, "TTC outside horizon")
        }
        return TtcEstimate(ttcMs, approachSpeed.confidence, TtcSource.METRIC_DISTANCE, "metric distance / closing speed")
    }

    fun fromBboxScale(
        bboxHistory: List<RectNorm>,
        observedAtHistory: List<Long>,
        minElapsedMs: Long = 500L,
        maxTtcMs: Long = 60_000L,
    ): TtcEstimate {
        val first = bboxHistory.firstOrNull() ?: return TtcEstimate(null, 0f, TtcSource.NONE, "bbox history unavailable")
        val latest = bboxHistory.lastOrNull() ?: return TtcEstimate(null, 0f, TtcSource.NONE, "bbox history unavailable")
        val firstAt = observedAtHistory.firstOrNull() ?: return TtcEstimate(null, 0f, TtcSource.NONE, "timestamp history unavailable")
        val latestAt = observedAtHistory.lastOrNull() ?: return TtcEstimate(null, 0f, TtcSource.NONE, "timestamp history unavailable")
        val elapsedMs = latestAt - firstAt
        if (elapsedMs < minElapsedMs) return TtcEstimate(null, 0f, TtcSource.NONE, "history is too short")
        if (first.area <= 0f || latest.area <= 0f) return TtcEstimate(null, 0f, TtcSource.NONE, "bbox area unavailable")

        val elapsedSeconds = elapsedMs / 1000f
        val firstRelativeDistance = 1f / sqrt(first.area.toDouble()).toFloat()
        val latestRelativeDistance = 1f / sqrt(latest.area.toDouble()).toFloat()
        val relativeClosingSpeed = (firstRelativeDistance - latestRelativeDistance) / elapsedSeconds
        if (relativeClosingSpeed <= 0f || !relativeClosingSpeed.isFinite()) {
            return TtcEstimate(null, 0f, TtcSource.NONE, "bbox scale is not closing")
        }

        val ttcMs = ((latestRelativeDistance / relativeClosingSpeed) * 1000f).roundToLong().coerceAtLeast(0L)
        if (ttcMs > maxTtcMs) return TtcEstimate(null, 0.35f, TtcSource.BBOX_SCALE, "bbox TTC outside horizon")
        val growthRatio = latest.area / first.area - 1f
        val confidence = (0.25f + abs(growthRatio).coerceAtMost(1f) * 0.35f + bboxHistory.size.coerceAtMost(5) * 0.08f).coerceIn(0f, 1f)
        return TtcEstimate(ttcMs, confidence, TtcSource.BBOX_SCALE, "bbox scale closing")
    }
}

data class IdSwitchSuspicion(
    val suspected: Boolean,
    val confidence: Float,
    val trackId: String,
    val reason: String,
)

data class IdSwitchSuspicionConfig(
    val maxCenterJumpNorm: Float = 0.35f,
    val maxAreaRatio: Float = 4.0f,
    val maxMetricDistanceJumpM: Float = 1.2f,
    val minSuspicionConfidence: Float = 0.50f,
)

class IdSwitchSuspicionPolicy(
    private val config: IdSwitchSuspicionConfig = IdSwitchSuspicionConfig(),
) {
    fun assess(
        track: TrackState,
        currentGeometry: ObjectGeometry,
        currentDistanceM: Float? = null,
    ): IdSwitchSuspicion {
        val previous = track.latestGeometry ?: return none(track, "previous geometry unavailable")
        if (previous.className != currentGeometry.className) return none(track, "class changed")

        val centerJump = centerDistance(previous.centerNorm, currentGeometry.centerNorm)
        val previousArea = previous.maskAreaNorm.coerceAtLeast(0.0001f)
        val currentArea = currentGeometry.maskAreaNorm.coerceAtLeast(0.0001f)
        val areaRatio = max(previousArea, currentArea) / minOf(previousArea, currentArea)
        val lastDistance = track.distanceHistory.lastOrNull()?.takeIf { it.source.metric }?.distanceM
        val distanceJump = if (lastDistance != null && currentDistanceM != null) abs(lastDistance - currentDistanceM) else 0f

        val centerScore = (centerJump / config.maxCenterJumpNorm).coerceIn(0f, 1f)
        val areaScore = ((areaRatio - 1f) / (config.maxAreaRatio - 1f)).coerceIn(0f, 1f)
        val distanceScore = (distanceJump / config.maxMetricDistanceJumpM).coerceIn(0f, 1f)
        val confidence = (centerScore * 0.45f + areaScore * 0.35f + distanceScore * 0.20f).coerceIn(0f, 1f)
        val suspected = confidence >= config.minSuspicionConfidence
        return IdSwitchSuspicion(
            suspected = suspected,
            confidence = confidence,
            trackId = track.trackId,
            reason = if (suspected) "geometry/depth jump suggests track id switch" else "id switch confidence below threshold",
        )
    }

    private fun none(track: TrackState, reason: String): IdSwitchSuspicion = IdSwitchSuspicion(
        suspected = false,
        confidence = 0f,
        trackId = track.trackId,
        reason = reason,
    )
}

enum class TrackingRiskType {
    DISPLAY_ONLY,
    APPROACHING_OBJECT,
    BLOCKING_OBJECT,
}

data class TrackingRiskDecision(
    val alertable: Boolean,
    val riskType: TrackingRiskType,
    val messageLevel: MessageLevel,
    val reason: String,
)

data class TrackingRiskConfig(
    val minStableFrames: Int = 3,
    val minStableMs: Long = 700L,
    val minMetricDistanceConfidence: Float = 0.50f,
    val closeDistanceM: Float = 2.20f,
    val stopDistanceM: Float = 1.20f,
    val highTtcMs: Long = 3_000L,
    val ttcAlertMs: Long = 10_000L,
    val awareTtcMs: Long = 30_000L,
    val areaGrowthSignalRatio: Float = 0.25f,
    val areaGrowthSignalPerSecond: Float = 0.30f,
    val highAreaGrowthPerSecond: Float = 0.60f,
)

data class TrackRiskSignals(
    val stableFrames: Int,
    val stableMs: Long,
    val distance: DistanceObservation?,
    val trend: Trend,
    val timeToCollision: TtcEstimate,
    val areaGrowthRatio: Float?,
    val areaGrowthPerSecond: Float?,
    val idSwitchSuspected: Boolean = false,
)

/**
 * Converts temporal tracking evidence into risk levels without treating bbox-scale TTC as distance.
 * The feedback layer applies the additional metric-source gate before speech or haptics.
 */
class TrackingRiskPolicy(
    private val config: TrackingRiskConfig = TrackingRiskConfig(),
) {
    fun evaluate(signals: TrackRiskSignals): TrackingRiskDecision {
        if (signals.idSwitchSuspected) return displayOnly("id switch suspected")
        val stable = signals.stableFrames >= config.minStableFrames && signals.stableMs >= config.minStableMs
        if (!stable) return displayOnly("tracking is not stable enough")

        val metricDistance = signals.distance?.takeIf {
            it.source.metric && it.confidence >= config.minMetricDistanceConfidence && it.distanceM > 0f && it.distanceM.isFinite()
        }?.distanceM
        val closeMetric = metricDistance != null && metricDistance <= config.closeDistanceM
        if (closeMetric) {
            return TrackingRiskDecision(
                alertable = true,
                riskType = TrackingRiskType.BLOCKING_OBJECT,
                messageLevel = if (metricDistance <= config.stopDistanceM) MessageLevel.STOP else MessageLevel.WARNING,
                reason = "metric depth marks a close object",
            )
        }

        val ttcMs = signals.timeToCollision.milliseconds
        val areaGrowthSignal = (signals.areaGrowthRatio ?: 0f) >= config.areaGrowthSignalRatio ||
            (signals.areaGrowthPerSecond ?: 0f) >= config.areaGrowthSignalPerSecond
        val collisionSignal = (ttcMs != null && ttcMs <= config.ttcAlertMs) || areaGrowthSignal
        if (signals.trend == Trend.APPROACHING && collisionSignal) {
            val high = (ttcMs != null && ttcMs <= config.highTtcMs) ||
                (signals.areaGrowthPerSecond ?: 0f) >= config.highAreaGrowthPerSecond ||
                (metricDistance != null && metricDistance <= config.stopDistanceM)
            return TrackingRiskDecision(
                alertable = true,
                riskType = TrackingRiskType.APPROACHING_OBJECT,
                messageLevel = if (high) MessageLevel.STOP else MessageLevel.WARNING,
                reason = "stable tracking marks an approaching object",
            )
        }
        if (signals.trend == Trend.APPROACHING && ttcMs != null && ttcMs <= config.awareTtcMs) {
            return TrackingRiskDecision(
                alertable = false,
                riskType = TrackingRiskType.APPROACHING_OBJECT,
                messageLevel = MessageLevel.AWARE,
                reason = "stable tracking marks an approaching object within aware horizon",
            )
        }

        return displayOnly("no approach or blocking risk signal")
    }

    private fun displayOnly(reason: String): TrackingRiskDecision = TrackingRiskDecision(
        alertable = false,
        riskType = TrackingRiskType.DISPLAY_ONLY,
        messageLevel = MessageLevel.NONE,
        reason = reason,
    )
}
