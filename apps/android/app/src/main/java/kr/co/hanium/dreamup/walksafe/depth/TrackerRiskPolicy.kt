package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Uses complete object positions reconstructed in one anchor reference. Axial depth residual alone
 * cannot establish stationary motion because an object may be moving sideways.
 */
object ObjectMotionPolicy {
    fun classify(track: TrackState, motionEstimate: ObjectMotionEstimate = estimate(track)): ObjectMotion {
        val center = track.latestGeometry?.centerNorm ?: return ObjectMotion.UNKNOWN
        if (center.x !in 0.35f..0.65f || (motionEstimate.relativeClosingSpeedMps ?: 0f) < 0.25f) {
            return ObjectMotion.UNKNOWN
        }
        val cameraVelocity = motionEstimate.cameraVelocityInAnchorMps ?: return ObjectMotion.UNKNOWN
        val latest = track.motionSamples().lastOrNull() ?: return ObjectMotion.UNKNOWN
        val pose = latest.cameraPoseEvidence ?: return ObjectMotion.UNKNOWN
        val objectPosition = latest.objectPositionInAnchor ?: return ObjectMotion.UNKNOWN
        val cameraTowardObject = (objectPosition - pose.position()).normalized()
        return when {
            motionEstimate.direction == ObjectMovementDirection.TOWARD_USER -> ObjectMotion.OBJECT_APPROACHING
            motionEstimate.direction == ObjectMovementDirection.STATIONARY && cameraVelocity.dot(cameraTowardObject) >= 0.35f ->
                ObjectMotion.USER_APPROACHING_STATIONARY
            else -> ObjectMotion.UNKNOWN
        }
    }

