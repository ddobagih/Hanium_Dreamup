package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Native decodability of the existing 320-pixel report image, never a quality/damage estimate. */
internal object AndroidReportJpegValidator {
    const val MAX_DIMENSION = 320

    fun isDecodable(bytes: ByteArray): Boolean {
        if (!JpegStructureValidator.isStructurallyValid(bytes)) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..MAX_DIMENSION ||
                bounds.outHeight !in 1..MAX_DIMENSION) return false
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            try {
                bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight &&
                    bitmap.isMutable && bitmap.byteCount <= MAX_DIMENSION * MAX_DIMENSION * 4
            } finally {
                try { if (bitmap.isMutable) bitmap.eraseColor(0) }
                finally { bitmap.recycle() }
            }
        } catch (_: RuntimeException) { false }
    }
}
