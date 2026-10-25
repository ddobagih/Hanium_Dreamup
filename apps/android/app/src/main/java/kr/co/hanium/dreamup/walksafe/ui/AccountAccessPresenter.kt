package kr.co.hanium.dreamup.walksafe.ui

import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.EmailAccountFormPolicy
import kr.co.hanium.dreamup.walksafe.EmailAccountFormValidity
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.session.account.EmailAccountController

/** Owns account screen state and View output. Server authority and request ownership stay outside. */
internal class AccountAccessPresenter(
    private val style: WalkSafeViewStyle,
    private val actions: Actions,
) {
    enum class AccountSignupStep { CONSENT, DETAILS }

    data class Actions(
        val isRequestBusy: () -> Boolean,
        val isGatewayRequestInFlight: () -> Boolean,
        val refreshActionAvailability: () -> Unit,
        val refreshAccessUi: () -> Unit,
        val showAccountMessage: (String) -> Unit,
    )

    data class AvailabilityFacts(
        val emailEnrollmentStorageBlocked: Boolean,
        val backendDeviceRestorePending: Boolean,
    )

    /** These flags are computed by Main at the original authority-check point. */
    data class AccessFacts(
        val emailFlow: Boolean,
        val accountStage: Boolean,
        val authenticated: Boolean,
        val reauthenticationRequired: Boolean,
        val deletionRecoveryBlocksReauthentication: Boolean,
        val storageBlocksReauthentication: Boolean,
        val staleGeneralSession: Boolean,
        val backendDeviceRestorePending: Boolean,
        val processStorageBlocked: Boolean,
        val processDeletionRecoveryOnly: Boolean,
        val processRequestInFlight: Boolean,
        val creating: Boolean,
        val verifiedLogin: Boolean,
        val otpEnrollmentStage: Boolean,
        val isComplete: Boolean,
        val emailEnrollmentStorageBlocked: Boolean,
        val hasEnrollmentPartial: Boolean,
    )

    data class SpeechButtonFacts(
        val playingKey: String?,
        val completed: Map<String, Boolean>,
    )

    data class MessagePresentation(val changed: Boolean, val isShown: Boolean)

    var accountSignupExpanded = false
    var accountSignupStep = AccountSignupStep.CONSENT
    var accountConsentDisclosureExpanded = false
    var accountInputErrorActive = false
    var accountAccessNotice: String? = null
    var passwordLoginFailureNotice: String? = null

    private var panel: AccountAccessPanel? = null
    private var displayedSignupVersions: Map<String, String>? = null
    private var unavailableOptionalDocuments: Set<String> = emptySet()

    private lateinit var publishedStatus: AccountAccessPanel
    private lateinit var publishedInputs: AccountAccessPanel
    private lateinit var publishedRoot: AccountAccessPanel

    fun publishStatusTarget() { publishedStatus = checkNotNull(panel) }
    fun publishInputTargets() { publishedInputs = checkNotNull(panel) }
    fun publishRootTarget() { publishedRoot = checkNotNull(panel) }
    fun statusTarget(): TextView = publishedStatus.accountAccessStatusText
    fun rootTarget(): android.widget.LinearLayout = publishedRoot.accountAccessControls
    fun inputTarget(field: EmailAccountController.InputField): EditText = when (field) {
        EmailAccountController.InputField.EMAIL -> publishedInputs.accountEmailInput
        EmailAccountController.InputField.DATE_OF_BIRTH -> publishedInputs.accountDateOfBirthInput
        EmailAccountController.InputField.PASSWORD -> publishedInputs.accountPasswordInput
        EmailAccountController.InputField.PASSWORD_CONFIRMATION -> publishedInputs.accountPasswordConfirmationInput
        EmailAccountController.InputField.OTP -> publishedInputs.accountOtpInput
    }

    fun bindPanel(panel: AccountAccessPanel) {
        this.panel = panel
        displayedSignupVersions = null
    }

    fun displayedSignupDocumentVersions(): Map<String, String>? {
        panel?.visibleSignupDocumentVersionsOrNull()?.let { displayedSignupVersions = it }
        return displayedSignupVersions
    }

    fun showUnavailableOptionalSignupDocuments(keys: Set<String>) {
        require(keys.all { it in setOf("raw_original", "automatic_reporting", "training_reuse") })
        unavailableOptionalDocuments = keys.toSet()
        panel?.showUnavailableOptionalSignupDocuments(unavailableOptionalDocuments, actions.isRequestBusy())
        refreshAccountConsentSummary()
    }

    private val accountAccessStatusText: TextView get() = requireNotNull(panel).accountAccessStatusText
    private val accountEmailInput: EditText get() = requireNotNull(panel).accountEmailInput
    private val accountDateOfBirthInput: EditText get() = requireNotNull(panel).accountDateOfBirthInput
    private val accountPasswordInput: EditText get() = requireNotNull(panel).accountPasswordInput
    private val accountPasswordConfirmationInput: EditText get() = requireNotNull(panel).accountPasswordConfirmationInput
    private val accountOtpInput: EditText get() = requireNotNull(panel).accountOtpInput
    private val accountConsentDisclosureText: TextView get() = requireNotNull(panel).accountConsentDisclosureText
    private val accountConsentAllCheck: CheckBox get() = requireNotNull(panel).accountConsentAllCheck
    private val accountConsentSummaryText: TextView get() = requireNotNull(panel).accountConsentSummaryText
    private val accountConsentContinueButton: Button get() = requireNotNull(panel).accountConsentContinueButton
    private val accountRequestOtpButton: Button get() = requireNotNull(panel).accountRequestOtpButton
    private val accountCreateButton: Button get() = requireNotNull(panel).accountCreateButton
    private val accountLoginButton: Button get() = requireNotNull(panel).accountLoginButton
    private val accountSessionLogoutButton: Button get() = requireNotNull(panel).accountSessionLogoutButton
    private val accountSignupToggleButton: Button get() = requireNotNull(panel).accountSignupToggleButton
    private val accountConsentDisclosureToggleButton: Button get() = requireNotNull(panel).accountConsentDisclosureToggleButton
    private val accountConsentStepControls: LinearLayout get() = requireNotNull(panel).accountConsentStepControls
    private val accountDetailsStepControls: LinearLayout get() = requireNotNull(panel).accountDetailsStepControls
    private val accountSignupBackButton: Button get() = requireNotNull(panel).accountSignupBackButton
    private val accountSignupControls: LinearLayout get() = requireNotNull(panel).accountSignupControls
    private val accountLandingHeader: LinearLayout get() = requireNotNull(panel).accountLandingHeader
    private val accountAccessControls: LinearLayout get() = requireNotNull(panel).accountAccessControls
    private val accountConsentChecks get() = panel?.accountConsentChecks ?: emptyMap()
    private val accountConsentCards get() = requireNotNull(panel).accountConsentCards
    private val accountConsentListenButtons get() = panel?.accountConsentListenButtons ?: emptyMap()

    /** The surrounding stage and divider are the only cross-panel View slots. */
    fun renderAccountEntry(
        accountEntryScreen: Boolean,
        firstRunOnboardingStatusText: TextView,
        runtimeSectionDivider: View?,
    ) {
        firstRunOnboardingStatusText.visibility =
            if (accountEntryScreen) View.GONE else View.VISIBLE
        runtimeSectionDivider?.visibility =
            if (accountEntryScreen) View.GONE else View.VISIBLE
        accountAccessStatusText.visibility = View.VISIBLE
    }

    fun recordAccountMessage(message: String, statusInitialized: Boolean): MessagePresentation? {
        accountInputErrorActive = false
        passwordLoginFailureNotice = null
        accountAccessNotice = message
        if (!statusInitialized || panel == null) return null
        val changed = accountAccessStatusText.text?.toString() != message
        accountAccessStatusText.text = message
        accountAccessStatusText.contentDescription = message
        return MessagePresentation(changed, accountAccessStatusText.isShown)
    }

    fun onInputChanged(snapshotInitialized: Boolean) {
        if (accountInputErrorActive) {
            accountInputErrorActive = false
            accountAccessNotice = null
            if (snapshotInitialized) actions.refreshAccessUi()
        } else {
            actions.refreshActionAvailability()
        }
    }

    /** Main cancels the OTP retry before completing the toggle, exactly as before. */
    fun nextSignupExpanded(): Boolean = !accountSignupExpanded

    fun completeSignupToggle(opening: Boolean, creating: Boolean) {
        if (opening && passwordLoginFailureNotice != null &&
            accountAccessNotice == passwordLoginFailureNotice) {
            accountAccessNotice = null
            passwordLoginFailureNotice = null
        }
        accountSignupExpanded = opening
        if (!opening) displayedSignupVersions = null
        accountSignupStep = if (opening && creating) {
            AccountSignupStep.DETAILS
        } else {
            AccountSignupStep.CONSENT
        }
        actions.refreshAccessUi()
    }

    /** Main computes creating again after the synchronous access render. */
    fun focusOpenedSignup(opening: Boolean, creating: Boolean) {
        if (!opening) return
        val focusTarget = if (creating) {
            accountPasswordInput
        } else {
            accountConsentChecks.getValue("terms_of_service")
        }
        focusTarget.post {
            focusTarget.requestFocus()
            focusTarget.requestRectangleOnScreen(
                Rect(0, 0, focusTarget.width, focusTarget.height),
                true,
            )
        }
    }

    fun toggleConsentDisclosure() {
        accountConsentDisclosureExpanded = !accountConsentDisclosureExpanded
        actions.refreshAccessUi()
        if (accountConsentDisclosureExpanded) {
            accountConsentDisclosureText.post {
                accountConsentDisclosureText.requestFocus()
                accountConsentDisclosureText.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
        }
    }

    fun continueToDetails() {
        displayedSignupDocumentVersions()
        accountSignupStep = AccountSignupStep.DETAILS
        actions.refreshAccessUi()
        accountEmailInput.post { accountEmailInput.requestFocus() }
    }

    private fun wsFieldGroupOf(input: EditText): View = style.wsFieldGroupOf(input)

    fun updateAccountConsentSpeechButtons(facts: SpeechButtonFacts) {
        accountConsentListenButtons.forEach { (key, button) ->
            val label = accountConsentChecks.getValue(key).text.toString()
                .removeSuffix(" 동의").removeSuffix(" 확인")
            val playing = facts.playingKey == key
            val completed = facts.completed[key]
            button.text = when {
                playing -> "$label 듣기 중지"
                completed == true -> "$label 다시 듣기"
                completed == false -> "$label 듣기 재시도"
                else -> "$label 듣기"
            }
            val playbackStatus = when {
                playing -> "약관 듣기 재생 중. 이번 듣기는 아직 완료되지 않았습니다."
                completed == true -> "이전 약관 듣기 완료."
                completed == false -> "약관 듣기 미완료."
                else -> "약관 듣기를 아직 완료하지 않았습니다."
            }
            button.contentDescription = button.text.toString() + ". " + playbackStatus +
                " 청취만으로 동의되지 않습니다."
        }
    }


    fun currentEmailAccountFormValidity(): EmailAccountFormValidity =
        EmailAccountFormPolicy.evaluate(
            email = accountEmailInput.text?.toString().orEmpty(),
            dateOfBirth = accountDateOfBirthInput.text?.toString().orEmpty(),
            password = accountPasswordInput.text?.toString().orEmpty(),
            passwordConfirmation =
                accountPasswordConfirmationInput.text?.toString().orEmpty(),
            otp = accountOtpInput.text?.toString().orEmpty(),
            selections = currentAccountConsentSelections(),
        )


    fun refreshAccountConsentSummary(allCheckInitialized: Boolean = true) {
        if (panel == null) return
        val selections = currentAccountConsentSelections()
        val granted = listOf(
            selections.termsOfService,
            selections.privacyNotice,
            selections.locationTerms,
        ).count { it }
        val summary = if (selections.requiredGranted) {
            "필수 동의 3개를 모두 확인했습니다. 다음 가입 단계로 진행할 수 있습니다."
        } else {
            "필수 동의 3개 중 ${granted}개를 확인했습니다. 남은 필수 항목에 동의하세요."
        }
        if (accountConsentSummaryText.text != summary) {
            accountConsentSummaryText.text = summary
            accountConsentSummaryText.contentDescription = summary
        }
        if (allCheckInitialized) {
            val allGranted = accountConsentChecks.filterKeys { it !in unavailableOptionalDocuments }
                .values.all { it.isChecked }
            if (accountConsentAllCheck.isChecked != allGranted) {
                accountConsentAllCheck.isChecked = allGranted
            }
        }
    }


    fun refreshAccountActionAvailability(facts: AvailabilityFacts) {
        if (panel == null) return
        val validity = currentEmailAccountFormValidity()
        val busy = actions.isRequestBusy()
        val emailEntered = accountEmailInput.text?.toString().orEmpty().isNotBlank()
        val dateOfBirthEntered =
            accountDateOfBirthInput.text?.toString().orEmpty().isNotBlank()
        val passwordEntered = accountPasswordInput.text?.toString().orEmpty().isNotBlank()
        val passwordConfirmationEntered =
            accountPasswordConfirmationInput.text?.toString().orEmpty().isNotBlank()
        val otpEntered = accountOtpInput.text?.toString().orEmpty().isNotBlank()
        accountConsentContinueButton.isEnabled =
            validity.canContinueFromConsent && !busy
        accountRequestOtpButton.isEnabled =
            validity.requiredConsentsGranted &&
                emailEntered &&
                dateOfBirthEntered &&
                passwordEntered &&
                passwordConfirmationEntered &&
                !busy &&
                !facts.emailEnrollmentStorageBlocked
        accountCreateButton.isEnabled =
            validity.requiredConsentsGranted &&
                emailEntered &&
                passwordEntered &&
                passwordConfirmationEntered &&
                otpEntered &&
                !busy &&
                !facts.emailEnrollmentStorageBlocked
        accountLoginButton.isEnabled = if (facts.backendDeviceRestorePending) {
            !busy && !actions.isGatewayRequestInFlight()
        } else {
            emailEntered && passwordEntered && !busy
        }
    }


    fun currentAccountConsentSelections(): SignupConsentSelections =
        SignupConsentSelections(
            termsOfService = (panel?.accountConsentChecks ?: emptyMap())["terms_of_service"]?.isChecked == true,
            privacyNotice = (panel?.accountConsentChecks ?: emptyMap())["privacy_notice"]?.isChecked == true,
            locationTerms = (panel?.accountConsentChecks ?: emptyMap())["location_terms"]?.isChecked == true,
            rawOriginal = "raw_original" !in unavailableOptionalDocuments &&
                (panel?.accountConsentChecks ?: emptyMap())["raw_original"]?.isChecked == true,
            automaticReporting = "automatic_reporting" !in unavailableOptionalDocuments &&
                (panel?.accountConsentChecks ?: emptyMap())["automatic_reporting"]?.isChecked == true,
            trainingReuse = "training_reuse" !in unavailableOptionalDocuments &&
                (panel?.accountConsentChecks ?: emptyMap())["training_reuse"]?.isChecked == true,
        )


    fun applyAccountConsentSelections(selections: SignupConsentSelections) {
        accountConsentChecks["terms_of_service"]?.isChecked = selections.termsOfService
        accountConsentChecks["privacy_notice"]?.isChecked = selections.privacyNotice
        accountConsentChecks["location_terms"]?.isChecked = selections.locationTerms
        accountConsentChecks["raw_original"]?.isChecked =
            selections.rawOriginal && "raw_original" !in unavailableOptionalDocuments
        accountConsentChecks["automatic_reporting"]?.isChecked =
            selections.automaticReporting && "automatic_reporting" !in unavailableOptionalDocuments
        accountConsentChecks["training_reuse"]?.isChecked =
            selections.trainingReuse && "training_reuse" !in unavailableOptionalDocuments
        refreshAccountConsentSummary()
        actions.refreshActionAvailability()
    }


    fun updateEmailAccountAccessUi(facts: AccessFacts) {
        if (panel == null) return
        val emailFlow = facts.emailFlow
        val accountStage = facts.accountStage
        val authenticated = facts.authenticated
        val reauthenticationRequired = facts.reauthenticationRequired
        val deletionRecoveryBlocksReauthentication = facts.deletionRecoveryBlocksReauthentication
        val storageBlocksReauthentication = facts.storageBlocksReauthentication
        val staleGeneralSession = facts.staleGeneralSession
        val reauthenticationBlocked =
            deletionRecoveryBlocksReauthentication || storageBlocksReauthentication || staleGeneralSession
        accountAccessControls.visibility =
            if (emailFlow && (accountStage || authenticated || reauthenticationRequired)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (!emailFlow || (!accountStage && !authenticated && !reauthenticationRequired)) return
        accountSessionLogoutButton.visibility =
            if (authenticated || staleGeneralSession) View.VISIBLE else View.GONE
        accountSessionLogoutButton.text =
            if (staleGeneralSession) "이전 로그인 상태 정리" else "현재 계정 로그아웃"
        accountSessionLogoutButton.contentDescription = accountSessionLogoutButton.text
        accountLoginButton.text = "로그인"
        accountLoginButton.contentDescription = "이메일과 비밀번호로 로그인"
        if (facts.backendDeviceRestorePending && !authenticated &&
            !facts.processStorageBlocked && !facts.processDeletionRecoveryOnly
        ) {
            accountAccessControls.visibility = View.VISIBLE
            accountAccessStatusText.visibility = View.VISIBLE
            wsFieldGroupOf(accountEmailInput).visibility = View.GONE
            wsFieldGroupOf(accountPasswordInput).visibility = View.GONE
            accountLandingHeader.visibility = View.GONE
            accountSignupControls.visibility = View.GONE
            accountSignupToggleButton.visibility = View.GONE
            accountSignupBackButton.visibility = View.GONE
            accountLoginButton.visibility = View.VISIBLE
            accountLoginButton.text = "저장된 로그인 다시 확인"
            accountLoginButton.contentDescription = accountLoginButton.text
            val verifying = facts.processRequestInFlight
            accountLoginButton.isEnabled = !verifying
            if (accountSessionLogoutButton.parent == null) {
                accountAccessControls.addView(accountSessionLogoutButton)
            }
            accountSessionLogoutButton.visibility = View.VISIBLE
            accountSessionLogoutButton.isEnabled = !verifying
            accountSessionLogoutButton.text = "저장된 로그인 해제"
            accountSessionLogoutButton.contentDescription = accountSessionLogoutButton.text
            val message = if (verifying) "저장된 로그인을 확인하고 있습니다." else
                "인터넷에 연결한 뒤 저장된 로그인을 다시 확인하세요. 비밀번호를 다시 입력하지 않아도 됩니다."
            accountAccessStatusText.text = message
            accountAccessStatusText.contentDescription = message
            return
        }
        accountSessionLogoutButton.isEnabled = !facts.processRequestInFlight
        if (authenticated) {
            displayedSignupVersions = null
            wsFieldGroupOf(accountEmailInput).visibility = View.GONE
            wsFieldGroupOf(accountPasswordInput).visibility = View.GONE
            accountLandingHeader.visibility = View.GONE
            accountLoginButton.visibility = View.GONE
            accountSignupToggleButton.visibility = View.GONE
            accountSignupControls.visibility = View.GONE
            val authenticatedStatus = if (facts.isComplete) {
                "로그인과 첫 실행 등록을 완료했습니다. 보행 전 실제 상태를 확인하세요."
            } else {
                "로그인했습니다. 다음 단계가 끝날 때까지 보행 기능은 잠깁니다."
            }
            accountAccessStatusText.text = authenticatedStatus
            accountAccessStatusText.contentDescription = authenticatedStatus
            accountAccessControls.visibility = View.GONE
            accountAccessStatusText.visibility = View.GONE
            accountSessionLogoutButton.visibility = View.GONE
            return
        }
        if (reauthenticationBlocked) {
            wsFieldGroupOf(accountEmailInput).visibility = View.GONE
            wsFieldGroupOf(accountPasswordInput).visibility = View.GONE
            accountLandingHeader.visibility = View.GONE
            accountLoginButton.visibility = View.GONE
            accountSignupToggleButton.visibility = View.GONE
            accountSignupControls.visibility = View.GONE
            val blockedStatus = when {
                deletionRecoveryBlocksReauthentication ->
                    "계정 삭제 복구 전용 로그인이 진행 중입니다. 계정 삭제 절차에서 계속하세요."
                storageBlocksReauthentication ->
                    "로그인 저장소가 안전 차단 상태입니다. 재로그인 전에 앱 지원 담당자에게 문의하세요."
                else ->
                    "이전 로그인 상태를 안전하게 정리해야 합니다. 정리한 뒤 다시 로그인하세요."
            }
            if (staleGeneralSession && accountSessionLogoutButton.parent == null) {
                accountAccessControls.addView(accountSessionLogoutButton)
            }
            accountAccessStatusText.text = blockedStatus
            accountAccessStatusText.contentDescription = blockedStatus
            return
        }
        val creating = facts.creating
        val verifiedLogin = facts.verifiedLogin
        if (verifiedLogin || reauthenticationRequired) {
            accountSignupExpanded = false
            accountSignupStep = AccountSignupStep.CONSENT
        }
        val signupVisible =
            accountSignupExpanded && !verifiedLogin && !reauthenticationRequired
        if (!signupVisible) accountSignupStep = AccountSignupStep.CONSENT
        accountLandingHeader.visibility = View.GONE
        val busy = actions.isRequestBusy()
        panel?.showUnavailableOptionalSignupDocuments(unavailableOptionalDocuments, busy)
        val inSignup = signupVisible && !creating
        val onConsentStep =
            inSignup && accountSignupStep == AccountSignupStep.CONSENT
        val onDetailsStep =
            signupVisible && (creating || accountSignupStep == AccountSignupStep.DETAILS)
        accountConsentStepControls.visibility =
            if (onConsentStep) View.VISIBLE else View.GONE
        accountDetailsStepControls.visibility =
            if (onDetailsStep) View.VISIBLE else View.GONE
        val credentialFieldsVisible = !onConsentStep
        wsFieldGroupOf(accountEmailInput).visibility =
            if (credentialFieldsVisible) View.VISIBLE else View.GONE
        wsFieldGroupOf(accountPasswordInput).visibility =
            if (credentialFieldsVisible) View.VISIBLE else View.GONE
        accountLoginButton.visibility = if (signupVisible) View.GONE else View.VISIBLE
        accountSignupToggleButton.visibility = when {
            verifiedLogin || reauthenticationRequired -> View.GONE
            signupVisible -> View.GONE
            else -> View.VISIBLE
        }
        accountSignupBackButton.visibility =
            if (signupVisible) View.VISIBLE else View.GONE
        accountSignupToggleButton.text =
            when {
                signupVisible -> "로그인 화면으로 돌아가기"
                creating -> "계정 만들기 계속"
                else -> "회원가입"
            }
        accountSignupToggleButton.contentDescription =
            when {
                signupVisible ->
                    "로그인 화면으로 돌아가기. 두 번 탭하여 가입 입력 접기"
                creating ->
                    "계정 만들기 계속, 가입 입력 접힘. 두 번 탭하여 펼치기"
                else ->
                    "새 계정 만들기, 가입 입력 접힘. 두 번 탭하여 펼치기"
            }
        accountSignupControls.visibility =
            if (signupVisible && !verifiedLogin) View.VISIBLE else View.GONE
        wsFieldGroupOf(accountDateOfBirthInput).visibility =
            if (onDetailsStep) View.VISIBLE else View.GONE
        wsFieldGroupOf(accountPasswordConfirmationInput).visibility =
            if (onDetailsStep) View.VISIBLE else View.GONE
        wsFieldGroupOf(accountOtpInput).visibility =
            if (creating && signupVisible) View.VISIBLE else View.GONE
        accountConsentDisclosureToggleButton.visibility = View.GONE
        accountConsentDisclosureToggleButton.text =
            if (accountConsentDisclosureExpanded) {
                "가입 동의 자세히 접기"
            } else {
                "가입 동의 자세히 보기"
            }
        accountConsentDisclosureToggleButton.contentDescription =
            if (accountConsentDisclosureExpanded) {
                "가입 동의 자세히 보기, 펼침. 두 번 탭하여 접기"
            } else {
                "가입 동의 자세히 보기, 접힘. 두 번 탭하여 펼치기"
            }
        accountConsentDisclosureText.visibility = View.GONE
        accountConsentCards.values.forEach { card ->
            card.visibility = if (onConsentStep) View.VISIBLE else View.GONE
        }
        accountConsentChecks.forEach { (key, check) ->
            check.isEnabled = !busy && key !in unavailableOptionalDocuments
        }
        accountConsentAllCheck.isEnabled = !busy
        accountRequestOtpButton.visibility =
            if (
                onDetailsStep &&
                facts.otpEnrollmentStage
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        accountCreateButton.visibility =
            if (creating && signupVisible) View.VISIBLE else View.GONE
        accountSessionLogoutButton.visibility = View.GONE
        refreshAccountConsentSummary()
        actions.refreshActionAvailability()
        accountEmailInput.isEnabled = !busy
        accountDateOfBirthInput.isEnabled = !busy
        accountPasswordInput.isEnabled = !busy
        accountPasswordConfirmationInput.isEnabled = !busy
        accountOtpInput.isEnabled = !busy
        val hasEnrollmentPartial = facts.hasEnrollmentPartial
        val status = when {
            busy -> "계정 요청을 안전하게 처리하고 있습니다. 버튼을 다시 누르지 마세요."
            accountAccessNotice != null -> requireNotNull(accountAccessNotice)
            facts.emailEnrollmentStorageBlocked ->
                "가입 임시 상태를 안전하게 저장할 수 없습니다. 기존 계정 로그인만 가능합니다."
            signupVisible && creating && hasEnrollmentPartial -> null
            facts.verifiedLogin ->
                "계정 생성이 완료되었습니다."
            reauthenticationRequired ->
                "다시 로그인해 주세요."
            else -> null
        }
        accountAccessStatusText.text = status
        accountAccessStatusText.contentDescription = status
        accountAccessStatusText.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    fun showAccountInputError(target: EditText, message: String) {
        accountInputErrorActive = true
        target.error = message
        target.post {
            target.requestFocus()
            target.requestRectangleOnScreen(
                Rect(0, 0, target.width, target.height),
                true,
            )
        }
    }


    fun showMissingRequiredConsentError() {
        accountSignupExpanded = true
        accountSignupStep = AccountSignupStep.CONSENT
        val message = "필수 약관 세 가지에 모두 동의해야 가입할 수 있습니다."
        actions.showAccountMessage(message)
        actions.refreshAccessUi()
        refreshAccountConsentSummary()
        val firstMissing = listOf(
            "terms_of_service",
            "privacy_notice",
            "location_terms",
        ).firstNotNullOfOrNull { key ->
            accountConsentChecks[key]?.takeUnless { it.isChecked }
        }
        firstMissing?.post {
            firstMissing.requestFocus()
            firstMissing.requestRectangleOnScreen(
                Rect(0, 0, firstMissing.width, firstMissing.height),
                true,
            )
        }
    }

}
