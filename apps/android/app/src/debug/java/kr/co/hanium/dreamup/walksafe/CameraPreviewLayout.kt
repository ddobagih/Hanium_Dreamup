package kr.co.hanium.dreamup.walksafe

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout

/** Camera and pixel-coordinate overlays always share exactly the same measured rectangle. */
internal class CameraPreviewLayout(context: Context) : FrameLayout(context) {
    private var imageSize: CameraPreviewGeometry.ImageSize? = null
    private var revision = 0L
    private var committedViewport: CameraPreviewGeometry.Viewport? = null
    var onViewportChanged: ((CameraPreviewGeometry.Viewport) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun setImageSize(width: Int, height: Int, quarterTurns: Int, revision: Long) {
        val next = CameraPreviewGeometry.orientedSize(width, height, quarterTurns) ?: return
        if (this.revision == revision && imageSize == next) return
        imageSize = next
        this.revision = revision
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val bounds = CameraPreviewGeometry.fit(measuredWidth, measuredHeight, imageSize)
        val childWidth = View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY)
        val childHeight = View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).takeIf { it.visibility != View.GONE }?.measure(childWidth, childHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bounds = CameraPreviewGeometry.fit(right - left, bottom - top, imageSize)
        for (index in 0 until childCount) {
            getChildAt(index).takeIf { it.visibility != View.GONE }?.layout(
                bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height,
            )
        }
        val next = CameraPreviewGeometry.Viewport(bounds.width, bounds.height, revision)
        if (committedViewport != next) {
            committedViewport = next
            onViewportChanged?.invoke(next)
        }
    }
}
