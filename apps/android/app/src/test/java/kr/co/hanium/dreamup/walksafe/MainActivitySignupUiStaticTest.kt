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
    fun signupUsesTwoScreensAndSixInlineConsentCards() {
        assertTrue(source.contains("private enum class AccountSignupStep { CONSENT, DETAILS }"))
        assertTrue(source.contains("private val accountConsentCards"))
        assertTrue(source.contains("private val accountConsentClauseTexts"))

        val composition = sourceBlock(
            "val accountConsentDisclosure =",
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
        )
        assertTrue(composition.contains("val consentClauseStops ="))
        assertTrue(composition.contains("documentClauseOrNull("))
        assertTrue(composition.contains("applyWsClauseBox(this)"))
        assertFalse(composition.contains("addView(accountConsentAllCheck)"))
        listOf(
            "terms_of_service",
            "privacy_notice",
            "location_terms",
            "raw_original",
            "automatic_reporting",
            "training_reuse",
        ).forEach { key -> assertTrue(composition.contains("\"$key\" to")) }
        assertTrue(composition.contains("addView(accountConsentCards.getValue(key))"))
        assertTrue(composition.contains("showMissingRequiredConsentError()"))
        assertTrue(composition.contains("addView(listenButton)"))
        assertTrue(composition.contains("addView(accountConsentContinueButton)"))
        assertTrue(composition.contains("addView(accountConsentStepControls)"))
        assertTrue(composition.contains("addView(accountDetailsStepControls)"))
        assertTrue(composition.contains("addView(accountSignupBackButton)"))
    }

    @Test
    fun inlineClausesUseTheOuterScrollViewWithoutInterceptingTouches() {
        val clauseBox = functionBlock("private fun applyWsClauseBox(")
        assertTrue(clauseBox.contains("view.maxHeight = Int.MAX_VALUE"))
        assertTrue(clauseBox.contains("view.isVerticalScrollBarEnabled = false"))
        assertTrue(clauseBox.contains("view.movementMethod = null"))
        assertTrue(clauseBox.contains("view.setOnTouchListener(null)"))
        assertFalse(clauseBox.contains("ScrollingMovementMethod()"))
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
        assertInOrder(
            signupControls,
            "addView(accountCreateButton)",
            "addView(accountSignupBackButton)",
        )

        val update = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(update.contains("signupVisible -> View.GONE"))
        assertTrue(update.contains("accountSignupBackButton.visibility ="))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.CONSENT"))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.DETAILS"))
    }

    @Test
    fun selectedVisualPatternsKeepTheExistingTwoStepFlowAccessible() {
        assertTrue(source.contains("private enum class AccountSignupStep { CONSENT, DETAILS }"))
        val home = functionBlock("private fun buildHomeCardGrid()")
        assertTrue(home.contains("orientation = LinearLayout.VERTICAL"))
        assertTrue(home.contains("title = \"길라잡이\""))
        assertTrue(home.contains("title = \"음성 명령\""))
        assertTrue(home.contains("title = \"설정\""))
        assertFalse(home.contains("title = \"손상 점자블록 신고\""))
        assertFalse(home.contains("설정 화면은 준비 중입니다"))
        val settingsCard = home.substringAfter("title = \"설정\"")
        assertTrue(settingsCard.contains("unlocked = { true }"))
        assertTrue(settingsCard.contains("showNativeUiPage(NativeUiPage.SETTINGS)"))
        assertTrue(home.contains("nativeFeatureAvailable(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(home.contains("nativeFeatureAvailable(NativeUiPage.DESTINATION_SEARCH)"))
        assertTrue(home.contains("requestNativeFeature(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(home.contains("requestNativeFeature(NativeUiPage.DESTINATION_SEARCH)"))

        val card = functionBlock("private fun wsHomeCard(")
        assertTrue(card.contains("val card = Button(this).apply"))
        assertTrue(card.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(card.contains("잠김. \$lockTitle. \$lockDetail"))
        assertTrue(card.contains("setOnClickListener { onOpen() }"))
        assertTrue(card.contains("card.isEnabled = !isWalkSessionRuntimeActive() || unlocked()"))
        val refresh = functionBlock("private fun refreshHomeCards()")
        assertTrue(refresh.contains("renderMainUi()"))
        assertFalse(refresh.contains("WalkSessionState.ACTIVE"))

        val fields = functionBlock("private fun wsFieldGroup(")
        assertTrue(fields.contains("text = label"))
        assertTrue(fields.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        val composition = sourceBlock(
            "accountDetailsStepControls = LinearLayout(this).apply",
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
        )
        assertTrue(composition.contains("wsFieldGroup(accountDateOfBirthInput"))
        assertTrue(composition.contains("wsFieldGroup(accountEmailInput"))
        assertTrue(composition.contains("wsFieldGroup(accountPasswordInput"))

        assertTrue(
            source.contains(
                "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
            ),
        )
        val safetyNotice = functionBlock("private fun refreshFirstRunNoticeUi(")
        assertTrue(safetyNotice.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
        assertTrue(safetyNotice.contains("firstRunNoticeToggleButton.visibility = View.GONE"))
        assertTrue(safetyNotice.contains("productPurposeText.visibility = if (visible) View.VISIBLE else View.GONE"))
        val accountControls = sourceBlock(
            "accountAccessControls = LinearLayout(this).apply",
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
        )
        assertFalse(accountControls.contains("addView(accountLandingHeader)"))

        val reportDisclosure = functionBlock(
            "private fun renderExplicitReportConfirmationDisclosure(",
        )
        val reportIdle = functionBlock("private fun renderExplicitReportConfirmationIdle(")
        val voiceButton = functionBlock("private fun updateVoiceCommandButton(")
        assertTrue(reportDisclosure.contains("refreshHomeCards()"))
        assertTrue(reportIdle.contains("refreshHomeCards()"))
        assertTrue(voiceButton.contains("refreshHomeCards()"))
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
