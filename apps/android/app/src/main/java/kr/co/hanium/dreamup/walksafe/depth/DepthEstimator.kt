package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.ln

data class ObjectDepthInput(
    val frameId: Long,
    val timestampMs: Long,
    val geometry: ObjectGeometry,
    val track: TrackState,
    val mapper: CoordinateMapper,
    val rawDepth: DepthImage16? = null,
    val rawConfidence: ConfidenceImage8? = null,
    val rawDepthFreshnessQuality: Float = 1f,
    val fullDepth: DepthImage16? = null,
    val motionContext: MotionContext = MotionContext(),
    val groundDistanceM: Float? = null,
    val rayDistanceM: Float? = null,
    val rawDepthMatchesCameraFrame: Boolean = false,
    val fullDepthMatchesCameraFrame: Boolean = false,
    val rawDepthTimestampNs: Long? = null,
    val fullDepthTimestampNs: Long? = null,
    val visualTrackingQuality: Float = 1f,
    val requireIndependentDepthObservation: Boolean = false,
)

/**
 * Produces one depth decision per tracked object.
 *
 * Trusted metric sources are selected in Raw Depth -> Full Depth order. Bbox/polygon growth is a
 * qualitative fallback only and never receives a meter distance or step count.
 */
class ObjectDepthEstimator(
    private val sampler: DepthSampler = DepthSampler(),
    private val tracker: ObjectTracker,
    private val messagePolicy: MessagePolicy = MessagePolicy(),
) {
    /** Accepts validated mask statistics without resampling the surrounding bounding rectangle. */
    fun estimateSupportedMask(input: ObjectDepthInput, stats: DepthStats, supportedPositionInAnchor: Vec3? = null,
                              source: DepthSource = DepthSource.ARCORE_RAW_DEPTH): TrackedObjectDepth {
        require(input.requireIndependentDepthObservation) { "mask depth requires independent observation accounting" }
        require(when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> input.rawDepth != null && input.rawConfidence != null
            DepthSource.ARCORE_FULL_DEPTH -> input.fullDepth != null
            else -> false
        }) { "mask depth requires its captured depth source" }
        require(stats.medianM?.let { it.isFinite() && it > 0f } == true && stats.validSampleCount > 0 &&
            stats.validSampleRatio.isFinite() && stats.validSampleRatio in 0f..1f
        ) { "mask statistics must describe a supported whole mask" }
        return buildMetricResult(source, stats, input, supportedPositionInAnchor)
    }

    fun estimate(input: ObjectDepthInput): TrackedObjectDepth {
        val depthPolygon = input.mapper.imagePolygonToDepthPolygon(input.geometry.polygonNorm)
        if (depthPolygon.size < 3) {
            return buildPseudoTrendResult(input, reasonHardGate = 0f)
        }

        // Raw Depth is accepted only with its confidence image and enough in-polygon samples.
        val rawStats = if (input.rawDepth != null && input.rawConfidence != null) {
            sampler.sampleObjectDepth(
                depthImage = input.rawDepth,
                confidenceImage = input.rawConfidence,
                polygonDepthNorm = depthPolygon,
                options = DepthSampleOptions(minValidSamples = MIN_RAW_VALID_SAMPLES, minConfidence = RAW_CONFIDENCE_MIN),
            )
        } else {
            null
        }
        if (rawStats != null && rawStats.validSampleCount >= MIN_RAW_VALID_SAMPLES && rawStats.medianM != null) {
            return buildMetricResult(DepthSource.ARCORE_RAW_DEPTH, rawStats, input)
        }

        // Full Depth is denser but has no per-pixel confidence, so it uses a larger sample gate.
        val fullStats = input.fullDepth?.let { fullDepth ->
            sampler.sampleObjectDepth(
                depthImage = fullDepth,
                confidenceImage = null,
                polygonDepthNorm = depthPolygon,
                options = DepthSampleOptions(minValidSamples = MIN_FULL_VALID_SAMPLES, minConfidence = null),
            )
        }
        if (fullStats != null && fullStats.validSampleCount >= MIN_FULL_VALID_SAMPLES && fullStats.medianM != null) {
            return buildMetricResult(DepthSource.ARCORE_FULL_DEPTH, fullStats, input)
        }

        return buildPseudoTrendResult(input)
    }

    private fun buildMetricResult(source: DepthSource, stats: DepthStats, input: ObjectDepthInput,
                                  supportedPositionInAnchor: Vec3? = null): TrackedObjectDepth {
        val riskDistance = chooseRiskDistance(input.geometry.className, stats, input.groundDistanceM)
        val provisionalConfidence = computeConfidence(
            source = source,
            stats = stats,
            geometry = input.geometry,
            track = input.track,
            motionContext = input.motionContext,
            depthFreshnessQuality = input.depthFreshnessQuality(source),
            visualTrackingQuality = input.visualTrackingQuality,
            hardGate = if (input.track.idSwitchSuspected) 0f else 1f,
        )
        val cameraPose = input.motionContext.reliableCameraPoseEvidence()?.takeIf {
            it.timestampMs == input.timestampMs && input.depthFreshnessQuality(source) >= 0.8f &&
                when (source) {
                    DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthMatchesCameraFrame
                    DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthMatchesCameraFrame
                    else -> false
                }
        }
        val metricObservationAccepted = tracker.recordDistance(
            track = input.track,
            distanceM = riskDistance,
            source = source,
            confidence = provisionalConfidence.finalScore,
            timestampMs = input.timestampMs,
            cameraPoseEvidence = cameraPose,
            objectPositionInAnchor = supportedPositionInAnchor ?: stats.medianM?.let { cameraPose?.objectCenterInAnchor(input.geometry.centerNorm, it) },
            depthObservationTimestampNs = when (source) {
                DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthTimestampNs?.takeIf { it > 0L && input.rawDepthMatchesCameraFrame }
                DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthTimestampNs?.takeIf { it > 0L && input.fullDepthMatchesCameraFrame }
                else -> null
            },
            requireIndependentDepthObservation = input.requireIndependentDepthObservation,
        )
        val kinematics = tracker.approachKinematics(input.track, riskDistance, source)
        val confidence = computeConfidence(
            source = source,
            stats = stats,
            geometry = input.geometry,
            track = input.track,
            motionContext = input.motionContext,
            depthFreshnessQuality = input.depthFreshnessQuality(source),
            visualTrackingQuality = input.visualTrackingQuality,
            hardGate = if (input.track.idSwitchSuspected || !metricObservationAccepted) 0f else 1f,
        )
        val userFacing = messagePolicy.buildUserFacing(
            MetricDepthDecision(
                className = input.geometry.className,
                source = source,
                riskDistanceM = riskDistance,
                trend = kinematics.trend,
                confidenceFinal = confidence.finalScore,
                trackKey = input.track.trackId,
                timeToCollisionMs = kinematics.timeToCollisionMs,
                objectMotion = kinematics.objectMotion,
                motionEstimate = kinematics.motionEstimate,
            ),
            nowMs = input.timestampMs,
        )
        return input.toTrackedObjectDepth(
            source = source,
            zDistanceM = stats.medianM,
            rayDistanceM = input.rayDistanceM,
            groundDistanceM = input.groundDistanceM,
            riskDistanceM = riskDistance,
            stats = stats,
            kinematics = kinematics,
            confidence = confidence,
            userFacing = userFacing,
        )
    }

    /** Keeps approach/recede context without fabricating metric distance when ARCore depth is unusable. */
    private fun buildPseudoTrendResult(input: ObjectDepthInput, reasonHardGate: Float = 1f): TrackedObjectDepth {
        input.track.invalidateMotionEvidence(input.timestampMs)
        val pseudo = pseudoTrend(input.track, input.motionContext)
        val stats = DepthStats(
            validSampleCount = 0,
            validSampleRatio = 0f,
            medianM = null,
            p10M = null,
            p20M = null,
            p80M = null,
            iqrM = null,
            madM = null,
            confidenceMedian = null,
            outlierRatio = 1f,
        )
        val confidence = DepthConfidenceBreakdown(
            sourceQuality = DepthSource.POLYGON_TREND_PSEUDO_DEPTH.sourceQuality,
            sampleQuality = 0f,
            depthQuality = 0f,
            detectionQuality = input.geometry.detectionConfidence.coerceIn(0f, 1f),
            trackingQuality = (if (input.track.stable && !input.track.idSwitchSuspected) 0.8f else 0.2f) *
                input.visualTrackingQuality.coerceIn(0f, 1f),
            motionQuality = input.motionContext.safeMotionQuality,
            freshnessQuality = input.motionContext.freshnessQuality.coerceIn(0f, 1f),
            corridorQuality = corridorQuality(input.geometry),
            hardGate = reasonHardGate,
        )
        val userFacing = messagePolicy.buildUserFacing(
            MetricDepthDecision(
                className = input.geometry.className,
                source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                riskDistanceM = null,
                trend = pseudo.trend,
                confidenceFinal = confidence.finalScore,
                trackKey = input.track.trackId,
            ),
            nowMs = input.timestampMs,
        )
        return input.toTrackedObjectDepth(
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            zDistanceM = null,
            rayDistanceM = null,
            groundDistanceM = null,
            riskDistanceM = null,
            stats = stats,
            kinematics = pseudo,
            confidence = confidence,
            userFacing = userFacing,
        )
    }

    private fun computeConfidence(
        source: DepthSource,
        stats: DepthStats,
        geometry: ObjectGeometry,
        track: TrackState,
        motionContext: MotionContext,
        depthFreshnessQuality: Float,
        visualTrackingQuality: Float,
        hardGate: Float,
    ): DepthConfidenceBreakdown {
        val sampleCountScore = (stats.validSampleCount / TARGET_SAMPLES.toFloat()).coerceIn(0f, 1f)
        val validRatioScore = (stats.validSampleRatio / 0.60f).coerceIn(0f, 1f)
        val maskAreaScore = (geometry.maskAreaNorm / MIN_GOOD_MASK_AREA_NORM).coerceIn(0f, 1f)
        val sampleQuality = sampleCountScore * 0.50f + validRatioScore * 0.30f + maskAreaScore * 0.20f
        val spreadScore = (1f - ((stats.iqrM ?: 1f) / MAX_ALLOWED_IQR_M)).coerceIn(0f, 1f)
        val outlierScore = (1f - stats.outlierRatio).coerceIn(0f, 1f)
        val pixelConfidenceScore = stats.confidenceMedian ?: if (source == DepthSource.ARCORE_FULL_DEPTH) spreadScore else 0f
        val depthQuality = if (source == DepthSource.ARCORE_RAW_DEPTH) {
            pixelConfidenceScore * 0.45f + spreadScore * 0.35f + outlierScore * 0.20f
        } else {
            spreadScore * 0.45f + motionContext.freshnessQuality.coerceIn(0f, 1f) * 0.35f + outlierScore * 0.20f
        }
        val trackingQuality = when {
            track.idSwitchSuspected -> 0f
            track.stable -> 1f
            track.ageFrames >= 2 -> 0.55f
            else -> 0.25f
        }
        val effectiveHardGate = if (stats.medianM == null || geometry.detectionConfidence < 0.10f) 0f else hardGate
        val safeDepthFreshness = depthFreshnessQuality.coerceIn(0f, 1f)
        return DepthConfidenceBreakdown(
            sourceQuality = source.sourceQuality * safeDepthFreshness,
            sampleQuality = sampleQuality.coerceIn(0f, 1f),
            depthQuality = depthQuality.coerceIn(0f, 1f),
            detectionQuality = geometry.detectionConfidence.coerceIn(0f, 1f),
            trackingQuality = trackingQuality * visualTrackingQuality.coerceIn(0f, 1f),
            motionQuality = motionContext.safeMotionQuality,
            freshnessQuality = minOf(
                motionContext.freshnessQuality.coerceIn(0f, 1f),
                safeDepthFreshness,
            ),
            corridorQuality = corridorQuality(geometry),
            hardGate = effectiveHardGate,
        )
    }

    private fun ObjectDepthInput.depthFreshnessQuality(source: DepthSource): Float =
        if (source == DepthSource.ARCORE_RAW_DEPTH) rawDepthFreshnessQuality else 1f

    private fun chooseRiskDistance(className: String, stats: DepthStats, groundDistanceM: Float?): Float? {
        val median = stats.medianM ?: return null
        val p20 = stats.p20M ?: median
        if (groundDistanceM != null && groundDistanceM > 0f && groundDistanceM.isFinite()) return groundDistanceM
        return when {
            isTactileBlockClass(className) -> p20
            className.equals("person", ignoreCase = true) -> minOf(median, p20 + 0.20f)
            else -> p20
        }
    }

    private fun pseudoTrend(track: TrackState, motionContext: MotionContext): ApproachKinematics {
        if (!track.stable || track.idSwitchSuspected || motionContext.safeMotionQuality < 0.45f) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        val geometries = track.polygonHistory.takeLast(5)
        val latestGeometry = track.latestGeometry ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val areas = track.bboxHistory.takeLast(5).map { it.area.coerceAtLeast(0.0001f) }
        if (areas.size < 3) return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val logGrowth = ln(areas.last()) - ln(areas.first())
        val bottomGrowth = track.polygonHistory.takeLast(5).mapNotNull { polygon -> polygon.maxOfOrNull { it.y } }
            .let { if (it.size >= 3) it.last() - it.first() else 0f }
        val centerCorridor = latestGeometry.centerNorm.x in 0.30f..0.70f && latestGeometry.centerNorm.y > 0.35f
        val score = ((logGrowth / 0.35f) * 0.60f + (bottomGrowth / 0.15f) * 0.40f).coerceIn(0f, 1f)
        val trend = when {
            centerCorridor && score >= 0.45f -> Trend.APPROACHING
            logGrowth < -0.25f -> Trend.RECEDING
            else -> Trend.STABLE
        }
        return ApproachKinematics(trend = trend, approachScore = score, approachSpeedMps = null, timeToCollisionMs = null)
    }

    private fun corridorQuality(geometry: ObjectGeometry): Float {
        return when {
            geometry.centerNorm.x in 0.30f..0.70f && geometry.centerNorm.y >= 0.35f -> 1f
            geometry.screenZone == ScreenZone.LOWER -> 0.75f
            geometry.screenZone == ScreenZone.LEFT || geometry.screenZone == ScreenZone.RIGHT -> 0.45f
            else -> 0.30f
        }
    }

    private fun ObjectDepthInput.toTrackedObjectDepth(
        source: DepthSource,
        zDistanceM: Float?,
        rayDistanceM: Float?,
        groundDistanceM: Float?,
        riskDistanceM: Float?,
        stats: DepthStats,
        kinematics: ApproachKinematics,
        confidence: DepthConfidenceBreakdown,
        userFacing: UserFacingDepth,
    ): TrackedObjectDepth {
        return TrackedObjectDepth(
            frameId = frameId,
            timestampMs = timestampMs,
            trackId = track.trackId,
            className = geometry.className,
            detectionConfidence = geometry.detectionConfidence,
            bboxNorm = geometry.bboxNorm,
            polygonNorm = geometry.polygonNorm,
            maskAreaNorm = geometry.maskAreaNorm,
            centerNorm = geometry.centerNorm,
            bottomContactNorm = geometry.bottomContactNorm,
            source = source,
            zDistanceM = zDistanceM,
            rayDistanceM = rayDistanceM,
            groundDistanceM = groundDistanceM,
            riskDistanceM = riskDistanceM,
            validSampleCount = stats.validSampleCount,
            validSampleRatio = stats.validSampleRatio,
            depthMedianM = stats.medianM,
            depthP20M = stats.p20M,
            depthIqrM = stats.iqrM,
            trend = kinematics.trend,
            approachScore = kinematics.approachScore,
            approachSpeedMps = kinematics.approachSpeedMps,
            timeToCollisionMs = kinematics.timeToCollisionMs,
            confidence = confidence,
            userFacing = userFacing,
            trackAgeFrames = track.ageFrames,
            trackStableMs = (timestampMs - track.createdAtMs).coerceAtLeast(0L),
            objectMotion = kinematics.objectMotion,
            motionEstimate = kinematics.motionEstimate,
            userMotion = motionContext.userMotion,
        )
    }

    private companion object {
        const val MIN_RAW_VALID_SAMPLES = 30
        const val MIN_FULL_VALID_SAMPLES = 50
        const val RAW_CONFIDENCE_MIN = 0.35f
        const val TARGET_SAMPLES = 160
        const val MIN_GOOD_MASK_AREA_NORM = 0.003f
        const val MAX_ALLOWED_IQR_M = 1.2f
    }
}
