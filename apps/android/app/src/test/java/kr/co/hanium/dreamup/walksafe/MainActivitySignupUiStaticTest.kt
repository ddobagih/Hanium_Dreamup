package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySignupUiStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val accountPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt",
    ).readText()

    private val accountPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt",
    ).readText()

    private val homePanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/HomePanel.kt",
    ).readText()

    private val styleSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/WalkSafeViewStyle.kt",
    ).readText()

    private val firstRunPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()

    @Test
    fun signupUsesTwoScreensAndSixInlineConsentCards() {
        assertTrue(accountPresenterSource.contains("enum class AccountSignupStep { CONSENT, DETAILS }"))
        assertTrue(source.contains("private val accountConsentCards"))
        assertTrue(source.contains("private val accountConsentClauseTexts"))

        val composition = sourceBlock(
            "val accountConsentDisclosure =",
            "private fun bindAccountInputValidation(",
            accountPanelSource,
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
        assertTrue(composition.contains("addView(consentCards.getValue(key))"))
        assertTrue(sourceBlock("onConsentContinue = {", "onRequestOtp =").contains("showMissingRequiredConsentError()"))
        assertTrue(accountPanelSource.contains("onClick = actions.onConsentContinue"))
        assertTrue(source.contains("accountConsentCards.putAll(accountPanel.accountConsentCards)"))
        assertTrue(composition.contains("addView(listenButton)"))
        assertTrue(composition.contains("addView(accountConsentContinueButton)"))
        assertTrue(composition.contains("addView(accountConsentStepControls)"))
        assertTrue(composition.contains("addView(accountDetailsStepControls)"))
        assertTrue(composition.contains("addView(accountSignupBackButton)"))
    }

    @Test
    fun inlineClausesUseTheOuterScrollViewWithoutInterceptingTouches() {
        val clauseBox = functionBlock("fun applyWsClauseBox(", styleSource)
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
            "accountConsentDisclosureText = TextView(context).apply",
            accountPanelSource,
        )
        assertFalse(disclosure.contains("출시 전 확정"))
        assertFalse(disclosure.contains("확정 필요"))
    }

    @Test
    fun requiredThreeGateSignupProgressAndOptionalThreeDoNot() {
        val summary = functionBlock("fun refreshAccountConsentSummary(", accountPresenterSource)
        assertTrue(summary.contains("selections.requiredGranted"))
        assertTrue(summary.contains("selections.termsOfService"))
        assertTrue(summary.contains("selections.privacyNotice"))
        assertTrue(summary.contains("selections.locationTerms"))

        val availability = functionBlock("fun refreshAccountActionAvailability(", accountPresenterSource)
        assertTrue(availability.contains("validity.canContinueFromConsent"))
        assertFalse(availability.contains("rawOriginal"))
        assertFalse(availability.contains("automaticReporting"))
        assertFalse(availability.contains("trainingReuse"))
    }

    @Test
    fun validationErrorsTargetAndFocusTheExactInput() {
        val inputError = functionBlock("fun showAccountInputError(", accountPresenterSource)
        assertTrue(functionBlock("private fun showAccountInputError(").contains("accountAccessPresenter.showAccountInputError(target, message)"))
        assertTrue(inputError.contains("target.error = message"))
        assertTrue(inputError.contains("target.requestFocus()"))
        assertTrue(inputError.contains("target.requestRectangleOnScreen("))

        val factory = MainActivityRefactorSource.member(source, "createEmailAccountController")
        assertTrue(factory.contains("showAccountInputError(this@MainActivity.accountAccessPresenter.inputTarget(field), message)"))
        val inputBridge = factory
        assertTrue(inputBridge.contains("override fun text(field: EmailAccountController.InputField) = this@MainActivity.accountAccessPresenter.inputTarget(field).text?.toString().orEmpty()"))
        val fields = functionBlock("fun inputTarget(", accountPresenterSource)
        mapOf("EMAIL" to "accountEmailInput", "DATE_OF_BIRTH" to "accountDateOfBirthInput",
            "PASSWORD" to "accountPasswordInput", "PASSWORD_CONFIRMATION" to "accountPasswordConfirmationInput",
            "OTP" to "accountOtpInput").forEach { (field, control) ->
            assertTrue(fields.contains("EmailAccountController.InputField.$field -> publishedInputs.$control"))
        }
        fun assertTargets(body: String, vararg expected: String) = expected.forEach { field ->
            assertTrue(Regex("ui\\.showInputError\\s*\\(\\s*InputField\\.$field\\b").containsMatchIn(body))
        }
        assertTargets(functionBlock("private fun requestEmailAccountOtp()"), "EMAIL", "DATE_OF_BIRTH")
        assertTargets(functionBlock("private fun createEmailAccount()"), "EMAIL", "OTP", "PASSWORD", "PASSWORD_CONFIRMATION")
        assertTargets(functionBlock("private fun loginEmailAccount("), "EMAIL", "PASSWORD")
    }

    @Test
    fun invalidButNonBlankInputsKeepSubmitAttemptsReachableForExactErrors() {
        val availability = functionBlock("fun refreshAccountActionAvailability(", accountPresenterSource)
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
            "accountSignupControls = LinearLayout(context).apply",
            "accountAccessControls = LinearLayout(context).apply",
            accountPanelSource,
        )
        assertInOrder(
            signupControls,
            "addView(accountCreateButton)",
            "addView(accountSignupBackButton)",
        )

        val update = functionBlock("fun updateEmailAccountAccessUi(", accountPresenterSource)
        assertTrue(MainActivityRefactorSource.member(source, "updateEmailAccountAccessUi").contains("accountAccessInteractionOwner.updateEmailAccountAccessUi(snapshot)"))
        assertTrue(functionBlock("private fun updateEmailAccountAccessUi(").contains("screen.renderAccess(AccountAccessPresenter.AccessFacts("))
        assertTrue(MainActivityRefactorSource.member(source, "createAccountAccessInteractionOwner").contains("accountAccessPresenter.updateEmailAccountAccessUi(facts)"))
        assertTrue(update.contains("signupVisible -> View.GONE"))
        assertTrue(update.contains("accountSignupBackButton.visibility ="))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.CONSENT"))
        assertTrue(update.contains("accountSignupStep == AccountSignupStep.DETAILS"))
    }

    @Test
    fun selectedVisualPatternsKeepTheExistingTwoStepFlowAccessible() {
        assertTrue(accountPresenterSource.contains("enum class AccountSignupStep { CONSENT, DETAILS }"))
        val home = homePanelSource
        val homeWiring = sourceBlock("val homePanel = HomePanel(", "nativeScreenPresenter.bindRuntimeViews(")
        assertTrue(homeWiring.contains("style = viewStyle"))
        assertTrue(homeWiring.contains("nativeHomeUiPresenter.publishGridTarget(homePanel)"))
        assertTrue(homeWiring.contains("home = homePanel"))
        assertTrue(home.contains("orientation = LinearLayout.VERTICAL"))
        assertTrue(home.contains("title = \"길라잡이\""))
        assertTrue(home.contains("title = \"음성 명령\""))
        assertTrue(home.contains("title = \"설정\""))
        assertFalse(home.contains("title = \"손상 점자블록 신고\""))
        assertFalse(home.contains("설정 화면은 준비 중입니다"))
        val settingsCard = sourceBlock(
            "renderCardAvailability(\n            settingsCard,",
            "\n    }",
            homePanelSource,
        )
        assertTrue(settingsCard.contains("available = true"))
        assertTrue(homeWiring.contains("showNativeUiPage(NativeUiPage.SETTINGS)"))
        assertTrue(homePanelSource.contains("homeCard(\"설정\", actions.onSettings)"))
        assertTrue(homeWiring.contains("nativeFeatureAvailable(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(homeWiring.contains("nativeFeatureAvailable(NativeUiPage.DESTINATION_SEARCH)"))
        assertTrue(homeWiring.contains("requestNativeFeature(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(homeWiring.contains("requestNativeFeature(NativeUiPage.DESTINATION_SEARCH)"))

        val card = functionBlock("private fun homeCard(", homePanelSource)
        val cardAvailability = functionBlock("private fun renderCardAvailability(", homePanelSource)
        assertTrue(card.contains("Button(context).apply"))
        assertTrue(card.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(cardAvailability.contains("잠김. \$lockTitle. \$lockDetail"))
        assertTrue(card.contains("setOnClickListener { onOpen() }"))
        assertTrue(cardAvailability.contains("val cardEnabled = !runtimeActive || available"))
        assertTrue(cardAvailability.contains("card.isEnabled = cardEnabled"))
        assertTrue(cardAvailability.indexOf("val cardEnabled =") < cardAvailability.indexOf("card.isEnabled = cardEnabled"))
        val refresh = functionBlock("private fun refreshHomeCards()")
        assertTrue(refresh.contains("renderMainUi()"))
        assertFalse(refresh.contains("WalkSessionState.ACTIVE"))

        val fields = functionBlock("fun wsFieldGroup(", styleSource)
        assertTrue(fields.contains("text = label"))
        assertTrue(fields.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        val composition = sourceBlock(
            "accountDetailsStepControls = LinearLayout(context).apply",
            "private fun bindAccountInputValidation(",
            accountPanelSource,
        )
        assertTrue(composition.contains("wsFieldGroup(accountDateOfBirthInput"))
        assertTrue(composition.contains("wsFieldGroup(accountEmailInput"))
        assertTrue(composition.contains("wsFieldGroup(accountPasswordInput"))

        val voiceFirstPresentation = functionBlock(
            "fun applyVoiceFirstPresentation(",
            File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText(),
        )
        val compactPresentation = voiceFirstPresentation.filterNot { it.isWhitespace() }
        assertTrue(compactPresentation.contains("valguidanceVisible=pageName==\"GUIDANCE\""))
        assertTrue(compactPresentation.contains(
            "valshown=(it===destination.nativeGuidanceStatusText&&guidanceVisible&&speechFailure!=null)",
        ))
        assertTrue(compactPresentation.contains(
            "valimportance=if(shown)View.IMPORTANT_FOR_ACCESSIBILITY_YES" +
                "elseView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
        ))
        assertTrue(compactPresentation.contains(
            "if(it.importantForAccessibility!=importance)it.importantForAccessibility=importance",
        ))
        assertTrue(functionBlock("private fun applyNativeVoiceFirstPresentation(").contains("nativeScreenPresenter.applyVoiceFirstPresentation("))
        val safetyNotice = functionBlock("private fun refreshFirstRunNoticeUi(")
        assertTrue(safetyNotice.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
        assertTrue(safetyNotice.contains("firstRunOnboardingPresenter.refreshFirstRunNoticeUi(visible)"))
        val noticeUi = functionBlock("fun refreshFirstRunNoticeUi(", firstRunPresenterSource)
        assertTrue(noticeUi.contains("firstRunNoticeToggleButton.visibility = View.GONE"))
        assertTrue(noticeUi.contains("productPurposeText.visibility = if (purposeStageVisible) View.VISIBLE else View.GONE"))
        val accountControls = sourceBlock(
            "accountAccessControls = LinearLayout(context).apply",
            "private fun bindAccountInputValidation(",
            accountPanelSource,
        )
        assertFalse(accountControls.contains("addView(accountLandingHeader)"))

        val reportDisclosure = functionBlock(
            "private fun renderExplicitReportConfirmationDisclosure(",
        )
        val reportIdle = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AndroidExplicitReportPresentation.kt").readText(), "renderIdle")
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

    private fun sourceBlock(startMarker: String, endMarker: String, ownerSource: String = source): String {
        val start = ownerSource.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = ownerSource.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return ownerSource.substring(start, end)
    }

    private fun functionBlock(signature: String, ownerSource: String = source): String =
        MainActivityRefactorSource.function(ownerSource, signature)
}
