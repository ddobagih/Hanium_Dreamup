package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.ar.core.Session
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Acquires camera and depth images tied to one ARCore [Frame]. Every returned [Image] owns a scarce
 * native buffer; callers must close it directly or close the returned [ArCoreDepthBundle].
 */
class ArCoreFrameProvider(private val session: Session) : Closeable {
    private var motionAnchor: Anchor? = null
    private var motionReferenceId = 0L
    private var closed = false
    private var preflightFrameCount = 0
    private var preflightTrackingFrameCount = 0
    private var preflightFullFrameCount = 0
    private var preflightRawFrameCount = 0
    private var preflightMaxValidSamples = 0
    private val preflightAcquisitionFailures = linkedMapOf<String, Int>()
    private val runtimeAcquisitionFailures = DepthAcquisitionFailureCounters()

    fun configureDepthMode(): Boolean {
        val config = session.config
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        session.configure(config)
        return depthSupported
    }

    fun acquireCameraImageOrNull(frame: Frame): Image? =
        tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE) { frame.acquireCameraImage() }

    /** S11-18: the same acquisition with its consumer counted separately (camera.<consumer>). */
    fun acquireCameraImageOrNull(frame: Frame, consumer: CameraImageConsumer): Image? =
        tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, cameraConsumer = consumer) { frame.acquireCameraImage() }

    /** Bounded operational failure counts by stage and reason; preflight keeps its own summary. */
    fun runtimeAcquisitionDiagnostics(): String = runtimeAcquisitionFailures.summaryText()

    /**
     * S11-18: the field-log form of [runtimeAcquisitionDiagnostics], at most once per
     * [DepthAcquisitionFailureCounters.FIELD_LOG_INTERVAL_MS] and only when it changed.
     */
    fun runtimeAcquisitionFieldLogSummaryIfDue(nowElapsedRealtimeMs: Long): String? =
        runtimeAcquisitionFailures.fieldLogSummaryIfDue(nowElapsedRealtimeMs)

    fun runtimeAcquisitionFailureCount(stage: DepthAcquisitionStage, reason: DepthAcquisitionFailureReason): Int =
        runtimeAcquisitionFailures.count(stage, reason)

    /**
     * Most severe failure of this frame's own depth acquisition (S14-25-F1): [acquireDepthSnapshot]
     * starts a fresh window, and camera-image failures of later consumers (detector, tactile, the
     * timestamp read) never reach the depth gate. The caller reads it once, right after acquiring.
     */
    fun consumeRuntimeAcquisitionFailure(): DepthAcquisitionFailureReason? =
        runtimeAcquisitionFailures.consumeMostSevereSinceLastCheck()

    fun acquireDepthBundle(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean = true,
    ): ArCoreDepthBundle {
        // Read the actual CPU image clock. Frame and Android camera metadata timestamps can differ.
        val cameraImageTimestampNs = acquireCameraImageOrNull(frame, CameraImageConsumer.DEPTH)?.use { it.timestamp }
        val rawPair = acquireMatchingRawDepthPair(
            acquireRawDepth = { tryAcquire(DepthAcquisitionStage.RAW_DEPTH) { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = {
                tryAcquire(DepthAcquisitionStage.RAW_CONFIDENCE) { frame.acquireRawDepthConfidenceImage() }
            },
            isMatchingPair = ::matchingRawConfidenceImages,
            onPairMismatch = {
                runtimeAcquisitionFailures.record(DepthAcquisitionStage.RAW_PAIR, DepthAcquisitionFailureReason.PAIR_MISMATCH)
            },
        )
        val rawDepth = rawPair?.first
        val rawConfidence = rawPair?.second
        val fullDepth = if (
            includeFullDepthWhenRawAvailable || rawDepth == null || rawConfidence == null
        ) {
            tryAcquire(DepthAcquisitionStage.FULL_DEPTH) { frame.acquireDepthImage16Bits() }
        } else {
            null
        }
        return ArCoreDepthBundle(
            frameTimestampNs = frame.timestamp,
            rawDepthTimestampNs = rawDepth?.timestamp,
            rawDepth = rawDepth,
            rawConfidence = rawConfidence,
            fullDepth = fullDepth,
            fullDepthTimestampNs = fullDepth?.timestamp,
            cameraImageTimestampNs = cameraImageTimestampNs,
            rawConfidenceTimestampNs = rawConfidence?.timestamp,
        )
    }

    /** Copies this frame's raw pair before acquiring full depth, releasing scarce native buffers. */
    @Synchronized
    fun acquireDepthSnapshot(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean = true,
    ): DepthFrameSnapshot {
        if (closed) return DepthFrameSnapshot(0L, null, null, null)
        // S14-25-F1: only this call's own depth-stage attempts decide the gate's fatal signal.
        runtimeAcquisitionFailures.beginDepthAcquisition()
        val cameraPoseEvidence = captureCameraPose(frame)
        val snapshot = acquireDepthBundle(
            frame,
            includeFullDepthWhenRawAvailable = false,
        ).toSnapshotAndClose().copy(
            cameraPoseEvidence = cameraPoseEvidence,
        )
        if (!includeFullDepthWhenRawAvailable || !snapshot.hasMetricRawDepth) return snapshot
        return tryAcquire(DepthAcquisitionStage.FULL_DEPTH) { frame.acquireDepthImage16Bits() }?.use { fullDepth ->
            snapshot.copy(
                fullDepth = fullDepth.toDepthImage16Snapshot(),
                fullDepthTimestampNs = fullDepth.timestamp,
            )
        } ?: snapshot
    }

    private fun captureCameraPose(frame: Frame): CameraPoseEvidence? {
        val camera = frame.camera
        if (frame.timestamp <= 0L || camera.trackingState != TrackingState.TRACKING) {
            clearCameraMotion()
            return null
        }
        return try {
            // ARCore may rebase world coordinates between frames. Compare camera poses relative
            // to one nearby anchor, resolving both poses from the current frame each time.
            val anchor = motionAnchor ?: session.createAnchor(camera.pose).also {
                motionAnchor = it
                motionReferenceId = nextMotionReferenceId.incrementAndGet()
            }
            if (anchor.trackingState != TrackingState.TRACKING) {
                clearCameraMotion()
                return null
            }
            val worldToAnchor = anchor.pose.inverse()
            val relativePose = worldToAnchor.compose(camera.pose)
            val x = relativePose.tx()
            val y = relativePose.ty()
            val z = relativePose.tz()
            if (x * x + y * y + z * z > 64f) {
                clearCameraMotion()
                return null
            }
            val forward = relativePose.getTransformedAxis(2, -1f)
            val right = relativePose.getTransformedAxis(0, 1f)
            val up = relativePose.getTransformedAxis(1, 1f)
            val intrinsics = camera.imageIntrinsics
            val focalLength = intrinsics.focalLength
            val principalPoint = intrinsics.principalPoint
            val imageDimensions = intrinsics.imageDimensions
            CameraPoseEvidence(
                referenceId = motionReferenceId,
                timestampMs = frame.timestamp / 1_000_000L,
                positionX = x,
                positionY = y,
                positionZ = z,
                forwardX = forward[0],
                forwardY = forward[1],
                forwardZ = forward[2],
                imageProjection = CameraImageProjection(
                    imageWidth = imageDimensions[0],
                    imageHeight = imageDimensions[1],
                    fx = focalLength[0],
                    fy = focalLength[1],
                    cx = principalPoint[0],
                    cy = principalPoint[1],
                    rightX = right[0],
                    rightY = right[1],
                    rightZ = right[2],
                    upX = up[0],
                    upY = up[1],
                    upZ = up[2],
                ),
            ).withCapturedSpatialMetadata(
                readGravityUpInAnchor = {
                    val gravityUp = worldToAnchor.rotateVector(floatArrayOf(0f, 1f, 0f))
                    Vec3(gravityUp[0], gravityUp[1], gravityUp[2])
                },
                readPlanes = { session.getAllTrackables(Plane::class.java) },
                readPolygonInAnchor = { plane ->
                    if (plane.trackingState != TrackingState.TRACKING ||
                        plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING || plane.subsumedBy != null
                    ) {
                        null
                    } else {
                        // Resolve plane and anchor poses in this frame's world coordinates.
                        val planeToAnchor = worldToAnchor.compose(plane.centerPose)
                        captureHorizontalPlanePolygon(plane.polygon, planeToAnchor::transformPoint)
                    }
                },
            )
        } catch (_: Exception) {
            clearCameraMotion()
            null
        }
    }

    private fun clearCameraMotion() {
        val anchor = motionAnchor
        motionAnchor = null
        runCatching { anchor?.detach() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clearCameraMotion()
    }

    private companion object {
        val nextMotionReferenceId = AtomicLong()
    }

    @Synchronized
    fun acquirePreflightDepthBundle(frame: Frame): ArCoreDepthBundle {
        preflightFrameCount += 1
        if (frame.camera.trackingState == TrackingState.TRACKING) preflightTrackingFrameCount += 1
        val cameraImageTimestampNs = tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, "camera") {
            frame.acquireCameraImage()
        }?.use { it.timestamp }
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { tryAcquire(DepthAcquisitionStage.FULL_DEPTH, "full") { frame.acquireDepthImage16Bits() } },
            acquireRawDepth = { tryAcquire(DepthAcquisitionStage.RAW_DEPTH, "raw") { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = {
                tryAcquire(DepthAcquisitionStage.RAW_CONFIDENCE, "confidence") { frame.acquireRawDepthConfidenceImage() }
            },
            isMatchingRawPair = ::matchingRawConfidenceImages,
        ) { full, raw, confidence ->
            ArCoreDepthBundle(
                frame.timestamp,
                raw?.timestamp,
                raw,
                confidence,
                full,
                full?.timestamp,
                cameraImageTimestampNs = cameraImageTimestampNs,
                rawConfidenceTimestampNs = confidence?.timestamp,
            )
        }
        if (bundle.hasFullDepth) preflightFullFrameCount += 1
        if (bundle.hasRawDepth) preflightRawFrameCount += 1
        return bundle
    }

    @Synchronized
    fun recordPreflightSamples(validSamples: Int) {
        preflightMaxValidSamples = maxOf(preflightMaxValidSamples, validSamples)
    }

    @Synchronized
    fun preflightDiagnostics(): String =
        "attempts=$preflightFrameCount trackingFrames=$preflightTrackingFrameCount " +
            "fullFrames=$preflightFullFrameCount rawPairFrames=$preflightRawFrameCount " +
            "maxValidSamples=$preflightMaxValidSamples failures=$preflightAcquisitionFailures"

    /** Preflight keeps its textual summary; operational attempts use bounded enum counters. */
    private fun recordAcquisitionFailure(
        stage: DepthAcquisitionStage,
        preflightSource: String?,
        error: Exception,
        cameraConsumer: CameraImageConsumer? = null,
    ): Image? {
        if (preflightSource != null) {
            val key = "$preflightSource:${error.javaClass.simpleName}"
            preflightAcquisitionFailures[key] = (preflightAcquisitionFailures[key] ?: 0) + 1
        } else {
            runtimeAcquisitionFailures.record(stage, depthAcquisitionFailureReason(error), cameraConsumer)
        }
        return null
    }

    private inline fun tryAcquire(
        stage: DepthAcquisitionStage,
        preflightSource: String? = null,
        cameraConsumer: CameraImageConsumer? = null,
        block: () -> Image,
    ): Image? {
        return try {
            block()
        } catch (error: NotYetAvailableException) {
            recordAcquisitionFailure(stage, preflightSource, error, cameraConsumer)
        } catch (error: NotTrackingException) {
            recordAcquisitionFailure(stage, preflightSource, error, cameraConsumer)
        } catch (error: DeadlineExceededException) {
            recordAcquisitionFailure(stage, preflightSource, error, cameraConsumer)
        } catch (error: ResourceExhaustedException) {
            recordAcquisitionFailure(stage, preflightSource, error, cameraConsumer)
        } catch (error: IllegalStateException) {
            recordAcquisitionFailure(stage, preflightSource, error, cameraConsumer)
        }
    }
}

internal fun <T : AutoCloseable, R> acquirePreflightDepth(
    acquireFullDepth: () -> T?,
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    createBundle: (full: T?, raw: T?, confidence: T?) -> R,
): R = acquirePreflightDepth(
    acquireFullDepth, acquireRawDepth, acquireRawConfidence, { _, _ -> true }, createBundle,
)

internal fun <T : AutoCloseable, R> acquirePreflightDepth(
    acquireFullDepth: () -> T?,
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    isMatchingRawPair: (T, T) -> Boolean,
    createBundle: (full: T?, raw: T?, confidence: T?) -> R,
): R {
    val full = acquireFullDepth()
    if (full != null) {
        try {
            return createBundle(full, null, null)
        } catch (error: Exception) {
            full.close()
            throw error
        }
    }
    val pair = acquireMatchingRawDepthPair(acquireRawDepth, acquireRawConfidence, isMatchingRawPair)
        ?: return createBundle(null, null, null)
    try {
        return createBundle(null, pair.first, pair.second)
    } catch (error: Exception) {
        closeRawPairAfterFailure(pair.first, pair.second, error)
        throw error
    }
}

/** Ownership transfers only for a validated pair; rejected/missing pairs release both images. */
internal fun <T : AutoCloseable> acquireMatchingRawDepthPair(
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    isMatchingPair: (T, T) -> Boolean,
): Pair<T, T>? = acquireMatchingRawDepthPair(
    acquireRawDepth = acquireRawDepth,
    acquireRawConfidence = acquireRawConfidence,
    onPairMismatch = {},
    isMatchingPair = isMatchingPair,
)

/** [onPairMismatch]: both images arrived but describe different observations. Missing images are counted where acquired. */
internal fun <T : AutoCloseable> acquireMatchingRawDepthPair(
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    onPairMismatch: () -> Unit,
    isMatchingPair: (T, T) -> Boolean,
): Pair<T, T>? {
    val raw = acquireRawDepth() ?: return null
    var confidence: T? = null
    try {
        confidence = acquireRawConfidence()
        if (confidence != null && isMatchingPair(raw, confidence)) return raw to confidence
        if (confidence != null) onPairMismatch()
    } catch (error: Exception) {
        closeRawPairAfterFailure(raw, confidence, error)
        throw error
    }
    closeRawPair(raw, confidence)
    return null
}

private fun closeRawPair(raw: AutoCloseable?, confidence: AutoCloseable?) {
    try {
        raw?.close()
    } finally {
        confidence?.close()
    }
}

private fun closeRawPairAfterFailure(raw: AutoCloseable?, confidence: AutoCloseable?, error: Exception) {
    try {
        closeRawPair(raw, confidence)
    } catch (cleanupError: Exception) {
        error.addSuppressed(cleanupError)
    }
}

private fun matchingRawConfidenceImages(raw: Image, confidence: Image): Boolean =
    rawConfidenceObservationMatches(
        raw.timestamp, confidence.timestamp, raw.width, raw.height, confidence.width, confidence.height,
    )

data class ArCoreDepthBundle(
    val frameTimestampNs: Long,
    val rawDepthTimestampNs: Long?,
    val rawDepth: Image?,
    val rawConfidence: Image?,
    val fullDepth: Image?,
    val fullDepthTimestampNs: Long? = null,
    val cameraImageTimestampNs: Long? = null,
    val rawConfidenceTimestampNs: Long? = null,
) : Closeable {
    val hasRawDepth: Boolean
        get() = rawDepth != null && rawConfidence != null && matchingRawConfidenceImages(rawDepth, rawConfidence)
    val hasFullDepth: Boolean get() = fullDepth != null

    override fun close() {
        rawDepth?.close()
        rawConfidence?.close()
        fullDepth?.close()
    }
}

/** Operational depth-acquisition stages with separately counted failures. */
enum class DepthAcquisitionStage(val key: String) {
    CAMERA_IMAGE("camera"),
    RAW_DEPTH("raw"),
    RAW_CONFIDENCE("confidence"),
    RAW_PAIR("raw_pair"),
    FULL_DEPTH("full"),
    ;

    /**
     * S14-25-F1: depth stages decide the runtime depth gate. The camera image only supplies a
     * timestamp there and is shared with the detector and tactile consumers (which may hold images
     * across frames), so its failures keep the transient budget: counted, never an immediate stop.
     */
    val decidesDepthGate: Boolean
        get() = this != CAMERA_IMAGE
}

/**
 * S11-18: who asked for the shared CPU camera image. The detector keeps the unlabeled
 * acquisition (reported as plain `camera`); the others are counted as `camera.<key>`.
 */
enum class CameraImageConsumer(val key: String) {
    DEPTH("depth"),
    VISUAL_TRACKING("visual_tracking"),
    UNKNOWN_OBJECT("unknown"),
}

/**
 * Bounded failure reasons. "Raw not yet available", frame expiry, resource exhaustion, lost
 * tracking and a Raw/confidence pair mismatch stay distinct so threshold problems are not
 * confused with system problems.
 */
enum class DepthAcquisitionFailureReason {
    NOT_YET_AVAILABLE,
    NOT_TRACKING,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    ILLEGAL_STATE,
    PAIR_MISMATCH,
    ;

    /** S14-25: upper-policy class of this failure. */
    val severity: DepthAcquisitionFailureSeverity
        get() = when (this) {
            NOT_YET_AVAILABLE, DEADLINE_EXCEEDED, PAIR_MISMATCH -> DepthAcquisitionFailureSeverity.TRANSIENT
            NOT_TRACKING -> DepthAcquisitionFailureSeverity.NOT_TRACKING
            RESOURCE_EXHAUSTED, ILLEGAL_STATE -> DepthAcquisitionFailureSeverity.FATAL
        }

    /** Fatal failures skip the stale-timeout budget (FP-043-DETAIL-01). */
    val stopsImmediately: Boolean
        get() = severity == DepthAcquisitionFailureSeverity.FATAL
}

/** Upper-policy class of a failure; ordinal order is severity order. */
enum class DepthAcquisitionFailureSeverity {
    /** Depth not produced for this frame yet: temporary uncertainty, stale timeout still applies. */
    TRANSIENT,
    /** Tracking lost: no metric evidence until tracking returns, stale timeout still applies. */
    NOT_TRACKING,
    /** Scarce image buffers exhausted or session error: no retry count, stop at once. */
    FATAL,
}

/** DeadlineExceededException extends IllegalStateException, so it is classified first. */
internal fun depthAcquisitionFailureReason(error: Exception): DepthAcquisitionFailureReason = when (error) {
    is NotYetAvailableException -> DepthAcquisitionFailureReason.NOT_YET_AVAILABLE
    is NotTrackingException -> DepthAcquisitionFailureReason.NOT_TRACKING
    is DeadlineExceededException -> DepthAcquisitionFailureReason.DEADLINE_EXCEEDED
    is ResourceExhaustedException -> DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED
    else -> DepthAcquisitionFailureReason.ILLEGAL_STATE
}

/**
 * Fixed-size stage/reason counters for the operational path. Keys are enum pairs, so a long walk
 * cannot grow memory or produce per-frame log lines; counts saturate instead of overflowing.
 */
class DepthAcquisitionFailureCounters {
    private val stages = DepthAcquisitionStage.entries
    private val reasons = DepthAcquisitionFailureReason.entries
    private val counts = IntArray(stages.size * reasons.size)
    private val consumers = CameraImageConsumer.entries
    private val cameraConsumerCounts = IntArray(consumers.size * reasons.size)
    private var mostSevereSinceLastCheck: DepthAcquisitionFailureReason? = null
    private var lastFieldLogAtMs: Long? = null
    private var lastFieldLogSummary: String? = null

    /** A labeled camera-image failure is counted under its consumer instead of the plain camera key. */
    @Synchronized
    fun record(
        stage: DepthAcquisitionStage,
        reason: DepthAcquisitionFailureReason,
        cameraConsumer: CameraImageConsumer? = null,
    ) {
        if (stage == DepthAcquisitionStage.CAMERA_IMAGE && cameraConsumer != null) {
            val index = cameraConsumer.ordinal * reasons.size + reason.ordinal
            if (cameraConsumerCounts[index] < Int.MAX_VALUE) cameraConsumerCounts[index] += 1
            return
        }
        val index = stage.ordinal * reasons.size + reason.ordinal
        if (counts[index] < Int.MAX_VALUE) counts[index] += 1
        if (!stage.decidesDepthGate) return
        val previous = mostSevereSinceLastCheck
        if (previous == null || reason.severity > previous.severity) mostSevereSinceLastCheck = reason
    }

    /** S14-25-F1: a new depth acquisition starts; nothing recorded before it reaches its gate. */
    @Synchronized
    fun beginDepthAcquisition() {
        mostSevereSinceLastCheck = null
    }

    /** Returns and clears the most severe depth-stage reason since the previous call or begin. */
    @Synchronized
    fun consumeMostSevereSinceLastCheck(): DepthAcquisitionFailureReason? =
        mostSevereSinceLastCheck.also { mostSevereSinceLastCheck = null }

    @Synchronized
    fun count(stage: DepthAcquisitionStage, reason: DepthAcquisitionFailureReason): Int =
        counts[stage.ordinal * reasons.size + reason.ordinal]

    @Synchronized
    fun cameraCount(consumer: CameraImageConsumer, reason: DepthAcquisitionFailureReason): Int =
        cameraConsumerCounts[consumer.ordinal * reasons.size + reason.ordinal]

    @Synchronized
    fun summaryText(): String {
        val parts = summaryParts()
        return if (parts.isEmpty()) "none" else parts.joinToString(" ")
    }

    /**
     * S11-18: field-log value (the log's safe character set joins parts with '+'), at most once per
     * [FIELD_LOG_INTERVAL_MS] and only when the counts changed since the last record.
     */
    @Synchronized
    fun fieldLogSummaryIfDue(nowElapsedRealtimeMs: Long): String? {
        val last = lastFieldLogAtMs
        if (last != null && nowElapsedRealtimeMs - last in 0L until FIELD_LOG_INTERVAL_MS) return null
        val parts = summaryParts()
        val summary = if (parts.isEmpty()) "none" else parts.joinToString("+")
        if (summary == lastFieldLogSummary) return null
        lastFieldLogAtMs = nowElapsedRealtimeMs
        lastFieldLogSummary = summary
        return summary
    }

    private fun summaryParts(): List<String> = stages.flatMap { stage ->
        val plain = reasons.mapNotNull { reason ->
            count(stage, reason).takeIf { it > 0 }?.let { "${stage.key}:${reason.name}=$it" }
        }
        if (stage != DepthAcquisitionStage.CAMERA_IMAGE) return@flatMap plain
        plain + consumers.flatMap { consumer ->
            reasons.mapNotNull { reason ->
                cameraCount(consumer, reason).takeIf { it > 0 }
                    ?.let { "${stage.key}.${consumer.key}:${reason.name}=$it" }
            }
        }
    }

    companion object {
        /** Bounded field-log cadence for these counters (30 s, root choice for S11-18). */
        const val FIELD_LOG_INTERVAL_MS = 30_000L
    }
}
