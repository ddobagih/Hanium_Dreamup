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
    val metricExtent: ObservedMaskExtent? = null,
    /** Nearest displayed queue region; the warning may use another independently qualified region.
     * Never an ObjectTracker identity or motion attribution. */
    val proximityRegionId: String? = null,
    val proximityDistanceM: Float? = null,
    val proximityMask: BinaryImageMask? = null,
    val spatialEvidence: UnknownSpatialEvidence? = null,
)

data class UnknownDepthFrameResult(
    val token: FastSamFrameToken,
    val objects: List<TrackedObjectDepth>,
    val observations: List<UnknownDepthObservation>,
    val rejectionReason: String? = null,
    val proximityObjects: List<TrackedObjectDepth> = emptyList(),
    /** Same support was seen with reused depth. Only preserve already queued evidence for these
     * IDs with its original capture, epoch and deadline; never create a current metric output. */
    val retainedProximityRegionIds: Set<String> = emptySet(),
) {
    /** Delivery delay consumes a prediction's original lifetime; it never becomes a new observation. */
    fun forDisplayAt(nowElapsedRealtimeMs: Long): UnknownDepthFrameResult {
        val capturedMs = token.capturedElapsedNs / 1_000_000L
        val expired = (objects + proximityObjects).filter { output -> output.prediction?.let {
            !DepthPredictionPresentation.isCurrent(it, capturedMs, nowElapsedRealtimeMs)
        } == true }.map { it.trackId }.toSet()
        if (expired.isEmpty()) return this
        fun expire(output: TrackedObjectDepth) = if (output.trackId !in expired) output else output.copy(
            prediction = null, depthAvailability = DepthAvailability.UNAVAILABLE,
            walkingObstacleCandidate = false, userFacing = UserFacingDepth(null, MessageLevel.NONE, null))
        return copy(objects = objects.map(::expire), proximityObjects = proximityObjects.map(::expire),
            observations = observations.map { observation ->
                if ((observation.proximityRegionId ?: observation.trackId) !in expired) observation else
                    observation.copy(walkingSelection = UnknownObstacleSelection(false, false, "depth_prediction_expired"))
            }, retainedProximityRegionIds = retainedProximityRegionIds - expired)
    }
}

