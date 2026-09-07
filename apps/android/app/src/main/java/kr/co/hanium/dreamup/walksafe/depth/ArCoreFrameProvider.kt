package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.ar.core.Session
import java.io.Closeable

/**
 * Acquires camera and depth images tied to one ARCore [Frame]. Every returned [Image] owns a scarce
 * native buffer; callers must close it directly or close the returned [ArCoreDepthBundle].
 */
class ArCoreFrameProvider(private val session: Session) {
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
        val rawDepth = tryAcquire { frame.acquireRawDepthImage16Bits() }
        val rawConfidence = if (rawDepth != null) tryAcquire { frame.acquireRawDepthConfidenceImage() } else null
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
        )
    }

    @Synchronized
    fun acquirePreflightDepthBundle(frame: Frame): ArCoreDepthBundle {
        preflightFrameCount += 1
        if (frame.camera.trackingState == TrackingState.TRACKING) preflightTrackingFrameCount += 1
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { tryAcquire("full") { frame.acquireDepthImage16Bits() } },
            acquireRawDepth = { tryAcquire("raw") { frame.acquireRawDepthImage16Bits() } },
            acquireRawConfidence = { tryAcquire("confidence") { frame.acquireRawDepthConfidenceImage() } },
        ) { full, raw, confidence ->
            ArCoreDepthBundle(frame.timestamp, raw?.timestamp, raw, confidence, full)
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
    val raw = acquireRawDepth()
    var confidence: T? = null
    try {
        if (raw != null) confidence = acquireRawConfidence()
        if (raw != null && confidence != null) {
            return createBundle(null, raw, confidence)
        }
    } catch (error: Exception) {
        raw?.close()
        confidence?.close()
        throw error
    }
    raw?.close()
    return createBundle(null, null, null)
}

data class ArCoreDepthBundle(
    val frameTimestampNs: Long,
    val rawDepthTimestampNs: Long?,
    val rawDepth: Image?,
    val rawConfidence: Image?,
    val fullDepth: Image?,
) : Closeable {
    val hasRawDepth: Boolean get() = rawDepth != null && rawConfidence != null
    val hasFullDepth: Boolean get() = fullDepth != null

    override fun close() {
        rawDepth?.close()
        rawConfidence?.close()
        fullDepth?.close()
    }
}
