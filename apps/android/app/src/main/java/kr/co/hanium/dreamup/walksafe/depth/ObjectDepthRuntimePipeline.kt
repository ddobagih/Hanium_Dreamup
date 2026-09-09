package kr.co.hanium.dreamup.walksafe.depth

import java.util.Locale

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

        return activeTracks.filter { it.missedFrames == 0 }.mapNotNull { track ->
            val geometry = track.latestGeometry ?: return@mapNotNull null
            estimator.estimate(
                ObjectDepthInput(
                    frameId = frameId,
                    timestampMs = timestampMs,
                    geometry = geometry,
                    track = track,
                    mapper = effectiveMapper,
                    rawDepth = snapshot.rawDepth,
                    rawConfidence = snapshot.rawConfidence,
                    rawDepthFreshnessQuality = snapshot.rawDepthFreshnessQuality,
                    fullDepth = snapshot.fullDepth,
                    motionContext = motionContext,
                ),
            )
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

fun TrackedObjectDepth.debugSummaryText(): String {
    val distance = riskDistanceM?.let { String.format(Locale.US, "%.2fm", it) } ?: "거리 없음"
    val confidence = String.format(Locale.US, "%.0f%%", confidence.finalScore * 100f)
    val message = userFacing.message?.let { " · $it" } ?: ""
    return "$trackId $className · ${source.name} · $distance · conf $confidence$message"
}
