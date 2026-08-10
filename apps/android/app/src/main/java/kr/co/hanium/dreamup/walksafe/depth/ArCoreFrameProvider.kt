package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Config
import com.google.ar.core.Frame
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

    fun acquireDepthBundle(frame: Frame): ArCoreDepthBundle {
        val rawDepth = tryAcquire { frame.acquireRawDepthImage16Bits() }
        val rawConfidence = if (rawDepth != null) tryAcquire { frame.acquireRawDepthConfidenceImage() } else null
        val fullDepth = tryAcquire { frame.acquireDepthImage16Bits() }
        return ArCoreDepthBundle(
            frameTimestampNs = frame.timestamp,
            rawDepth = rawDepth,
            rawConfidence = rawConfidence,
            fullDepth = fullDepth,
        )
    }

    private inline fun tryAcquire(block: () -> Image): Image? {
        return try {
            block()
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
}

data class ArCoreDepthBundle(
    val frameTimestampNs: Long,
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
