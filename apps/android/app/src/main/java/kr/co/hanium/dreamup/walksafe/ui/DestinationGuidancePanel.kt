package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_WARNING
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_CORNER_RADIUS_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_TOUCH_WALK_ACTION_DP
import kotlin.math.roundToInt
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult

/** Destination, guidance and voice views; route and speech decisions stay with the caller. */
internal class DestinationGuidancePanel(
    private val context: Context,
    private val style: WalkSafeViewStyle,
    actions: Actions,
) : DestinationSearchPresenter.RenderTarget {
    data class Actions(
        val onSearch: () -> Unit,
        val onLoadMore: () -> Unit,
        val onSearchCancel: () -> Unit,
        val onStart: () -> Unit,
        val onGuidanceCancel: () -> Unit,
        val onVoiceStart: () -> Unit,
        val onVoiceCancel: () -> Unit,
        val onRoute: () -> Unit,
        val onReroute: () -> Unit,
        val onRecheck: () -> Unit,
        val onEndDeviation: () -> Unit,
        val onPause: () -> Unit = {},
        val onTouchGuard: () -> Unit = {},
        val onRecognitionDisplay: () -> Unit = {},
    )

    val voiceReportButton = Button(context).apply {
        text = "음성 명령"
        contentDescription = "음성 명령 듣기 시작"
        minimumHeight = style.accessibilityTargetSizePx()
        isSingleLine = false
        setOnClickListener { actions.onVoiceStart() }
    }

    val gatewayVoiceStatusText = TextView(context).apply {
        text = "음성 명령 대기"
        contentDescription = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    val destinationQueryInput = EditText(context).apply {
        hint = "검색어"
        contentDescription = "목적지 입력"
        textSize = 20f
        minimumHeight =
            (WS_TOUCH_WALK_ACTION_DP * context.resources.displayMetrics.density).roundToInt()
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val destinationSearchButton = Button(context).apply {
        text = "목적지 검색"
        setOnClickListener { actions.onSearch() }
    }

    val destinationCancelButton = Button(context).apply {
        text = "검색 취소"
        isEnabled = true
        setOnClickListener { actions.onSearchCancel() }
    }

    val destinationSearchResultsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val destinationMoreButton = Button(context).apply {
        text = "결과 더보기"
        isEnabled = false
        visibility = View.GONE
        setOnClickListener { actions.onLoadMore() }
    }

    val routeButton = Button(context).apply {
        text = "경로 시작"
        setOnClickListener { actions.onRoute() }
    }

    val routeDeviationNewRouteButton = Button(context).apply {
        text = "새 경로 요청"
        contentDescription = text
        setOnClickListener { actions.onReroute() }
    }

    val routeDeviationRecheckButton = Button(context).apply {
        text = "위치 다시 확인"
        contentDescription = text
        setOnClickListener { actions.onRecheck() }
    }

    val routeDeviationEndButton = Button(context).apply {
        text = "길안내 종료"
        contentDescription = text
        setOnClickListener { actions.onEndDeviation() }
    }

    val offRouteNoticeText = TextView(context).apply {
        textSize = 18f
        typeface = style.wsTypeface(Typeface.BOLD)
        setTextColor(WS_COLOR_WARNING)
        setLineSpacing(0f, 1.45f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val routeDeviationActions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        val density = context.resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = WS_CORNER_RADIUS_DP * density
            setStroke((1f * density).roundToInt(), WS_COLOR_WARNING)
        }
        setPadding(
            (16f * density).roundToInt(),
            (16f * density).roundToInt(),
            (16f * density).roundToInt(),
            (16f * density).roundToInt(),
        )
        addView(offRouteNoticeText)
        addView(routeDeviationNewRouteButton)
        addView(routeDeviationRecheckButton)
        addView(routeDeviationEndButton)
    }

    val nativeDestinationConfirmationText = TextView(context).apply {
        textSize = 20f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val nativeDestinationStartButton = style.accessiblePriorityUserButton(
        label = "안내 시작",
        emphasis = true,
        onClick = actions.onStart,
    )

    val nativeDestinationCancelButton = style.accessiblePriorityUserButton(
        label = "안내 취소",
        onClick = actions.onSearchCancel,
    )

    val nativeGuidanceDestinationText = TextView(context).apply {
        textSize = 24f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val nativeGuidanceStatusText = TextView(context).apply {
        textSize = 20f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        setLineSpacing(0f, 1.35f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    val nativeGuidanceMeasurementText = TextView(context).apply {
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    val nativeGuidanceRetryButton = style.accessiblePriorityUserButton(
        label = "다시 시도",
        emphasis = true,
        onClick = actions.onStart,
    )

    val nativeGuidanceCancelButton = style.accessiblePriorityUserButton(
        label = "안내 종료",
        onClick = actions.onGuidanceCancel,
    )

    val nativeVoiceCancelButton = style.accessiblePriorityUserButton(
        label = "음성 명령 취소",
        onClick = actions.onVoiceCancel,
    )

    val nativeDestinationControls = style.walkSection(
        null,
        style.wsFieldGroup(destinationQueryInput, "목적지"),
        destinationSearchButton,
        destinationCancelButton,
        destinationSearchResultsContainer,
        destinationMoreButton,
    )

    val nativeDestinationConfirmationControls = style.walkSection(
        null,
        nativeDestinationConfirmationText,
        nativeDestinationStartButton,
        nativeDestinationCancelButton,
    )

    val nativeGuidanceVoiceButton = style.accessiblePriorityUserButton("음성 명령", emphasis = true, onClick = actions.onVoiceStart)
    val nativeGuidancePauseButton = style.accessiblePriorityUserButton("일시정지", onClick = actions.onPause)
    val guidanceTouchGuardButton = style.accessiblePriorityUserButton("터치 오작동 방지", onClick = actions.onTouchGuard)
    val guidanceRecognitionButton = style.accessiblePriorityUserButton("인식 현황 보기", onClick = actions.onRecognitionDisplay)

    init {
        listOf(nativeGuidanceVoiceButton, nativeGuidancePauseButton, nativeGuidanceCancelButton,
            guidanceTouchGuardButton).forEachIndexed { index, button ->
            style.applyWsButtonStyle(button, 120f, primary = index == 0)
            button.textSize = 24f
            button.id = View.generateViewId()
        }
        nativeGuidancePauseButton.accessibilityTraversalAfter = nativeGuidanceVoiceButton.id
        nativeGuidanceCancelButton.accessibilityTraversalAfter = nativeGuidancePauseButton.id
        guidanceTouchGuardButton.accessibilityTraversalAfter = nativeGuidanceCancelButton.id
        guidanceRecognitionButton.id = View.generateViewId()
        guidanceRecognitionButton.accessibilityTraversalAfter = guidanceTouchGuardButton.id
        guidanceRecognitionButton.contentDescription = "인식 현황 보기. 현재 카메라와 인식 결과를 화면에 표시합니다."
    }

    val nativeGuidanceControls = style.walkSection(
        null,
        nativeGuidanceVoiceButton,
        nativeGuidancePauseButton,
        nativeGuidanceCancelButton,
        guidanceTouchGuardButton,
        guidanceRecognitionButton,
        nativeGuidanceDestinationText,
        nativeGuidanceStatusText,
        nativeGuidanceMeasurementText,
        nativeGuidanceRetryButton,
    )

    val nativeVoiceControls = style.walkSection(
        null,
        voiceReportButton,
        nativeVoiceCancelButton,
    )

    val routeDeviationSection = style.walkSection(null, routeDeviationActions).apply {
        visibility = View.GONE
    }

    val runtimeControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(gatewayVoiceStatusText)
        addView(nativeDestinationControls)
        addView(nativeDestinationConfirmationControls)
        addView(nativeGuidanceControls)
        addView(nativeVoiceControls)
    }

    private var voiceRangeHeader: TextView? = null
    override val hasVoiceRangeHeader: Boolean get() = voiceRangeHeader != null

    override fun clearSearchResults() {
        destinationSearchResultsContainer.removeAllViews()
        destinationSearchButton.contentDescription = "목적지 검색"
    }

    override fun renderEmptySearch(message: String, statusCard: Boolean) {
        destinationSearchResultsContainer.addView(TextView(context).apply {
            text = message
            destinationSearchButton.contentDescription = "목적지 검색. $text"
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            if (statusCard) style.applyWsStatusCard(this)
        })
    }

    override fun renderSearchResults(
        originNotice: String?,
        candidates: List<DestinationSearchPresenter.Candidate>,
        voiceRange: String?,
        onSelected: (DestinationSearchResult) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        destinationSearchButton.contentDescription = "목적지 검색"
        originNotice?.let { notice ->
            destinationSearchResultsContainer.addView(TextView(context).apply {
                text = notice
                contentDescription = notice
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                textSize = 18f
                setTextColor(WS_COLOR_NOTICE_TEXT)
                style.applyWsStatusCard(this)
            })
        }
        voiceRangeHeader = TextView(context).apply {
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
        }.also { header ->
            destinationSearchResultsContainer.addView(header)
            // Preserve the legacy order: refresh the previously stored header before assigning this one.
            renderVoiceRange(voiceRange)
        }
        candidates.forEach { candidate ->
            val label = android.text.SpannableStringBuilder("${candidate.number}번 ${candidate.result.name}").apply {
                append("\n")
                val detailsStart = length
                append(candidate.details)
                setSpan(android.text.style.AbsoluteSizeSpan(18, true), detailsStart, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(WS_COLOR_NOTICE_TEXT), detailsStart, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            destinationSearchResultsContainer.addView(Button(context).apply {
                style.applyWsButtonStyle(this, WS_TOUCH_WALK_ACTION_DP)
                style.applyNativePanelStyle(this)
                text = label
                textSize = 22f
                setTextColor(0xff1b1b1d.toInt())
                isAllCaps = false
                setSingleLine(false)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minimumHeight = (200 * density).toInt()
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * density).toInt() }
                setOnClickListener { onSelected(candidate.result) }
            })
        }
    }

    override fun renderMoreResults(visible: Boolean) {
        destinationMoreButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun renderVoiceRange(label: String?) { voiceRangeHeader?.let { applyVoiceRange(it, label) } }

    private fun applyVoiceRange(header: TextView, label: String?) {
        header.text = label.orEmpty()
        header.contentDescription = label
        header.visibility = View.GONE
        header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun renderSearchAvailability(searchAvailable: Boolean, inFlight: Boolean, routeAvailable: Boolean, resultsReady: Boolean) {
        destinationQueryInput.isEnabled = searchAvailable && !inFlight
        destinationSearchButton.isEnabled = searchAvailable && !inFlight
        destinationCancelButton.isEnabled = true
        destinationMoreButton.isEnabled = searchAvailable && !inFlight
        routeButton.isEnabled = routeAvailable
        if (resultsReady) {
            for (index in 0 until destinationSearchResultsContainer.childCount) {
                val child = destinationSearchResultsContainer.getChildAt(index)
                if (child is Button) child.isEnabled = searchAvailable && !inFlight
            }
        }
        destinationQueryInput.hint = if (searchAvailable) "목적지 검색" else "필수 상태 확인 · 목적지 검색 사용 불가"
        destinationQueryInput.contentDescription = if (searchAvailable) "목적지 입력"
            else "필수 기능과 로그인 상태를 확인한 뒤 목적지와 경로 안내를 사용할 수 있습니다"
    }

    override fun renderGuidanceStatus(message: String) {
        if (nativeGuidanceStatusText.text?.toString() != message) nativeGuidanceStatusText.text = message
    }

    override fun renderGuidanceMeasurements(message: String) {
        if (nativeGuidanceMeasurementText.text?.toString() != message) nativeGuidanceMeasurementText.text = message
    }
}
