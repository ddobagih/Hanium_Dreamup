package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Frame
import java.lang.ref.WeakReference

/** The caller owns [image] and closes it with its existing Image.use/close path. */
class RecognitionCameraImage internal constructor(
    val image: Image,
    val timestampEvidence: CameraImageTimestampEvidence,
)

/**
 * The CPU image clock read during the provider's own acquisition, bound to that exact AR frame.
 * This receipt remains usable after the caller closes the image. It owns no image or native frame.
 * The caller supplies the strong current Frame for identity and timestamp validation.
 */
class CameraImageTimestampEvidence internal constructor(
    private val owner: Any,
    private val epoch: Long,
    frame: Frame,
    private val frameTimestampNs: Long,
    val cameraImageTimestampNs: Long,
) {
    private val frameReference = WeakReference(frame)

    internal fun timestampFor(currentOwner: Any, currentEpoch: Long, currentFrame: Frame): Long? =
        cameraImageTimestampNs.takeIf {
            owner === currentOwner && epoch == currentEpoch && frameReference.get() === currentFrame &&
                frameTimestampNs > 0L && currentFrame.timestamp == frameTimestampNs && it > 0L
        }
}
