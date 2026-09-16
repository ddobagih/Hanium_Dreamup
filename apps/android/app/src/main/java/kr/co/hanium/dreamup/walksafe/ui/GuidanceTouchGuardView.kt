package kr.co.hanium.dreamup.walksafe.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Touch-only shield: never stops navigation, speech, sensors, or the walk session. */
@SuppressLint("ClickableViewAccessibility")
internal class GuidanceTouchGuardView(context: Context, onUnlock: () -> Unit) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hold = ContinuousTouchHold()
    private var activePointer = -1
    private var heldKey = -1
    private val releaseButton = Button(context)
    private val completeHold = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && isShown && hasWindowFocus() && hold.ready(SystemClock.elapsedRealtime())) {
                cancelHold()
                onUnlock()
            }
        }
    }

    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply {
            text = "터치 오작동 방지 중"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(32) })
        releaseButton.apply {
            text = "3초간 눌러 해제"
            contentDescription = "터치 오작동 방지 해제. 3초간 누르세요. TalkBack 사용 시 두 번 누르되 두 번째 누름을 3초간 유지하세요."
            textSize = 26f
            isAllCaps = false
            gravity = Gravity.CENTER
            minimumHeight = dp(200)
            setTextColor(Color.WHITE)
            backgroundTintList = null
            background = GradientDrawable().apply {
                setColor(0xff1b4cd8.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelHold()
                        if (event.pointerCount == 1) {
                            activePointer = event.getPointerId(0)
                            beginHold()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointer)
                        if (event.pointerCount != 1 || index < 0 ||
                            event.getX(index) < 0 || event.getX(index) >= width ||
                            event.getY(index) < 0 || event.getY(index) >= height) cancelHold()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelHold()
                }
                true
            }
            // A click (including TalkBack double-tap) deliberately cannot unlock the shield.
            setOnClickListener { announceForAccessibility(contentDescription) }
            setOnKeyListener { _, key, event ->
                if (key !in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        cancelHold()
                        heldKey = key
                        beginHold()
                    } else if (event.action == KeyEvent.ACTION_UP && heldKey == key) cancelHold()
                    true
                }
            }
        }
        addView(releaseButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun beginHold() {
        hold.begin(SystemClock.elapsedRealtime())
        releaseButton.isPressed = true
        releaseButton.text = "계속 눌러 주세요"
        handler.postDelayed(completeHold, 3_000L)
    }

    fun cancelHold() {
        handler.removeCallbacks(completeHold)
        hold.cancel()
        activePointer = -1
        heldKey = -1
        releaseButton.isPressed = false
        releaseButton.text = "3초간 눌러 해제"
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelHold()
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        super.onDetachedFromWindow()
    }
}