/** Mask sampling -> existing ObjectTracker -> existing ObjectDepthEstimator/MessagePolicy. */
class UnknownObjectDepthPipeline(
    private val elapsedRealtimeMs: () -> Long,
    private val maximumSourceAgeMs: Long = 1_000L,
    // Evaluate the current candidate every frame. The shared feedback queue owns spoken cooldowns.
    messagePolicy: MessagePolicy = MessagePolicy(),
) {
    private var messagePolicy = messagePolicy.forFeedbackQueue()
    private var trackerGeneration = 0L
    private var tracker = ObjectTracker(trackIdPrefix = "unknown-track-")
    private var estimator = ObjectDepthEstimator(tracker = tracker, messagePolicy = this.messagePolicy)
    private var sampler = MaskDepthEstimator()
    private var fullSampler = MaskDepthEstimator()
    private var epoch: Long? = null
    private var lastToken: FastSamFrameToken? = null
    private val scopeByTrack = mutableMapOf<String, String>()
    private val supportByTrack = mutableMapOf<String, RepresentativeSupport>()
    private data class ExtentHistory(val firstMs: Long, val lastMs: Long, val count: Int, val extent: ObservedMaskExtent)
    private val extentByTrack = mutableMapOf<String, ExtentHistory>()
    private val proximityRegions = UnknownProximityRegions()
    private val travelDirection = UnknownTravelDirection()
    private val unavailableMapper = LetterboxCoordinateMapper(
        ModelInputTransform(1, 1, 1, 1, 1f, 0f, 0f), ImageSize(1, 1))

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
        extentByTrack.clear()
        proximityRegions.reset()
        travelDirection.reset()
    }

    @Synchronized
    fun setUserStepLength(stepLengthM: Float) {
        messagePolicy = messagePolicy.withStepLength(stepLengthM)
        estimator = ObjectDepthEstimator(tracker = tracker, messagePolicy = messagePolicy)
    }

    /** Called for a completed capture with no FastSAM result; it never reuses old mask geometry. */
    @Synchronized
    fun missed() { tracker.predictOnly(); extentByTrack.clear(); proximityRegions.reset(); travelDirection.reset() }

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
            proximityRegions.reset()
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
        val prepared = masks.mapIndexed { index, mask -> prepare(index, mask, capturedMapper, capture.imageQuarterTurns) }
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
            ) full else if (full != null && !raw.componentDepthConflict && raw.nearerLayers.isEmpty() &&
                raw.components.none { it.status == MaskDepthEstimator.Status.KNOWN || it.reason == "ambiguous_depth_layers" } &&
                (full.nearerLayers.isNotEmpty() || full.components.any { it.status == MaskDepthEstimator.Status.KNOWN })
            ) {
                // With no supported or contradictory raw observation, keep independently measured
                // full-depth regions even when they cannot establish a whole-mask distance.
                full
            } else raw
        }
        val spatialSupport = CapturedUnknownSpatialSupport(capture,
            travelDirection.update(capture.motionContext.reliableCameraPoseEvidence()))
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
                val supportedWholeMask = mask.fullyCovered && result.status == MaskDepthEstimator.Status.KNOWN && result.axialDepthM != null
                if (supportedWholeMask) {
                    val scope = "${result.status}:${mask.fullyCovered}:${if (result.components.size == 1) "single" else "multiple"}"
                    val oldScope = scopeByTrack.put(track.trackId, scope)
                    val fullProximity = result.source == "ARCORE_FULL_DEPTH" && result.status == MaskDepthEstimator.Status.KNOWN && mask.fullyCovered
                    val support = representativeSupport(capture, result).takeIf { mask.fullyCovered && !fullProximity }
                    val oldSupport = supportByTrack[track.trackId]
                    if (!fullProximity) {
                        supportByTrack.remove(track.trackId)
                        if (support != null) supportByTrack[track.trackId] = support
                    }
                    if ((oldScope != null && oldScope != scope) ||
                        (!fullProximity && oldSupport != null && (support == null || !oldSupport.agreesWith(support)))) {
                        track.resetMetricAndSpatialHistoryPreservingTrackId(capture.frameTimestampMs)
                        extentByTrack.remove(track.trackId)
                    }
                    objects += supported(capture, mask.geometry, track, result, nowElapsedRealtimeMs)
                } else {
                    // A missing measurement is not automatically a new surface. Preserve the
                    // bounded prediction seed only while the actual mask identity remains unique.
                    if (!mask.fullyCovered || result.status == MaskDepthEstimator.Status.PARTIAL ||
                        result.nearerLayers.isNotEmpty() || result.componentDepthConflict || result.components.any {
                            it.status == MaskDepthEstimator.Status.KNOWN || it.reason == "ambiguous_depth_layers"
                        }) {
                        track.resetMetricAndSpatialHistoryPreservingTrackId(capture.frameTimestampMs)
                        scopeByTrack.remove(track.trackId)
                        supportByTrack.remove(track.trackId)
                    }
                    objects += unsupported(capture, mask.geometry, track)
                }
            }
            UnknownDepthObservation(mask.index, mask.binary, result, track?.trackId, assignment?.status,
                if (i !in visible) "same_capture_named_mask_and_depth" else if (capturedMapper != null && !mask.fullyCovered)
                    "mask_outside_calibrated_depth_coverage" else null,
                metricExtent = stableExtent(track?.trackId, capture.frameTimestampMs,
                    ObservedMaskExtentEstimator.estimate(mask.binary, result, calibrationFor(capture, result),
                        mask.fullyCovered, capture.imageQuarterTurns)),
                spatialEvidence = spatialSupport.evaluate(result, mask.fullyCovered, mask.binary)?.let { evidence ->
                    evidence.copy(predictionUpperRangeM = objects.lastOrNull { it.trackId == track?.trackId }
                        ?.prediction?.rangeUpperBoundM)
                } ?: objects.lastOrNull { it.trackId == track?.trackId }?.prediction?.rangeUpperBoundM?.let {
                    UnknownSpatialEvidence(UnknownSpatialDisposition.UNCERTAIN, null, 0f,
                        "bounded_prediction_without_current_depth", it)
                })
        }
        val activeIds = tracker.activeTracks().map { it.trackId }.toSet()
        scopeByTrack.keys.retainAll(activeIds)
        supportByTrack.keys.retainAll(activeIds)
        extentByTrack.keys.retainAll(activeIds)
        extentByTrack.keys.retainAll(observations.filter { it.associationStatus != MaskAssociationStatus.AMBIGUOUS }
            .mapNotNull { it.trackId }.toSet())
        val completedMs = elapsedRealtimeMs()
        if (completedMs < capturedMs || completedMs - capturedMs > maximumSourceAgeMs) {
            tracker.predictOnly()
            proximityRegions.reset()
            return UnknownDepthFrameResult(token, emptyList(), observations, "source_frame_expired_during_depth")
        }
        val regions = proximityRegions.update(observations.flatMap { observation ->
            if (observation.suppressionReason != null) emptyList() else proximitySamples(capture,
                prepared[observation.sourceDetectionIndex], observation)
        }, capture.frameTimestampMs)
        // Track every supported region once, before choosing display and warning candidates.
        // A weak nearest observation must neither vanish from display nor hide another valid warning.
        val regionsByIndex = regions.flatMap { region -> region.sourceIndices.map { it to region } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, candidates) ->
                candidates.sortedBy { it.sample.stats.medianM }
        }
        val regionSpatial = regions.associate { region ->
            val observation = observations.first { it.sourceDetectionIndex in region.sourceIndices }
            region.regionId to spatialSupport.evaluate(observation.depth, true, region.sample.mask, supportedRegion = true)
        }
        val regionOutputs = regions.associate { region -> region.regionId to proximityOutput(capture, region, completedMs)
            .let { output -> output.copy(rayDistanceM = regionSpatial[region.regionId]?.nearestReliableRangeM ?: output.rayDistanceM) } }
        val regionSelections = regions.associate { region -> region.regionId to UnknownWalkingObstaclePolicy.select(
            region.sample.mask, regionOutputs[region.regionId], capture.imageQuarterTurns,
            spatialEvidence = regionSpatial[region.regionId])
        }
        // A rejected nearest side/out-of-range patch must not hide a separately supported
        // walking-corridor patch from the same segmentation mask.
        val displayedRegions = regionsByIndex.mapValues { (_, candidates) ->
            candidates.firstOrNull { regionSelections[it.regionId]?.show == true } ?: candidates.first()
        }
        val warningRegions = regionsByIndex.mapNotNull { (index, candidates) ->
            candidates.firstOrNull { region ->
                val output = requireNotNull(regionOutputs[region.regionId])
                regionSelections[region.regionId]?.warningCandidate == true &&
                    region.count >= 3 && region.stableMs >= 700L &&
                    UnknownObjectFeedbackPolicy.hasMeasuredDistance(output) && output.userFacing.message != null
            }?.let { index to it }
        }.toMap()
        val selections = observations.associate { observation ->
            val displayed = displayedRegions[observation.sourceDetectionIndex]
            observation.sourceDetectionIndex to if (displayed != null) {
                requireNotNull(regionSelections[displayed.regionId]).copy(
                    warningCandidate = observation.sourceDetectionIndex in warningRegions)
            } else UnknownWalkingObstaclePolicy.select(
                observation.mask, objects.firstOrNull { it.trackId == observation.trackId }, capture.imageQuarterTurns,
                observation.suppressionReason, observation.metricExtent, observation.spatialEvidence,
            )
        }
        val candidateTrackIds = observations.filter {
            it.sourceDetectionIndex !in displayedRegions && selections[it.sourceDetectionIndex]?.warningCandidate == true
        }.mapNotNull { it.trackId }.toSet()
        val finishedMs = elapsedRealtimeMs()
        if (finishedMs < capturedMs || finishedMs - capturedMs > maximumSourceAgeMs) {
            tracker.predictOnly()
            proximityRegions.reset()
            return UnknownDepthFrameResult(token, emptyList(), observations, "source_frame_expired_during_depth")
        }
        val result = UnknownDepthFrameResult(token, objects.map { obj ->
            val eligible = obj.trackId in candidateTrackIds
            obj.copy(walkingObstacleCandidate = eligible,
                rayDistanceM = observations.firstOrNull { it.trackId == obj.trackId }?.spatialEvidence?.nearestReliableRangeM ?: obj.rayDistanceM,
                userFacing = if (eligible) obj.userFacing else UserFacingDepth(null, MessageLevel.NONE, null))
        }, observations.map { observation ->
            val region = displayedRegions[observation.sourceDetectionIndex]
            observation.copy(walkingSelection = selections[observation.sourceDetectionIndex],
                proximityRegionId = region?.regionId, proximityDistanceM = region?.sample?.stats?.medianM,
                proximityMask = region?.sample?.mask,
                spatialEvidence = region?.let { regionSpatial[it.regionId] } ?: observation.spatialEvidence)
        }, proximityObjects = displayedRegions.map { (index, displayed) ->
            val region = warningRegions[index] ?: displayed
            val output = requireNotNull(regionOutputs[region.regionId])
            val eligible = index in warningRegions
            output.copy(walkingObstacleCandidate = eligible,
                userFacing = if (eligible) output.userFacing else UserFacingDepth(null, MessageLevel.NONE, null))
        }.distinctBy { it.trackId }, retainedProximityRegionIds = proximityRegions.retainedRegionIds + objects.filter { output ->
            output.depthAvailability == DepthAvailability.PREDICTED && observations.any { observation ->
                observation.trackId == output.trackId && selections[observation.sourceDetectionIndex]?.show == true
            }
        }.map { it.trackId })
        val consolidated = consolidateSurfaces(result, capture, spatialSupport)
        val finishedAtMs = elapsedRealtimeMs()
        if (finishedAtMs < capturedMs || finishedAtMs - capturedMs > maximumSourceAgeMs) {
            return UnknownDepthFrameResult(token, emptyList(), emptyList(), "source_frame_expired_during_selection")
        }
        return consolidated.forDisplayAt(finishedAtMs)
    }

    private fun consolidateSurfaces(result: UnknownDepthFrameResult, capture: FrozenUnknownDepthCapture,
                                    support: CapturedUnknownSpatialSupport): UnknownDepthFrameResult {
        val pose = capture.motionContext.reliableCameraPoseEvidence() ?: return result
        val fragments = (result.objects + result.proximityObjects).mapNotNull { output ->
            val observation = result.observations.singleOrNull {
                (it.proximityRegionId ?: it.trackId) == output.trackId && it.walkingSelection?.show == true
            } ?: return@mapNotNull null
            if (!output.source.metric || output.depthAvailability != DepthAvailability.MEASURED ||
                output.confidence.hardGate <= 0f) return@mapNotNull null
            val depthTimestamp = observation.depth.depthTimestampNs ?: return@mapNotNull null
            val mask = observation.proximityMask ?: observation.mask
            val points = support.points(observation.depth, mask)
            // Keep the adapter's bounded extrema: another downsample could discard a protrusion
            // and falsely turn two different measured surfaces into one plane.
            UnknownSurfaceGrouping.Fragment(output.trackId, mask, points.map {
                UnknownSurfaceGrouping.Sample(it.imageX, it.imageY, it.pointInAnchor, it.confidence)
            }, output.source, capture.token.cpuImageTimestampNs, depthTimestamp, pose.referenceId,
                output.confidence.finalScore, output.userFacing.messageLevel, output.timeToCollisionMs, output.rayDistanceM)
        }.distinctBy { it.id }
        if (fragments.size < 2) return result
        val suppressed = UnknownSurfaceGrouping.group(fragments).representativeByMember
            .filter { (member, representative) -> member != representative }.keys
        if (suppressed.isEmpty()) return result
        fun mute(output: TrackedObjectDepth) = if (output.trackId in suppressed) output.copy(
            walkingObstacleCandidate = false, userFacing = UserFacingDepth(null, MessageLevel.NONE, null)) else output
        return result.copy(objects = result.objects.map(::mute), proximityObjects = result.proximityObjects.map(::mute),
            observations = result.observations.map { observation ->
                if ((observation.proximityRegionId ?: observation.trackId) in suppressed) observation.copy(
                    walkingSelection = UnknownObstacleSelection(false, false, "same_supported_surface")) else observation
            }, retainedProximityRegionIds = result.retainedProximityRegionIds - suppressed)
    }

    private fun proximitySamples(capture: FrozenUnknownDepthCapture, mask: Prepared,
                                 observation: UnknownDepthObservation): List<UnknownProximityRegions.Sample> {
        val result = observation.depth
        if (!mask.fullyCovered) return emptyList()
        val source = when (result.source) {
            "ARCORE_RAW_DEPTH" -> DepthSource.ARCORE_RAW_DEPTH
            "ARCORE_FULL_DEPTH" -> DepthSource.ARCORE_FULL_DEPTH
            else -> return emptyList()
        }
        val calibration = calibrationFor(capture, result) ?: return emptyList()
        val timestamp = result.depthTimestampNs ?: return emptyList()
        val samples = mutableListOf<UnknownProximityRegions.Sample>()
        fun add(regionMask: BinaryImageMask, stats: DepthStats, ray: Float?) {
            if (regionMask.area == 0 || (stats.medianM ?: return) > 3f) return
            samples += UnknownProximityRegions.Sample(mask.index, regionMask,
                geometryFor(regionMask, mask.geometry.detectionConfidence, capture.imageQuarterTurns), source, stats, ray, timestamp,
                result.newDepthInformation)
        }
        for (layer in result.nearerLayers) {
            if (layer.axialDepthM > 3.0) continue
            add(supportedImageRegion(mask.binary, layer.inlierDepthPixelIndices(), calibration),
                DepthStats(layer.inlierPixels, layer.interiorFraction.toFloat(), layer.axialDepthM.toFloat(),
                    null, null, null, layer.axialIqrM.toFloat(), layer.axialMadM.toFloat(),
                    layer.rawConfidenceMedian?.let { (it / 255.0).toFloat() }, 0f),
                layer.euclideanRangeM?.toFloat())
        }
        // A confirmed component is still measured when its disconnected neighbours prevent
        // a whole-mask distance. Keep its actual robust support, never the enclosing mask box.
        if (result.status != MaskDepthEstimator.Status.KNOWN) for (component in result.components) {
            val distance = component.axialDepthM?.toFloat() ?: continue
            if (component.status != MaskDepthEstimator.Status.KNOWN || distance > 3f) continue
            add(supportedImageRegion(mask.binary, component.inlierDepthPixelIndices(), calibration),
                DepthStats(component.inlierPixels, component.inlierPixels.toFloat() / component.interiorPixels,
                    distance, null, component.axialP20M?.toFloat(), component.axialP80M?.toFloat(),
                    component.axialIqrM()?.toFloat(), component.axialMadM?.toFloat(),
                    component.rawConfidenceMedian?.let { (it / 255.0).toFloat() },
                    1f - component.inlierPixels.toFloat() / component.validPixels),
                component.euclideanRangeM?.toFloat())
        }
        if (samples.isEmpty() && observation.associationStatus == MaskAssociationStatus.AMBIGUOUS &&
            result.status == MaskDepthEstimator.Status.KNOWN && result.axialDepthM != null) {
            add(mask.binary, depthStats(result), result.euclideanRangeM?.toFloat())
        }
        return samples
    }

    /** Project only the measured depth cells back into the original foreground, preserving its holes. */
    private fun supportedImageRegion(mask: BinaryImageMask, indices: IntArray,
                                     calibration: MaskDepthEstimator.Calibration): BinaryImageMask {
        val support = java.util.BitSet(calibration.depthWidth * calibration.depthHeight)
        indices.forEach(support::set)
        val mapper = CapturedDepthMapper(calibration)
        val packed = ByteArray((mask.width * mask.height + 7) / 8)
        var left = mask.left + mask.width; var top = mask.top + mask.height
        var right = mask.left; var bottom = mask.top
        for (y in mask.top until mask.top + mask.height) for (x in mask.left until mask.left + mask.width) {
            if (!mask.contains(x, y)) continue
            val uv = mapper.imageToDepth(Point2((x + .5f) / mask.originalWidth, (y + .5f) / mask.originalHeight)) ?: continue
            val dx = (uv.x * calibration.depthWidth).toInt(); val dy = (uv.y * calibration.depthHeight).toInt()
            if (dx !in 0 until calibration.depthWidth || dy !in 0 until calibration.depthHeight ||
                !support[dy * calibration.depthWidth + dx]) continue
            val bit = (y - mask.top) * mask.width + x - mask.left
            packed[bit / 8] = (packed[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        if (right <= left || bottom <= top) return BinaryImageMask.fromPackedRoi(mask.originalWidth,
            mask.originalHeight, mask.left, mask.top, 0, 0, byteArrayOf())
        val width = right - left; val height = bottom - top
        val compact = ByteArray((width * height + 7) / 8)
        for (y in top until bottom) for (x in left until right) {
            val oldBit = (y - mask.top) * mask.width + x - mask.left
            if ((packed[oldBit / 8].toInt() and (1 shl (oldBit % 8))) == 0) continue
            val bit = (y - top) * width + x - left
            compact[bit / 8] = (compact[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return BinaryImageMask.fromPackedRoi(mask.originalWidth, mask.originalHeight,
            left, top, width, height, compact)
    }

    private fun geometryFor(mask: BinaryImageMask, confidence: Float, imageQuarterTurns: Int): ObjectGeometry {
        var left = mask.originalWidth; var top = mask.originalHeight; var right = 0; var bottom = 0
        var sumX = 0.0; var sumY = 0.0
        for (y in mask.top until mask.top + mask.height) for (x in mask.left until mask.left + mask.width) {
            if (!mask.contains(x, y)) continue
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            sumX += x + .5; sumY += y + .5
        }
        val bbox = RectNorm(left.toFloat() / mask.originalWidth, top.toFloat() / mask.originalHeight,
            (right - left).toFloat() / mask.originalWidth, (bottom - top).toFloat() / mask.originalHeight)
        return ObjectGeometry(UNNAMED_OBSTACLE_CLASS, confidence, bbox, bboxPolygon(bbox, 0f),
            mask.area.toFloat() / (mask.originalWidth.toLong() * mask.originalHeight),
            Point2((sumX / mask.area / mask.originalWidth).toFloat(), (sumY / mask.area / mask.originalHeight).toFloat()), null,
            imageQuarterTurns = imageQuarterTurns)
    }

    private fun proximityOutput(capture: FrozenUnknownDepthCapture, region: UnknownProximityRegions.Observation,
                                completedMs: Long): TrackedObjectDepth {
        val sample = region.sample
        // An ephemeral measurement holder cannot inherit velocity, distance smoothing, or object identity.
        val track = TrackState(region.regionId, UNNAMED_OBSTACLE_CLASS, capture.frameTimestampMs)
        track.markSeen(sample.geometry, capture.frameTimestampMs, 3)
        val freshness = (1f - (completedMs - capture.token.capturedElapsedNs / 1_000_000L).toFloat() /
            maximumSourceAgeMs).coerceIn(0f, 1f)
        val base = estimator.estimateSupportedMask(ObjectDepthInput(
            frameId = capture.token.frameId, timestampMs = capture.frameTimestampMs,
            geometry = sample.geometry, track = track,
            mapper = CapturedDepthMapper(requireNotNull(if (sample.source == DepthSource.ARCORE_FULL_DEPTH)
                capture.fullDepth?.calibration else capture.calibration)),
            rawDepth = capture.snapshot.rawDepth, rawConfidence = capture.snapshot.rawConfidence,
            fullDepth = capture.snapshot.fullDepth, rawDepthFreshnessQuality = capture.snapshot.rawDepthFreshnessQuality,
            rayDistanceM = sample.rayDistanceM, requireIndependentDepthObservation = true,
            motionContext = capture.motionContext.copy(freshnessQuality = minOf(capture.motionContext.freshnessQuality, freshness),
                cameraPoseEvidence = null),
        ), sample.stats, source = sample.source)
        val confidence = base.confidence.copy(trackingQuality = when {
            region.count >= 3 && region.stableMs >= 700L -> 1f
            region.count >= 2 -> .55f
            else -> .25f
        })
        return base.copy(trackAgeFrames = region.count, trackStableMs = region.stableMs,
            trend = Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            objectMotion = ObjectMotion.UNKNOWN, motionEstimate = ObjectMotionEstimate(), confidence = confidence,
            userFacing = messagePolicy.buildUserFacing(MetricDepthDecision(UNNAMED_OBSTACLE_CLASS,
                sample.source, sample.stats.medianM, Trend.UNKNOWN, confidence.finalScore, region.regionId), capture.frameTimestampMs))
    }

    /** One suddenly shrunken segmentation must not suppress a previously larger tracked obstacle. */
    private fun stableExtent(trackId: String?, timestampMs: Long, extent: ObservedMaskExtent?): ObservedMaskExtent? {
        if (trackId == null || extent?.canRejectSmall != true) {
            if (trackId != null) extentByTrack.remove(trackId)
            return extent?.copy(canRejectSmall = false)
        }
        val previous = extentByTrack[trackId]
        fun similar(a: Float, b: Float) = minOf(a, b) / maxOf(a, b) >= .80f
        val consecutive = previous != null && timestampMs - previous.lastMs in 1L..1_000L &&
            similar(extent.widthM, previous.extent.widthM) && similar(extent.heightM, previous.extent.heightM)
        val next = if (consecutive) ExtentHistory(previous!!.firstMs, timestampMs, previous.count + 1, extent)
            else ExtentHistory(timestampMs, timestampMs, 1, extent)
        extentByTrack[trackId] = next
        return if (next.count >= 3 && timestampMs - next.firstMs >= 700L) extent
            else extent.copy(canRejectSmall = false, reason = "extent_temporal_support_pending")
    }

    private fun supported(capture: FrozenUnknownDepthCapture, geometry: ObjectGeometry, track: TrackState,
                          result: MaskDepthEstimator.Result, nowElapsedRealtimeMs: Long): TrackedObjectDepth {
        val snapshot = capture.snapshot
        val spatial = spatialEvidence(capture, result)
        val hasIndependentSpatialSupport = spatial != null
        val stats = depthStats(result)
        val freshness = (1f - (nowElapsedRealtimeMs - capture.token.capturedElapsedNs / 1_000_000L).toFloat() /
            maximumSourceAgeMs).coerceIn(0f, 1f)
        return estimator.estimateSupportedMask(ObjectDepthInput(
            frameId = capture.token.frameId, timestampMs = capture.frameTimestampMs, geometry = geometry, track = track,
            mapper = CapturedDepthMapper(requireNotNull(calibrationFor(capture, result))), rawDepth = snapshot.rawDepth,
            rawConfidence = snapshot.rawConfidence, rawDepthFreshnessQuality = snapshot.rawDepthFreshnessQuality,
            fullDepth = snapshot.fullDepth,
            fullDepthMatchesCameraFrame = result.source == "ARCORE_FULL_DEPTH" && snapshot.hasFreshFullDepth,
            fullDepthTimestampNs = snapshot.fullDepthTimestampNs,
            rawDepthMatchesCameraFrame = hasIndependentSpatialSupport,
            rawDepthTimestampNs = snapshot.rawDepthTimestampNs.takeIf { hasIndependentSpatialSupport },
            rayDistanceM = result.euclideanRangeM?.toFloat(), requireIndependentDepthObservation = true,
            rawDepthMotionOnly = true,
            motionContext = capture.motionContext.copy(freshnessQuality = minOf(capture.motionContext.freshnessQuality, freshness),
                cameraPoseEvidence = snapshot.cameraPoseEvidence.takeIf { hasIndependentSpatialSupport || result.source == "ARCORE_FULL_DEPTH" }),
        ), stats, spatial?.pointInAnchor, if (result.source == "ARCORE_FULL_DEPTH")
            DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH)
    }

    private fun depthStats(result: MaskDepthEstimator.Result): DepthStats {
        val single = result.components.singleOrNull()
        return DepthStats(
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

    private fun unsupported(capture: FrozenUnknownDepthCapture, geometry: ObjectGeometry, track: TrackState): TrackedObjectDepth =
        estimator.estimateUnavailable(ObjectDepthInput(capture.token.frameId, capture.frameTimestampMs,
            geometry, track, unavailableMapper, motionContext = capture.motionContext,
            requireIndependentDepthObservation = true, rawDepthMotionOnly = true)).copy(
            source = DepthSource.UNKNOWN, trend = Trend.UNKNOWN, approachScore = 0f,
            approachSpeedMps = null, timeToCollisionMs = null, objectMotion = ObjectMotion.UNKNOWN,
            motionEstimate = ObjectMotionEstimate(), userFacing = UserFacingDepth(null, MessageLevel.NONE, null))

    private fun calibrationFor(capture: FrozenUnknownDepthCapture, result: MaskDepthEstimator.Result) =
        if (result.source == "ARCORE_FULL_DEPTH") capture.fullDepth?.calibration else capture.calibration

    private data class Prepared(val index: Int, val adapter: MaskDepthEstimator.ImageMask, val binary: BinaryImageMask,
                                val geometry: ObjectGeometry, val fullyCovered: Boolean)

    private fun prepare(index: Int, mask: InstanceMask, mapper: CapturedDepthMapper?, imageQuarterTurns: Int): Prepared {
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
            screenZone = when { center.x < 0.3f -> ScreenZone.LEFT; center.x > 0.7f -> ScreenZone.RIGHT; else -> ScreenZone.CENTER },
            imageQuarterTurns = imageQuarterTurns)
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
