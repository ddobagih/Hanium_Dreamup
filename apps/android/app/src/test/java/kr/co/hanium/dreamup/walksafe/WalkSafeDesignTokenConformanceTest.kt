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

    private fun argb(name: String): String {
        val match = Regex("""const val $name = 0x([0-9a-fA-F]{8})\.toInt\(\)""")
            .find(source)
        requireNotNull(match) { "missing constant: $name" }
        return "#" + match.groupValues[1].uppercase()
    }

    private fun dimension(name: String): String {
        val match = Regex("""const val $name = ([0-9.]+)f""").find(source)
        requireNotNull(match) { "missing constant: $name" }
        return match.groupValues[1].removeSuffix(".0") + "dp"
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
            "private fun wsTypeface(",
            "private fun wsButtonFace(",
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
        assertTrue(build.contains("setBackgroundColor(WS_COLOR_OVERLAY_FILL)"))
        assertTrue(build.contains("setBackgroundColor(WS_COLOR_WALK_OVERLAY_FILL)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(overlay)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(walkSafetyOverlay)"))
        assertTrue(Regex("""applyWsButtonStyle\(\s*startupCapabilityConfirmButton,\s*WS_TOUCH_WALK_PRIMARY_DP,\s*primary = true,\s*\)""").containsMatchIn(build))
        assertTrue(Regex("""listOf\(\s*firstRunPhonePostureText,\s*startupCapabilityText,\s*postLoginDeviceCheckLiveStatusText,\s*nativeDestinationConfirmationText,\s*\)\.forEach\(::applyWsStatusCard\)""").containsMatchIn(build))
        assertTrue(build.contains("wsEmphasisButtons.forEach"))
        assertTrue(build.contains("applyWsButtonStyle(it, WS_TOUCH_PRIMARY_DP, primary = true)"))
        assertTrue(functionBlock("private fun renderUserReportList(").contains("applyWsButtonStyle(detailButton, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(functionBlock("private fun updateDestinationSearchUi()").contains("applyWsButtonStyle(this, WS_TOUCH_WALK_ACTION_DP)"))
    }

    @Test
    fun sharedControlStylesKeepVisibleFocusDisabledStateAndStackSpacing() {
        val defaults = functionBlock("private fun applyAccessibleControlDefaults(")
        val spacing = functionBlock("private fun applyWsStackSpacing(")
        val secondary = functionBlock("private fun applyWsSecondaryButtonStyle(")
        val button = functionBlock("private fun applyWsButtonStyle(")
        val face = functionBlock("private fun wsButtonFace(")
        assertTrue(defaults.contains("applyWsButtonStyle(root, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(defaults.contains("if (root is EditText) applyWsFieldStyle(root)"))
        assertTrue(defaults.contains("intArrayOf(WS_COLOR_PRIMARY_ACTION_FILL, WS_COLOR_BUTTON_BORDER)"))
        assertTrue(spacing.contains("children.dropLast(1).forEach"))
        assertTrue(spacing.contains("if (params.bottomMargin == 0) params.bottomMargin = gap"))
        assertFalse(spacing.contains("children.any"))
        assertTrue(secondary.contains("applyWsButtonStyle(button, 144f)"))
        assertTrue(button.contains("button.background = StateListDrawable().apply"))
        assertTrue(button.contains("intArrayOf(-android.R.attr.state_enabled)"))
        assertTrue(button.contains("face(restingFill, faded = true)"))
        assertTrue(button.contains("intArrayOf(android.R.attr.state_pressed)"))
        assertTrue(button.contains("intArrayOf(android.R.attr.state_focused)"))
        assertTrue(button.contains("face(restingFill, focused = true)"))
        assertTrue(button.contains("button.setTextColor("))
        assertTrue(face.contains("WS_FOCUS_BORDER_DP"))
        assertTrue(face.contains("WS_COLOR_FOCUS"))
    }

    @Test
    fun consentBodiesUseTheFullScrollablePageWithIndependentListeningControls() {
        val clause = functionBlock("private fun applyWsClauseBox(")
        val build = functionBlock("private fun buildContentView()")
        assertTrue(clause.contains("view.background = null"))
        assertTrue(clause.contains("view.textSize = 18f"))
        assertTrue(clause.contains("view.setLineSpacing(0f, 1.65f)"))
        assertTrue(clause.contains("view.maxHeight = Int.MAX_VALUE"))
        assertTrue(clause.contains("view.movementMethod = null"))
        assertTrue(clause.contains("view.isClickable = false"))
        assertTrue(build.contains("accountConsentClauseTexts[key] = clauseText"))
        assertTrue(build.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(build.contains("addView(clauseText)"))
        assertTrue(build.contains("addView(listenButton)"))
        assertFalse(build.contains("addView(firstRunConsentAllButton)"))
        assertTrue(source.contains("controlsScroll = ScrollView(this).apply {\n            isFillViewport = true"))
        val listening = functionBlock("private fun playAccountConsentClause(")
        assertFalse(listening.contains("isChecked ="))
        assertFalse(listening.contains("acceptEducationConsent"))
        assertTrue(listening.contains("onCompleted = {"))
        assertTrue(listening.contains("onFailed ="))
    }

    @Test
    fun emailWaitingStateDoesNotReattachTheRemovedProgressCard() {
        val build = functionBlock("private fun buildContentView()")
        val waiting = functionBlock("private fun firstRunWaitingTextOrNull(")
        val emailWaiting = waiting.substringBefore("return when (snapshot.stage)")
        val dots = functionBlock("private fun updateFirstRunWaitingDots(")
        val update = functionBlock("private fun updateFirstRunOnboardingUi(")
        val onResume = functionBlock("override fun onResume()")
        val onPause = functionBlock("override fun onPause()")

        assertTrue(build.contains("firstRunWaitingCard = LinearLayout(this).apply"))
        assertTrue(build.contains("repeat(FIRST_RUN_WAITING_DOT_COUNT)"))
        assertTrue(build.contains("GradientDrawable.OVAL"))
        assertTrue(build.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(build.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertFalse(build.contains("addView(firstRunWaitingCard)"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.VERIFIED_SMS"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.GUARDIAN_APPROVAL"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.ACCOUNT_ACTIVATION"))
        assertTrue(waiting.contains("FirstRunOnboardingStage.VERIFIED_LOGIN"))
        assertTrue(emailWaiting.contains("snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(emailWaiting.contains("return if (accountRequestFence.isInFlight())"))
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
        assertTrue(dots.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(dots.contains("AlphaAnimation(FIRST_RUN_WAITING_DOT_MIN_ALPHA, 1f)"))
        assertTrue(dots.contains("repeatMode = Animation.REVERSE"))
        assertTrue(dots.contains("repeatCount = Animation.INFINITE"))
        assertTrue(dots.contains("dot.clearAnimation()"))
        assertTrue(update.contains("firstRunWaitingCard.visibility ="))
        assertTrue(update.contains("active = waitingText != null && isActivityForeground"))
        assertTrue(onResume.contains("updateFirstRunWaitingDots("))
        assertTrue(onPause.contains("updateFirstRunWaitingDots(active = false)"))
        assertTrue(source.contains("const val FIRST_RUN_WAITING_DOT_COUNT = 3"))
        assertTrue(source.contains("const val FIRST_RUN_WAITING_DOT_MIN_ALPHA = 0.2f"))
        assertTrue(source.contains("const val FIRST_RUN_WAITING_DOT_PERIOD_MS = 750L"))
        assertTrue(source.contains("const val FIRST_RUN_WAITING_DOT_STAGGER_MS = 300L"))
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

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing function: $signature" }
        val opening = source.indexOf('{', start)
        require(opening >= 0) { "missing function body: $signature" }
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

    private fun sourceBlock(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return source.substring(start, end)
    }
}
