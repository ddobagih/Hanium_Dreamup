package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.R

import kotlin.math.roundToInt

/** Constructs camera views at explicit publication stages; stores no domain state. */
internal class NativeCameraViewComposer(private val context: Context, private val style: WalkSafeViewStyle) {
    fun createSurface(renderer: GLSurfaceView.Renderer): GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun createFallbackPreview(): PreviewView = PreviewView(context).apply {
        // This preview follows display rotation; let CameraX retain device-specific fallbacks
        // while using a separate surface when supported instead of forcing texture composition.
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun createDebugOverlay(): DebugBboxOverlayView = DebugBboxOverlayView(context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun createCameraStatus(): TextView = TextView(context).apply {
        setTextColor(android.graphics.Color.WHITE)
        textSize = 15f
        setPadding(16, 12, 16, 12)
        setBackgroundColor(0xcc000000.toInt())
        text = "카메라 준비 중 · 객체와 깊이 데이터를 기다리고 있습니다."
    }

    fun createCameraControls(status: TextView, onClose: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(style.accessiblePriorityUserButton(
            label = "카메라 닫기",
            onClick = onClose,
        ))
        addView(ScrollView(context).apply { addView(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (220 * context.resources.displayMetrics.density).roundToInt()))
    }

    fun createRecognitionStatus(): TextView = createCameraStatus().apply {
        text = "현재 인식 결과를 기다리고 있습니다."
        textSize = 16f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    fun createRecognitionControls(status: TextView, onClose: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(0xee101820.toInt())
        ViewCompat.setAccessibilityPaneTitle(this, "길안내 인식 현황")
        addView(style.accessiblePriorityUserButton("인식 현황 닫기", onClick = onClose).apply {
            contentDescription = "인식 현황 닫기. 길안내 버튼 화면으로 돌아갑니다."
        })
        addView(ScrollView(context).apply { addView(status) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (180 * context.resources.displayMetrics.density).roundToInt()))
    }
}
