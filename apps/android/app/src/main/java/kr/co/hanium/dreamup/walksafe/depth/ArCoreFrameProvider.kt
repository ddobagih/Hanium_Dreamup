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

    fun configureDepthMode(): Boolean {
        val config = session.config
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        session.configure(config)
        return depthSupported
    }

    fun acquireCameraImageOrNull(frame: Frame): Image? {
        return try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            null
        } catch (_: NotTrackingException) {
            null
        } catch (_: DeadlineExceededException) {
            null
        } catch (_: ResourceExhaustedException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun acquireDepthBundle(
        frame: Frame,
        includeFullDepthWhenRawAvailable: Boolean = true,
    ): ArCoreDepthBundle {
        // Read the actual CPU image clock. Frame and Android camera metadata timestamps can differ.
        val cameraImageTimestampNs = acquireCameraImageOrNull(frame)?.use { it.timestamp }
        val rawPair = acquireMatchingRawDepthPair(
            acquireRawDepth = { tryAcquire { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = { tryAcquire { frame.acquireRawDepthConfidenceImage() } },
            isMatchingPair = ::matchingRawConfidenceImages,
        )
        val rawDepth = rawPair?.first
        val rawConfidence = rawPair?.second
        val fullDepth = if (
            includeFullDepthWhenRawAvailable || rawDepth == null || rawConfidence == null
        ) {
            tryAcquire { frame.acquireDepthImage16Bits() }
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
        val cameraPoseEvidence = captureCameraPose(frame)
        val snapshot = acquireDepthBundle(
            frame,
            includeFullDepthWhenRawAvailable = false,
        ).toSnapshotAndClose().copy(
            cameraPoseEvidence = cameraPoseEvidence,
        )
        if (!includeFullDepthWhenRawAvailable || !snapshot.hasMetricRawDepth) return snapshot
        return tryAcquire { frame.acquireDepthImage16Bits() }?.use { fullDepth ->
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
        val cameraImageTimestampNs = tryAcquire("camera") { frame.acquireCameraImage() }?.use { it.timestamp }
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { tryAcquire("full") { frame.acquireDepthImage16Bits() } },
            acquireRawDepth = { tryAcquire("raw") { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = { tryAcquire("confidence") { frame.acquireRawDepthConfidenceImage() } },
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

    private fun recordAcquisitionFailure(source: String?, error: Exception): Image? {
        if (source != null) {
            val key = "$source:${error.javaClass.simpleName}"
            preflightAcquisitionFailures[key] = (preflightAcquisitionFailures[key] ?: 0) + 1
        }
        return null
    }

    private inline fun tryAcquire(source: String? = null, block: () -> Image): Image? {
        return try {
            block()
        } catch (error: NotYetAvailableException) {
            recordAcquisitionFailure(source, error)
        } catch (error: NotTrackingException) {
            recordAcquisitionFailure(source, error)
        } catch (error: DeadlineExceededException) {
            recordAcquisitionFailure(source, error)
        } catch (error: ResourceExhaustedException) {
            recordAcquisitionFailure(source, error)
        } catch (error: IllegalStateException) {
            recordAcquisitionFailure(source, error)
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
): Pair<T, T>? {
    val raw = acquireRawDepth() ?: return null
    var confidence: T? = null
    try {
        confidence = acquireRawConfidence()
        if (confidence != null && isMatchingPair(raw, confidence)) return raw to confidence
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
