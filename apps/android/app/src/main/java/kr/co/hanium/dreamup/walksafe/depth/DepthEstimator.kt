package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

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
    /** Full Depth may supply proximity while only independent Raw captures supply motion. */
    val rawDepthMotionOnly: Boolean = false,
    /** Age attribution is separate from the stricter depth IDs admitted to motion history. */
    val depthCaptureTimes: DepthCaptureTimes? = null,
    val materialPoint: VisualMaterialPoint? = null,
)

/** Only current, valid rotation-corrected image geometry can support a screen-side phrase. */
internal fun ObjectGeometry.cameraScreenLateralPosition(): CameraScreenLateralPosition {
    val center = uprightCenterNorm ?: return CameraScreenLateralPosition.UNKNOWN
    if (!center.x.isFinite() || !center.y.isFinite() ||
        center.x !in 0f..1f || center.y !in 0f..1f
    ) return CameraScreenLateralPosition.UNKNOWN
    return when {
        center.x < 0.33f -> CameraScreenLateralPosition.LEFT
        center.x > 0.67f -> CameraScreenLateralPosition.RIGHT
        else -> CameraScreenLateralPosition.CENTER
    }
}

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
    /** An observed visual object with unsupported depth; no rectangle resampling or metric sample. */
    fun estimateUnavailable(input: ObjectDepthInput): TrackedObjectDepth = buildPseudoTrendResult(input)

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

    @JvmOverloads
    fun estimate(input: ObjectDepthInput, riskGuidanceNowElapsedRealtimeMs: Long? = null): TrackedObjectDepth {
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
        // Full smoothing cannot establish that ambiguous nearer Raw support was just noise.
        if (rawStats?.foregroundAmbiguous == true) return buildPseudoTrendResult(input, reasonHardGate = 0f)
        val rawUsable = rawStats != null && rawStats.validSampleCount >= MIN_RAW_VALID_SAMPLES && rawStats.medianM != null &&
            hasUsableCandidateConfidence(DepthSource.ARCORE_RAW_DEPTH, rawStats, input)
        val rawSupported = rawStats?.takeIf { rawUsable && it.hasSpatialSupport() }
        // S12-29: an old Raw capture may still be displayed, but cannot hide a Full capture
        // whose original observation is within the existing guidance age budget.
        val evaluateFreshFull = rawSupported != null &&
            !input.hasRiskAge(DepthSource.ARCORE_RAW_DEPTH, riskGuidanceNowElapsedRealtimeMs) &&
            input.hasRiskAge(DepthSource.ARCORE_FULL_DEPTH, riskGuidanceNowElapsedRealtimeMs)
        if (rawSupported != null && !evaluateFreshFull) {
            return buildMetricResult(DepthSource.ARCORE_RAW_DEPTH, rawSupported, input, interpretPose = true)
        }
        // S11-6-F1: a usable Raw source without spatial support is kept aside as proximity-only
        // evidence; it is used only if no spatially supported source follows.
        val rawLimited = rawStats?.takeIf { rawUsable && admitsSpatiallyLimited(DepthSource.ARCORE_RAW_DEPTH, it, input) }

        // Full Depth is denser but has no per-pixel confidence, so it uses a larger sample gate.
        val fullStats = input.fullDepth?.let { fullDepth ->
            sampler.sampleObjectDepth(
                depthImage = fullDepth,
                confidenceImage = null,
                polygonDepthNorm = depthPolygon,
                options = DepthSampleOptions(minValidSamples = MIN_FULL_VALID_SAMPLES, minConfidence = null),
            )
        }
        if (fullStats?.foregroundAmbiguous == true) {
            // Full ambiguity cannot release nearer Raw proximity evidence either.
            return rawSupported?.let { buildMetricResult(DepthSource.ARCORE_RAW_DEPTH, it, input, interpretPose = true) }
                ?: rawLimited?.let { spatiallyLimitedResult(DepthSource.ARCORE_RAW_DEPTH, it, input) }
                ?: buildPseudoTrendResult(input, reasonHardGate = 0f)
        }
        val fullUsable = fullStats != null && fullStats.validSampleCount >= MIN_FULL_VALID_SAMPLES && fullStats.medianM != null &&
            hasUsableCandidateConfidence(DepthSource.ARCORE_FULL_DEPTH, fullStats, input)
        if (fullUsable && checkNotNull(fullStats).hasSpatialSupport()) {
            return buildMetricResult(DepthSource.ARCORE_FULL_DEPTH, fullStats, input, interpretPose = true)
        }
        // Failed Full admission does not remove Raw's separate, longer display allowance.
        rawSupported?.let { return buildMetricResult(DepthSource.ARCORE_RAW_DEPTH, it, input, interpretPose = true) }
        rawLimited?.let { return spatiallyLimitedResult(DepthSource.ARCORE_RAW_DEPTH, it, input) }
        fullStats?.takeIf { fullUsable && admitsSpatiallyLimited(DepthSource.ARCORE_FULL_DEPTH, it, input) }
            ?.let { return spatiallyLimitedResult(DepthSource.ARCORE_FULL_DEPTH, it, input) }

        // Candidate evaluation never records an unsupported metric observation. Only the
        // selected usable source reaches buildMetricResult and updates distance tracking.
        return buildPseudoTrendResult(input)
    }

    private fun ObjectDepthInput.hasRiskAge(source: DepthSource, nowElapsedRealtimeMs: Long?): Boolean {
        val observation = depthObservationTime(source) ?: return false
        val maxAge = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS
        if (timestampMs - observation.frameTimestampMs > maxAge) return false
        // Pre-aligned callers have only Frame time. Runtime callers also keep the capture's
        // original elapsed receipt; evaluation/rendering never renews that receipt.
        if (nowElapsedRealtimeMs == null) return true
        val capturedAt = observation.capturedAtElapsedRealtimeMs ?: return false
        return capturedAt >= 0L && nowElapsedRealtimeMs >= capturedAt && nowElapsedRealtimeMs - capturedAt <= maxAge
    }

    /**
     * S11-6-F1: low coverage is admitted only where it can raise a warning (the message policy's
     * warning distance, measured like the user-facing risk distance). Pure: records nothing.
     */
    private fun admitsSpatiallyLimited(source: DepthSource, stats: DepthStats, input: ObjectDepthInput): Boolean {
        val riskDistance = chooseRiskDistance(input.geometry.className, stats, input.groundDistanceM) ?: return false
        val userRisk = conservativeRiskDistance(input.geometry.className, stats, input.groundDistanceM,
            input.geometry.centerNorm, sameCapturePose(source, input))
        return messagePolicy.admitsSpatiallyLimitedDistance(
            input.geometry.className, userRisk?.distanceM ?: riskDistance, input.track.trackId,
        )
    }

    /** Proximity-only (no motion, TTC or gap prediction), capped below STOP, never reportable. */
    private fun spatiallyLimitedResult(source: DepthSource, stats: DepthStats, input: ObjectDepthInput): TrackedObjectDepth =
        buildMetricResult(source, stats, input, interpretPose = true, spatiallyLimited = true)

    /**
     * S11-6: many samples crowded into one part of the object may be background, not the object.
     * Missing coverage only removes a full metric claim; it never raises confidence. S11-6-F1: a
     * near one-sided distance is still kept as escalation-only proximity evidence (see below).
     */
    private fun DepthStats.hasSpatialSupport(): Boolean =
        spatialCoverage?.let { it.isFinite() && it >= MIN_LONG_AXIS_DEPTH_COVERAGE } != false

    private fun hasUsableCandidateConfidence(source: DepthSource, stats: DepthStats, input: ObjectDepthInput): Boolean =
        computeConfidence(source, stats, input.geometry, input.track, input.motionContext,
            input.depthFreshnessQuality(source), input.visualTrackingQuality,
            if (input.track.idSwitchSuspected) 0f else 1f).finalScore >=
            (defaultMetricSourceMinConfidence()[source] ?: 1f)

    private fun buildMetricResult(source: DepthSource, stats: DepthStats, input: ObjectDepthInput,
                                  supportedPositionInAnchor: Vec3? = null,
                                  interpretPose: Boolean = false,
                                  spatiallyLimited: Boolean = false): TrackedObjectDepth {
        input.track.observeMaterialPointIdentity(input.materialPoint?.id)
        val depthObservation = input.depthObservationTime(source)
        val riskDistance = chooseRiskDistance(input.geometry.className, stats, input.groundDistanceM)
        val contactDepth = contactDepthFor(source, stats, input)
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
        val cameraPose = sameCapturePose(source, input)
        // S11-9: tracking, TTC and gap prediction keep the existing riskDistance series; only the
        // user-facing risk distance takes the smallest same-capture pose interpretation.
        val userRisk = conservativeRiskDistance(input.geometry.className, stats, input.groundDistanceM,
            input.geometry.centerNorm, cameraPose?.takeIf { interpretPose })
        val spatialPoint = materialPointInAnchor(source, stats, input, cameraPose)
        val proximityOnly = spatiallyLimited || (input.rawDepthMotionOnly && source != DepthSource.ARCORE_RAW_DEPTH)
        val metricObservationAccepted = tracker.recordDistance(
            track = input.track,
            distanceM = riskDistance,
            source = source,
            confidence = provisionalConfidence.finalScore,
            timestampMs = input.timestampMs,
            cameraPoseEvidence = cameraPose,
            objectPositionInAnchor = spatialPoint?.second,
            materialPointId = spatialPoint?.first,
            depthObservationTimestampNs = when (source) {
                DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthTimestampNs?.takeIf { it > 0L && input.rawDepthMatchesCameraFrame }
                DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthTimestampNs?.takeIf { it > 0L && input.fullDepthMatchesCameraFrame }
                else -> null
            },
            requireIndependentDepthObservation = input.requireIndependentDepthObservation || input.rawDepthMotionOnly,
            proximityOnly = proximityOnly,
            // S11-24: the capture's own depth spread as a normal-sigma equivalent (IQR / 1.349).
            distanceSigmaM = stats.iqrM?.takeIf { it.isFinite() && it > 0f }?.div(IQR_PER_NORMAL_SIGMA),
            depthObservedAtFrameMs = depthObservation?.frameTimestampMs,
        )
        if (!metricObservationAccepted) {
            // Continuity is a final safety veto, not permission to replace a rejected near
            // Raw observation with smoothed Full background. Keep its pending confirmation,
            // withhold metric output, and do not commit a second source for this frame.
            // S11-28: "can it join the distance trend" (no, pending) is kept apart from "is there
            // reason to suspect a near hazard now": a same-frame, confident Raw reading that jumped
            // nearer, inside the obstacle warning distance, may lift only the qualitative caution
            // below, and only while the visual trend also shows an approach. No distance or TTC.
            val nearSuspicion = input.track.lastDistancePendingNearerJump && !proximityOnly &&
                source == DepthSource.ARCORE_RAW_DEPTH && input.rawDepthMatchesCameraFrame &&
                provisionalConfidence.finalScore >= (defaultMetricSourceMinConfidence()[source] ?: 1f) &&
                messagePolicy.admitsSpatiallyLimitedDistance(input.geometry.className, riskDistance, input.track.trackId)
            return buildPseudoTrendResult(input, reasonHardGate = 0f, nearSuspicion = nearSuspicion)
        }
        val kinematics = if (proximityOnly) {
            ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        } else {
            tracker.approachKinematics(input.track, riskDistance, source)
        }
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
                riskDistanceM = userRisk?.distanceM ?: riskDistance,
                trend = kinematics.trend,
                confidenceFinal = confidence.finalScore,
                trackKey = input.track.trackId,
                timeToCollisionMs = kinematics.approachTimeMs,
                objectMotion = kinematics.objectMotion,
                motionEstimate = kinematics.motionEstimate,
                cameraScreenLateralPosition = input.geometry.cameraScreenLateralPosition(),
                spatiallyLimited = spatiallyLimited,
            ),
            nowMs = input.timestampMs,
        )
        // S11-6-F1: limited evidence neither seeds nor extends a short-gap distance prediction.
        if (metricObservationAccepted && !spatiallyLimited) {
            input.track.depthGap.observe(input, source, stats, riskDistance, confidence.finalScore, cameraPose,
                spatialPoint?.second, kinematics.motionEstimate,
                materialPoint = input.materialPoint?.takeIf { it.id === spatialPoint?.first })
        } else input.track.depthGap.clear()
        return input.toTrackedObjectDepth(
            source = source,
            zDistanceM = stats.medianM,
            rayDistanceM = input.rayDistanceM ?: userRisk?.rayM,
            groundDistanceM = input.groundDistanceM,
            riskDistanceM = userRisk?.distanceM ?: riskDistance,
            stats = stats,
            kinematics = kinematics,
            confidence = confidence,
            userFacing = userFacing,
            contactDepth = contactDepth,
        ).copy(
            measuredSurfaceRangeM = cameraPose?.let { measuredSurfaceRange(stats, input.mapper, it) },
            riskDistanceKind = userRisk?.kind,
            horizontalDistanceM = userRisk?.horizontalM,
            spatialCoverageLimited = spatiallyLimited,
            depthObservedAtMs = depthObservation?.frameTimestampMs,
            sourceObservedAtElapsedRealtimeMs = depthObservation?.capturedAtElapsedRealtimeMs,
            // Unknown original depth cannot be leased by a later evaluation or publisher.
            sourceValidUntilElapsedRealtimeMs = if (depthObservation == null) Long.MIN_VALUE else null,
            depthReprojected = when (source) {
                DepthSource.ARCORE_RAW_DEPTH -> !input.rawDepthMatchesCameraFrame || depthObservation?.frameTimestampMs != input.timestampMs
                DepthSource.ARCORE_FULL_DEPTH -> !input.fullDepthMatchesCameraFrame || depthObservation?.frameTimestampMs != input.timestampMs
                else -> null
            },
        )
    }

    /** The reliable camera pose of this capture, when the selected depth source matches it. */
    private fun materialPointInAnchor(
        source: DepthSource, stats: DepthStats, input: ObjectDepthInput, pose: CameraPoseEvidence?,
    ): Pair<VisualMaterialPointId, Vec3>? {
        val point = input.materialPoint ?: return null
        val sameDepthCapture = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthMatchesCameraFrame && (input.rawDepthTimestampNs ?: 0L) > 0L
            DepthSource.ARCORE_FULL_DEPTH -> !input.rawDepthMotionOnly && input.fullDepthMatchesCameraFrame &&
                (input.fullDepthTimestampNs ?: 0L) > 0L
            else -> false
        }
        if (!sameDepthCapture || point.targetKey.frameId != input.frameId ||
            point.targetKey.frameId / 1_000_000L != input.timestampMs || pose == null ||
            !input.geometry.polygonNorm.let { polygon ->
                polygon.size >= 3 && input.geometry.bboxNorm.let { box ->
                    point.imagePointNorm.x in box.x..(box.x + box.width) &&
                        point.imagePointNorm.y in box.y..(box.y + box.height)
                }
            }
        ) return null
        val depth = if (source == DepthSource.ARCORE_RAW_DEPTH) input.rawDepth else input.fullDepth
        if (depth == null) return null
        val confidence = if (source == DepthSource.ARCORE_RAW_DEPTH) input.rawConfidence ?: return null else null
        val mapped = input.mapper.imageToDepth(point.imagePointNorm) ?: return null
        val polygon = input.mapper.imagePolygonToDepthPolygon(input.geometry.polygonNorm)
        val z = sampler.sampleMaterialPointDepth(depth, confidence, polygon, mapped,
            if (source == DepthSource.ARCORE_RAW_DEPTH) RAW_CONFIDENCE_MIN else null) ?: return null
        // The point must belong to the supported depth population, not a farther background patch.
        val median = stats.medianM ?: return null
        if (abs(z - median) > max(0.25f, (stats.madM ?: 0f) * 3f)) return null
        return pose.objectCenterInAnchor(point.imagePointNorm, z)?.let { point.id to it }
    }

    private fun sameCapturePose(source: DepthSource, input: ObjectDepthInput): CameraPoseEvidence? =
        input.motionContext.reliableCameraPoseEvidence()?.takeIf {
            it.timestampMs == input.timestampMs && input.depthFreshnessQuality(source) >= 0.8f &&
                input.depthObservationTime(source)?.frameTimestampMs == input.timestampMs &&
                when (source) {
                    DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthMatchesCameraFrame
                    DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthMatchesCameraFrame
                    else -> false
                }
        }

    private fun contactDepthFor(source: DepthSource, stats: DepthStats, input: ObjectDepthInput): ContactDepthEstimate? {
        if (!isTactileBlockClass(input.geometry.className) || stats.foregroundAmbiguous) return null
        val depthImage = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> input.rawDepth?.takeIf {
                input.rawDepthMatchesCameraFrame && (input.rawDepthTimestampNs ?: 0L) > 0L && input.rawConfidence != null
            }
            DepthSource.ARCORE_FULL_DEPTH -> input.fullDepth?.takeIf {
                input.fullDepthMatchesCameraFrame && (input.fullDepthTimestampNs ?: 0L) > 0L
            }
            else -> null
        } ?: return null
        val contact = input.geometry.bottomContactNorm?.let(input.mapper::imageToDepth) ?: return null
        val polygon = input.mapper.imagePolygonToDepthPolygon(input.geometry.polygonNorm)
        return sampler.sampleContactDepth(
            depthImage = depthImage,
            confidenceImage = if (source == DepthSource.ARCORE_RAW_DEPTH) input.rawConfidence else null,
            polygonDepthNorm = polygon,
            contactDepthNorm = contact,
            minConfidence = if (source == DepthSource.ARCORE_RAW_DEPTH) RAW_CONFIDENCE_MIN else null,
        )
    }

    /** Keeps approach/recede context without fabricating metric distance when ARCore depth is unusable. */
    private fun buildPseudoTrendResult(
        input: ObjectDepthInput,
        reasonHardGate: Float = 1f,
        nearSuspicion: Boolean = false,
    ): TrackedObjectDepth {
        input.track.observeMaterialPointIdentity(input.materialPoint?.id)
        if (reasonHardGate <= 0f) input.track.depthGap.clear()
        val gap = input.track.depthGap.gap(input)
        input.track.invalidateMotionEvidence(input.timestampMs)
        val visualTrend = if (reasonHardGate > 0f || nearSuspicion) pseudoTrend(input.track, input.motionContext) else null
        // S11-28: a vetoed near jump lifts the gate only with a visual approach (single-sample depth
        // contamination without one stays silent); otherwise the veto keeps its zero gate.
        val hardGate = if (reasonHardGate <= 0f && nearSuspicion && visualTrend?.trend == Trend.APPROACHING) {
            1f
        } else reasonHardGate
        val pseudo = if (hardGate <= 0f || visualTrend == null) {
            ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        } else visualTrend
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
            hardGate = hardGate,
        )
        val userFacing = messagePolicy.buildUserFacing(
            MetricDepthDecision(
                className = input.geometry.className,
                source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                riskDistanceM = null,
                trend = pseudo.trend,
                confidenceFinal = confidence.finalScore,
                trackKey = input.track.trackId,
                cameraScreenLateralPosition = input.geometry.cameraScreenLateralPosition(),
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
        ).copy(depthAvailability = gap.availability, depthObservedAtMs = gap.observedAtMs,
            prediction = gap.prediction)
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
        val z = zRiskDistance(className, stats) ?: return null
        if (groundDistanceM != null && groundDistanceM > 0f && groundDistanceM.isFinite()) return groundDistanceM
        return z
    }

    /** The class-specific camera-Z statistic. */
    private fun zRiskDistance(className: String, stats: DepthStats): Float? {
        val median = stats.medianM ?: return null
        val p20 = stats.p20M ?: median
        return when {
            isTactileBlockClass(className) -> p20
            className.equals("person", ignoreCase = true) -> minOf(median, p20 + 0.20f)
            else -> p20
        }
    }

    /** Centre ray at the class-specific risk Z; independent of the chosen risk interpretation. */
    private data class RiskDistanceChoice(val distanceM: Float, val kind: RiskDistanceKind, val horizontalM: Float?, val rayM: Float?)

    /**
     * S11-9: camera Z is depth along the optical axis, not the user's distance. A chest camera
     * tilted down sees a near ground object at a Z larger than its horizontal range. With the
     * same-capture pose (and gravity for the horizontal range) the smallest interpretation is
     * used, so the distance is never overstated. Without that pose only Z (or a supplied ground
     * distance) remains. No body-clearance (collision) distance is claimed.
     */
    private fun conservativeRiskDistance(
        className: String,
        stats: DepthStats,
        groundDistanceM: Float?,
        centerNorm: Point2,
        cameraPose: CameraPoseEvidence?,
    ): RiskDistanceChoice? {
        val z = zRiskDistance(className, stats) ?: return null
        val offset = cameraPose?.objectCenterInAnchor(centerNorm, z)
            ?.minus(Vec3(cameraPose.positionX, cameraPose.positionY, cameraPose.positionZ))
        val up = cameraPose?.gravityUpInAnchor
            ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.norm() > 0f }?.normalized()
        val ray = offset?.norm()?.takeIf { it.isFinite() && it > 0f }
        val horizontal = if (offset != null && up != null) {
            (offset - up * offset.dot(up)).norm().takeIf { it.isFinite() && it > 0f }
        } else null
        val ground = groundDistanceM?.takeIf { it.isFinite() && it > 0f }
        // minBy keeps the first of equal values, so plain camera Z wins a tie.
        return listOfNotNull(
            RiskDistanceChoice(z, RiskDistanceKind.CAMERA_Z, horizontal, ray),
            ray?.let { RiskDistanceChoice(it, RiskDistanceKind.CAMERA_RAY_RANGE, horizontal, ray) },
            horizontal?.let { RiskDistanceChoice(it, RiskDistanceKind.HORIZONTAL_RANGE, horizontal, ray) },
            ground?.let { RiskDistanceChoice(it, RiskDistanceKind.GROUND_DISTANCE, horizontal, ray) },
        ).minBy { it.distanceM }
    }

    private fun pseudoTrend(track: TrackState, motionContext: MotionContext): ApproachKinematics {
        if (!track.stable || track.idSwitchSuspected || motionContext.safeMotionQuality < 0.45f) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        val latestGeometry = track.latestGeometry ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val uprightCenter = latestGeometry.uprightCenterNorm
            ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        // Compare change over observation time, not over a sample count: the same approach must
        // score alike at 3, 10 or 30 observations per second. Changes are normalized to the
        // reference window; spans that are too short, too old or out of order stay UNKNOWN.
        val history = track.visualTrendHistory
        val latestMs = history.lastOrNull()?.timestampMs ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val usable = history.filter { latestMs - it.timestampMs in 0L..VISUAL_TREND_RETENTION_MS }
        val baseIndex = usable.indexOfLast { latestMs - it.timestampMs >= PSEUDO_TREND_REFERENCE_WINDOW_MS }
        val window = if (baseIndex >= 0) usable.subList(baseIndex, usable.size) else usable
        val spanMs = latestMs - (window.firstOrNull()?.timestampMs ?: latestMs)
        if (window.size < 3 || spanMs < PSEUDO_TREND_MIN_SPAN_MS ||
            window.zipWithNext().any { (a, b) -> b.timestampMs < a.timestampMs }
        ) return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val timeScale = PSEUDO_TREND_REFERENCE_WINDOW_MS / spanMs
        val logGrowth = (ln(window.last().bboxNorm.area.coerceAtLeast(0.0001f)) -
            ln(window.first().bboxNorm.area.coerceAtLeast(0.0001f))) * timeScale
        // TrackState clears visual history at rotation boundaries, so every polygon here shares
        // this captured rotation. Sensor polygons themselves remain unchanged for depth mapping.
        val bottoms = window.mapNotNull { sample ->
            sample.polygonNorm.mapNotNull(latestGeometry::sensorPointToUpright).maxOfOrNull { it.y }
                ?.let { sample.timestampMs to it }
        }
        val bottomSpanMs = if (bottoms.size >= 3) bottoms.last().first - bottoms.first().first else 0L
        val bottomGrowth = if (bottomSpanMs >= PSEUDO_TREND_MIN_SPAN_MS) {
            (bottoms.last().second - bottoms.first().second) * PSEUDO_TREND_REFERENCE_WINDOW_MS / bottomSpanMs
        } else 0f
        val centerCorridor = uprightCenter.x in 0.30f..0.70f && uprightCenter.y > 0.35f
        val score = ((logGrowth / 0.35f) * 0.60f + (bottomGrowth / 0.15f) * 0.40f).coerceIn(0f, 1f)
        val trend = when {
            centerCorridor && score >= 0.45f -> Trend.APPROACHING
            logGrowth < -0.25f -> Trend.RECEDING
            else -> Trend.STABLE
        }
        return ApproachKinematics(trend = trend, approachScore = score, approachSpeedMps = null, timeToCollisionMs = null)
    }

    private fun corridorQuality(geometry: ObjectGeometry): Float {
        val center = geometry.uprightCenterNorm ?: return 0.30f
        return when {
            center.x in 0.30f..0.70f && center.y >= 0.35f -> 1f
            geometry.uprightScreenZone == ScreenZone.LOWER -> 0.75f
            geometry.uprightScreenZone == ScreenZone.LEFT || geometry.uprightScreenZone == ScreenZone.RIGHT -> 0.45f
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
        contactDepth: ContactDepthEstimate? = null,
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
            timeToCollisionMs = kinematics.approachTimeMs,
            confidence = confidence,
            userFacing = userFacing,
            trackAgeFrames = track.ageFrames,
            trackStableMs = (timestampMs - track.createdAtMs).coerceAtLeast(0L),
            objectMotion = kinematics.objectMotion,
            motionEstimate = kinematics.motionEstimate,
            userMotion = motionContext.userMotion,
            imageQuarterTurns = geometry.imageQuarterTurns,
            contactDepth = contactDepth,
        )
    }

    private companion object {
        const val MIN_RAW_VALID_SAMPLES = 30
        const val MIN_FULL_VALID_SAMPLES = 50
        const val RAW_CONFIDENCE_MIN = 0.35f
        const val TARGET_SAMPLES = 160
        const val MIN_GOOD_MASK_AREA_NORM = 0.003f
        const val MAX_ALLOWED_IQR_M = 1.2f
        /** Normal-distribution IQR in standard deviations (2 x 0.6745); a unit conversion, not a tuning value. */
        const val IQR_PER_NORMAL_SIGMA = 1.349f
        /**
         * S11-6 engineering default: kept depth in at least two of three long-axis thirds.
         * Initial value only; recalibrate against field replay of thin and wide objects.
         */
        const val MIN_LONG_AXIS_DEPTH_COVERAGE = 2f / 3f
        /**
         * Former five-sample span at the ~300 ms detector cadence the qualitative-trend tests were
         * tuned on (1,000 ms at the default 250 ms cadence); keeps the 0.35/0.15/0.25 scales.
         * Engineering default until field replay calibrates it.
         */
        const val PSEUDO_TREND_REFERENCE_WINDOW_MS = 1_200f
        const val PSEUDO_TREND_MIN_SPAN_MS = 400L
    }
}
