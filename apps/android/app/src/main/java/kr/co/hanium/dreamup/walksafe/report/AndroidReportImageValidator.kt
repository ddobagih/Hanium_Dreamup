package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decode only report-sized images; this checks readability, not blur or damage quality. */
internal object AndroidReportImageValidator {
    const val MAX_DIMENSION = 320

    fun isDecodable(bytes: ByteArray): Boolean {
        if (!JpegStructureValidator.isStructurallyValid(bytes)) return false
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..MAX_DIMENSION ||
                bounds.outHeight !in 1..MAX_DIMENSION) return false
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            try {
                bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight
            } finally {
                try { bitmap.eraseColor(0) } finally { bitmap.recycle() }
            }
        }.getOrDefault(false)
    }
}
