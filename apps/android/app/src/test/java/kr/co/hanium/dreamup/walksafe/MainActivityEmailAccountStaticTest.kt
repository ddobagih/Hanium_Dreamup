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
