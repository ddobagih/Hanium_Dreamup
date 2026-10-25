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
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_WALK_OVERLAY_FILL
import kotlin.math.roundToInt

/** Constructs runtime views at explicit publication stages; stores no domain state. */
internal class NativeRuntimeViewComposer(private val context: Context, private val style: WalkSafeViewStyle) {
    fun createSafetySummary(): TextView = TextView(context).apply {
        id = View.generateViewId()
        text = context.getString(R.string.walk_safety_preparing)
        textSize = 20f
        setTextColor(WS_COLOR_BUTTON_TEXT)
        contentDescription = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    fun createRuntimeStatus(): TextView = TextView(context).apply {
        textSize = 24f
        typeface = style.wsTypeface(Typeface.BOLD)
        setLineSpacing(0f, 1.3f)
        setTextColor(WS_COLOR_EMPHASIS)
        contentDescription = "WalkSafe 상태"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // This text changes every 500 ms. Safety speech uses explicit announcements below.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    fun createDiagnosticDetail(): TextView = TextView(context).apply {
        textSize = 18f
        setLineSpacing(0f, 1.45f)
        setTextColor(WS_COLOR_NOTICE_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun createNavigationStatus(): TextView = TextView(context).apply {
        textSize = 14f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        text = "navigation=destination_none hazard_only"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun createLegacyLoginInput(initialReporterId: () -> String?): EditText = EditText(context).apply {
        hint = "교육 대상 로그인 ID"
        textSize = 16f
        setSingleLine(true)
        setText(initialReporterId().orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun createLegacyLoginAction(onClick: () -> Unit): Button = Button(context).apply {
        setOnClickListener { onClick() }
    }

    fun createLegacyLogoutAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "사용자 로그아웃"
        setOnClickListener { onClick() }
    }

    fun createDepthAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "ARCore Depth 시작"
        isEnabled = false
        setOnClickListener { onClick() }
    }

    fun createDebugUploadAction(onClick: () -> Unit): Button = Button(context).apply {
        setOnClickListener { onClick() }
    }

    fun createDebugCaptureAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "이미지 1장 캡쳐"
        setOnClickListener { onClick() }
    }

    fun createFieldSessionAction(onClick: () -> Unit): Button = Button(context).apply {
        setOnClickListener { onClick() }
    }

    fun createPositionRouteInput(): EditText = EditText(context).apply {
        hint = "위치 평가용 opaque route UUID"
        inputType = InputType.TYPE_CLASS_TEXT
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun createPositionExportConfirmation(): CheckBox = CheckBox(context).apply {
        text = "정확한 좌표가 평문 JSONL로 내보내지는 것을 확인했습니다"
    }

    fun createPositionCalibrationConfirmation(): CheckBox = CheckBox(context).apply {
        text = "휴대전화를 가슴 중앙에 고정하고 figure-8 자력계 보정을 완료했습니다"
    }

    fun createPositionStartAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "위치 평가 기록 시작"
        setOnClickListener { onClick() }
    }

    fun createPositionCheckpointAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "위치 기준점 기록"
        setOnClickListener { onClick() }
    }

    fun createPositionStopAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "위치 평가 기록 종료"
        setOnClickListener { onClick() }
    }

    fun createPositionExportAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "마지막 위치 평가 세션 내보내기"
        setOnClickListener { onClick() }
    }

    fun createPositionDeleteAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "위치 평가 기록 전체 삭제"
        setOnClickListener { onClick() }
    }

    fun createReportConfirmation(): TextView = TextView(context).apply {
        textSize = 16f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }

    fun createReportAction(onClick: () -> Unit, queueAvailable: () -> Boolean): Button = Button(context).apply {
        val queueAvailable = queueAvailable()
        text = if (queueAvailable) {
            "손상 점자블록 직접 신고"
        } else {
            "손상 점자블록 신고 저장 기능 준비 중"
        }
        contentDescription = if (queueAvailable) {
            "손상 점자블록 직접 신고. 누르면 신고 사진과 정확한 위치 전송 내용을 확인합니다."
        } else {
            "현재 앱 빌드에서는 손상 점자블록 신고 저장 기능을 사용할 수 없습니다."
        }
        isEnabled = queueAvailable
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onClick() }
    }

    fun createWalkVoiceAction(onClick: () -> Unit): Button = Button(context).apply {
        id = View.generateViewId()
        text = "기기 내 음성 명령"
        contentDescription = "기기 내 음성 명령 듣기 시작"
        minimumHeight = style.accessibilityTargetSizePx()
        minimumWidth = style.accessibilityTargetSizePx()
        isSingleLine = false
        setOnClickListener { onClick() }
    }

    fun createWalkVoiceStatus(): TextView = TextView(context).apply {
        id = View.generateViewId()
        text = "기기 내 음성 명령 대기"
        contentDescription = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    fun createDestinationLatitudeInput(): EditText = EditText(context).apply {
        hint = "목적지 위도"
        textSize = 12f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun createDestinationLongitudeInput(): EditText = EditText(context).apply {
        hint = "목적지 경도"
        textSize = 12f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun createRouteResetAction(onClick: () -> Unit): Button = Button(context).apply {
        text = "경로 초기화"
        setOnClickListener { onClick() }
    }

    fun createProgressToggleAction(onClick: () -> Unit): Button = Button(context).apply {
        setOnClickListener { onClick() }
    }

    fun createProgressVolumeAction(onClick: () -> Unit): Button = Button(context).apply {
        setOnClickListener { onClick() }
    }

    fun createLastWalkResult(): TextView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        setLineSpacing(0f, 1.45f)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    fun createWalkStatusSection(safetySummary: TextView, runtimeStatus: TextView, diagnosticDetail: TextView, lastResult: TextView): LinearLayout = style.walkSection(
        "보행 상태",
        safetySummary,
        runtimeStatus,
        diagnosticDetail,
        lastResult,
    ).apply {
        visibility = View.GONE
    }

    fun createWalkSafetyOverlay(horizontalPadding: Int, topPadding: Int, bottomPadding: Int, voiceStatus: TextView, voiceAction: Button): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(
            horizontalPadding,
            topPadding,
            horizontalPadding,
            bottomPadding,
        )
        setBackgroundColor(WS_COLOR_WALK_OVERLAY_FILL)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(voiceStatus)
        addView(voiceAction)
    }

    fun createWalkSafetyScroll(overlay: LinearLayout): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun createControlsScroll(overlay: LinearLayout, onLayoutChanged: () -> Unit): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            onLayoutChanged()
        }
    }

    data class DeveloperControls(
        val navigation: TextView, val depth: Button, val upload: Button, val capture: Button,
        val routeId: EditText, val exactExport: CheckBox, val calibration: CheckBox,
        val start: Button, val checkpoint: Button, val stop: Button, val export: Button, val delete: Button,
        val latitude: EditText, val longitude: EditText,
    )

    fun createRuntimeDebugControls(developer: DeveloperControls?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        if (developer != null) {
            addView(developer.navigation)
            addView(developer.depth)
            addView(developer.upload)
            addView(developer.capture)
            addView(developer.routeId)
            addView(developer.exactExport)
            addView(developer.calibration)
            addView(developer.start)
            addView(developer.checkpoint)
            addView(developer.stop)
            addView(developer.export)
            addView(developer.delete)
            addView(developer.latitude)
            addView(developer.longitude)
        }
    }
}
