package kr.co.hanium.dreamup.walksafe.depth

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
) {
    private var estimator = ObjectDepthEstimator(
        tracker = tracker,
        messagePolicy = messagePolicy,
    )
    private var lastCompletedDetectionSequenceId: Long? = null
    private var lastVisualSourceKey: VisualFrameKey? = null
    private var lastVisualObservation: CurrentTrackedFrameObservation? = null
    private var visualAssignments: Map<Int, String> = emptyMap()
    private var lastVisualOutputs: List<TrackedObjectDepth> = emptyList()

    fun setUserStepLength(stepLengthM: Float) {
        estimator = ObjectDepthEstimator(
            sampler = DepthSampler(),
            tracker = tracker,
            messagePolicy = MessagePolicy(stepLengthM = stepLengthM),
        )
    }

    fun process(
        snapshot: DepthFrameSnapshot,
        frameId: Long,
        timestampMs: Long,
        detections: List<DetectionCandidate>? = null,
        detectionSequenceId: Long = frameId,
        detectionCompleted: Boolean = true,
        mapper: CoordinateMapper? = null,
        motionContext: MotionContext = MotionContext(),
    ): List<TrackedObjectDepth> {
        // Partial legacy-model output is useful for the overlay, but must never count as one of
        // the three completed detector observations required by the warning/report safety gate.
        if (!detectionCompleted) return emptyList()
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width ?: DEFAULT_DEPTH_SIZE
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height ?: DEFAULT_DEPTH_SIZE
        // A missing mapper is valid only for tests or pre-aligned normalized image/depth planes.
        val effectiveMapper = mapper ?: identityMapper(depthWidth, depthHeight)
        val sourceDetections = detections ?: detectionProvider.detect(frameId, timestampMs)
        val geometries = sourceDetections
            .map { extractor.extract(it) }
            .filter { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE }
        val isNewCompletedDetection = detectionSequenceId != lastCompletedDetectionSequenceId
        if (isNewCompletedDetection) {
            lastCompletedDetectionSequenceId = detectionSequenceId
        }
        if (geometries.isEmpty()) {
            if (isNewCompletedDetection) tracker.predictOnly()
            return emptyList()
        }
        val activeTracks = if (isNewCompletedDetection) {
            tracker.update(geometries, timestampMs)
        } else {
            // ARCore depth can be resampled on every camera frame, while detector stability is
            // advanced only once for each unique completed inference snapshot.
            tracker.activeTracks()
        }

        return estimateTracks(snapshot, frameId, timestampMs, activeTracks, effectiveMapper, motionContext)
    }

    /** Consumes actual current-image geometry; detector confirmation advances only on a new source. */
    fun processTrackedObservation(
        snapshot: DepthFrameSnapshot,
        observation: CurrentTrackedFrameObservation,
        mapper: CoordinateMapper,
        mapperFrameId: Long,
        nowElapsedRealtimeMs: Long,
        motionContext: MotionContext = MotionContext(),
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
            val geometry = extractor.extract(requireNotNull(tracked.geometry))
                .takeIf { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE } ?: return@mapNotNull null
            IndexedObjectGeometry(tracked.sourceIndex, geometry)
        }
        val sourceChanged = observation.sourceKey != lastVisualSourceKey
        val tracks = if (sourceChanged) {
            // Equal source frame IDs with changed metadata are never another completed detection.
            if (lastVisualSourceKey?.frameId == observation.sourceKey.frameId) return emptyList()
            val assignments = tracker.updateWithAssignments(indexed, observation.timestampMs)
            visualAssignments = assignments.associate { it.sourceDetectionIndex to it.track.trackId }
            lastVisualSourceKey = observation.sourceKey
            assignments.map { it.track }
        } else {
            tracker.applyTrackedObservations(
                indexed.mapNotNull { indexedGeometry ->
                    visualAssignments[indexedGeometry.sourceDetectionIndex]?.let { it to indexedGeometry.geometry }
                },
                observation.timestampMs,
            )
        }
        val visualQualities = observation.trackedObservations.mapNotNull { tracked ->
            visualAssignments[tracked.sourceIndex]?.let { it to tracked.trackingQuality }
        }.toMap()
        lastVisualObservation = observation
        lastVisualOutputs = estimateTracks(
            snapshot, observation.targetKey.frameId, observation.timestampMs, tracks, mapper, motionContext, visualQualities,
            requireIndependentDepthObservation = true,
        )
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
    ): List<TrackedObjectDepth> = tracks.filter { it.missedFrames == 0 }.mapNotNull { track ->
            val geometry = track.latestGeometry ?: return@mapNotNull null
            estimator.estimate(
                ObjectDepthInput(
                    frameId = frameId,
                    timestampMs = timestampMs,
                    geometry = geometry,
                    track = track,
                    mapper = mapper,
                    rawDepth = snapshot.rawDepth,
                    rawConfidence = snapshot.rawConfidence,
                    rawDepthFreshnessQuality = snapshot.rawDepthFreshnessQuality,
                    rawDepthMatchesCameraFrame = snapshot.rawDepthMatchesCameraImage,
                    fullDepth = snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth },
                    fullDepthMatchesCameraFrame = snapshot.hasFreshFullDepth,
                    rawDepthTimestampNs = snapshot.rawDepthTimestampNs,
                    fullDepthTimestampNs = snapshot.fullDepthTimestampNs,
                    visualTrackingQuality = visualQualities[track.trackId] ?: 1f,
                    requireIndependentDepthObservation = requireIndependentDepthObservation,
                    motionContext = motionContext.copy(
                        cameraPoseEvidence = snapshot.cameraPoseEvidence,
                    ),
                ),
            )
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

fun TrackedObjectDepth.debugSummaryText(): String {
    val distance = riskDistanceM?.let { String.format(Locale.US, "%.2fm", it) } ?: "거리 없음"
    val steps = userFacing.stepsAhead?.let { " · ${it}보" } ?: ""
    val confidence = String.format(Locale.US, "%.0f%%", confidence.finalScore * 100f)
    val message = userFacing.message?.let { " · $it" } ?: ""
    return "$trackId $className · ${source.name} · $distance$steps · conf $confidence$message"
}
