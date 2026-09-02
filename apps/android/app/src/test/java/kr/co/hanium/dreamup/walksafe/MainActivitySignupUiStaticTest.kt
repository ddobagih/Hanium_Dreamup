package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySignupUiStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun signupUsesThreeScreensAndSixInlineConsentCards() {
        // AppDesign signup-1..3 순서: 정보 입력 -> 인증번호 -> 약관 동의
        assertTrue(
            source.contains(
                "private enum class AccountSignupStep { DETAILS, OTP, CONSENT }",
            ),
        )
        assertTrue(source.contains("private val accountConsentCards"))
        assertTrue(source.contains("private val accountConsentClauseTexts"))

        val composition = sourceBlock(
            "val accountConsentDisclosure =",
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
        )
        assertTrue(composition.contains("val consentClauseStops ="))
        assertTrue(composition.contains("documentClauseOrNull("))
        assertTrue(composition.contains("applyWsClauseBox(this)"))
        assertTrue(composition.contains("addView(accountConsentAllCheck)"))
        assertTrue(composition.contains("addView(accountConsentCards.getValue(key))"))
        assertTrue(composition.contains("addView(accountConsentSummaryText)"))
        assertTrue(composition.contains("addView(accountConsentContinueButton)"))
        assertTrue(composition.contains("addView(accountConsentStepControls)"))
        assertTrue(composition.contains("addView(accountDetailsStepControls)"))
        assertTrue(composition.contains("addView(accountOtpStepControls)"))
        assertTrue(composition.contains("addView(accountCredentialStepControls)"))
        assertTrue(composition.contains("addView(accountSignupBackButton)"))
    }

    @Test
    fun inlineClausesHandScrollBackToTheOuterAndroidScrollViewAtTheirEdges() {
        val clauseBox = functionBlock("private fun applyWsClauseBox(")
        assertTrue(clauseBox.contains("ScrollingMovementMethod()"))
        assertTrue(clauseBox.contains("MotionEvent.ACTION_MOVE"))
        assertTrue(clauseBox.contains("child.canScrollVertically(direction)"))
        assertTrue(clauseBox.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(clauseBox.contains("requestDisallowInterceptTouchEvent(false)"))
        assertTrue(clauseBox.contains("view.isClickable = false"))
        assertTrue(clauseBox.contains("view.isLongClickable = false"))
    }

    @Test
    fun releasedSignupCopyDoesNotExposeInternalLaunchPlaceholders() {
        val disclosure = sourceBlock(
            "val accountConsentDisclosure =",
            "accountConsentDisclosureText = TextView(this).apply",
        )
        assertFalse(disclosure.contains("출시 전 확정"))
        assertFalse(disclosure.contains("확정 필요"))
    }

    @Test
    fun requiredThreeGateSignupProgressAndOptionalThreeDoNot() {
        val summary = functionBlock("private fun refreshAccountConsentSummary()")
        assertTrue(summary.contains("selections.requiredGranted"))
        assertTrue(summary.contains("selections.termsOfService"))
        assertTrue(summary.contains("selections.privacyNotice"))
        assertTrue(summary.contains("selections.locationTerms"))

        val availability = functionBlock("private fun refreshAccountActionAvailability()")
        assertTrue(availability.contains("validity.canContinueFromConsent"))
        assertFalse(availability.contains("rawOriginal"))
        assertFalse(availability.contains("automaticReporting"))
        assertFalse(availability.contains("trainingReuse"))
    }

    @Test
    fun validationErrorsTargetAndFocusTheExactInput() {
        val inputError = functionBlock("private fun showAccountInputError(")
        assertTrue(inputError.contains("target.error = message"))
        assertTrue(inputError.contains("target.requestFocus()"))
        assertTrue(inputError.contains("target.requestRectangleOnScreen("))

        val requestOtp = functionBlock("private fun requestEmailAccountOtp()")
        assertTrue(requestOtp.contains("showAccountInputError(accountEmailInput"))
        assertTrue(
            Regex("showAccountInputError\\s*\\(\\s*accountDateOfBirthInput")
                .containsMatchIn(requestOtp),
        )

        val create = functionBlock("private fun createEmailAccount()")
        assertTrue(
            Regex("showAccountInputError\\s*\\(\\s*accountEmailInput")
                .containsMatchIn(create),
        )
        assertTrue(create.contains("showAccountInputError(accountOtpInput"))
        assertTrue(
            Regex("showAccountInputError\\s*\\(\\s*accountPasswordInput")
                .containsMatchIn(create),
        )
        assertTrue(
            Regex("showAccountInputError\\s*\\(\\s*accountPasswordConfirmationInput")
                .containsMatchIn(create),
        )

        val login = functionBlock("private fun loginEmailAccount(")
        assertTrue(login.contains("showAccountInputError(accountEmailInput"))
        assertTrue(
            Regex("showAccountInputError\\s*\\(\\s*accountPasswordInput")
                .containsMatchIn(login),
        )
    }

    @Test
    fun invalidButNonBlankInputsKeepSubmitAttemptsReachableForExactErrors() {
        val availability = functionBlock("private fun refreshAccountActionAvailability()")
        val requestOtp = assignmentBlock(
            availability,
            "accountRequestOtpButton.isEnabled =",
            "accountCreateButton.isEnabled =",
        )
        val create = assignmentBlock(
            availability,
            "accountCreateButton.isEnabled =",
            "accountLoginButton.isEnabled =",
        )
        val login = availability.substring(availability.indexOf("accountLoginButton.isEnabled ="))

        assertInputNonBlankGuardUsed(availability, requestOtp, "accountEmailInput")
        assertInputNonBlankGuardUsed(availability, requestOtp, "accountDateOfBirthInput")
        assertRequiredConsentGuardUsed(availability, requestOtp)

        assertInputNonBlankGuardUsed(availability, create, "accountEmailInput")
        assertInputNonBlankGuardUsed(availability, create, "accountPasswordInput")
        assertInputNonBlankGuardUsed(availability, create, "accountPasswordConfirmationInput")
        assertInputNonBlankGuardUsed(availability, create, "accountOtpInput")
        assertRequiredConsentGuardUsed(availability, create)

        assertInputNonBlankGuardUsed(availability, login, "accountEmailInput")
        assertInputNonBlankGuardUsed(availability, login, "accountPasswordInput")

        assertFalse(availability.contains("validity.canRequestOtp"))
        assertFalse(availability.contains("validity.canCreateAccount"))
        assertFalse(availability.contains("validity.canLogin"))
    }

    @Test
    fun signupBackIsLastAndCommonToggleIsHiddenInsideSignup() {
        val signupControls = sourceBlock(
            "accountSignupControls = LinearLayout(this).apply",
            "accountAccessControls = LinearLayout(this).apply",
        )
        // 계정 만들기는 마지막 단계 컨테이너 안으로 들어갔고, 되돌아가기는 여전히 맨 끝이다.
        assertInOrder(
            signupControls,
            "addView(accountConsentStepControls)",
            "addView(accountDetailsStepControls)",
            "addView(accountOtpStepControls)",
            "addView(accountCredentialStepControls)",
            "addView(accountSignupBackButton)",
        )
        // 계정 만들기는 마지막 화면인 약관 동의 안에 있다.
        val consentStep = sourceBlock(
            "accountConsentStepControls = LinearLayout(this).apply",
            "accountDetailsStepControls = LinearLayout(this).apply",
        )
        assertTrue(consentStep.contains("addView(accountCreateButton)"))

        // NavBar 는 가입 컨테이너가 아니라 그 바깥, 공유 입력칸보다 위에 있어야 한다.
        // 안에 두면 이메일(로그인과 공유)과 생년월일 사이에 끼어 순서가 깨진다.
        val accessControls = sourceBlock(
            "accountAccessControls = LinearLayout(this).apply",
            "addView(accountAccessStatusText)",
        )
        assertTrue(accessControls.contains("addView(accountSignupNavBar)"))
        assertFalse(signupControls.contains("addView(accountSignupNavBar)"))

        // 라벨·힌트가 입력칸 없이 남지 않도록 가시성은 그룹 단위로 바꾼다.
        val update2 = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(update2.contains("wsFieldGroupOf(accountEmailInput).visibility"))
        assertTrue(update2.contains("wsFieldGroupOf(accountPasswordInput).visibility"))
        assertTrue(update2.contains("wsFieldGroupOf(accountOtpInput).visibility"))

        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        // 가입·로그인 화면에서는 고르는 화면의 진입 버튼이 사라진다.
        assertTrue(
            update.contains(
                "accountSignupToggleButton.visibility =\n" +
                    "            if (onLandingScreen) View.VISIBLE else View.GONE",
            ),
        )
        assertTrue(update.contains("accountSignupBackButton.visibility ="))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.CONSENT"))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.DETAILS"))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.OTP"))
    }

    @Test
    fun signupStepsAdvanceOnOtpSendAndNeverStrandTheUser() {
        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        val back = functionBlock("private fun accountSignupStepBack()")
        val navBar = functionBlock("private fun buildAccountSignupNavBar()")
        val otpRequest = functionBlock("private fun requestEmailAccountOtp()")

        // 앞으로 가는 전환은 OTP 발송 성공이 직접 한다. 파생 상태로 추측하지 않는다.
        assertTrue(otpRequest.contains("accountSignupStep = AccountSignupStep.OTP"))

        // enrollment 유무에 맞춰 단계를 양쪽으로 고정한다.
        assertTrue(
            update.contains(
                "if (signupVisible && !creating && accountSignupStep > AccountSignupStep.DETAILS)",
            ),
        )
        assertTrue(
            update.contains(
                "if (signupVisible && creating && accountSignupStep < AccountSignupStep.OTP)",
            ),
        )

        // OTP 를 보낸 뒤 정보 입력에는 누를 버튼이 없으므로 되돌아가기가 그리로 가지 않는다.
        assertTrue(back.contains("AccountSignupStep.OTP -> null"))
        assertTrue(back.contains("accountSignupToggleButton.performClick()"))

        // 인증번호 단계의 다음 버튼은 로컬 전환이다. 검증은 계정 생성에서 함께 한다.
        assertTrue(source.contains("accountSignupStep = AccountSignupStep.CONSENT"))

        assertTrue(navBar.contains("accountSignupStepBack()"))
        assertTrue(navBar.contains("minimumHeight = accessibilityTargetSizePx()"))
    }

    private fun assertInOrder(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val current = source.indexOf(marker)
            assertTrue("Missing or out of order: $marker", current > previous)
            previous = current
        }
    }

    private fun assignmentBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing assignment: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing assignment after $startMarker: $endMarker" }
        return source.substring(start, end)
    }

    private fun assertInputNonBlankGuardUsed(
        function: String,
        assignment: String,
        inputName: String,
    ) {
        val directGuard =
            "$inputName.text?.toString().orEmpty().isNotBlank()"
        if (assignment.contains(directGuard)) return
        val flag = Regex(
            "val\\s+(\\w+)\\s*=\\s*${Regex.escape(directGuard)}",
        ).find(function)?.groupValues?.get(1)
            ?: error("missing nonblank guard for $inputName")
        assertTrue(
            "$inputName nonblank guard is not used by assignment",
            Regex("\\b$flag\\b").containsMatchIn(assignment),
        )
    }

    private fun assertRequiredConsentGuardUsed(function: String, assignment: String) {
        if (
            assignment.contains("requiredGranted") ||
            assignment.contains("requiredConsentsGranted")
        ) return
        val flag = Regex(
            "val\\s+(\\w+)\\s*=\\s*(?:\\w+\\.)?required(?:Consents)?Granted",
        ).find(function)?.groupValues?.get(1)
            ?: error("missing required consent guard")
        assertTrue(
            "required consent guard is not used by assignment",
            Regex("\\b$flag\\b").containsMatchIn(assignment),
        )
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
