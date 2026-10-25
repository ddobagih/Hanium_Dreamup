package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import android.graphics.Bitmap
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage

internal object AndroidFrameCaptureBitmaps : FrameCaptureBitmaps<Bitmap> {
    override fun create(image: ArgbImage): Bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    override fun scale(bitmap: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap = Bitmap.createScaledBitmap(bitmap, width, height, filter)
    override fun compress(bitmap: Bitmap, quality: Int, output: java.io.OutputStream): Boolean = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    override fun recycle(bitmap: Bitmap) { bitmap.recycle() }
}
