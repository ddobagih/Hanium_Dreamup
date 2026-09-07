package kr.co.hanium.dreamup.walksafe.visualtest

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute

/** Shares the Activity's existing camera surface and guidance runtime. */
class VisualTestScreens(
    private val activity: Activity,
    private val root: FrameLayout,
    private val onVisibilityChanged: () -> Unit,
    private val refresh: () -> Unit,
) {
    @Volatile var active = false
        private set
    @Volatile var modelVisible = false
        private set
    private var panel: FrameLayout? = null
    private var map: VisualRouteMapView? = null
    private var boxes: DebugBboxOverlayView? = null
    private var modelText: TextView? = null
    private var lastModelAtMs = 0L
    private var lastModelCapturedAtMs = 0L
    private var lastModelText = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            refresh()
            if (modelVisible && lastModelAtMs > 0L) {
                val age = SystemClock.elapsedRealtime() - lastModelCapturedAtMs
                if (age > 800L) {
                    boxes?.clear()
                    modelText?.text = "인식 결과 대기 중 · 마지막 프레임 ${age}ms 전\n오래된 바운딩 박스와 거리는 숨겼습니다."
                } else {
                    modelText?.text = "현재 프레임 경과 ${age}ms\n$lastModelText"
                }
            }
            root.postDelayed(this, 250L)
        }
    }

    fun openModel() = open(true)
    fun openMap() = open(false)

    private fun open(model: Boolean) {
        close()
        active = true
        modelVisible = model
        val content = FrameLayout(activity).apply { isClickable = true }
        panel = content
        if (model) {
            boxes = DebugBboxOverlayView(activity).also { content.addView(it, fullSize()) }
            modelText = TextView(activity).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0xe6101828.toInt())
            }
            content.addView(ScrollView(activity).apply { addView(modelText) },
                FrameLayout.LayoutParams(-1, dp(200), Gravity.BOTTOM))
        } else {
            map = VisualRouteMapView(activity).also {
                content.addView(it, fullSize().apply { topMargin = dp(60) })
            }
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xf0101828.toInt())
            setPadding(dp(8), 0, dp(8), 0)
            addView(Button(activity).apply {
                text = "← 메인"
                contentDescription = "안내를 유지하며 메인 화면으로 돌아가기"
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(108), -1))
            addView(TextView(activity).apply {
                text = if (model) "객체 인식 · 테스트" else "TMap 경로 · 테스트"
                textSize = 18f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(header, FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP))
        root.addView(content, fullSize())
        onVisibilityChanged()
        ticker.run()
    }

    fun updateRoute(route: WalkingRoute?, location: TrustedLocation?, destinationName: String?, instruction: String, running: Boolean) {
        map?.update(route, location, destinationName, instruction, running)
    }

    fun updateModel(mapped: List<DebugBboxOverlayView.DebugOverlayBox>, detail: String, ageMs: Long?) {
        if (!modelVisible) return
        lastModelAtMs = SystemClock.elapsedRealtime()
        lastModelCapturedAtMs = lastModelAtMs - (ageMs ?: 0L).coerceAtLeast(0L)
        lastModelText = detail
        boxes?.updateMapped(mapped)
        modelText?.text = detail
    }

    fun modelUnavailable(reason: String) {
        if (!modelVisible) return
        lastModelAtMs = 0L
        boxes?.clear()
        modelText?.text = reason
    }

    fun close(): Boolean {
        if (!active) return false
        active = false
        modelVisible = false
        root.removeCallbacks(ticker)
        map?.dispose()
        map = null
        panel?.let(root::removeView)
        panel = null
        boxes = null
        modelText = null
        lastModelAtMs = 0L
        onVisibilityChanged()
        return true
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun fullSize() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
