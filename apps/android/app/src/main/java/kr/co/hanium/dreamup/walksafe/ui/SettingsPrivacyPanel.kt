package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_EMPHASIS

/** Settings views; consent, deletion, and session decisions remain with the caller. */
internal class SettingsPrivacyPanel(
    context: Context,
    style: WalkSafeViewStyle,
    actions: Actions,
    props: Props,
) {
    data class Props(
        val reportPrivacyDisclosure: String,
    )

    data class Actions(
        val onReportConsent: () -> Unit,
        val onAutomaticConsent: () -> Unit,
        val onMobileNetworkPreference: () -> Unit,
        val onTrainingReuseConsent: () -> Unit,
        val onPrivacyRights: () -> Unit,
        val onDeletionRequest: () -> Unit,
        val onDeletionConfirm: () -> Unit,
        val onDeletionCancel: () -> Unit,
        val onDeletionRefresh: () -> Unit,
        val onGatewaySession: () -> Unit,
        val onLogout: () -> Unit,
        val onHome: () -> Unit,
        val onVoiceDataSettings: () -> Unit,
        val onGuidanceDiagnostics: () -> Unit = {},
        val onDeletionPasswordLogin: () -> Unit = {},
    )

    val privacyConsentStatusText: TextView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 16f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val reportPrivacyDisclosureText: TextView = TextView(context).apply {
        text = props.reportPrivacyDisclosure
        textSize = 13f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val reportPrivacyConsentButton: Button = Button(context).apply {
        setOnClickListener { actions.onReportConsent() }
    }

    val automaticReportConsentButton: Button = Button(context).apply {
        setOnClickListener { actions.onAutomaticConsent() }
    }

    val mobileNetworkPreferenceButton: Button = Button(context).apply {
        setOnClickListener { actions.onMobileNetworkPreference() }
    }

    val trainingReuseConsentButton: Button = Button(context).apply {
        setOnClickListener { actions.onTrainingReuseConsent() }
    }

    val guidanceDiagnosticButton: Button? = if (BuildConfig.DEBUG &&
        BuildConfig.WALKSAFE_GUIDANCE_DIAGNOSTIC_BUILD_ID != "disabled") {
        style.accessiblePriorityUserButton(label = "길 안내 문제 기록: 확인 중", onClick = actions.onGuidanceDiagnostics)
    } else null

    private val guidanceDiagnosticDisclosure = TextView(context).apply {
        text = "길 안내 문제 기록에는 센서 품질, 처리 단계, 음성 시작·취소 원인을 담습니다. " +
            "좌표·목적지·사진·녹음은 보내지 않습니다. 서버에서 14일 후 삭제합니다."
        textSize = 13f
        setTextColor(WS_COLOR_EMPHASIS)
    }

    val privacyRightsButton: Button = Button(context).apply {
        text = "개인정보 열람·동의 철회·서버 자료 삭제 요청"
        setOnClickListener { actions.onPrivacyRights() }
    }

    val accountDeletionStatusText: TextView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 16f
        setTextColor(WS_COLOR_EMPHASIS)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val accountDeletionRequestButton: Button = style.accessiblePriorityUserButton(
        label = "계정 삭제",
        onClick = actions.onDeletionRequest,
    )

    val accountDeletionConfirmButton: Button = style.accessiblePriorityUserButton(
        label = "계정과 개인정보 삭제 요청 확인",
        onClick = actions.onDeletionConfirm,
    )

    val accountDeletionCancelButton: Button = style.accessiblePriorityUserButton(
        label = "계정 삭제 취소",
        onClick = actions.onDeletionCancel,
    )

    val accountDeletionRefreshButton: Button = style.accessiblePriorityUserButton(
        label = "삭제 진행 상태 새로고침",
        onClick = actions.onDeletionRefresh,
    )

    val deletionRecoveryNoticeText: TextView = TextView(context).apply {
        textSize = 16f
        setTextColor(WS_COLOR_EMPHASIS)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    val deletionRecoveryEmailInput: EditText = EditText(context).apply {
        hint = "삭제할 계정의 이메일"
        contentDescription = hint
        textSize = 18f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        isSaveEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    val deletionRecoveryPasswordInput: EditText = EditText(context).apply {
        hint = "비밀번호"
        contentDescription = "삭제할 계정의 비밀번호"
        textSize = 18f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSaveEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    val deletionRecoveryPasswordButton: Button = style.accessiblePriorityUserButton(
        label = "비밀번호 확인 후 삭제 요청 계속",
        onClick = actions.onDeletionPasswordLogin,
    )

    val deletionPasswordRecoveryControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(deletionRecoveryNoticeText)
        addView(deletionRecoveryEmailInput)
        addView(deletionRecoveryPasswordInput)
        addView(deletionRecoveryPasswordButton)
    }

    val accountDeletionItemTexts: Map<DeletionInventoryItem, TextView> =
        DeletionInventoryItem.entries.associateWith {
            TextView(context).apply {
                id = View.generateViewId()
                textSize = 14f
                setTextColor(WS_COLOR_BUTTON_TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        }

    val backendUrlInput: EditText? = if (BuildConfig.DEBUG) {
        EditText(context).apply {
            hint = "WalkSafe Gateway URL"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isEnabled = BuildConfig.DEBUG
            isFocusable = BuildConfig.DEBUG
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    } else {
        null
    }

    val backendFieldTokenInput: EditText = EditText(context).apply {
        hint = "현장 named-account token (저장 안 함)"
        textSize = 12f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    val backendAuthApplyButton: Button = Button(context).apply {
        setOnClickListener { actions.onGatewaySession() }
    }

    val nativeSettingsTitle: TextView = TextView(context).apply {
        text = "설정"
        textSize = 26f
        setTextColor(WS_COLOR_EMPHASIS)
        typeface = style.wsTypeface(Typeface.NORMAL)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    val nativeSettingsLogoutButton: Button = style.accessiblePriorityUserButton(
        label = "계정 로그아웃",
        onClick = actions.onLogout,
    )

    val nativeSettingsHomeButton: Button = style.accessiblePriorityUserButton(
        label = "홈으로",
        emphasis = true,
        onClick = actions.onHome,
    )

    val nativeSettingsVoiceDataButton: Button = Button(context).apply {
        text = "한국어 음성 설정"
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { actions.onVoiceDataSettings() }
    }

    val privacySettingsControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(privacyConsentStatusText)
        addView(nativeSettingsVoiceDataButton)
        addView(mobileNetworkPreferenceButton)
        addView(automaticReportConsentButton)
        addView(reportPrivacyConsentButton)
        addView(trainingReuseConsentButton)
        guidanceDiagnosticButton?.let { addView(guidanceDiagnosticDisclosure); addView(it) }
    }

    val accountDeletionControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(accountDeletionStatusText)
        addView(deletionPasswordRecoveryControls)
        addView(accountDeletionRequestButton)
        addView(accountDeletionConfirmButton)
        addView(accountDeletionCancelButton)
        addView(accountDeletionRefreshButton)
    }

    val gatewaySessionControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        backendUrlInput?.let { addView(it) }
        addView(backendFieldTokenInput)
        addView(backendAuthApplyButton)
    }

    val privacyControls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(nativeSettingsTitle)
        addView(privacySettingsControls)
        addView(accountDeletionControls)
        addView(gatewaySessionControls)
        addView(nativeSettingsLogoutButton)
        addView(nativeSettingsHomeButton)
    }
}
