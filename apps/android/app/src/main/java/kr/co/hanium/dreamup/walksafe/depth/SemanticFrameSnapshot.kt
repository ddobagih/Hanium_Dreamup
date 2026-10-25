package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image

/**
 * Label/confidence images acquired in one depth capture's Frame, with no native owner
 * retained. Each image clock is preserved independently; equality with each other, Frame, CPU or
 * pose clocks is not asserted. Matching dimensions alone do not qualify RGB/depth coordinates,
 * metric distance, floor/foot/body geometry or walkability. That requires the consumer's explicit
 * projection and spatial evidence. ARCore's semantic confidence API guarantees the same image
 * size and confidence for the corresponding semantic label pixel. That semantic-grid (x, y)
 * correspondence does not require equal image timestamps and does not register RGB/depth/pose
 * clocks or coordinates. ML confidence is not metric, floor/foot/body or walkability evidence.
 * A label is an ML observation, not proof of space at the feet.
 */
class SemanticFrameSnapshot internal constructor(
    val frameTimestampNs: Long,
    val cameraImageTimestampNs: Long?,
    val cameraPoseReferenceId: Long?,
    val labels: SemanticImage8Snapshot,
    val confidence: SemanticImage8Snapshot,
) {
    init {
        require(labels.width == confidence.width && labels.height == confidence.height) {
            "Semantic label/confidence dimensions must match"
        }
    }
}

/** Immutable owned plane bytes, including padding; access/copies never expose the stored array. */
class SemanticImage8Snapshot private constructor(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    private val bytes: ByteArray,
) {
    fun valueAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return bytes[y * rowStride + x * pixelStride].toInt() and 0xff
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    internal companion object {
        fun copyOf(image: Image): SemanticImage8Snapshot {
            val width = image.width
            val height = image.height
            require(width > 0 && height > 0) { "Semantic image dimensions must be positive" }
            val plane = image.planes.singleOrNull() ?: error("Semantic image must have one plane")
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            require(rowStride > 0 && pixelStride > 0) { "Semantic strides must be positive" }
            val rowBytes = (width - 1L) * pixelStride + 1L
            val requiredBytes = (height - 1L) * rowStride + rowBytes
            val buffer = plane.buffer.duplicate()
            require(rowBytes <= rowStride && requiredBytes <= buffer.limit()) {
                "Semantic plane does not contain every pixel"
            }
            val timestampNs = image.timestamp
            val bytes = ByteArray(buffer.limit())
            buffer.rewind()
            buffer.get(bytes)
            return SemanticImage8Snapshot(timestampNs, width, height, rowStride, pixelStride, bytes)
        }
    }
}
