package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT

/** Report views; report state and request handling remain with the caller. */
internal class UserReportPanel(
    context: Context,
    style: WalkSafeViewStyle,
    actions: Actions,
) {
    data class Actions(
        val onFilter: () -> Unit,
        val onRefresh: () -> Unit,
        val onRetry: () -> Unit,
        val onMore: () -> Unit,
        val onHistoryMore: () -> Unit,
        val onContent: () -> Unit,
        val onToggleDescriptionClear: () -> Unit,
        val onNextCategory: () -> Unit,
        val onCorrection: () -> Unit,
        val onDelete: () -> Unit,
        val onRequestStatus: () -> Unit,
        val onDeletionStatus: () -> Unit,
        val onLocalQuarantine: () -> Unit = {},
        val onLocalQuarantineDelete: () -> Unit = {},
    )

    val userReportStatusText: TextView = TextView(context).apply {
        id = View.generateViewId()
        text = "내 신고 상태: 로그인이 필요합니다."
        contentDescription = text
        textSize = 18f
        setTextColor(WS_COLOR_BUTTON_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val userReportFilterButton: Button = style.accessiblePriorityUserButton(
        label = "상태 필터: 전체",
        spokenLabel = "내 신고 상태 필터: 전체",
        onClick = actions.onFilter,
    )

    val userReportRefreshButton: Button = style.accessiblePriorityUserButton(
        label = "내 신고 새로고침",
        onClick = actions.onRefresh,
    )

    val userReportRetryButton: Button = style.accessiblePriorityUserButton(
        label = "내 신고 다시 시도",
        onClick = actions.onRetry,
    ).apply {
        visibility = View.GONE
    }

    val userReportMoreButton: Button = style.accessiblePriorityUserButton(
        label = "내 신고 더 보기",
        onClick = actions.onMore,
    ).apply {
        visibility = View.GONE
    }

    val userReportListContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val userReportHistoryStatusText: TextView = TextView(context).apply {
        id = View.generateViewId()
        text = "요청 이력: 로그인이 필요합니다."
        contentDescription = text
        textSize = 18f
        setTextColor(WS_COLOR_BUTTON_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val userReportHistoryContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val userReportHistoryMoreButton: Button = style.accessiblePriorityUserButton(
        label = "다음 요청 이력",
        onClick = actions.onHistoryMore,
    ).apply {
        visibility = View.GONE
    }

    val userReportDetailText: TextView = TextView(context).apply {
        id = View.generateViewId()
        text = "신고를 선택하면 상세 상태가 표시됩니다."
        contentDescription = text
        textSize = 16f
        setTextColor(WS_COLOR_BUTTON_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    val userReportRequestTextInput: EditText = EditText(context).apply {
        id = View.generateViewId()
        hint = "신고 삭제 요청 사유"
        contentDescription = "선택한 신고 한 건의 삭제 요청 사유"
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        maxLines = 6
        minimumHeight = style.accessibilityTargetSizePx()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val userReportContentButton: Button = style.accessiblePriorityUserButton(
        label = "선택 신고 내용 불러오기",
        onClick = actions.onContent,
    )

    val userReportCorrectionDescriptionInput: EditText = EditText(context).apply {
        id = View.generateViewId()
        hint = "정정할 신고 설명 (비워두면 기존값 유지)"
        contentDescription = "선택한 신고의 정정할 설명. 비워두면 기존값을 유지합니다."
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        maxLines = 6
        minimumHeight = style.accessibilityTargetSizePx()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val userReportCorrectionDescriptionClearButton: Button = style.accessiblePriorityUserButton(
        label = "설명: 기존값 유지",
        onClick = actions.onToggleDescriptionClear,
    )

    val userReportCorrectionCategoryButton: Button = style.accessiblePriorityUserButton(
        label = "분류: 기존값 유지",
        onClick = actions.onNextCategory,
    )

    val userReportCorrectionButton: Button = style.accessiblePriorityUserButton(
        label = "선택 신고 내용 정정",
        spokenLabel = "선택한 신고의 구조화된 내용 정정 확인",
        onClick = actions.onCorrection,
    )

    val userReportDeleteButton: Button = style.accessiblePriorityUserButton(
        label = "선택 신고 삭제 요청",
        spokenLabel = "선택한 신고 한 건의 삭제 요청 확인. 계정 전체 삭제가 아닙니다.",
        onClick = actions.onDelete,
    )

    val userReportRequestStatusButton: Button = style.accessiblePriorityUserButton(
        label = "선택 신고 요청 처리 상태 확인",
        spokenLabel = "선택한 신고의 정정 또는 삭제 요청 처리 상태 확인",
        onClick = actions.onRequestStatus,
    )

    val userReportDeletionStatusButton: Button = style.accessiblePriorityUserButton(
        label = "신고 물리 삭제·기관 보관본 상태 확인",
        onClick = actions.onDeletionStatus,
    )

    val localQuarantineStatusText = TextView(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        text = "기기 보관 신고: 확인을 눌러 기기에만 보관한 신고를 확인하세요."
        contentDescription = text
        textSize = 18f
        setTextColor(WS_COLOR_BUTTON_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }
    val localQuarantineButton = style.accessiblePriorityUserButton(
        label = "기기에 보관한 신고 확인", onClick = actions.onLocalQuarantine)
    val localQuarantineContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val localQuarantineDeleteButton = style.accessiblePriorityUserButton(
        label = "선택한 기기 보관 신고 삭제 확인", onClick = actions.onLocalQuarantineDelete).apply { isEnabled = false }

    val userReportControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(userReportStatusText)
        addView(userReportFilterButton)
        addView(userReportRefreshButton)
        addView(userReportRetryButton)
        addView(localQuarantineStatusText)
        addView(localQuarantineButton)
        addView(localQuarantineContainer)
        addView(localQuarantineDeleteButton)
        addView(userReportListContainer)
        addView(userReportMoreButton)
        addView(userReportHistoryStatusText)
        addView(userReportHistoryContainer)
        addView(userReportHistoryMoreButton)
        addView(userReportDetailText)
        addView(userReportContentButton)
        addView(userReportCorrectionDescriptionInput)
        addView(userReportCorrectionDescriptionClearButton)
        addView(userReportCorrectionCategoryButton)
        addView(userReportCorrectionButton)
        addView(userReportRequestTextInput)
        addView(userReportDeleteButton)
        addView(userReportRequestStatusButton)
        addView(userReportDeletionStatusButton)
    }
}
