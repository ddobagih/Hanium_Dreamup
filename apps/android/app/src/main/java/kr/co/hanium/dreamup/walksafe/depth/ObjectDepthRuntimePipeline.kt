package kr.co.hanium.dreamup.walksafe.depth

import java.util.IdentityHashMap
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingFailure

interface DetectionProvider {
    fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate>
}

class EmptyDetectionProvider : DetectionProvider {
    override fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate> = emptyList()
}

/** Stateful detection-to-depth pipeline; one instance owns track history across consecutive frames. */
class ObjectDepthRuntimePipeline(
    private val detectionProvider: DetectionProvider = EmptyDetectionProvider(),
    private val extractor: MaskPolygonExtractor = MaskPolygonExtractor(),
    private val tracker: ObjectTracker = ObjectTracker(),
    messagePolicy: MessagePolicy = MessagePolicy(),
    /** The camera diagnostic uses Full for proximity without treating smoothing as independent motion. */
    private val rawDepthMotionOnly: Boolean = false,
) {
    private var messagePolicy = messagePolicy.forFeedbackQueue()
    private var estimator = ObjectDepthEstimator(
        tracker = tracker,
        messagePolicy = this.messagePolicy,
    )
    private var lastCompletedDetectionSequenceId: Long? = null
    private var lastCompletedDetectionDepthCapture: DepthCaptureIdentity? = null
    private var lastVisualSourceKey: VisualFrameKey? = null
    private var lastVisualObservation: CurrentTrackedFrameObservation? = null
    private var visualAssignments: Map<Int, String> = emptyMap()
    private var lastVisualOutputs: List<TrackedObjectDepth> = emptyList()
    private var sourceIndexByGeometry: Map<ObjectGeometry, Int> = emptyMap()
    // Capture provenance belongs to the camera pipeline, including across step-length changes.
    private val depthCaptureClock = DepthCaptureClock()

    fun setUserStepLength(stepLengthM: Float) {
        messagePolicy = messagePolicy.withStepLength(stepLengthM)
        estimator = ObjectDepthEstimator(
            sampler = DepthSampler(),
            tracker = tracker,
            messagePolicy = messagePolicy,
        )
    }

    /**
     * Camera safety path (S14-29): the frozen image-to-depth [mapper] and its capture [mapperFrameId]
     * are required, and a mapper of another capture produces no output.
     */
    fun process(
        snapshot: DepthFrameSnapshot,
        frameId: Long,
        timestampMs: Long,
        detections: List<DetectionCandidate>? = null,
        detectionSequenceId: Long = frameId,
        detectionCompleted: Boolean = true,
        mapper: CoordinateMapper,
        mapperFrameId: Long,
        motionContext: MotionContext = MotionContext(),
        imageQuarterTurns: Int? = 0,
        capturedAtElapsedRealtimeMs: Long? = null,
    ): List<TrackedObjectDepth> {
        if (mapperFrameId != frameId) return emptyList()
        return processMapped(
            snapshot, frameId, timestampMs, detections, detectionSequenceId, detectionCompleted,
            mapper, motionContext, imageQuarterTurns, capturedAtElapsedRealtimeMs,
        )
    }

    /**
     * Identity image-to-depth mapping for tests and pre-aligned normalized image/depth planes only.
     * Never used by the camera safety path, which must supply its frame transform through [process].
     */
    internal fun processPrealignedForTest(
        snapshot: DepthFrameSnapshot,
        frameId: Long,
        timestampMs: Long,
        detections: List<DetectionCandidate>? = null,
        detectionSequenceId: Long = frameId,
        detectionCompleted: Boolean = true,
        motionContext: MotionContext = MotionContext(),
        imageQuarterTurns: Int? = 0,
        capturedAtElapsedRealtimeMs: Long? = null,
    ): List<TrackedObjectDepth> {
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width ?: DEFAULT_DEPTH_SIZE
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height ?: DEFAULT_DEPTH_SIZE
        return processMapped(
            snapshot, frameId, timestampMs, detections, detectionSequenceId, detectionCompleted,
            identityMapper(depthWidth, depthHeight), motionContext, imageQuarterTurns, capturedAtElapsedRealtimeMs,
        )
    }

    private fun processMapped(
        snapshot: DepthFrameSnapshot,
        frameId: Long,
        timestampMs: Long,
        detections: List<DetectionCandidate>?,
        detectionSequenceId: Long,
        detectionCompleted: Boolean,
        effectiveMapper: CoordinateMapper,
        motionContext: MotionContext,
        imageQuarterTurns: Int?,
        capturedAtElapsedRealtimeMs: Long?,
    ): List<TrackedObjectDepth> {
        // Partial legacy-model output is useful for the overlay, but must never count as one of
        // the three completed detector observations required by the warning/report safety gate.
        if (!detectionCompleted) return emptyList()
        depthCaptureClock.observe(snapshot, timestampMs, capturedAtElapsedRealtimeMs)
        val sourceDetections = detections ?: detectionProvider.detect(frameId, timestampMs)
        val indexedGeometries = sourceDetections.mapIndexedNotNull { index, detection ->
            extractor.extract(detection, imageQuarterTurns = imageQuarterTurns)
                .takeIf { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE }?.let { index to it }
        }
        val geometries = indexedGeometries.map { it.second }
        val isNewCompletedDetection = detectionSequenceId != lastCompletedDetectionSequenceId
        val depthCapture = snapshot.captureIdentity()
        if (isNewCompletedDetection) {
            lastCompletedDetectionSequenceId = detectionSequenceId
            lastCompletedDetectionDepthCapture = depthCapture
            // S10-21: the tracker keeps each geometry object, so its candidate index is bound by identity.
            sourceIndexByGeometry = IdentityHashMap<ObjectGeometry, Int>().apply {
                indexedGeometries.forEach { (index, geometry) -> put(geometry, index) }
            }
        } else if (depthCapture != lastCompletedDetectionDepthCapture) {
            // Detector geometry describes its own capture only. Sampling a newer depth image under
            // that old region could read background, so it is not a distance or TTC observation.
            return emptyList()
        }
        if (geometries.isEmpty()) {
            if (isNewCompletedDetection) tracker.predictOnly()
            return emptyList()
        }
        val activeTracks = if (isNewCompletedDetection) {
            tracker.update(geometries, timestampMs)
        } else {
            // Replaying the same capture must not advance detector stability, which counts each
            // unique completed inference snapshot once.
            tracker.activeTracks()
        }

        val tracksById = activeTracks.associateBy { it.trackId }
        return estimateTracks(snapshot, frameId, timestampMs, activeTracks, effectiveMapper, motionContext,
            capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs).map { output ->
            // Legacy report consumers also need the first depth receipt, rather than an age
            // translated from whichever camera receipt happens to publish this output.
            val originalElapsed = output.sourceObservedAtElapsedRealtimeMs?.takeIf {
                output.depthObservedAtMs != null && capturedAtElapsedRealtimeMs != null &&
                    it in 0L..capturedAtElapsedRealtimeMs
            }
            output.copy(
                sourceDetectionIndex = tracksById[output.trackId]?.latestGeometry?.let { sourceIndexByGeometry[it] },
                sourceValidUntilElapsedRealtimeMs = if (!output.source.metric) output.sourceValidUntilElapsedRealtimeMs
                    else if (originalElapsed == null) Long.MIN_VALUE else minOf(
                        output.sourceValidUntilElapsedRealtimeMs ?: Long.MAX_VALUE,
                        originalElapsed + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
                    ),
                sourceMaxAgeMs = if (output.source.metric && originalElapsed != null)
                    DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS else output.sourceMaxAgeMs,
            ).bindPointClosestApproachPublication()
        }
    }

    /** Consumes actual current-image geometry; detector confirmation advances only on a new source. */
    fun processTrackedObservation(
        snapshot: DepthFrameSnapshot,
        observation: CurrentTrackedFrameObservation,
        mapper: CoordinateMapper,
        mapperFrameId: Long,
        nowElapsedRealtimeMs: Long,
        motionContext: MotionContext = MotionContext(),
        imageQuarterTurns: Int? = 0,
    ): List<TrackedObjectDepth> {
        if (!observation.isFreshAt(nowElapsedRealtimeMs) || !observation.matchesDepthSnapshot(snapshot) ||
            mapperFrameId != observation.targetKey.frameId
        ) return emptyList()
        // The visual tracker resumes unfinished backfill. It has no current geometry yet, so do
        // not consume this detector source or turn pending work into a terminal track loss.
        if (observation.observations.any {
                it.failure == VisualTrackingFailure.WORK_BUDGET_EXCEEDED ||
                    it.failure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED
            }
        ) return emptyList()
        val previous = lastVisualObservation
        if (previous != null) {
            if (previous.targetKey.epoch != observation.targetKey.epoch ||
                previous.targetKey.geometryVersion != observation.targetKey.geometryVersion ||
                observation.targetKey.frameId < previous.targetKey.frameId ||
                observation.sourceKey.frameId < previous.sourceKey.frameId
            ) return emptyList()
            if (observation.targetKey.frameId == previous.targetKey.frameId) {
                return if (observation.targetKey == previous.targetKey && observation.sourceKey == previous.sourceKey &&
                    observation.sourceDetections == previous.sourceDetections && observation.observations == previous.observations
                ) lastVisualOutputs else emptyList()
            }
            if (observation.targetKey.cameraTimestampNs <= previous.targetKey.cameraTimestampNs ||
                observation.targetKey.capturedAtElapsedRealtimeMs < previous.targetKey.capturedAtElapsedRealtimeMs ||
                (observation.sourceKey == previous.sourceKey && observation.sourceDetections != previous.sourceDetections)
            ) return emptyList()
        }
        val indexed = observation.trackedObservations.mapNotNull { tracked ->
            val geometry = extractor.extract(requireNotNull(tracked.geometry), imageQuarterTurns = imageQuarterTurns)
                .takeIf { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE } ?: return@mapNotNull null
            IndexedObjectGeometry(tracked.sourceIndex, geometry)
        }
        val sourceChanged = observation.sourceKey != lastVisualSourceKey
        val pointContinuity = if (sourceChanged) TrackedMaterialPointContinuity.fromSourceChange(
            previous, observation, visualAssignments, extractor, imageQuarterTurns,
        ) else emptyMap()
        if (sourceChanged) {
            // Equal source frame IDs with changed metadata are never another completed detection.
            if (lastVisualSourceKey?.frameId == observation.sourceKey.frameId) return emptyList()
            // Every completed source detection confirms identity, including budget-deferred flow.
            // Its coordinates are source-time evidence and never enter current depth sampling.
            val invalidIndices = observation.observations.filter {
                it.failure == VisualTrackingFailure.INVALID_GEOMETRY
            }.map { it.sourceIndex }.toSet()
            val sourceGeometry = observation.sourceDetections.mapIndexedNotNull { index, detection ->
                if (index in invalidIndices) return@mapIndexedNotNull null
                extractor.extract(detection, imageQuarterTurns = imageQuarterTurns)
                    .takeIf { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE }
                    ?.let { IndexedObjectGeometry(index, it) }
            }
            val assignments = tracker.updateSourceWithMaterialPointAssignments(
                sourceGeometry, observation.sourceKey.frameId / 1_000_000L,
                currentGeometries = indexed, currentTimestampMs = observation.timestampMs,
                continuityBySourceIndex = pointContinuity,
            )
            visualAssignments = assignments.associate { it.sourceDetectionIndex to it.track.trackId }
            lastVisualSourceKey = observation.sourceKey
        }
        val deferredIds = observation.observations.filter {
            it.failure == VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED
        }.mapNotNull { visualAssignments[it.sourceIndex] }.toSet()
        val continuity = indexed.mapNotNull { indexedGeometry ->
            val id = visualAssignments[indexedGeometry.sourceDetectionIndex] ?: return@mapNotNull null
            val proof = if (sourceChanged) {
                pointContinuity[indexedGeometry.sourceDetectionIndex]?.currentGeometryContinuity(id)
                    ?: TrackedGeometryContinuity.fromDetectorSource(
                        observation, indexedGeometry.sourceDetectionIndex, id, extractor, imageQuarterTurns,
                    )
            } else TrackedGeometryContinuity.from(previous, observation, indexedGeometry.sourceDetectionIndex,
                extractor, imageQuarterTurns)
            proof?.let { id to it }
        }.toMap()
        val tracks = tracker.applyTrackedObservationsWithContinuity(
            indexed.mapNotNull { indexedGeometry ->
                visualAssignments[indexedGeometry.sourceDetectionIndex]?.let { it to indexedGeometry.geometry }
            },
            observation.timestampMs,
            budgetDeferredTrackIds = deferredIds,
            continuityByTrackId = continuity,
        )
        // This is the actual tracker assignment, not a geometric correspondence guessed later.
        val sourceIndicesByTrackId = visualAssignments.entries.groupBy({ it.value }, { it.key })
            .mapValues { (_, indices) -> indices.singleOrNull() }
        val materialPoints = observation.trackedObservations.mapNotNull { tracked ->
            visualAssignments[tracked.sourceIndex]?.let { id -> tracked.materialPoint?.let { id to it } }
        }.toMap()
        val visualQualities = observation.trackedObservations.mapNotNull { tracked ->
            visualAssignments[tracked.sourceIndex]?.let { it to tracked.trackingQuality }
        }.toMap()
        lastVisualObservation = observation
        lastVisualOutputs = estimateTracks(
            snapshot, observation.targetKey.frameId, observation.timestampMs, tracks, mapper, motionContext, visualQualities,
            requireIndependentDepthObservation = true,
            capturedAtElapsedRealtimeMs = observation.targetKey.capturedAtElapsedRealtimeMs,
            materialPoints = materialPoints,
            riskGuidanceNowElapsedRealtimeMs = nowElapsedRealtimeMs,
        ).map { output ->
            // The existing depth-gap policy requires this same visual-tracking quality before
            // trusting a gap. A low-quality optical-flow box cannot establish pseudo approach.
            val currentOutput = if (output.source == DepthSource.POLYGON_TREND_PSEUDO_DEPTH &&
                output.userFacing.messageLevel == MessageLevel.CAUTION &&
                visualQualities[output.trackId]?.let { it.isFinite() && it >= CurrentTrackedFrameObservation.MIN_QUALITATIVE_TRACKING_QUALITY } != true
            ) output.copy(userFacing = UserFacingDepth(null, MessageLevel.NONE, null)) else output
            // S11-11: guidance keeps its own depth-age budget from the first depth observation, and a
            // TTC exists only for the capture's own non-reprojected Raw observation.
            val budgetedOutput = if (currentOutput.timeToCollisionMs != null &&
                !DepthAgeBudget.admits(DepthUse.TIME_TO_COLLISION, currentOutput, observation.timestampMs)
            ) currentOutput.copy(timeToCollisionMs = null, approachSpeedMps = null) else currentOutput
            // Same-capture attribution already recorded the first elapsed receipt. A later
            // Frame/receipt must never translate or renew the old Image clock again.
            val depthObservedAtElapsedMs = if (budgetedOutput.source.metric) {
                budgetedOutput.sourceObservedAtElapsedRealtimeMs?.takeIf {
                    it in 0L..observation.targetKey.capturedAtElapsedRealtimeMs && budgetedOutput.depthObservedAtMs != null
                }
            } else observation.targetKey.capturedAtElapsedRealtimeMs
            val observedAtElapsedRealtimeMs = depthObservedAtElapsedMs?.let {
                minOf(observation.sourceKey.capturedAtElapsedRealtimeMs, it)
            }
            budgetedOutput.copy(
                sourceDetectionIndex = sourceIndicesByTrackId[budgetedOutput.trackId],
                sourceValidUntilElapsedRealtimeMs = if (observedAtElapsedRealtimeMs == null) Long.MIN_VALUE else minOf(
                    budgetedOutput.sourceValidUntilElapsedRealtimeMs ?: Long.MAX_VALUE,
                    observation.sourceKey.capturedAtElapsedRealtimeMs + CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS,
                    observedAtElapsedRealtimeMs + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
                ),
                sourceObservedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                sourceMaxAgeMs = minOf(budgetedOutput.sourceMaxAgeMs ?: DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
                    CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS, DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS),
            ).bindPointClosestApproachPublication()
        }
        return lastVisualOutputs
    }

    private fun estimateTracks(
        snapshot: DepthFrameSnapshot,
        frameId: Long,
        timestampMs: Long,
        tracks: List<TrackState>,
        mapper: CoordinateMapper,
        motionContext: MotionContext,
        visualQualities: Map<String, Float> = emptyMap(),
        requireIndependentDepthObservation: Boolean = false,
        capturedAtElapsedRealtimeMs: Long? = null,
        materialPoints: Map<String, kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint> = emptyMap(),
        riskGuidanceNowElapsedRealtimeMs: Long? = capturedAtElapsedRealtimeMs,
    ): List<TrackedObjectDepth> {
        val captureTimes = depthCaptureClock.observe(snapshot, timestampMs, capturedAtElapsedRealtimeMs)
        return tracks.filter { it.missedFrames == 0 }.mapNotNull { track ->
            val geometry = track.latestGeometry ?: return@mapNotNull null
            val hasValidatedRawPair = snapshot.rawConfidenceMatchesRawDepth
            estimator.estimate(
                ObjectDepthInput(
                    frameId = frameId,
                    timestampMs = timestampMs,
                    geometry = geometry,
                    track = track,
                    mapper = mapper,
                    rawDepth = snapshot.rawDepth?.takeIf { hasValidatedRawPair },
                    rawConfidence = snapshot.rawConfidence?.takeIf { hasValidatedRawPair },
                    rawDepthFreshnessQuality = snapshot.rawDepthFreshnessQuality,
                    rawDepthMatchesCameraFrame = hasValidatedRawPair && snapshot.rawDepthMatchesCameraImage,
                    fullDepth = snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth },
                    fullDepthMatchesCameraFrame = snapshot.hasFreshFullDepth,
                    rawDepthTimestampNs = snapshot.rawDepthTimestampNs?.takeIf { hasValidatedRawPair },
                    fullDepthTimestampNs = snapshot.fullDepthTimestampNs,
                    visualTrackingQuality = visualQualities[track.trackId] ?: 1f,
                    requireIndependentDepthObservation = requireIndependentDepthObservation || rawDepthMotionOnly,
                    rawDepthMotionOnly = rawDepthMotionOnly,
                    depthCaptureTimes = captureTimes,
                    materialPoint = materialPoints[track.trackId],
                    motionContext = motionContext.copy(
                        cameraPoseEvidence = snapshot.cameraPoseEvidence,
                    ),
                ),
                riskGuidanceNowElapsedRealtimeMs,
            ).let { output ->
                // S11-21: a compatible-class flicker keeps the track's accumulated class name.
                val accumulated = track.accumulatedClassName
                if (accumulated != output.className && sameAssociationClass(accumulated, output.className)) {
                    output.copy(className = accumulated)
                } else output
            }
        }
    }

    private fun identityMapper(width: Int, height: Int): LetterboxCoordinateMapper {
        return LetterboxCoordinateMapper(
            transform = ModelInputTransform(
                imageWidth = width,
                imageHeight = height,
                modelWidth = width,
                modelHeight = height,
                scale = 1f,
                padX = 0f,
                padY = 0f,
            ),
            depthSize = ImageSize(width, height),
        )
    }

    private companion object {
        const val MIN_DETECTION_CONFIDENCE = 0.10f
        const val DEFAULT_DEPTH_SIZE = 1
    }
}

/** Clocks that identify one captured depth observation, independent of pixel buffer identity. */
private data class DepthCaptureIdentity(
    val frameTimestampNs: Long,
    val cameraImageTimestampNs: Long?,
    val rawDepthTimestampNs: Long?,
    val rawConfidenceTimestampNs: Long?,
    val fullDepthTimestampNs: Long?,
)

private fun DepthFrameSnapshot.captureIdentity() = DepthCaptureIdentity(
    frameTimestampNs, cameraImageTimestampNs, rawDepthTimestampNs, rawConfidenceTimestampNs, fullDepthTimestampNs,
)

fun TrackedObjectDepth.debugSummaryText(): String {
    val distance = riskDistanceM?.let { String.format(Locale.US, "%.2fm", it) } ?: "거리 없음"
    val steps = userFacing.stepsAhead?.let { " · ${it}보" } ?: ""
    val confidence = String.format(Locale.US, "%.0f%%", confidence.finalScore * 100f)
    val message = userFacing.message?.let { " · $it" } ?: ""
    return "$trackId $className · ${source.name} · $distance$steps · conf $confidence$message"
}
