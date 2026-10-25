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
class ArCoreFrameProvider(
    private val session: Session,
    private val timingClockNs: () -> Long = System::nanoTime,
) : Closeable {
    private var motionAnchor: Anchor? = null
    private var motionReferenceId = 0L
    private var closed = false
    private var semanticAcquisitionEnabled = false
    private val cameraTimestampOwner = Any()
    private var cameraTimestampEpoch = 0L
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

    /**
     * Call after the owner successfully configures this session, before captures resume. Camera
     * configuration changes require another configure/refresh at that same stopped boundary; a
     * replacement Session requires a new provider. External configuration changes are not polled
     * per frame. This only reads configuration/support and never enables or configures semantics.
     */
    @Synchronized
    fun refreshSemanticAcquisitionConfiguration(): Boolean {
        semanticAcquisitionEnabled = !closed && try {
            session.config.semanticMode == Config.SemanticMode.ENABLED &&
                session.isSemanticModeSupported(Config.SemanticMode.ENABLED)
        } catch (_: Exception) {
            false
        }
        return semanticAcquisitionEnabled
    }

    fun acquireCameraImageOrNull(frame: Frame): Image? =
        tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE) { frame.acquireCameraImage() }

    /** S11-18: the same acquisition with its consumer counted separately (camera.<consumer>). */
    fun acquireCameraImageOrNull(frame: Frame, consumer: CameraImageConsumer): Image? =
        tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, cameraConsumer = consumer) { frame.acquireCameraImage() }

    /**
     * Acquires the recognition image once and records its actual CPU clock at that same boundary.
     * Only a successfully processed recognition image's receipt should be passed to depth. The
     * caller still owns and closes the returned image; depth never retains or closes that image.
     */
    @Synchronized
    fun acquireRecognitionCameraImageOrNull(
        frame: Frame,
        consumer: CameraImageConsumer? = null,
    ): RecognitionCameraImage? {
        if (closed) return null
        // A newer attempt, including a failed one, cannot reuse an earlier successful receipt.
        invalidateRecognitionCameraImageTimestamps()
        val frameTimestampNs = frame.timestamp
        if (frameTimestampNs <= 0L || frame.camera.trackingState != TrackingState.TRACKING) {
            return null
        }
        val image = tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, cameraConsumer = consumer) {
            frame.acquireCameraImage()
        } ?: return null
        val cameraImageTimestampNs = try {
            image.timestamp.takeIf {
                it > 0L && frame.timestamp == frameTimestampNs &&
                    frame.camera.trackingState == TrackingState.TRACKING
            }
        } catch (error: Exception) {
            image.close()
            recordAcquisitionFailure(DepthAcquisitionStage.CAMERA_IMAGE, null, error, consumer)
            return null
        }
        if (cameraImageTimestampNs == null) {
            image.close()
            return null
        }
        return RecognitionCameraImage(
            image,
            CameraImageTimestampEvidence(
                cameraTimestampOwner, cameraTimestampEpoch, frame, frameTimestampNs, cameraImageTimestampNs,
            ),
        )
    }

    /**
     * Keeps RGB-only recognition available when this frame starts PAUSED/STOPPED. Choose this
     * path before acquiring an image; it is never a retry for a failed TRACKING capture. No CPU
     * clock receipt or depth qualification is created. The caller owns and closes the image.
     */
    @Synchronized
    fun acquireUntrackedRecognitionCameraImageOrNull(
        frame: Frame,
        consumer: CameraImageConsumer? = null,
    ): Image? {
        if (closed) return null
        if (frame.camera.trackingState == TrackingState.TRACKING) return null
        invalidateRecognitionCameraImageTimestamps()
        clearCameraMotion()
        return tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, cameraConsumer = consumer) {
            frame.acquireCameraImage()
        }
    }

    /** Cancel/pause or a replaced capture generation invalidates receipts without owning images. */
    @Synchronized
    fun invalidateRecognitionCameraImageTimestamps() {
        cameraTimestampEpoch += 1L
    }

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

    @Synchronized
    fun acquireDepthBundle(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean = true,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)? = null,
    ): ArCoreDepthBundle {
        // Read the actual CPU image clock. Frame and Android camera metadata timestamps can differ.
        val cameraImageTimestampNs = acquireCameraImageOrNull(frame, CameraImageConsumer.DEPTH)?.use { it.timestamp }
        return acquireDepthBundleWithTimestamp(frame, includeFullDepthWhenRawAvailable, cameraImageTimestampNs,
            fullDepthTimingObserver)
    }

    /** Explicitly shared path: missing/foreign/expired receipts never fall back to acquisition. */
    @Synchronized
    fun acquireDepthBundle(
        frame: Frame,
        cameraImageTimestampEvidence: CameraImageTimestampEvidence?,
        includeFullDepthWhenRawAvailable: Boolean = true,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)? = null,
    ): ArCoreDepthBundle {
        if (closed) return ArCoreDepthBundle(0L, null, null, null, null)
        return acquireDepthBundleWithTimestamp(
            frame, includeFullDepthWhenRawAvailable, recognitionCameraTimestamp(frame, cameraImageTimestampEvidence),
            fullDepthTimingObserver,
        )
    }

    private fun recognitionCameraTimestamp(frame: Frame, evidence: CameraImageTimestampEvidence?): Long? {
        if (closed) return null
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            invalidateRecognitionCameraImageTimestamps()
            return null
        }
        return evidence?.timestampFor(cameraTimestampOwner, cameraTimestampEpoch, frame)
    }

    private fun acquireDepthBundleWithTimestamp(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean,
        cameraImageTimestampNs: Long?,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)?,
    ): ArCoreDepthBundle {
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
        val needsFullDepth = includeFullDepthWhenRawAvailable || rawDepth == null || rawConfidence == null
        val fullTiming = if (needsFullDepth) fullDepthTimingObserver?.let {
            FullDepthTimingCapture(
                if (rawDepth == null || rawConfidence == null) FullDepthRequestPurpose.RAW_UNAVAILABLE_FALLBACK
                else FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT,
                timingClockNs, it,
            )
        } else null
        var fullDepth: Image? = null
        try {
            if (needsFullDepth) fullDepth = acquireFullDepth(frame, fullTiming)
            return ArCoreDepthBundle(
                frameTimestampNs = frame.timestamp,
                rawDepthTimestampNs = rawDepth?.timestamp,
                rawDepth = rawDepth,
                rawConfidence = rawConfidence,
                fullDepth = fullDepth,
                fullDepthTimestampNs = fullDepth?.timestamp,
                cameraImageTimestampNs = cameraImageTimestampNs,
                rawConfidenceTimestampNs = rawConfidence?.timestamp,
            ).also { if (fullDepth != null) it.fullDepthTimingCapture = fullTiming }
        } catch (error: Throwable) {
            closeDepthImages(rawDepth, rawConfidence, failure = error) {
                // Failed acquisition already reports its timing; only acquired images are closed.
                fullDepth?.let { image ->
                    if (fullTiming == null) image.close() else fullTiming.close { image.close() }
                }
            }
            throw error
        }
    }

    private fun acquireFullDepth(
        frame: Frame,
        timing: FullDepthTimingCapture?,
        preflightSource: String? = null,
    ): Image? = tryAcquire(DepthAcquisitionStage.FULL_DEPTH, preflightSource) {
        if (timing == null) frame.acquireDepthImage16Bits()
        else timing.acquire { frame.acquireDepthImage16Bits() }
    }

    /** Copies this frame's raw pair before acquiring full depth, releasing scarce native buffers. */
    @Synchronized
    fun acquireDepthSnapshot(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean = true,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)? = null,
    ): DepthFrameSnapshot = acquireDepthSnapshotWithTimestamp(frame, includeFullDepthWhenRawAvailable,
        fullDepthTimingObserver) {
        acquireCameraImageOrNull(frame, CameraImageConsumer.DEPTH)?.use { it.timestamp }
    }.let { withSemanticObservation(frame, it) }

    /** Reuses the recognition CPU clock only for the receipt's exact frame and provider epoch. */
    @Synchronized
    fun acquireDepthSnapshot(
        frame: Frame,
        cameraImageTimestampEvidence: CameraImageTimestampEvidence?,
        includeFullDepthWhenRawAvailable: Boolean = true,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)? = null,
    ): DepthFrameSnapshot = acquireDepthSnapshotWithTimestamp(frame, includeFullDepthWhenRawAvailable,
        fullDepthTimingObserver) {
        recognitionCameraTimestamp(frame, cameraImageTimestampEvidence)
    }.let { withSemanticObservation(frame, it) }

    /** Optional copied observations never decide the depth gate or change Full timing/results. */
    private fun withSemanticObservation(frame: Frame, snapshot: DepthFrameSnapshot): DepthFrameSnapshot {
        if (closed || !semanticAcquisitionEnabled) return snapshot
        val semantic = try {
            frame.acquireSemanticImage().use { labels ->
                frame.acquireSemanticConfidenceImage().use { confidence ->
                    SemanticFrameSnapshot(
                        frameTimestampNs = snapshot.frameTimestampNs,
                        cameraImageTimestampNs = snapshot.cameraImageTimestampNs,
                        cameraPoseReferenceId = snapshot.cameraPoseEvidence?.referenceId,
                        labels = SemanticImage8Snapshot.copyOf(labels),
                        confidence = SemanticImage8Snapshot.copyOf(confidence),
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
        return if (semantic == null) snapshot else snapshot.copy(semanticFrame = semantic)
    }

    private inline fun acquireDepthSnapshotWithTimestamp(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean,
        noinline fullDepthTimingObserver: ((FullDepthTiming) -> Unit)?,
        cameraTimestamp: () -> Long?,
    ): DepthFrameSnapshot {
        if (closed) return DepthFrameSnapshot(0L, null, null, null)
        // S14-25-F1: only this call's own depth-stage attempts decide the gate's fatal signal.
        runtimeAcquisitionFailures.beginDepthAcquisition()
        val cameraPoseEvidence = captureCameraPose(frame)
        val snapshot = acquireDepthBundleWithTimestamp(
            frame,
            includeFullDepthWhenRawAvailable = false,
            cameraImageTimestampNs = cameraTimestamp(),
            fullDepthTimingObserver = fullDepthTimingObserver,
        ).toSnapshotAndClose().copy(
            cameraPoseEvidence = cameraPoseEvidence,
        )
        if (!includeFullDepthWhenRawAvailable || !snapshot.hasMetricRawDepth) return snapshot
        val fullTiming = fullDepthTimingObserver?.let {
            FullDepthTimingCapture(FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT, timingClockNs, it)
        }
        val fullDepth = acquireFullDepth(frame, fullTiming) ?: return snapshot
        val copyFullDepth = { image: Image ->
            snapshot.copy(
                fullDepth = if (fullTiming == null) image.toDepthImage16Snapshot()
                    else fullTiming.convert { image.toDepthImage16Snapshot() },
                fullDepthTimestampNs = image.timestamp,
            )
        }
        return if (fullTiming == null) fullDepth.use(copyFullDepth)
        else fullTiming.useAndClose(fullDepth, copyFullDepth)
    }

    private fun captureCameraPose(frame: Frame): CameraPoseEvidence? {
        val camera = frame.camera
        if (frame.timestamp <= 0L || camera.trackingState != TrackingState.TRACKING) {
            invalidateRecognitionCameraImageTimestamps()
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
        semanticAcquisitionEnabled = false
        invalidateRecognitionCameraImageTimestamps()
        clearCameraMotion()
    }

    private companion object {
        val nextMotionReferenceId = AtomicLong()
    }

    @Synchronized
    fun acquirePreflightDepthBundle(
        frame: Frame,
        fullDepthTimingObserver: ((FullDepthTiming) -> Unit)? = null,
    ): ArCoreDepthBundle {
        preflightFrameCount += 1
        if (frame.camera.trackingState == TrackingState.TRACKING) preflightTrackingFrameCount += 1
        val cameraImageTimestampNs = tryAcquire(DepthAcquisitionStage.CAMERA_IMAGE, "camera") {
            frame.acquireCameraImage()
        }?.use { it.timestamp }
        val fullTiming = fullDepthTimingObserver?.let {
            FullDepthTimingCapture(FullDepthRequestPurpose.PREFLIGHT_PRIMARY, timingClockNs, it)
        }
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { acquireFullDepth(frame, fullTiming, "full") },
            acquireRawDepth = { tryAcquire(DepthAcquisitionStage.RAW_DEPTH, "raw") { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = {
                tryAcquire(DepthAcquisitionStage.RAW_CONFIDENCE, "confidence") { frame.acquireRawDepthConfidenceImage() }
            },
            isMatchingRawPair = ::matchingRawConfidenceImages,
            closeFullDepth = { image ->
                if (fullTiming == null) image.close() else fullTiming.close { image.close() }
            },
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
            ).also {
                if (full != null) it.fullDepthTimingCapture = fullTiming
                // Metadata validation is still part of assembly, before ownership transfers.
                if (it.hasFullDepth) preflightFullFrameCount += 1
                if (it.hasRawDepth) preflightRawFrameCount += 1
            }
        }
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
    acquireFullDepth, acquireRawDepth, acquireRawConfidence, { _, _ -> true }, createBundle = createBundle,
)

internal fun <T : AutoCloseable, R> acquirePreflightDepth(
    acquireFullDepth: () -> T?,
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    isMatchingRawPair: (T, T) -> Boolean,
    createBundle: (full: T?, raw: T?, confidence: T?) -> R,
): R = acquirePreflightDepth(
    acquireFullDepth, acquireRawDepth, acquireRawConfidence, isMatchingRawPair, { it.close() }, createBundle,
)

internal fun <T : AutoCloseable, R> acquirePreflightDepth(
    acquireFullDepth: () -> T?,
    acquireRawDepth: () -> T?,
    acquireRawConfidence: () -> T?,
    isMatchingRawPair: (T, T) -> Boolean,
    closeFullDepth: (T) -> Unit,
    createBundle: (full: T?, raw: T?, confidence: T?) -> R,
): R {
    val full = acquireFullDepth()
    if (full != null) {
        try {
            return createBundle(full, null, null)
        } catch (error: Throwable) {
            closeDepthImages(null, null, failure = error) { closeFullDepth(full) }
            throw error
        }
    }
    val pair = acquireMatchingRawDepthPair(acquireRawDepth, acquireRawConfidence, isMatchingRawPair)
        ?: return createBundle(null, null, null)
    try {
        return createBundle(null, pair.first, pair.second)
    } catch (error: Throwable) {
        closeDepthImages(pair.first, pair.second, failure = error)
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
    } catch (error: Throwable) {
        closeDepthImages(raw, confidence, failure = error)
        throw error
    }
    closeDepthImages(raw, confidence)
    return null
}

/** Attempt every release, retaining the operation failure or otherwise the first close failure. */
private fun closeDepthImages(
    raw: AutoCloseable?,
    confidence: AutoCloseable?,
    failure: Throwable? = null,
    closeFullDepth: () -> Unit = {},
) {
    var primaryFailure = failure
    fun attemptClose(close: () -> Unit) {
        try {
            close()
        } catch (cleanupError: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = cleanupError
            else if (primary !== cleanupError) primary.addSuppressed(cleanupError)
        }
    }
    attemptClose { raw?.close() }
    attemptClose { confidence?.close() }
    attemptClose(closeFullDepth)
    primaryFailure?.let { throw it }
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
    internal var fullDepthTimingCapture: FullDepthTimingCapture? = null

    val hasRawDepth: Boolean
        get() = rawDepth != null && rawConfidence != null && matchingRawConfidenceImages(rawDepth, rawConfidence)
    val hasFullDepth: Boolean get() = fullDepth != null

    /** Only this Full image's copy is measured; Raw/confidence conversion stays outside. */
    internal fun copyFullDepthSnapshot(): DepthImage16? {
        val image = fullDepth ?: return null
        val timing = fullDepthTimingCapture
        return if (timing == null) image.toDepthImage16Snapshot()
        else timing.convert { image.toDepthImage16Snapshot() }
    }

    override fun close() {
        closeDepthImages(rawDepth, rawConfidence) {
            val timing = fullDepthTimingCapture
            if (timing == null) fullDepth?.close() else timing.close { fullDepth?.close() }
        }
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
