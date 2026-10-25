package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT

/** Owns account controls; enrollment, consent authority and speech remain with the caller. */
internal class AccountAccessPanel(
    context: Context,
    style: WalkSafeViewStyle,
    private val actions: Actions,
) {
    data class Actions(
        val onInputChanged: () -> Unit,
        val onConsentChanged: () -> Unit,
        val onListenConsent: (String) -> Unit,
        val onConsentContinue: () -> Unit,
        val onRequestOtp: () -> Unit,
        val onCreateAccount: () -> Unit,
        val onLogin: () -> Unit,
        val onLogout: () -> Unit,
        val onSignupToggle: () -> Unit,
        val onDisclosureToggle: () -> Unit,
    )

    val accountAccessStatusText: TextView
    val accountEmailInput: EditText
    val accountDateOfBirthInput: EditText
    val accountPasswordInput: EditText
    val accountPasswordConfirmationInput: EditText
    val accountOtpInput: EditText
    val accountConsentDisclosureText: TextView
    val accountConsentAllCheck: CheckBox
    val accountConsentSummaryText: TextView
    val accountConsentContinueButton: Button
    val accountRequestOtpButton: Button
    val accountCreateButton: Button
    val accountLoginButton: Button
    val accountSessionLogoutButton: Button
    val accountSignupToggleButton: Button
    val accountConsentDisclosureToggleButton: Button
    val accountConsentStepControls: LinearLayout
    val accountDetailsStepControls: LinearLayout
    val accountSignupBackButton: Button
    val accountSignupControls: LinearLayout
    val accountLandingHeader: LinearLayout
    val accountAccessControls: LinearLayout
    private val consentChecks = mutableMapOf<String, CheckBox>()
    private var unavailableOptionalDocuments: Set<String> = emptySet()
    private val bundledClauseTexts = linkedMapOf<String, String>()
    private val bundledVersions = linkedMapOf<String, String>()
    val accountConsentChecks: Map<String, CheckBox> get() = consentChecks
    private val consentCards = linkedMapOf<String, LinearLayout>()
    val accountConsentCards: Map<String, LinearLayout> get() = consentCards
    private val consentClauseTexts = linkedMapOf<String, TextView>()
    val accountConsentClauseTexts: Map<String, TextView> get() = consentClauseTexts
    private val consentListenButtons = linkedMapOf<String, Button>()
    val accountConsentListenButtons: Map<String, Button> get() = consentListenButtons
    val root: LinearLayout get() = accountAccessControls

    init {
        accountAccessStatusText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(accountAccessStatusText, true)
        accountEmailInput = EditText(context).apply {
            id = View.generateViewId()
            hint = "이메일"
            contentDescription = "계정 이메일 입력"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        accountDateOfBirthInput = EditText(context).apply {
            id = View.generateViewId()
            hint = "생년월일 (예: 20020101)"
            contentDescription = "생년월일 연도 네 자리 월 두 자리 일 두 자리, 하이픈 없이 입력 가능"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_DATETIME or
                InputType.TYPE_DATETIME_VARIATION_DATE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        accountPasswordInput = EditText(context).apply {
            id = View.generateViewId()
            hint = "비밀번호"
            contentDescription = "계정 비밀번호 입력, 새 비밀번호는 12자 이상 128자 이하"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        accountPasswordConfirmationInput = EditText(context).apply {
            id = View.generateViewId()
            hint = "비밀번호 확인"
            contentDescription = "계정 비밀번호 다시 입력"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        accountOtpInput = EditText(context).apply {
            id = View.generateViewId()
            hint = "이메일 인증번호 6자리"
            contentDescription = "이메일로 받은 숫자 인증번호 6자리 입력"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val accountConsentDisclosure =
            "가입 동의 상세 안내. [필수] 서비스 이용약관: 계정 생성과 WalkSafe 제공을 위해 " +
                "이메일과 암호화된 계정 식별자를 처리하며, 거부하면 가입할 수 없습니다. " +
                "[필수] 개인정보 수집·이용: 이메일과 사용자가 입력한 생년월일을 가입 자격 " +
                "확인에 사용합니다. 생년월일은 만 14세 이상인지 자기입력으로 확인할 뿐, " +
                "본인인증이나 공적 연령 인증이 아니며, 거부하면 가입할 수 없습니다. " +
                "[필수] 위치기반서비스: 길안내와 신고를 위해 기능 사용 중 위치정보를 처리하며, " +
                "거부하면 가입할 수 없습니다. [선택] 원본·진단수집 raw v2: 탐지·성능 메타데이터와 " +
                "chunk 시각·크기·hash를 " +
                "품질 진단에 사용합니다. 영상·음성·이미지·정확한 위치·이동경로·개별 frame·bbox는 " +
                "이 경로에서 수집하지 않습니다. 기기에는 최대 30일 암호화 저장하고, 보행을 끝낸 뒤 " +
                "Wi-Fi에 연결되어 충전 중일 때만 원래 보행 기록 그대로 전송하며, 보행 중·일시정지 중에는 " +
                "전송하지 않습니다. 서버 검역은 receipt commit부터 최대 " +
                "14일입니다. 거부하면 raw 수집만 꺼지며, 신고 사진과 정확한 위치를 건별 확인하는 " +
                "직접 신고에는 영향이 없습니다. " +
                "[선택] 자동 신고: JPEG 신고 사진, 정확한 위치·방향과 탐지 metadata를 보행 중에는 " +
                "보내지 않고 휴대전화에 암호화해 보관했다가, 보행을 끝낸 뒤 허용된 연결(Wi-Fi, 또는 " +
                "이동통신망 전송을 선택한 경우 이동통신망)에서 자동 전송합니다. 후보별 취소는 없으며 " +
                "설정에서 앞으로의 자동 신고를 끌 수 있습니다. 거부하면 자동 신고만 꺼집니다. " +
                "[선택] 학습 재사용: 사람이 승인하고 " +
                "비식별 처리한 정제 이미지·라벨·metadata만 dataset 승인일부터 최대 3년 모델 개선에 " +
                "사용하고, 정확한 위치·원본 음성·식별 가능한 얼굴은 제외합니다. 거부하면 학습 재사용만 " +
                "꺼집니다. " +
                "선택 동의는 나중에 설정에서 철회할 수 있습니다."
        accountConsentDisclosureText = TextView(context).apply {
            id = View.generateViewId()
            text = accountConsentDisclosure
            contentDescription = accountConsentDisclosure
            setTextColor(WS_COLOR_BUTTON_TEXT)
            textSize = 18f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val consentClauseStops = listOf("[필수] ", "[선택] ", "선택 동의는 나중에")
        linkedMapOf(
            "terms_of_service" to
                ("[필수] 서비스 이용약관 동의" to "[필수] 서비스 이용약관:"),
            "privacy_notice" to
                ("[필수] 개인정보 수집·이용 안내 확인" to "[필수] 개인정보 수집·이용:"),
            "location_terms" to
                ("[필수] 위치기반서비스 이용약관 동의" to "[필수] 위치기반서비스:"),
            "raw_original" to
                ("[선택] 원본·진단수집 raw v2 자료 처리 동의" to
                    "[선택] 원본·진단수집 raw v2:"),
            "automatic_reporting" to
                ("[선택] 자동 신고 동의" to "[선택] 자동 신고:"),
            "training_reuse" to
                ("[선택] 승인·비식별 자료 학습 재사용 동의" to "[선택] 학습 재사용:"),
        ).forEach { (key, labels) ->
            val (label, clauseLabel) = labels
            val check = CheckBox(context).apply {
                id = View.generateViewId()
                text = label
                contentDescription = label
                setTextColor(WS_COLOR_BUTTON_TEXT)
                textSize = 18f
                isChecked = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnCheckedChangeListener { button, _ ->
                    if (key in unavailableOptionalDocuments && button.isChecked) {
                        button.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    actions.onConsentChanged()
                }
            }
            consentChecks[key] = check
            val clause = documentClauseOrNull(
                source = accountConsentDisclosure,
                label = clauseLabel,
                stops = consentClauseStops,
            ) ?: accountConsentDisclosure
            val bundledVersion = SIGNUP_DOCUMENT_VERSIONS.getValue(key)
            val displayedClause = "$clause\n문서 버전: $bundledVersion"
            bundledClauseTexts[key] = displayedClause
            bundledVersions[key] = bundledVersion
            val clauseText = TextView(context).apply {
                id = View.generateViewId()
                text = displayedClause
                contentDescription = displayedClause
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                style.applyWsClauseBox(this)
            }
            consentClauseTexts[key] = clauseText
            val listenButton = style.accessiblePriorityUserButton(
                label = "${clauseLabel.removeSuffix(":")} 듣기",
                spokenLabel = "${clauseLabel.removeSuffix(":")} 듣기. 청취만으로 동의되지 않습니다.",
                onClick = { actions.onListenConsent(key) },
            )
            consentListenButtons[key] = listenButton
            consentCards[key] = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(check)
                addView(clauseText)
                addView(listenButton)
            }
        }
        accountConsentAllCheck = CheckBox(context).apply {
            id = View.generateViewId()
            text = ACCOUNT_CONSENT_ALL_LABEL
            contentDescription = ACCOUNT_CONSENT_ALL_LABEL
            setTextColor(WS_COLOR_BUTTON_TEXT)
            textSize = 18f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                val grantAll = isChecked
                consentChecks.forEach { (key, check) ->
                    check.isChecked = grantAll && key !in unavailableOptionalDocuments
                }
                actions.onConsentChanged()
            }
        }
        accountConsentSummaryText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        accountConsentContinueButton = style.accessiblePriorityUserButton(
            label = "동의하고 계속",
            emphasis = true,
            spokenLabel = "동의하고 계속. 다음 화면에서 가입 정보를 입력합니다",
            onClick = actions.onConsentContinue,
        )
        accountRequestOtpButton = style.accessiblePriorityUserButton(
            label = "인증번호 받기",
            spokenLabel = "이메일 인증번호 받기",
            emphasis = true,
            onClick = actions.onRequestOtp,
        )
        accountCreateButton = style.accessiblePriorityUserButton(
            label = "계정 만들기",
            spokenLabel = "인증번호 확인 후 계정 만들기",
            emphasis = true,
            onClick = actions.onCreateAccount,
        )
        accountLoginButton = style.accessiblePriorityUserButton(
            label = "로그인",
            spokenLabel = "이메일과 비밀번호로 로그인",
            emphasis = true,
            onClick = actions.onLogin,
        )
        accountSessionLogoutButton = style.accessiblePriorityUserButton(
            label = "로그아웃",
            spokenLabel = "현재 계정 로그아웃",
            onClick = actions.onLogout,
        )
        accountSignupToggleButton = style.accessiblePriorityUserButton(
            label = "회원가입",
            spokenLabel = "새 계정 만들기, 가입 입력 펼치기",
            onClick = actions.onSignupToggle,
        )
        accountConsentDisclosureToggleButton = style.accessiblePriorityUserButton(
            label = "가입 동의 자세히 보기",
            spokenLabel = "가입 동의 자세히 보기, 접힘",
            onClick = actions.onDisclosureToggle,
        )
        accountConsentStepControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            SIGNUP_DOCUMENT_VERSIONS.keys.forEach { key ->
                addView(consentCards.getValue(key))
            }
            addView(accountConsentContinueButton)
        }
        accountDetailsStepControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(style.wsFieldGroup(accountPasswordConfirmationInput, "비밀번호 확인"))
            addView(style.wsFieldGroup(accountDateOfBirthInput, "생년월일"))
            addView(accountRequestOtpButton)
        }
        accountSignupBackButton = style.accessiblePriorityUserButton(
            label = "로그인으로",
            spokenLabel = "로그인 화면으로 돌아가기. 두 번 탭하여 가입 입력을 닫습니다",
            onClick = { accountSignupToggleButton.performClick() },
        )
        accountSignupControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(accountConsentStepControls)
            addView(accountDetailsStepControls)
            addView(style.wsFieldGroup(accountOtpInput, "이메일 인증번호", "숫자 6자리"))
            addView(accountCreateButton)
            addView(accountSignupBackButton)
        }
        accountLandingHeader = style.buildAccountLandingHeader().apply { visibility = View.GONE }
        accountAccessControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(accountAccessStatusText)
            addView(style.wsFieldGroup(accountEmailInput, "이메일"))
            addView(style.wsFieldGroup(accountPasswordInput, "비밀번호"))
            addView(accountLoginButton)
            addView(accountSignupToggleButton)
            addView(accountSignupControls)
        }
        listOf(
            accountEmailInput,
            accountDateOfBirthInput,
            accountPasswordInput,
            accountPasswordConfirmationInput,
            accountOtpInput,
        ).forEach(::bindAccountInputValidation)
    }

    /** Only actually visible bundled clauses can establish a displayed-version snapshot. */
    fun visibleSignupDocumentVersionsOrNull(): Map<String, String>? {
        if (bundledVersions.keys != SIGNUP_DOCUMENT_VERSIONS.keys) return null
        if (bundledVersions.keys.any { key ->
                val clause = consentClauseTexts[key]
                clause == null || !clause.isShown || !consentCards.getValue(key).isShown ||
                    !clause.text.toString().startsWith(bundledClauseTexts.getValue(key))
            }) return null
        return java.util.Collections.unmodifiableMap(LinkedHashMap(bundledVersions))
    }

    fun showUnavailableOptionalSignupDocuments(keys: Set<String>, busy: Boolean) {
        require(keys.all { it in OPTIONAL_DOCUMENT_LABELS })
        unavailableOptionalDocuments = keys.toSet()
        consentChecks.forEach { (key, check) ->
            if (key in keys) check.isChecked = false
            check.isEnabled = !busy && key !in keys
        }
        consentClauseTexts.forEach { (key, clause) ->
            val notice = if (key in keys) {
                "\n${OPTIONAL_DOCUMENT_LABELS.getValue(key)} 문서가 바뀌어 이 앱에서 새 문서를 제공할 수 없습니다. " +
                    "이번 가입에서는 동의하지 않은 상태로 처리합니다. 앱 업데이트 후 설정에서 다시 확인할 수 있습니다."
            } else ""
            clause.text = bundledClauseTexts.getValue(key) + notice
            clause.contentDescription = clause.text
        }
        accountConsentAllCheck.text = if (keys.isEmpty()) ACCOUNT_CONSENT_ALL_LABEL
            else "필수 3개와 현재 제공되는 선택 항목에 모두 동의합니다"
        accountConsentAllCheck.contentDescription = accountConsentAllCheck.text
    }

    private fun bindAccountInputValidation(target: EditText) {
        target.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(text: Editable?) {
                    target.error = null
                    actions.onInputChanged()
                }
            },
        )
    }


    private fun documentClauseOrNull(
        source: String,
        label: String,
        stops: List<String>,
    ): String? {
        val start = source.indexOf(label)
        if (start < 0) return null
        val rest = source.substring(start)
        val next = stops
            .mapNotNull { stop -> rest.indexOf(stop, label.length).takeIf { it > 0 } }
            .minOrNull()
        return if (next != null) rest.substring(0, next).trim() else rest.trim()
    }


    private companion object {
        val OPTIONAL_DOCUMENT_LABELS = mapOf(
            "raw_original" to "원본·진단수집",
            "automatic_reporting" to "자동 신고",
            "training_reuse" to "학습 재사용",
        )
        const val ACCOUNT_CONSENT_ALL_LABEL = "필수 3개와 선택 3개에 모두 동의합니다"
    }
}
