package kr.co.hanium.dreamup.walksafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Debug-only overlay. [DebugOverlayBox.rectPx] must already be mapped into this View's pixel space;
 * this class deliberately performs no camera, texture or display-rotation transform. Short screen
 * interpolation smooths detector updates; it is not camera-motion compensation or new evidence.
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
    private val interpolatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff40c4ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
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
    private val continuity = DebugOverlayContinuity()
    private val drawingRect = RectF()
    private val expireOverlay = Runnable { invalidate() }
    private var legacySequence = 0L

    init {
        setWillNotDraw(false)
    }

    /** Compatibility for diagnostic callers without capture metadata: bounded, without animation. */
    fun updateMapped(boxes: List<DebugOverlayBox>) {
        val nowMs = SystemClock.elapsedRealtime()
        continuity.clear()
        continuity.update(
            boxes = boxes.map { it.toPresentationBox() },
            sourceFrameId = ++legacySequence,
            sourceCapturedAtElapsedRealtimeMs = nowMs,
            nowElapsedRealtimeMs = nowMs,
            animate = false,
        )
        removeCallbacks(expireOverlay)
        postInvalidateOnAnimation()
    }

    fun updateMapped(
        boxes: List<DebugOverlayBox>,
        sourceFrameId: Long,
        sourceCapturedAtElapsedRealtimeMs: Long,
        sourceComplete: Boolean = true,
        animate: Boolean = true,
    ) {
        continuity.update(
            boxes = boxes.map { it.toPresentationBox() },
            sourceFrameId = sourceFrameId,
            sourceCapturedAtElapsedRealtimeMs = sourceCapturedAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            sourceComplete = sourceComplete,
            animate = animate,
        )
        removeCallbacks(expireOverlay)
        postInvalidateOnAnimation()
    }

    fun clear() {
        continuity.clear()
        removeCallbacks(expireOverlay)
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) clear()
    }

    override fun onDetachedFromWindow() {
        continuity.clear()
        removeCallbacks(expireOverlay)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rendered = continuity.render(SystemClock.elapsedRealtime())
        for (presentation in rendered.boxes) {
            val box = presentation.box
            val rect = drawingRect
            rect.set(box.rect.left, box.rect.top, box.rect.right, box.rect.bottom)
            val paint = when {
                presentation.interpolated -> interpolatedPaint
                box.best -> bestPaint
                box.held -> heldPaint
                else -> detectionPaint
            }
            canvas.drawRect(rect, paint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), CENTER_RADIUS, centerPaint)
            val label = if (presentation.interpolated) "${box.label} · interp" else box.label
            drawLabel(canvas, label, rect.left, rect.top)
        }
        removeCallbacks(expireOverlay)
        rendered.nextRedrawDelayMs?.let { delayMs ->
            if (delayMs == 0L) {
                postInvalidateOnAnimation()
            } else {
                // Expire even when detector/camera callbacks stop arriving.
                postDelayed(expireOverlay, delayMs)
            }
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

    private fun DebugOverlayBox.toPresentationBox(): DebugOverlayContinuity.Box = DebugOverlayContinuity.Box(
        rect = DebugOverlayContinuity.Rect(rectPx.left, rectPx.top, rectPx.right, rectPx.bottom),
        label = displayLabel(),
        best = best,
        className = className,
        held = held,
        smoothed = smoothed,
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
