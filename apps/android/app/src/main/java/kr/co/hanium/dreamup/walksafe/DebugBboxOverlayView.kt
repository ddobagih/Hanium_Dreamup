package kr.co.hanium.dreamup.walksafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kr.co.hanium.dreamup.walksafe.depth.RectNorm

/**
 * Debug-only overlay. [DebugOverlayBox.rectPx] must already be mapped into this View's pixel space;
 * this class deliberately performs no camera, texture or display-rotation transform.
 */
class DebugBboxOverlayView(context: Context) : View(context) {
    private val detectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff00e676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffd54f.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val heldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff40c4ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffff5252.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = 28f
    }
    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xaa000000.toInt()
        style = Paint.Style.FILL
    }
    private var boxes: List<DebugBox> = emptyList()

    init {
        setWillNotDraw(false)
    }

    fun updateMapped(boxes: List<DebugOverlayBox>) {
        this.boxes = boxes.map {
            DebugBox(
                bbox = null,
                rectPx = RectF(it.rectPx),
                label = it.displayLabel(),
                best = it.best,
                held = it.held,
            )
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        boxes = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return
        for (box in boxes) {
            val rect = box.rectPx ?: box.bbox?.toScreenRect(width.toFloat(), height.toFloat()) ?: continue
            val paint = when {
                box.best -> bestPaint
                box.held -> heldPaint
                else -> detectionPaint
            }
            canvas.drawRect(rect, paint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), CENTER_RADIUS, centerPaint)
            drawLabel(canvas, box.label, rect.left, rect.top)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, left: Float, top: Float) {
        val safeLeft = left.coerceIn(0f, width.toFloat())
        val baseline = (top - LABEL_MARGIN).takeIf { it > textPaint.textSize } ?: (top + textPaint.textSize + LABEL_MARGIN)
        val textWidth = textPaint.measureText(label)
        val background = RectF(
            safeLeft,
            baseline - textPaint.textSize - LABEL_PADDING,
            (safeLeft + textWidth + LABEL_PADDING * 2).coerceAtMost(width.toFloat()),
            baseline + LABEL_PADDING,
        )
        canvas.drawRect(background, textBackgroundPaint)
        canvas.drawText(label, safeLeft + LABEL_PADDING, baseline, textPaint)
    }

    private fun RectNorm.toScreenRect(screenWidth: Float, screenHeight: Float): RectF {
        val left = (x * screenWidth).coerceIn(0f, screenWidth)
        val top = (y * screenHeight).coerceIn(0f, screenHeight)
        val right = ((x + width) * screenWidth).coerceIn(left, screenWidth)
        val bottom = ((y + height) * screenHeight).coerceIn(top, screenHeight)
        return RectF(left, top, right, bottom)
    }

    private data class DebugBox(
        val bbox: RectNorm?,
        val rectPx: RectF? = null,
        val label: String,
        val best: Boolean,
        val held: Boolean = false,
    )

    data class DebugOverlayBox(
        val rectPx: RectF,
        val label: String,
        val best: Boolean,
        val className: String? = null,
        val held: Boolean = false,
        val smoothed: Boolean = false,
        val ageMs: Long? = null,
    ) {
        fun displayLabel(): String {
            val age = ageMs
            return when {
                held && age != null -> "$label · hold ${age}ms"
                held -> "$label · hold"
                smoothed -> "$label · smooth"
                else -> label
            }
        }
    }

    private companion object {
        const val LABEL_MARGIN = 8f
        const val LABEL_PADDING = 6f
        const val CENTER_RADIUS = 8f
    }
}
