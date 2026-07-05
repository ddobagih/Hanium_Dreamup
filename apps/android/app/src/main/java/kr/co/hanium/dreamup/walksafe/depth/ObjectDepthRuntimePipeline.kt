package kr.co.hanium.dreamup.walksafe.depth

import java.util.Locale

interface DetectionProvider {
    fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate>
}

class EmptyDetectionProvider : DetectionProvider {
    override fun detect(frameId: Long, timestampMs: Long): List<DetectionCandidate> = emptyList()
}

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
        mapper: CoordinateMapper? = null,
        motionContext: MotionContext = MotionContext(),
    ): List<TrackedObjectDepth> {
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width ?: DEFAULT_DEPTH_SIZE
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height ?: DEFAULT_DEPTH_SIZE
        val effectiveMapper = mapper ?: identityMapper(depthWidth, depthHeight)
        val sourceDetections = detections ?: detectionProvider.detect(frameId, timestampMs)
        val geometries = sourceDetections
            .map { extractor.extract(it) }
            .filter { it.detectionConfidence >= MIN_DETECTION_CONFIDENCE }
        val activeTracks = if (geometries.isEmpty()) {
            tracker.predictOnly()
            return emptyList()
        } else {
            tracker.update(geometries, timestampMs)
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
    val steps = userFacing.stepsAhead?.let { " · ${it}보" } ?: ""
    val confidence = String.format(Locale.US, "%.0f%%", confidence.finalScore * 100f)
    val message = userFacing.message?.let { " · $it" } ?: ""
    return "$trackId $className · ${source.name} · $distance$steps · conf $confidence$message"
}
