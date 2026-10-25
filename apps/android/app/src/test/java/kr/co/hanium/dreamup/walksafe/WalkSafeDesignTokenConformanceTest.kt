package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeDesignTokenConformanceTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val styleSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/WalkSafeViewStyle.kt").readText()

    private val accountSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt").readText()
    private val onboardingSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()

    private val firstRunPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()
    private val userReportPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/UserReportPresenter.kt",
    ).readText()

    private fun argb(name: String): String {
        val match = Regex("""const val $name = 0x([0-9a-fA-F]{8})\.toInt\(\)""")
            .find(styleSource)
        requireNotNull(match) { "missing constant: $name" }
        return "#" + match.groupValues[1].uppercase()
    }

    private fun dimension(name: String): String {
        val match = Regex("""const val $name = ([0-9.]+)f""").find(styleSource)
        requireNotNull(match) { "missing constant: $name" }
        return match.groupValues[1].removeSuffix(".0") + "dp"
    }

    @Test
    fun mainDelegatesToTheExactSharedStyleImplementation() {
        assertTrue(source.contains("WalkSafeViewStyle(this)"))
        listOf(
            "= viewStyle.applyAccessibleControlDefaults(root)",
            "= viewStyle.applyWsSecondaryButtonStyle(button)",
            "= viewStyle.applyWsButtonStyle(button, minHeightDp, primary)",
            "= viewStyle.wsButtonFace(fill, density, outlined, focused)",
            "= viewStyle.wsTypeface(style, medium)",
            "= viewStyle.accessiblePriorityUserButton(label, emphasis, spokenLabel, onClick)",
        ).forEach { delegate ->
            assertTrue("missing Main style connection: $delegate", source.contains(delegate))
        }
        val defaults = functionBlock("fun applyAccessibleControlDefaults(", styleSource)
        assertTrue(defaults.contains("applyWsStackSpacing(root)"))
        val content = functionBlock("private fun buildContentView(")
            .replace(Regex("\\s+"), "")
        assertTrue(content.contains("AccountAccessPanel(context=this,style=viewStyle,"))
        assertTrue(content.contains("OnboardingReadinessPanel(context=this,style=viewStyle,"))
        assertTrue(accountSource.contains("style.applyWsClauseBox(this)"))
        assertTrue(onboardingSource.contains("style.applyWsClauseBox(this)"))
    }

    @Test
    fun colorsMatchTheApprovedMinimalNativePalette() {
        val expected = mapOf(
            "WS_COLOR_OVERLAY_FILL" to "#FAFAF9F7",
            "WS_COLOR_WALK_OVERLAY_FILL" to "#F2FAF9F7",
            "WS_COLOR_NOTICE_FILL" to "#FFFFFFFF",
            "WS_COLOR_LINE" to "#FFDEDBD5",
            "WS_COLOR_BUTTON_TEXT" to "#FF1B1B1D",
            "WS_COLOR_NOTICE_TEXT" to "#FF5C5C64",
            "WS_COLOR_EMPHASIS" to "#FF1B1B1D",
            "WS_COLOR_WARNING" to "#FFC0340E",
            "WS_COLOR_BUTTON_FILL" to "#FFFFFFFF",
            "WS_COLOR_BUTTON_BORDER" to "#FF77747A",
            "WS_COLOR_BUTTON_PRESSED_FILL" to "#FFEEF2FF",
            "WS_COLOR_BUTTON_FOCUSED_FILL" to "#FF1B4CD8",
            "WS_COLOR_BUTTON_DISABLED_FILL" to "#FFE6E5E3",
            "WS_COLOR_BUTTON_DISABLED_TEXT" to "#FF747477",
            "WS_COLOR_GROUND" to "#FFFAF9F7",
            "WS_COLOR_PRIMARY_ACTION_FILL" to "#FF1B4CD8",
            "WS_COLOR_PRIMARY_ACTION_TEXT" to "#FFFFFFFF",
            "WS_COLOR_PRIMARY_ACTION_PRESSED_FILL" to "#FF153BA8",
            "WS_COLOR_PRIMARY_ACTION_DISABLED_FILL" to "#FFD8DDED",
            "WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT" to "#FF656A7A",
            "WS_COLOR_FOCUS" to "#FF1B4CD8",
        )
        expected.forEach { (name, value) -> assertEquals(name, value, argb(name)) }
    }

    @Test
    fun dimensionsMatchTheApprovedLargeNativeControls() {
        assertEquals("64dp", dimension("WS_TOUCH_MIN_DP"))
        assertEquals("72dp", dimension("WS_TOUCH_WALK_ACTION_DP"))
        assertEquals("88dp", dimension("WS_TOUCH_WALK_PRIMARY_DP"))
        assertEquals("16dp", dimension("WS_CORNER_RADIUS_DP"))
        assertEquals("24dp", dimension("WS_SECTION_GAP_DP"))
        assertEquals("12dp", dimension("WS_GROUP_GAP_DP"))
    }

    @Test
    fun fontThemeAndComponentStylesAreApplied() {
        val styles = File("src/main/res/values/styles.xml").readText()
        val stylesV29 = File("src/main/res/values-v29/styles.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val fontFamily = File("src/main/res/font/pretendard.xml").readText()
        val typeface = sourceBlock(
            "fun wsTypeface(",
            "fun wsButtonFace(",
            styleSource,
        )

        assertTrue(
            styles.contains(
                "<style name=\"AppTheme\" " +
                    "parent=\"android:style/Theme.Material.Light.NoActionBar\">",
            ),
        )
        assertTrue(styles.contains("<item name=\"android:fontFamily\">@font/pretendard</item>"))
        assertFalse(styles.contains("android:forceDarkAllowed"))
        assertTrue(
            stylesV29.contains(
                "<style name=\"AppTheme\" " +
                    "parent=\"android:style/Theme.Material.Light.NoActionBar\">",
            ),
        )
        assertTrue(
            stylesV29.contains("<item name=\"android:fontFamily\">@font/pretendard</item>"),
        )
        assertTrue(
            stylesV29.contains("<item name=\"android:forceDarkAllowed\">false</item>"),
        )
        assertTrue(manifest.contains("android:theme=\"@style/AppTheme\""))
        assertFontWeight(fontFamily, "400", "pretendard_regular")
        assertFontWeight(fontFamily, "500", "pretendard_medium")
        assertFontWeight(fontFamily, "700", "pretendard_bold")
        listOf("pretendard_regular.otf", "pretendard_medium.otf", "pretendard_bold.otf")
            .forEach { name ->
                val file = File("src/main/res/font/$name")
                assertTrue("missing font: $name", file.isFile)
                assertTrue("empty font: $name", file.length() > 0L)
            }
        val license = File("../licenses/pretendard-OFL-1.1.txt")
        val packagedLicense = File("src/main/assets/licenses/pretendard-OFL-1.1.txt")
        assertTrue("missing Pretendard license", license.isFile)
        assertTrue(license.readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue("missing packaged Pretendard license", packagedLicense.isFile)
        assertArrayEquals(license.readBytes(), packagedLicense.readBytes())
        assertTrue(typeface.contains("style == Typeface.BOLD -> R.font.pretendard_bold"))
        assertTrue(typeface.contains("medium -> R.font.pretendard_medium"))
        assertTrue(typeface.contains("else -> R.font.pretendard_regular"))
    }

    @Test
    fun componentStylesAreAppliedToTheCurrentVisibleControls() {
        val build = functionBlock("private fun buildContentView()")
        val contentComposer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeContentViewComposer.kt").readText()
        val runtimeComposer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        assertTrue(build.contains("NativeContentViewComposer(this, viewStyle)"))
        assertTrue(build.contains("contentComposer.createOverlayContainer("))
        assertTrue(functionBlock("fun createOverlayContainer(", contentComposer).contains("setBackgroundColor(WS_COLOR_OVERLAY_FILL)"))
        assertTrue(build.contains("NativeRuntimeViewComposer(this, viewStyle)"))
        assertTrue(build.contains("runtimeComposer.createWalkSafetyOverlay("))
        assertTrue(runtimeComposer.contains("setBackgroundColor(WS_COLOR_WALK_OVERLAY_FILL)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(overlay)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(walkSafetyOverlay)"))
        assertTrue(Regex("""applyWsButtonStyle\(\s*firstRunOnboardingPresenter\.readinessBinding\(\)\.startupCapabilityConfirmButton,\s*WS_TOUCH_WALK_PRIMARY_DP,\s*primary = true,\s*\)""").containsMatchIn(build))
        assertTrue(Regex("""contentComposer\.styleStatusCards\(\s*onboardingPanel\.firstRunPhonePostureText,\s*firstRunOnboardingPresenter\.readinessBinding\(\)\.startupCapabilityText,\s*firstRunOnboardingPresenter\.readinessBinding\(\)\.postLoginDeviceCheckLiveStatusText,\s*nativeScreenPresenter\.guidanceBinding\(\)\.nativeDestinationConfirmationText,\s*\)""").containsMatchIn(build))
        assertTrue(functionBlock("fun styleStatusCards(", contentComposer).contains("listOf(phonePosture, startup, deviceCheck, destination).forEach(style::applyWsStatusCard)"))
        assertTrue(build.contains("viewStyle.clearEmphasisButtons()"))
        assertTrue(build.contains("viewStyle.applyEmphasisButtonStyles()"))
        val emphasis = functionBlock("fun applyEmphasisButtonStyles(", styleSource)
        assertTrue(emphasis.contains("wsEmphasisButtons.forEach"))
        assertTrue(emphasis.contains("applyWsButtonStyle(it, WS_TOUCH_PRIMARY_DP, primary = true)"))
        assertTrue(functionBlock("private fun renderUserReportList(", userReportPresenterSource).contains("applyWsButtonStyle(detailButton, WS_TOUCH_WALK_ACTION_DP)"))
        val reports = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        assertTrue(functionBlock("private fun renderUserReportState(").contains("userReportInteractionOwner.renderUserReportState(state)"))
        assertTrue(MainActivityRefactorSource.member(source, "createUserReportInteractionOwner").contains("UserReportInteractionOwner("))
        assertTrue(MainActivityRefactorSource.member(reports, "renderUserReportState").contains("presentation.render(state, currentUserReportAuthorityOrNull())"))
        assertTrue(MainActivityRefactorSource.member(source, "createUserReportInteractionOwner").contains("userReportPresenter.render(state, authority)"))
        val destinationPanel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        assertTrue(build.contains("DestinationGuidancePanel("))
        assertTrue(build.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        assertTrue(functionBlock("private fun updateDestinationSearchUi()").contains("destinationSearchPresenter.renderSearch("))
        assertTrue(functionBlock("override fun renderSearchResults(", destinationPanel).contains("style.applyWsButtonStyle(this, WS_TOUCH_WALK_ACTION_DP)"))
    }

    @Test
    fun sharedControlStylesKeepVisibleFocusDisabledStateAndStackSpacing() {
        val defaults = functionBlock("fun applyAccessibleControlDefaults(", styleSource)
        val spacing = functionBlock("fun applyWsStackSpacing(", styleSource)
        val secondary = functionBlock("fun applyWsSecondaryButtonStyle(", styleSource)
        val button = functionBlock("fun applyWsButtonStyle(", styleSource)
        val face = functionBlock("fun wsButtonFace(", styleSource)
        assertTrue(defaults.contains("applyWsButtonStyle(root, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(defaults.contains("if (root is EditText) applyWsFieldStyle(root)"))
        assertTrue(defaults.contains("intArrayOf(WS_COLOR_PRIMARY_ACTION_FILL, WS_COLOR_BUTTON_BORDER)"))
        assertTrue(spacing.contains("children.dropLast(1).forEach"))
        assertTrue(spacing.contains("if (params.bottomMargin == 0) params.bottomMargin = gap"))
        assertFalse(spacing.contains("children.any"))
        assertTrue(secondary.contains("applyWsButtonStyle(button, 144f)"))
        assertTrue(button.contains("button.background = StateListDrawable().apply"))
        assertTrue(button.contains("intArrayOf(-android.R.attr.state_enabled)"))
        assertTrue(source.contains("= viewStyle.applyWsButtonStyle(button, minHeightDp, primary)"))
        assertTrue(button.contains("addState(intArrayOf(-android.R.attr.state_enabled), face(WS_COLOR_BUTTON_DISABLED_FILL))"))
        assertTrue(button.contains("WS_COLOR_BUTTON_DISABLED_TEXT"))
        assertTrue(button.contains("intArrayOf(android.R.attr.state_pressed)"))
        assertTrue(button.contains("intArrayOf(android.R.attr.state_focused)"))
        assertTrue(button.contains("face(restingFill, focused = true)"))
        assertTrue(button.contains("button.setTextColor("))
        assertTrue(face.contains("WS_FOCUS_BORDER_DP"))
        assertTrue(face.contains("WS_COLOR_FOCUS"))
    }

    @Test
    fun consentBodiesUseTheFullScrollablePageWithIndependentListeningControls() {
        val clause = functionBlock("fun applyWsClauseBox(", styleSource)
        val build = functionBlock("private fun buildContentView()")
        assertTrue(clause.contains("view.background = null"))
        assertTrue(clause.contains("view.textSize = 18f"))
        assertTrue(clause.contains("view.setLineSpacing(0f, 1.65f)"))
        assertTrue(clause.contains("view.maxHeight = Int.MAX_VALUE"))
        assertTrue(clause.contains("view.movementMethod = null"))
        assertTrue(clause.contains("view.isClickable = false"))
        assertTrue(accountSource.contains("consentClauseTexts[key] = clauseText"))
        assertTrue(accountSource.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(accountSource.contains("addView(clauseText)"))
        assertTrue(accountSource.contains("addView(listenButton)"))
        assertTrue(build.contains("accountConsentClauseTexts.putAll(accountPanel.accountConsentClauseTexts)"))
        assertTrue(build.contains("onListenConsent = {\n                    this@MainActivity.accountConsentSpeechOwner.playAccountConsentClause(it)"))
        assertFalse(build.contains("addView(firstRunConsentAllButton)"))
        assertFalse(onboardingSource.contains("addView(firstRunConsentAllButton)"))
        val runtimeComposer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        assertTrue(build.contains("NativeRuntimeViewComposer(this, viewStyle)"))
        assertTrue(build.contains("controlsScroll = runtimeComposer.createControlsScroll(overlay,"))
        assertTrue(functionBlock("fun createControlsScroll(", runtimeComposer).contains("isFillViewport = true"))
        val listening = MainActivityRefactorSource.function(source, "private fun playAccountConsentClause(")
        assertFalse(listening.contains("isChecked ="))
        assertFalse(listening.contains("acceptEducationConsent"))
        assertTrue(listening.contains("onCompleted = {"))
        assertTrue(listening.contains("onFailed ="))
    }

    @Test
    fun emailWaitingStateDoesNotReattachTheRemovedProgressCard() {
        val build = functionBlock("private fun buildContentView()")
        val waiting = MainActivityRefactorSource.function(source, "private fun firstRunWaitingTextOrNull(")
        val emailWaiting = waiting.substringBefore("return when (snapshot.stage)")
        val dots = functionBlock("fun updateFirstRunWaitingDots(", firstRunPresenterSource)
        val dotSettings = functionBlock("private fun updateFirstRunWaitingDots(")
        val update = MainActivityRefactorSource.function(source, "private fun updateFirstRunOnboardingUi(")
        val waitingComposition = MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator")
        val onResume = functionBlock("override fun onResume()")
        val onPause = functionBlock("override fun onPause()")

        val waitingCard = sourceBlock("firstRunWaitingText = TextView(context).apply", "priorityUserOnboardingStatusText = TextView(context).apply", onboardingSource)
        assertTrue(waitingCard.contains("repeat(FIRST_RUN_WAITING_DOT_COUNT)"))
        assertTrue(waitingCard.contains("GradientDrawable.OVAL"))
        assertTrue(waitingCard.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(waitingCard.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(build.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        assertTrue(MainActivityRefactorSource.member(registration, "bindRegistration").contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativeRegistrationComposition").contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication)"))
        assertTrue(build.contains("nativeRegistrationComposition.publishRegistrationConsentTail()"))
        assertTrue(MainActivityRefactorSource.member(registration, "publishRegistrationConsentTail").contains("publication.publish(NativeUiPublication.Target.FIRST_RUN_WAITING_CARD)"))
        assertTrue(functionBlock("fun bindOnboardingPanel(", firstRunPresenterSource).contains("onboarding = panel"))
        assertTrue(functionBlock("fun publishRegistrationTargets(", firstRunPresenterSource).contains("publishedRegistration = onboarding"))
        assertTrue(firstRunPresenterSource.contains("fun registrationBinding(): OnboardingReadinessPanel = publishedRegistration"))
        assertTrue(dots.contains("with(onboarding)"))
        assertTrue(dots.contains("firstRunWaitingDots.forEachIndexed { index, dot ->"))
        assertTrue(MainActivityRefactorSource.member(registration, "bindRegistration").contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertFalse(build.contains("addView(firstRunWaitingCard)"))
        assertFalse(onboardingSource.contains("addView(firstRunWaitingCard)"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.VERIFIED_SMS"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.GUARDIAN_APPROVAL"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.ACCOUNT_ACTIVATION"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.VERIFIED_LOGIN"))
        assertTrue(emailWaiting.contains("snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(emailWaiting.contains("return if (facts.accountRequestInFlight())"))
        assertTrue(
            emailWaiting.contains(
                "인증번호 요청, 계정 생성 또는 로그인을 처리하고 있습니다. 잠시 기다려 주세요.",
            ),
        )
        assertTrue(emailWaiting.contains("else {\n                null\n            }"))
        assertFalse(emailWaiting.contains("snapshot.stage"))
        assertFalse(waiting.contains("FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
        assertFalse(waiting.contains("Button("))
        assertFalse(waiting.contains("재전송"))
        assertFalse(waiting.contains("타이머"))
        assertTrue(dotSettings.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(dotSettings.contains("firstRunOnboardingPresenter.updateFirstRunWaitingDots(active, animationsEnabled)"))
        assertTrue(dots.contains("AlphaAnimation(FIRST_RUN_WAITING_DOT_MIN_ALPHA, 1f)"))
        assertTrue(dots.contains("repeatMode = Animation.REVERSE"))
        assertTrue(dots.contains("repeatCount = Animation.INFINITE"))
        assertTrue(dots.contains("dot.clearAnimation()"))
        assertTrue(functionBlock("fun renderWaiting(", firstRunPresenterSource).contains("firstRunWaitingCard.visibility ="))
        assertTrue(update.contains("effects.renderWaiting {"))
        assertTrue(waitingComposition.contains("firstRunOnboardingPresenter.renderWaiting(facts())"))
        assertTrue(waitingComposition.contains("override fun accountRequestInFlight() = emailAccountController.isRequestInFlight()"))
        assertTrue(functionBlock("fun renderWaiting(", firstRunPresenterSource).contains("active = waitingText != null && facts.activityForeground"))
        assertTrue(update.contains("text = firstRunWaitingTextOrNull(snapshot)"))
        assertTrue(update.contains("activityForeground = facts.foreground"))
        assertTrue(waitingComposition.contains("override val foreground get() = isActivityForeground"))
        assertTrue(onResume.contains("updateFirstRunWaitingDots("))
        assertTrue(onPause.contains("updateFirstRunWaitingDots(active = false)"))
        assertTrue(onboardingSource.contains("const val FIRST_RUN_WAITING_DOT_COUNT = 3"))
        assertTrue(firstRunPresenterSource.contains("const val FIRST_RUN_WAITING_DOT_MIN_ALPHA = 0.2f"))
        assertTrue(firstRunPresenterSource.contains("const val FIRST_RUN_WAITING_DOT_PERIOD_MS = 750L"))
        assertTrue(firstRunPresenterSource.contains("const val FIRST_RUN_WAITING_DOT_STAGGER_MS = 300L"))
    }

    private fun assertFontWeight(xml: String, weight: String, resource: String) {
        assertTrue(
            "missing Pretendard $weight mapping",
            Regex(
                """<font(?=[^>]*android:fontWeight="$weight")""" +
                    """(?=[^>]*android:font="@font/$resource")[^>]*/>""",
            ).containsMatchIn(xml),
        )
    }

    private fun functionBlock(signature: String, text: String = source): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing function: $signature" }
        val opening = text.indexOf('{', start)
        require(opening >= 0) { "missing function body: $signature" }
        var depth = 0
        for (index in opening until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }

    private fun sourceBlock(startMarker: String, endMarker: String, text: String = source): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return text.substring(start, end)
    }
}
