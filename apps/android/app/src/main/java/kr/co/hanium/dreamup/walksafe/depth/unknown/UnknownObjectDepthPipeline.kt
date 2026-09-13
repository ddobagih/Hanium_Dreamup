package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask

data class NamedMaskDepthEvidence(
    val token: FastSamFrameToken,
    /** Must be a real supported region mask, never a filled detector bounding box. */
    val mask: BinaryImageMask,
    val depth: TrackedObjectDepth,
)

data class UnknownDepthObservation(
    val sourceDetectionIndex: Int,
    val mask: BinaryImageMask,
    val depth: MaskDepthEstimator.Result,
    val trackId: String?,
    val associationStatus: MaskAssociationStatus?,
    val suppressionReason: String? = null,
    val walkingSelection: UnknownObstacleSelection? = null,
)

data class UnknownDepthFrameResult(
    val token: FastSamFrameToken,
    val objects: List<TrackedObjectDepth>,
    val observations: List<UnknownDepthObservation>,
    val rejectionReason: String? = null,
)

/** Mask sampling -> existing ObjectTracker -> existing ObjectDepthEstimator/MessagePolicy. */
class UnknownObjectDepthPipeline(
    private val elapsedRealtimeMs: () -> Long,
    private val maximumSourceAgeMs: Long = 1_000L,
    // Evaluate the current candidate every frame. The shared feedback queue owns spoken cooldowns.
    private var messagePolicy: MessagePolicy = MessagePolicy(
        config = MessagePolicyConfig(rateLimit = MessageRateLimitConfig(0L, 0L, 0L))),
) {
    private var trackerGeneration = 0L
    private var tracker = ObjectTracker(trackIdPrefix = "unknown-track-")
    private var estimator = ObjectDepthEstimator(tracker = tracker, messagePolicy = messagePolicy)
    private var sampler = MaskDepthEstimator()
    private var fullSampler = MaskDepthEstimator()
    private var epoch: Long? = null
    private var lastToken: FastSamFrameToken? = null
    private val scopeByTrack = mutableMapOf<String, String>()
    private val supportByTrack = mutableMapOf<String, RepresentativeSupport>()

    init { require(maximumSourceAgeMs > 0L) }

    @Synchronized
    fun reset() {
        trackerGeneration++
        tracker = ObjectTracker(trackIdPrefix = "unknown-track-$trackerGeneration-")
        estimator = ObjectDepthEstimator(tracker = tracker, messagePolicy = messagePolicy)
        sampler = MaskDepthEstimator()
        fullSampler = MaskDepthEstimator()
        epoch = null
        lastToken = null
        scopeByTrack.clear()
        supportByTrack.clear()
    }

    @Synchronized
    fun setUserStepLength(stepLengthM: Float) {
        messagePolicy = MessagePolicy(stepLengthM = stepLengthM,
            config = MessagePolicyConfig(rateLimit = MessageRateLimitConfig(0L, 0L, 0L)))
        estimator = ObjectDepthEstimator(tracker = tracker, messagePolicy = messagePolicy)
    }

    /** Called for a completed capture with no FastSAM result; it never reuses old mask geometry. */
    @Synchronized
    fun missed() { tracker.predictOnly() }

    @Synchronized
    fun process(
        capture: FrozenUnknownDepthCapture,
        token: FastSamFrameToken,
        masks: List<InstanceMask>,
        nowElapsedRealtimeMs: Long,
        namedEvidence: List<NamedMaskDepthEvidence> = emptyList(),
    ): UnknownDepthFrameResult {
        fun reject(reason: String) = UnknownDepthFrameResult(token, emptyList(), emptyList(), reason)
        if (!sameCapture(capture.token, token)) return reject("capture_token_mismatch")
        val capturedMs = token.capturedElapsedNs / 1_000_000L
        if (nowElapsedRealtimeMs < capturedMs || nowElapsedRealtimeMs - capturedMs > maximumSourceAgeMs) {
            tracker.predictOnly()
            return reject("source_frame_expired")
        }
        if (epoch != null && token.sessionEpoch < requireNotNull(epoch)) return reject("old_session_epoch")
        if (epoch != token.sessionEpoch) {
            reset()
            epoch = token.sessionEpoch
        }
        val previous = lastToken
        if (previous != null) {
            if (token.frameId <= previous.frameId || token.cameraTimestampNs <= previous.cameraTimestampNs ||
                token.cpuImageTimestampNs <= previous.cpuImageTimestampNs || token.capturedElapsedNs < previous.capturedElapsedNs
            ) return reject("duplicate_or_out_of_order_source")
            if (token.width != previous.width || token.height != previous.height || token.geometryId != previous.geometryId) {
                reset()
                epoch = token.sessionEpoch
            }
        }
        if (masks.any { it.imageWidth() != token.width || it.imageHeight() != token.height }) return reject("mask_image_dimensions_mismatch")
        lastToken = token
        val capturedMapper = capture.calibration?.let { CapturedDepthMapper(it) }
        val prepared = masks.mapIndexed { index, mask -> prepare(index, mask, capturedMapper) }
        val detections = prepared.map {
            MaskDepthEstimator.Detection(it.index.toString(), UNNAMED_OBSTACLE_CLASS, capture.identity, it.adapter)
        }
        val rawResults = sampler.associate(detections, capture.depth)
        val fallbackIndices = rawResults.indices.filter { rawResults[it].status != MaskDepthEstimator.Status.KNOWN }
        val fullResults = if (capture.fullDepth != null && fallbackIndices.isNotEmpty())
            fullSampler.associate(fallbackIndices.map { detections[it] }, capture.fullDepth)
                .mapIndexed { i, result -> fallbackIndices[i] to result }.toMap() else emptyMap()
        val results = rawResults.mapIndexed { i, raw ->
            val full = fullResults[i]
            // Smoothing must not overwrite contradictory raw evidence or a supported nearer layer.
            if (full?.status == MaskDepthEstimator.Status.KNOWN && !raw.componentDepthConflict &&
                raw.components.none { it.reason == "ambiguous_depth_layers" ||
                    it.axialDepthM?.let { z -> kotlin.math.abs(z - full.axialDepthM!!) > 0.40 } == true }
            ) full else raw
        }
        val visible = prepared.indices.filter { !duplicateOfNamed(prepared[it], results[it], token, namedEvidence) }
        val indexed = visible.map { i ->
            val mask = prepared[i]
            val result = results[i]
            val spatial = spatialEvidence(capture, result).takeIf { mask.fullyCovered }
            IndexedObjectGeometry(mask.index, mask.geometry, MaskAssociationEvidence(
                frameTimestampMs = capture.frameTimestampMs,
                cameraTimestampNs = token.cpuImageTimestampNs,
                mask = mask.binary,
                spatial = spatial,
                representativeEpoch = null,
            ))
        }
        val assignments = tracker.updateMaskAwareWithAssignments(indexed, capture.frameTimestampMs).associateBy { it.sourceDetectionIndex }
        val objects = mutableListOf<TrackedObjectDepth>()
        val observations = prepared.mapIndexed { i, mask ->
            val result = results[i]
            val assignment = assignments[mask.index]
            val track = assignment?.track
            if (track != null && assignment.status != MaskAssociationStatus.AMBIGUOUS) {
                val scope = "${result.source}:${result.status}:${mask.fullyCovered}:${if (result.components.size == 1) "single" else "multiple"}"
                val oldScope = scopeByTrack.put(track.trackId, scope)
                val support = representativeSupport(capture, result).takeIf { mask.fullyCovered }
                val oldSupport = supportByTrack.remove(track.trackId)
                if (support != null) supportByTrack[track.trackId] = support
                if ((oldScope != null && oldScope != scope) ||
                    (oldSupport != null && (support == null || !oldSupport.agreesWith(support)))) {
                    track.resetMetricAndSpatialHistoryPreservingTrackId(capture.frameTimestampMs)
                }
                objects += if (mask.fullyCovered && result.status == MaskDepthEstimator.Status.KNOWN && result.axialDepthM != null) {
                    supported(capture, mask.geometry, track, result, nowElapsedRealtimeMs)
                } else {
                    track.resetMetricAndSpatialHistoryPreservingTrackId(capture.frameTimestampMs)
                    unsupported(capture, mask.geometry, track, result)
                }
            }
            UnknownDepthObservation(mask.index, mask.binary, result, track?.trackId, assignment?.status,
                if (i !in visible) "same_capture_named_mask_and_depth" else if (capturedMapper != null && !mask.fullyCovered)
                    "mask_outside_calibrated_depth_coverage" else null)
        }
        val activeIds = tracker.activeTracks().map { it.trackId }.toSet()
        scopeByTrack.keys.retainAll(activeIds)
        supportByTrack.keys.retainAll(activeIds)
        val completedMs = elapsedRealtimeMs()
        if (completedMs < capturedMs || completedMs - capturedMs > maximumSourceAgeMs) {
            tracker.predictOnly()
            return UnknownDepthFrameResult(token, emptyList(), observations, "source_frame_expired_during_depth")
        }
        val selections = observations.associate { observation ->
            observation.sourceDetectionIndex to UnknownWalkingObstaclePolicy.select(
                observation.mask, objects.firstOrNull { it.trackId == observation.trackId }, capture.imageQuarterTurns,
                observation.suppressionReason,
            )
        }
        val candidateTrackIds = observations.filter {
            selections[it.sourceDetectionIndex]?.warningCandidate == true
        }.mapNotNull { it.trackId }.toSet()
        return UnknownDepthFrameResult(token, objects.map { obj ->
            val eligible = obj.trackId in candidateTrackIds
            obj.copy(walkingObstacleCandidate = eligible,
                userFacing = if (eligible) obj.userFacing else UserFacingDepth(null, MessageLevel.NONE, null))
        }, observations.map { it.copy(walkingSelection = selections[it.sourceDetectionIndex]) })
    }

    private fun supported(capture: FrozenUnknownDepthCapture, geometry: ObjectGeometry, track: TrackState,
                          result: MaskDepthEstimator.Result, nowElapsedRealtimeMs: Long): TrackedObjectDepth {
        val snapshot = capture.snapshot
        val single = result.components.singleOrNull()
        val spatial = spatialEvidence(capture, result)
        val hasIndependentSpatialSupport = spatial != null
        val stats = DepthStats(
            validSampleCount = result.components.sumOf { it.inlierPixels },
            validSampleRatio = result.support.robustInlierMaskFraction().toFloat(),
            medianM = result.axialDepthM!!.toFloat(),
            p10M = null,
            p20M = single?.axialP20M?.toFloat(),
            p80M = single?.axialP80M?.toFloat(),
            // Use an actual quartile difference; never substitute p80-p20 for the IQR.
            iqrM = single?.axialIqrM()?.toFloat(),
            madM = single?.axialMadM?.toFloat(),
            confidenceMedian = result.components.mapNotNull { it.rawConfidenceMedian }.minOrNull()?.let { (it / 255.0).toFloat() },
            outlierRatio = (1.0 - result.components.sumOf { it.inlierPixels }.toDouble() /
                result.components.sumOf { it.validPixels }.coerceAtLeast(1)).toFloat(),
        )
        val freshness = (1f - (nowElapsedRealtimeMs - capture.token.capturedElapsedNs / 1_000_000L).toFloat() /
            maximumSourceAgeMs).coerceIn(0f, 1f)
        return estimator.estimateSupportedMask(ObjectDepthInput(
            frameId = capture.token.frameId, timestampMs = capture.frameTimestampMs, geometry = geometry, track = track,
            mapper = CapturedDepthMapper(requireNotNull(calibrationFor(capture, result))), rawDepth = snapshot.rawDepth,
            rawConfidence = snapshot.rawConfidence, rawDepthFreshnessQuality = snapshot.rawDepthFreshnessQuality,
            fullDepth = snapshot.fullDepth,
            // Full Depth is aligned and useful for proximity, but smoothed pixels do not prove new motion.
            fullDepthMatchesCameraFrame = false,
            rawDepthMatchesCameraFrame = hasIndependentSpatialSupport,
            rawDepthTimestampNs = snapshot.rawDepthTimestampNs.takeIf { hasIndependentSpatialSupport },
            rayDistanceM = result.euclideanRangeM?.toFloat(), requireIndependentDepthObservation = true,
            motionContext = capture.motionContext.copy(freshnessQuality = minOf(capture.motionContext.freshnessQuality, freshness),
                cameraPoseEvidence = snapshot.cameraPoseEvidence.takeIf { hasIndependentSpatialSupport }),
        ), stats, spatial?.pointInAnchor, if (result.source == "ARCORE_FULL_DEPTH")
            DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH)
    }

    private fun spatialEvidence(capture: FrozenUnknownDepthCapture, result: MaskDepthEstimator.Result): SpatialAssociationEvidence? {
        if (result.source != "ARCORE_RAW_DEPTH" || result.status != MaskDepthEstimator.Status.KNOWN || result.components.size != 1 ||
            !capture.snapshot.hasFreshMetricRawDepth || !result.newDepthInformation) return null
        val pose = capture.motionContext.reliableCameraPoseEvidence() ?: return null
        val z = result.axialDepthM?.toFloat() ?: return null
        val centroid = result.components.single().inlierImageCentroid() ?: return null
        val point = pose.objectCenterInAnchor(Point2((centroid[0] / capture.token.width).toFloat(),
            (centroid[1] / capture.token.height).toFloat()), z) ?: return null
        return SpatialAssociationEvidence(z, point, pose, capture.snapshot.rawDepthTimestampNs ?: return null,
            result.components.single().rawConfidenceMedian?.let { (it / 255.0).toFloat() } ?: return null, DepthSource.ARCORE_RAW_DEPTH)
    }

    /** Support identity is separate from whole-mask identity and the median's numeric value. */
    private data class RepresentativeSupport(val mask: BinaryImageMask, val calibration: DoubleArray) {
        fun agreesWith(other: RepresentativeSupport): Boolean =
            calibration.contentEquals(other.calibration) && mask.originalWidth == other.mask.originalWidth &&
                mask.originalHeight == other.mask.originalHeight && mask.iou(other.mask) >= 0.80f
    }

    private fun representativeSupport(capture: FrozenUnknownDepthCapture, result: MaskDepthEstimator.Result): RepresentativeSupport? {
        if (result.status != MaskDepthEstimator.Status.KNOWN) return null
        val component = result.components.singleOrNull() ?: return null
        val calibration = calibrationFor(capture, result) ?: return null
        val pixels = component.inlierDepthPixelIndices()
        if (pixels.isEmpty()) return null
        val bounds = component.bboxDepthPx()
        val width = bounds[2] - bounds[0]; val height = bounds[3] - bounds[1]
        val packed = ByteArray((width * height + 7) / 8)
        pixels.forEach { pixel ->
            val x = pixel % calibration.depthWidth - bounds[0]; val y = pixel / calibration.depthWidth - bounds[1]
            val bit = y * width + x
            packed[bit / 8] = (packed[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return RepresentativeSupport(BinaryImageMask.fromPackedRoi(calibration.depthWidth, calibration.depthHeight,
            bounds[0], bounds[1], width, height, packed), calibration.imageToDepthUv())
    }

    private fun unsupported(capture: FrozenUnknownDepthCapture, geometry: ObjectGeometry, track: TrackState,
                            result: MaskDepthEstimator.Result): TrackedObjectDepth {
        val confidence = DepthConfidenceBreakdown(0f, 0f, 0f, geometry.detectionConfidence, 0f, 0f, 0f, 0f, 0f)
        val facing = messagePolicy.buildUserFacing(MetricDepthDecision(UNNAMED_OBSTACLE_CLASS, DepthSource.UNKNOWN,
            null, Trend.UNKNOWN, 0f, track.trackId), capture.frameTimestampMs)
        return TrackedObjectDepth(capture.token.frameId, capture.frameTimestampMs, track.trackId, UNNAMED_OBSTACLE_CLASS,
            geometry.detectionConfidence, geometry.bboxNorm, geometry.polygonNorm, geometry.maskAreaNorm, geometry.centerNorm,
            geometry.bottomContactNorm, DepthSource.UNKNOWN, null, null, null, null,
            result.components.sumOf { it.inlierPixels }, result.support.robustInlierMaskFraction().toFloat(),
            null, null, null, Trend.UNKNOWN, 0f, null, null, confidence, facing,
            trackAgeFrames = track.ageFrames, trackStableMs = (capture.frameTimestampMs - track.createdAtMs).coerceAtLeast(0L))
    }

    private fun calibrationFor(capture: FrozenUnknownDepthCapture, result: MaskDepthEstimator.Result) =
        if (result.source == "ARCORE_FULL_DEPTH") capture.fullDepth?.calibration else capture.calibration

    private data class Prepared(val index: Int, val adapter: MaskDepthEstimator.ImageMask, val binary: BinaryImageMask,
                                val geometry: ObjectGeometry, val fullyCovered: Boolean)

    private fun prepare(index: Int, mask: InstanceMask, mapper: CapturedDepthMapper?): Prepared {
        val left = mask.maskLeft(); val top = mask.maskTop()
        val width = mask.maskRightExclusive() - left; val height = mask.maskBottomExclusive() - top
        val bits = ByteArray((width * height + 7) / 8)
        var sumX = 0.0; var sumY = 0.0
        var fullyCovered = mapper != null && mask.area() > 0
        mask.forEachPixel { x, y ->
            val bit = (y - top) * width + x - left
            bits[bit / 8] = (bits[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            sumX += x + 0.5; sumY += y + 0.5
            if (fullyCovered && mapper?.coversImagePixel(x, y) != true) fullyCovered = false
        }
        val binary = BinaryImageMask.fromPackedRoi(mask.imageWidth(), mask.imageHeight(), left, top, width, height, bits)
        val adapter = object : MaskDepthEstimator.ImageMask {
            override fun imageWidth() = mask.imageWidth()
            override fun imageHeight() = mask.imageHeight()
            override fun minX() = left
            override fun minY() = top
            override fun maxXExclusive() = left + width
            override fun maxYExclusive() = top + height
            override fun contains(x: Int, y: Int) = mask.contains(x, y)
        }
        val bbox = RectNorm(left.toFloat() / mask.imageWidth(), top.toFloat() / mask.imageHeight(),
            width.toFloat() / mask.imageWidth(), height.toFloat() / mask.imageHeight())
        val center = if (mask.area() > 0) Point2((sumX / mask.area() / mask.imageWidth()).toFloat(),
            (sumY / mask.area() / mask.imageHeight()).toFloat()) else bbox.center
        val geometry = ObjectGeometry(UNNAMED_OBSTACLE_CLASS, mask.score(), bbox, bboxPolygon(bbox, 0f),
            mask.area().toFloat() / (mask.imageWidth().toLong() * mask.imageHeight()), center, null,
            screenZone = when { center.x < 0.3f -> ScreenZone.LEFT; center.x > 0.7f -> ScreenZone.RIGHT; else -> ScreenZone.CENTER })
        return Prepared(index, adapter, binary, geometry, fullyCovered)
    }

    private fun duplicateOfNamed(mask: Prepared, result: MaskDepthEstimator.Result, token: FastSamFrameToken,
                                 named: List<NamedMaskDepthEvidence>): Boolean {
        if (!mask.fullyCovered || result.status != MaskDepthEstimator.Status.KNOWN || result.axialDepthM == null) return false
        return named.any { candidate ->
            val depth = candidate.depth
            if (!sameCapture(token, candidate.token) || depth.frameId != token.frameId ||
                depth.timestampMs != token.cameraTimestampNs / 1_000_000L || depth.className == UNNAMED_OBSTACLE_CLASS ||
                depth.source != DepthSource.ARCORE_RAW_DEPTH || depth.zDistanceM == null ||
                depth.confidence.finalScore < 0.55f || !depth.zDistanceM.isFinite() ||
                kotlin.math.abs(depth.zDistanceM - result.axialDepthM) > 0.25 ||
                candidate.mask.originalWidth != token.width || candidate.mask.originalHeight != token.height
            ) return@any false
            val overlap = mask.binary.intersectionArea(candidate.mask)
            val union = mask.binary.area + candidate.mask.area - overlap
            union > 0 && overlap.toDouble() / union >= 0.80
        }
    }
}
