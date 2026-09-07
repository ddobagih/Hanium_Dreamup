package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityEmailAccountStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun emailDobPasswordOtpAndSixConsentControlsAreActuallyComposed() {
        assertTrue(source.contains("accountEmailInput = EditText(this).apply"))
        assertTrue(source.contains("accountDateOfBirthInput = EditText(this).apply"))
        assertTrue(source.contains("accountPasswordInput = EditText(this).apply"))
        assertTrue(source.contains("accountPasswordConfirmationInput = EditText(this).apply"))
        assertTrue(source.contains("accountOtpInput = EditText(this).apply"))
        listOf(
            "terms_of_service",
            "privacy_notice",
            "location_terms",
            "raw_original",
            "automatic_reporting",
            "training_reuse",
        ).forEach { assertTrue(source.contains("\"$it\"")) }
        assertTrue(source.contains("addView(accountAccessControls)"))
        assertTrue(source.contains("contentDescription = \"계정 이메일 입력\""))
        assertTrue(source.contains("contentDescription = \"이메일로 받은 숫자 인증번호 6자리 입력\""))
    }

    @Test
    fun signupValidatesRequiredConsentAndDoesNotPersistSensitiveInputs() {
        val request = functionBlock("private fun requestEmailAccountOtp()")
        assertTrue(request.contains("if (!selections.requiredGranted)"))
        assertTrue(request.contains("EmailEnrollmentPartial("))
        assertFalse(request.contains("putString(email"))
        assertFalse(request.contains("putString(dateOfBirth"))

        val create = functionBlock("private fun createEmailAccount()")
        assertTrue(create.contains("if (!selections.requiredGranted)"))
        assertTrue(create.contains("password != confirmation"))
        assertTrue(create.contains("Regex(\"^[0-9]{6}$\")"))
        assertTrue(create.contains("emailEnrollmentStore.clear()"))
    }

    @Test
    fun loginPublishesBeforeLocalCommitAndCanResumeBoundAdvancedProgress() {
        val login = functionBlock("private fun loginEmailAccount(")
        assertTrue(login.contains("gatewaySessionClient.loginWithPassword("))
        assertTrue(login.contains("gatewaySessionStore.getOrCreateInstallDeviceId()"))
        assertTrue(login.contains("deviceId = installationDeviceId"))
        assertTrue(login.contains("rememberMe = false"))
        assertFalse(login.contains("val rememberMe"))
        assertFalse(login.contains("rememberMeOverride"))
        assertFalse(login.contains("accountRememberMeCheck"))
        assertTrue(login.contains("FirstRunOnboardingPolicy.recordVerifiedEmailLogin("))
        assertTrue(login.contains("FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor("))
        assertTrue(login.contains("val nextFirstRunSnapshot = if (reauthenticatesExistingProgress)"))
        assertTrue(login.contains("ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS"))
        assertTrue(login.contains("GatewaySessionProcessCoordinator.publishVerified("))
        assertTrue(login.contains("로그인했습니다. 먼저 서비스 목적과 안전 한계를 확인하세요."))
        assertTrue(login.contains("account=authenticated next=purpose_and_safety"))
        assertTrue(login.contains("account=reauthenticated next=onboarding_resume"))
        assertTrue(login.contains("restorePriorityUserOnboardingFromPrefs()"))
        assertInOrder(
            login,
            "GatewaySessionProcessCoordinator.publishVerified(",
            "firstRunOnboardingSnapshot = nextFirstRunSnapshot",
        )
        assertFalse(login.contains("PriorityUserAgeBand.AGE_14_TO_17"))
        assertFalse(login.contains("VERIFIED_SMS"))
        assertFalse(login.contains("LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
    }

    @Test
    fun purposeAndSafetyAcknowledgementAlsoPersistsVoiceProcessingDisclosure() {
        val acknowledgement =
            functionBlock("private fun acknowledgeFirstRunPurposeAndSafety()")
        assertInOrder(
            acknowledgement,
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY",
            "if (!acknowledgeHandsFreeVoiceDisclosure())",
            "FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(",
            "firstRunOnboardingSnapshot = transition.current",
        )
        val disclosureFailureGate = sourceBlock(
            "if (!acknowledgeHandsFreeVoiceDisclosure()) {",
            "val transition = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(",
        )
        assertTrue(disclosureFailureGate.contains("return"))

        val voiceDisclosure =
            functionBlock("private fun acknowledgeHandsFreeVoiceDisclosure()")
        assertInOrder(
            voiceDisclosure,
            ".putInt(",
            "PREF_HANDS_FREE_VOICE_DISCLOSURE_VERSION",
            "HANDS_FREE_VOICE_DISCLOSURE_VERSION",
            ".commit()",
        )
    }

    @Test
    fun duplicateAndBackgroundCallbacksAreFencedAndLogoutReturnsToLoginAccess() {
        assertTrue(source.contains("private val accountRequestFence = AccountRequestFence()"))
        assertTrue(source.contains("accountRequestFence.enteredBackground()"))
        assertTrue(source.contains("accountRequestFence.completeIfCurrent("))
        val logout = functionBlock("private fun onAccountLogoutClicked()")
        assertTrue(logout.contains("FirstRunOnboardingPolicy.initialEmailAccount("))
        assertTrue(logout.contains("이메일과 비밀번호를 다시 입력해 로그인하세요"))
        assertFalse(logout.contains("resetAfterConfirmedAccountDeletion"))
    }

    @Test
    fun backendMinimumAgeRejectionHasAnExplicitUserMessage() {
        val failure = functionBlock("private fun postAccountFailure(")
        assertTrue(failure.contains("account_enrollment_not_allowed"))
        assertTrue(failure.contains("만 14세 미만은 현재 WalkSafe 계정에 가입할 수 없습니다."))
    }

    @Test
    fun activityRecreationRestoresIncompleteEmailJitWithoutOpeningProtectedFeatures() {
        val changed = functionBlock("private fun onGatewayProcessSessionChanged(")
        assertTrue(changed.contains("verifiedEmailActorId"))
        assertTrue(changed.contains("firstRunOnboardingSnapshot = firstRun"))
        assertTrue(changed.contains("appliedFirstRun.isComplete &&"))
        assertTrue(changed.contains("if (firstRunIsStale)"))
        assertTrue(changed.contains("permissionSessionPolicy.authenticationExpired()"))
        assertTrue(changed.contains("updateFirstRunOnboardingUi()"))
    }

    @Test
    fun emailAccountProgressUsesTheActualSevenStageFlow() {
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 7"))

        val numbering = functionBlock("private fun firstRunStageNumber(")
        assertInOrder(
            numbering,
            "FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT -> 1",
            "FirstRunOnboardingStage.ACCOUNT_CREATED -> 2",
            "FirstRunOnboardingStage.VERIFIED_LOGIN -> 3",
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 4",
            "FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> 5",
            "FirstRunOnboardingStage.DEVICE_CHECK -> 6",
            "FirstRunOnboardingStage.FP004_TRAINING -> 7",
            "FirstRunOnboardingStage.COMPLETE -> 7",
        )

        val count = functionBlock("private fun firstRunStageCount(")
        assertTrue(count.contains("FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(count.contains("EMAIL_FIRST_RUN_STAGE_COUNT"))

        val progress = sourceBlock(
            "firstRunProgressBar = LinearLayout(this).apply",
            "firstRunOnboardingStatusText = TextView(this).apply",
        )
        assertTrue(
            progress.contains(
                "repeat(firstRunStageCount(firstRunOnboardingSnapshot))",
            ),
        )

        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertTrue(update.contains("val stageCount = firstRunStageCount(snapshot)"))
        assertTrue(update.contains("\$stageNumber/\${stageCount}단계"))
        assertFalse(update.contains("\$stageNumber/6"))
        assertFalse(update.contains("\$stageNumber/7"))
    }

    @Test
    fun existingAccountLoginIsPrimaryAndSignupDetailsAreProgressivelyDisclosed() {
        assertTrue(source.contains("private var accountSignupExpanded = false"))
        assertTrue(source.contains("private var accountConsentDisclosureExpanded = false"))
        assertTrue(source.contains("label = \"회원가입\""))
        assertTrue(source.contains("spokenLabel = \"새 계정 만들기, 가입 입력 펼치기\""))

        val controls = sourceBlock(
            "accountAccessControls = LinearLayout(this).apply",
            "emailEnrollmentPartial?.selections",
        )
        assertInOrder(
            controls,
            "addView(wsFieldGroup(accountEmailInput, \"이메일\"))",
            "addView(wsFieldGroup(accountPasswordInput, \"비밀번호\"",
            "addView(accountLoginButton)",
            "addView(accountSignupToggleButton)",
            "addView(accountSignupControls)",
        )
        assertFalse(source.contains("accountRememberMeCheck"))
        assertFalse(source.contains("이 기기에서 로그인 유지"))

        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(update.contains("if (verifiedLogin || reauthenticationRequired)"))
        assertTrue(update.contains("val reauthenticationRequired ="))
        assertTrue(update.contains("liveBinding?.actorId == expectedActorId"))
        assertTrue(update.contains("!reauthenticationRequired"))
        assertTrue(update.contains("accountSignupControls.visibility ="))
        assertTrue(update.contains("accountConsentCards.values.forEach"))
        assertTrue(update.contains("card.visibility = if (onConsentStep) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("accountConsentDisclosureText.visibility = View.GONE"))
        assertTrue(
            update.contains(
                "accountLoginButton.visibility = if (signupVisible) View.GONE else View.VISIBLE",
            ),
        )
        assertTrue(update.contains("로그인 화면으로 돌아가기"))
        assertTrue(update.contains("FirstRunOnboardingStage.ACCOUNT_CREATED"))
        assertTrue(update.contains("FirstRunOnboardingStage.VERIFIED_LOGIN"))
        assertTrue(update.contains("if (snapshot.isComplete)"))
        assertTrue(update.contains("로그인과 첫 실행 등록을 완료했습니다"))
        assertFalse(update.contains("accountEmailInput.text?.clear()"))
        assertFalse(update.contains("accountPasswordInput.text?.clear()"))
    }

    @Test
    fun lostSessionShowsReauthenticationWithoutDiscardingAdvancedProgress() {
        val sessionChange = functionBlock("private fun onGatewayProcessSessionChanged(")
        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        val login = functionBlock("private fun loginEmailAccount(")
        val sessionAction = functionBlock("private fun onAccountSessionButtonClicked()")

        assertTrue(sessionChange.contains("if (verifiedActorSession == null)"))
        assertTrue(sessionChange.contains("reporterUserId = null"))
        assertFalse(sessionChange.contains("firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initialEmailAccount("))
        assertTrue(update.contains("currentPostLoginDeviceCheckSessionBinding()"))
        assertTrue(update.contains("다시 로그인해 주세요."))
        assertTrue(update.contains("val credentialFieldsVisible = !onConsentStep"))
        assertTrue(update.contains("if (credentialFieldsVisible) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("accountLoginButton.visibility"))
        assertTrue(update.contains("이전 로그인 상태 정리"))
        assertTrue(login.contains("mayReauthenticateVerifiedEmailActor"))
        assertTrue(login.contains("firstRunSnapshot = nextFirstRunSnapshot"))
        assertTrue(source.contains("onClick = ::onAccountSessionButtonClicked"))
        assertTrue(sessionAction.contains("preservesAdvancedProgress"))
        assertTrue(sessionAction.contains("clearGatewaySession("))
        assertTrue(sessionAction.contains("온보딩 진도는 유지했습니다"))
        assertFalse(
            sessionAction.contains(
                "firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initialEmailAccount(",
            ),
        )
    }

    @Test
    fun staleSessionCleanupCannotDiscardDeletionRecoveryOrMaskStorageFailure() {
        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        val sessionAction = functionBlock("private fun onAccountSessionButtonClicked()")

        assertTrue(sessionAction.contains("process.deletionRecoveryOnly"))
        assertTrue(
            sessionAction.contains(
                "process.session?.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY",
            ),
        )
        assertTrue(sessionAction.contains("if (process.storageBlocked)"))
        assertInOrder(
            sessionAction,
            "process.deletionRecoveryOnly",
            "if (process.storageBlocked)",
            "val preservesAdvancedProgress",
            "val staleSession = process.session",
            "clearGatewaySession(",
        )
        assertTrue(sessionAction.contains("if (staleSession == null)"))
        assertTrue(sessionAction.contains("expectedSession = staleSession"))
        assertTrue(sessionAction.contains("expectedProcessGeneration = process.generation"))

        val clear = functionBlock("private fun clearGatewaySession(")
        assertTrue(clear.contains("expectedProcessGeneration: Long? = null"))
        assertTrue(clear.contains("beginGeneralSessionOperationIfCurrent("))

        assertTrue(update.contains("val deletionRecoveryBlocksReauthentication ="))
        assertTrue(update.contains("val storageBlocksReauthentication ="))
        assertTrue(update.contains("val staleGeneralSession ="))
        assertTrue(
            update.contains(
                "if (authenticated || staleGeneralSession) View.VISIBLE else View.GONE",
            ),
        )
        assertTrue(update.contains("계정 삭제 복구 전용 로그인이 진행 중입니다"))
        assertTrue(update.contains("로그인 저장소가 안전 차단 상태입니다"))
    }

    @Test
    fun openingSignupClearsOnlyTheDisplayedPasswordLoginFailureNotice() {
        val failure = functionBlock("private fun postAccountFailure(")
        assertInOrder(
            failure,
            "accountRequestFence.completeIfCurrent(",
            "showAccountMessage(message)",
            "passwordLoginFailureNotice =",
            "message.takeIf { token.action == AccountRemoteAction.PASSWORD_LOGIN }",
        )
        val message = functionBlock("private fun showAccountMessage(")
        assertInOrder(message, "passwordLoginFailureNotice = null", "accountAccessNotice = message")

        val toggle = sourceBlock(
            "accountSignupToggleButton = accessiblePriorityUserButton(",
            "accountConsentDisclosureToggleButton = accessiblePriorityUserButton(",
        )
        assertInOrder(
            toggle,
            "val opening = !accountSignupExpanded",
            "if (opening && passwordLoginFailureNotice != null &&",
            "accountAccessNotice == passwordLoginFailureNotice",
            "accountAccessNotice = null",
            "passwordLoginFailureNotice = null",
            "accountSignupExpanded = opening",
            "updateEmailAccountAccessUi(firstRunOnboardingSnapshot)",
        )
        assertFalse(toggle.contains("emailEnrollmentStorageBlocked ="))
        assertFalse(toggle.contains("emailEnrollmentStore.clear()"))
        assertFalse(toggle.contains("emailEnrollmentPartial ="))
        assertFalse(toggle.contains("GatewaySessionProcessCoordinator."))
        assertFalse(toggle.contains("accountRequestFence.cancel("))
        assertFalse(toggle.contains("check.isChecked ="))
    }

    private fun assertInOrder(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val current = source.indexOf(marker)
            assertTrue("Missing or out of order: $marker", current > previous)
            previous = current
        }
    }

    private fun sourceBlock(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return source.substring(start, end)
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing function: $signature" }
        val opening = source.indexOf('{', start)
        require(opening >= 0)
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}
