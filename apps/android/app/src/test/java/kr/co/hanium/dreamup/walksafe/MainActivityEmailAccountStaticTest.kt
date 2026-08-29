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
        assertTrue(create.contains("password != confirmation"))
        assertTrue(create.contains("Regex(\"^[0-9]{6}$\")"))
        assertTrue(create.contains("emailEnrollmentStore.clear()"))
    }

    @Test
    fun loginPublishesVerifiedSessionThenMovesExactlyToJit() {
        val login = functionBlock("private fun loginEmailAccount(")
        assertTrue(login.contains("gatewaySessionClient.loginWithPassword("))
        assertTrue(login.contains("gatewaySessionStore.getOrCreateInstallDeviceId()"))
        assertTrue(login.contains("deviceId = installationDeviceId"))
        assertTrue(login.contains("FirstRunOnboardingPolicy.recordVerifiedEmailLogin("))
        assertTrue(login.contains("GatewaySessionProcessCoordinator.publishVerified("))
        assertTrue(login.contains("next=jit_permission_observation"))
        assertFalse(login.contains("VERIFIED_SMS"))
        assertFalse(login.contains("LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
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
        assertTrue(changed.contains("firstRun.isComplete &&"))
        assertTrue(changed.contains("permissionSessionPolicy.authenticationExpired()"))
        assertTrue(changed.contains("updateFirstRunOnboardingUi()"))
    }

    @Test
    fun emailAccountProgressUsesTheActualSixStageFlow() {
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 6"))

        val numbering = functionBlock("private fun firstRunStageNumber(")
        assertInOrder(
            numbering,
            "FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT -> 1",
            "FirstRunOnboardingStage.ACCOUNT_CREATED -> 2",
            "FirstRunOnboardingStage.VERIFIED_LOGIN -> 3",
            "FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> 4",
            "FirstRunOnboardingStage.DEVICE_CHECK -> 5",
            "FirstRunOnboardingStage.FP004_TRAINING -> 6",
            "FirstRunOnboardingStage.COMPLETE -> 6",
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
    }

    @Test
    fun existingAccountLoginIsPrimaryAndSignupDetailsAreProgressivelyDisclosed() {
        assertTrue(source.contains("private var accountSignupExpanded = false"))
        assertTrue(source.contains("private var accountConsentDisclosureExpanded = false"))
        assertTrue(source.contains("label = \"새 계정 만들기\""))
        assertTrue(source.contains("label = \"가입 동의 자세히 보기\""))

        val controls = sourceBlock(
            "accountAccessControls = LinearLayout(this).apply",
            "emailEnrollmentPartial?.selections",
        )
        assertInOrder(
            controls,
            "addView(accountEmailInput)",
            "addView(accountPasswordInput)",
            "addView(accountRememberMeCheck)",
            "addView(accountLoginButton)",
            "addView(accountSignupToggleButton)",
            "addView(accountSignupControls)",
        )

        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(update.contains("if (verifiedLogin) accountSignupExpanded = false"))
        assertTrue(update.contains("val signupVisible = accountSignupExpanded && !verifiedLogin"))
        assertTrue(update.contains("accountSignupControls.visibility ="))
        assertTrue(update.contains("accountConsentDisclosureExpanded"))
        assertTrue(update.contains("accountConsentDisclosureText.visibility ="))
        assertTrue(
            update.contains(
                "accountRememberMeCheck.visibility = if (signupVisible) View.GONE else View.VISIBLE",
            ),
        )
        assertTrue(
            update.contains(
                "accountLoginButton.visibility = if (signupVisible) View.GONE else View.VISIBLE",
            ),
        )
        assertTrue(update.contains("로그인 화면으로 돌아가기"))
        assertTrue(update.contains("FirstRunOnboardingStage.ACCOUNT_CREATED"))
        assertTrue(update.contains("FirstRunOnboardingStage.VERIFIED_LOGIN"))
        assertFalse(update.contains("accountEmailInput.text?.clear()"))
        assertFalse(update.contains("accountPasswordInput.text?.clear()"))

        val toggle = sourceBlock(
            "accountSignupToggleButton = accessiblePriorityUserButton(",
            "accountConsentDisclosureToggleButton = accessiblePriorityUserButton(",
        )
        assertTrue(toggle.contains("accountRememberMeCheck.isChecked = false"))

        val create = functionBlock("private fun createEmailAccount()")
        assertTrue(create.contains("val rememberMe = false"))
        assertFalse(create.contains("val rememberMe = accountRememberMeCheck.isChecked"))
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