    fun estimate(track: TrackState): ObjectMotionEstimate {
        val unknown = ObjectMotionEstimate()
        if (!track.stable || track.idSwitchSuspected || !track.metricDistanceReliable || track.missedFrames != 0) return unknown
        // Selection is anchored at this observation, so an old/reprojected depth cannot publish fresh motion.
        val history = track.motionSamples()
        val latestAtMs = history.lastOrNull()?.timestampMs ?: return unknown
        if (history.size < 4 || history.any {
                !it.source.trustedForStepGuidance || !it.confidence.isFinite() || it.confidence < 0.55f ||
                    !it.distanceM.isFinite() || it.distanceM <= 0f ||
                    it.cameraPoseEvidence?.isValidFor(it.timestampMs) != true ||
                    it.cameraPoseEvidence?.imageProjection == null ||
                    it.objectPositionInAnchor?.isFinite() != true
            }
        ) return unknown
        if (history.map { it.source }.distinct().size != 1) return unknown
        val elapsedMs = history.last().timestampMs - history.first().timestampMs
        if (elapsedMs !in 600L..4_000L || history.zipWithNext().any { (a, b) ->
                b.timestampMs - a.timestampMs !in 100L..1_500L
            }
        ) return unknown
        val reference = requireNotNull(history.first().cameraPoseEvidence)
        if (history.any {
                val pose = requireNotNull(it.cameraPoseEvidence)
                pose.referenceId != reference.referenceId || forwardAlignment(reference, pose) < 0.9961947f
            }
        ) return unknown

        val first = history.first()
        val latest = history.last()
        val latestPose = requireNotNull(latest.cameraPoseEvidence)
        val projection = requireNotNull(latestPose.imageProjection)
        val elapsedSeconds = elapsedMs / 1_000f
        val objectVelocity = (requireNotNull(latest.objectPositionInAnchor) -
            requireNotNull(first.objectPositionInAnchor)) * (1f / elapsedSeconds)
        val cameraVelocity = (latestPose.position() - reference.position()) * (1f / elapsedSeconds)
        val denseHistory = track.denseMotionHistory().filter { it.timestampMs >= first.timestampMs }
        if (denseHistory.any {
                forwardAlignment(reference, requireNotNull(it.cameraPoseEvidence)) < 0.9961947f
            } || denseHistory.zipWithNext().any { (a, b) ->
                val seconds = (b.timestampMs - a.timestampMs) / 1_000f
                seconds <= 0f || (requireNotNull(b.cameraPoseEvidence).position() -
                    requireNotNull(a.cameraPoseEvidence).position()).norm() / seconds > 3.5f
            }
        ) return unknown
        // Key sampling must not hide a short movement onset inside a nominally stationary fit.
        if (objectVelocity.norm() <= 0.20f && denseHistory.zipWithNext().any { (a, b) ->
                val seconds = (b.timestampMs - a.timestampMs) / 1_000f
                seconds <= 0f || (requireNotNull(b.objectPositionInAnchor) -
                    requireNotNull(a.objectPositionInAnchor)).norm() / seconds > 0.20f
            }
        ) return unknown
        // Consistent full 3D displacement is needed: an average must not hide stop-go or depth noise.
        for ((a, b) in history.zipWithNext()) {
            val previous = requireNotNull(a.cameraPoseEvidence)
            val current = requireNotNull(b.cameraPoseEvidence)
            val seconds = (b.timestampMs - a.timestampMs) / 1_000f
            val cameraSegmentVelocity = (current.position() - previous.position()) * (1f / seconds)
            val objectSegmentVelocity = (requireNotNull(b.objectPositionInAnchor) -
                requireNotNull(a.objectPositionInAnchor)) * (1f / seconds)
            if (forwardAlignment(previous, current) < 0.9961947f ||
                cameraSegmentVelocity.norm() > 3.5f || (cameraSegmentVelocity - cameraVelocity).norm() > 0.35f ||
                (objectSegmentVelocity - objectVelocity).norm() > 0.35f ||
                // A low average must not label an object that just started moving as stationary.
                (objectVelocity.norm() <= 0.20f && objectSegmentVelocity.norm() > 0.20f)
            ) return unknown
        }
        val relativeVelocity = objectVelocity - cameraVelocity
        val objectToCamera = latestPose.position() - requireNotNull(latest.objectPositionInAnchor)
        if (objectToCamera.norm() <= 0.01f) return unknown
        val towardUser = objectToCamera.normalized()
        val objectClosingSpeed = objectVelocity.dot(towardUser)
        val relativeClosingSpeed = relativeVelocity.dot(towardUser)
        val speed = objectVelocity.norm()
        val rightSpeed = objectVelocity.dot(Vec3(projection.rightX, projection.rightY, projection.rightZ))
        val upSpeed = objectVelocity.dot(Vec3(projection.upX, projection.upY, projection.upZ))
        val forwardSpeed = objectVelocity.dot(Vec3(latestPose.forwardX, latestPose.forwardY, latestPose.forwardZ))
        if (listOf(speed, relativeClosingSpeed, rightSpeed, upSpeed, forwardSpeed).any { !it.isFinite() }) return unknown
        val direction = when {
            speed <= 0.20f -> ObjectMovementDirection.STATIONARY
            objectClosingSpeed >= 0.45f -> ObjectMovementDirection.TOWARD_USER
            objectClosingSpeed <= -0.45f -> ObjectMovementDirection.AWAY_FROM_USER
            abs(rightSpeed) >= 0.25f && abs(rightSpeed) >= abs(forwardSpeed) && abs(rightSpeed) >= abs(upSpeed) ->
                if (rightSpeed > 0f) ObjectMovementDirection.RIGHT else ObjectMovementDirection.LEFT
            else -> ObjectMovementDirection.OTHER
        }
        return ObjectMotionEstimate(
            referenceId = reference.referenceId,
            observedAtMs = latestAtMs,
            elapsedMs = elapsedMs,
            objectVelocityInAnchorMps = objectVelocity,
            relativeVelocityInAnchorMps = relativeVelocity,
            cameraVelocityInAnchorMps = cameraVelocity,
            objectDirectionInCamera = if (direction == ObjectMovementDirection.STATIONARY) null else
                Vec3(rightSpeed, upSpeed, forwardSpeed).normalized(),
            objectSpeedMps = speed,
            relativeClosingSpeedMps = relativeClosingSpeed,
            direction = direction,
            confidence = history.minOf { it.confidence },
        )
    }

    private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
    private fun CameraPoseEvidence.position(): Vec3 = Vec3(positionX, positionY, positionZ)

    private fun CameraPoseEvidence.isValidFor(observedAtMs: Long): Boolean {
        val values = listOf(positionX, positionY, positionZ, forwardX, forwardY, forwardZ)
        val forwardLengthSquared = forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ
        return referenceId >= 0L && timestampMs == observedAtMs && values.all { it.isFinite() } &&
            abs(forwardLengthSquared - 1f) <= 0.001f && objectCenterInAnchor(Point2(0.5f, 0.5f), 1f) != null
    }

    private fun forwardAlignment(a: CameraPoseEvidence, b: CameraPoseEvidence): Float =
        Vec3(a.forwardX, a.forwardY, a.forwardZ).normalized()
            .dot(Vec3(b.forwardX, b.forwardY, b.forwardZ).normalized())
}

/** Positive speed means relative distance is closing; it does not establish which actor moved. */
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
