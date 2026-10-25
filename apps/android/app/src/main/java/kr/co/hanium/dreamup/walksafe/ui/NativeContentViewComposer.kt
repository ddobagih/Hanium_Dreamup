package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_OVERLAY_FILL

/** SDK layout and style assembly; publication and authority remain with the calling owners. */
internal class NativeContentViewComposer(private val context: Context, private val style: WalkSafeViewStyle) {
    fun readOverlayInsets(bottomDp: Float, horizontalDp: Float, topDp: Float): NativeOverlayInsets =
        NativeOverlayInsets.read(bottomDp, horizontalDp, topDp) { context.resources.displayMetrics.density }

    fun createOverlayContainer(horizontal: Int, top: Int, bottom: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(horizontal, top, horizontal, bottom)
        setBackgroundColor(WS_COLOR_OVERLAY_FILL)
    }

    fun createDevelopmentNotice(): TextView = TextView(context).apply {
        text = "개발 전용 기능 테스트 · 실외 보행 안전 검증용이 아닙니다.\n" +
            "초기 로그인 화면·기기 점검·최초 장착 확인만 생략합니다. " +
            "최초 장착 확인은 개발용 가정이며, 환경 확인과 실제 센서 판정·사용 중 재점검은 유지합니다. " +
            "테스트 시작 시 사진·위치·자동 신고를 로컬 테스트 서버에 전송하도록 설정합니다."
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        setPadding(0, 16, 0, 16)
    }

    fun createDevelopmentStatus(): TextView = TextView(context).apply {
        text = "실제 테스트 계정 연결을 준비합니다."
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
    }

    fun createDevelopmentAction(onStart: () -> Unit): Button = style.accessiblePriorityUserButton(
        label = "개발 기능 테스트 시작 / 다시 시도",
        emphasis = true,
        onClick = onStart,
    )

    fun styleStatusCards(phonePosture: TextView, startup: TextView, deviceCheck: TextView, destination: TextView) {
        listOf(phonePosture, startup, deviceCheck, destination).forEach(style::applyWsStatusCard)
    }

    fun composeRootFrame(layers: NativeContentAssembly.Layers<View>, accessibilityStatus: () -> View): FrameLayout =
        NativeContentAssembly.compose(layers, object : NativeContentAssembly.Target<View> {
            override fun createContentFrame(): View = FrameLayout(context)
            override fun addFullscreen(parent: View, child: View, topAligned: Boolean) {
                val params = if (topAligned) FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP,
                ) else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                (parent as FrameLayout).addView(child, params)
            }
            override fun addCameraControls(parent: View, child: View) {
                (parent as FrameLayout).addView(child, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
                ))
            }
            override fun createRootFrame(): View = FrameLayout(context)
            override fun createStatusColumn(): View = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            override fun addWeightedContent(column: View, content: View) {
                (column as LinearLayout).addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            override fun addAccessibilityRow(column: View, status: View) {
                (column as LinearLayout).addView(status, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            override fun attachStatusColumn(root: View, column: View) {
                (root as FrameLayout).addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }, accessibilityStatus) as FrameLayout
}
