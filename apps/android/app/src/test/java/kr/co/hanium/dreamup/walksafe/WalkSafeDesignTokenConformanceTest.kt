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
    fun colorsMatchTheTeammateDesignTokens() {
        val expected = mapOf(
            "WS_COLOR_OVERLAY_FILL" to "#FAFAF9F7",
            "WS_COLOR_WALK_OVERLAY_FILL" to "#F2FAF9F7",
            "WS_COLOR_NOTICE_FILL" to "#FFFFFFFF",
            "WS_COLOR_LINE" to "#FFDEDBD5",
            "WS_COLOR_BUTTON_TEXT" to "#FF1B1B1D",
            "WS_COLOR_NOTICE_TEXT" to "#FF5F5C57",
            "WS_COLOR_EMPHASIS" to "#FF1B1B1D",
            "WS_COLOR_WARNING" to "#FFB3341A",
            "WS_COLOR_BUTTON_FILL" to "#FFFFFFFF",
            "WS_COLOR_BUTTON_BORDER" to "#FF888380",
            "WS_COLOR_BUTTON_PRESSED_FILL" to "#FFE8E5E0",
            "WS_COLOR_BUTTON_FOCUSED_FILL" to "#FF765D00",
            "WS_COLOR_BUTTON_DISABLED_FILL" to "#FFF0EDE8",
            "WS_COLOR_BUTTON_DISABLED_TEXT" to "#FF6E6B66",
            "WS_COLOR_GROUND" to "#FFFAF9F7",
            "WS_COLOR_PRIMARY_ACTION_FILL" to "#FF1B4CD8",
            "WS_COLOR_PRIMARY_ACTION_TEXT" to "#FFFFFFFF",
            "WS_COLOR_PRIMARY_ACTION_PRESSED_FILL" to "#FF1540B5",
            "WS_COLOR_PRIMARY_ACTION_DISABLED_FILL" to "#FF566EA0",
            "WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT" to "#FFFFFFFF",
            "WS_COLOR_FOCUS" to "#FF1B4CD8",
        )
        expected.forEach { (name, value) -> assertEquals(name, value, argb(name)) }
    }

    @Test
    fun dimensionsMatchTheTeammateDesignTokens() {
        assertEquals("48dp", dimension("WS_TOUCH_MIN_DP"))
        assertEquals("56dp", dimension("WS_TOUCH_WALK_ACTION_DP"))
        assertEquals("80dp", dimension("WS_TOUCH_WALK_PRIMARY_DP"))
        assertEquals("16dp", dimension("WS_CORNER_RADIUS_DP"))
        assertEquals("24dp", dimension("WS_SECTION_GAP_DP"))
        assertEquals("8dp", dimension("WS_GROUP_GAP_DP"))
    }

    @Test
    fun stepIndicatorUsesNumberedCirclesFromTheAppDesignStepper() {
        assertEquals("36dp", dimension("WS_STEP_CIRCLE_DP"))
        assertEquals("2dp", dimension("WS_STEP_LINE_DP"))
        assertTrue(source.contains("const val WS_TEXT_STEP_NUMBER_SP = 14f"))

        val build = functionBlock("private fun buildContentView()")
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertTrue(build.contains("GradientDrawable.OVAL"))
        assertTrue(build.contains("firstRunStepCircles += circle"))
        assertTrue(build.contains("firstRunProgressSegments += connector"))
        // 끝난 단계는 체크, 현재 단계는 번호. 연결선은 앞 원이 끝났을 때만 채운다.
        assertTrue(update.contains("""circle.text = if (done) "✓" else "${'$'}{index + 1}""""))
        assertTrue(update.contains("val done = index < stageNumber - 1"))
        assertTrue(update.contains("val current = index == stageNumber - 1"))
        assertTrue(update.contains("if (index < stageNumber - 1) WS_COLOR_PRIMARY_ACTION_FILL"))
    }

    @Test
    fun consentRowsCarryTheRequiredOptionalBadgeFromTheAppDesignChkRow() {
        assertEquals("WS_COLOR_BADGE_REQUIRED_FILL", "#FFFEF2F2", argb("WS_COLOR_BADGE_REQUIRED_FILL"))
        val badge = functionBlock("private fun applyWsConsentBadge(")
        val build = functionBlock("private fun buildContentView()")

        // 라벨의 `[필수]`/`[선택]` 구간에만 색을 입힌다. 문구는 바꾸지 않는다.
        assertTrue(badge.contains("""label.startsWith("[필수]")"""))
        assertTrue(badge.contains("""label.startsWith("[선택]")"""))
        assertTrue(badge.contains("BackgroundColorSpan("))
        assertTrue(badge.contains("WS_COLOR_BADGE_REQUIRED_FILL"))
        assertTrue(badge.contains("WS_COLOR_WARNING"))
        // 색만으로 구분하지 않는다. 원문 대괄호 표기가 그대로 남는다.
        assertFalse(badge.contains("check.text = check.text.toString().replace"))
        assertTrue(build.contains("accountConsentChecks.values.forEach(::applyWsConsentBadge)"))
    }

    @Test
    fun accountFieldsCarryAPersistentLabelFromTheAppDesignFld() {
        val group = functionBlock("private fun wsFieldGroup(")
        val build = functionBlock("private fun buildContentView()")

        // hint 는 입력을 시작하면 사라진다. 라벨은 남아야 한다.
        assertTrue(group.contains("addView(input)"))
        assertTrue(group.contains("hint?.let { hintText ->"))
        // 라벨과 hint 는 장식이다. 입력칸 contentDescription 이 더 자세하게 읽는다.
        assertEquals(3, Regex("""IMPORTANT_FOR_ACCESSIBILITY_NO""").findAll(group).count())

        listOf(
            """wsFieldGroup\(accountEmailInput, "이메일"\)""",
            """wsFieldGroup\(accountPasswordInput, "비밀번호", "10자 이상 128자 이하"\)""",
            """wsFieldGroup\(accountPasswordConfirmationInput, "비밀번호 확인"\)""",
            """wsFieldGroup\(\s*accountDateOfBirthInput,\s*"생년월일",""",
            """wsFieldGroup\(accountOtpInput, "인증번호"\)""",
        ).forEach { assertTrue(it, Regex(it).containsMatchIn(build)) }

        // placeholder 는 AppDesign 값이며 라벨과 겹치지 않는다.
        assertTrue(source.contains("""hint = "example@email.com""""))
        assertTrue(source.contains("""hint = "비밀번호 입력""""))
        assertTrue(source.contains("""hint = "비밀번호 다시 입력""""))
    }

    @Test
    fun brandHeaderIsPresentBeforeLoginAndHiddenAfterOnboarding() {
        assertEquals("32dp", dimension("WS_BRAND_MARK_DP"))
        val build = functionBlock("private fun buildContentView()")
        val header = functionBlock("private fun buildBrandRow()")
        val welcome = functionBlock("private fun buildWelcomeBlock()")
        // 가시성 소유자는 이 함수 하나다. 두 곳에서 다른 규칙으로 쓰면 호출 순서에 따라 갈린다.
        val update = functionBlock("private fun syncAccountScreenChrome()")
        assertTrue(build.contains("brandHeader = buildWelcomeBlock()"))
        // AppDesign 은 SafetyBar 아래가 브랜드 행이다.
        assertTrue(
            build.contains(
                "overlay.addView(brandHeader, " +
                    "overlay.indexOfChild(firstRunNoticeToggleButton) + 1)",
            ),
        )
        assertTrue(header.contains("R.drawable.ws_ic_brand_mark"))
        assertTrue(header.contains("\"WALKSAFE\""))
        // 워드마크는 장식이다. 화면 제목이 같은 정보를 낭독한다.
        assertTrue(header.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(update.contains("brandHeader.visibility = if (landing) View.VISIBLE else View.GONE"))
        // AppDesign Welcome 은 고르는 화면이라 스테퍼도 단계 안내도 없다.
        assertTrue(update.contains("firstRunProgressBar.visibility ="))
        assertTrue(update.contains("firstRunOnboardingStatusText.visibility ="))
        assertEquals(
            1,
            Regex("brandHeader\\.visibility =").findAll(source).count(),
        )

        // AppDesign Welcome 의 h1 과 부제. 워드마크와 달리 실제 내용이라 낭독되어야 한다.
        assertTrue(source.contains("const val WELCOME_HEADLINE_KO"))
        assertTrue(source.contains("const val WELCOME_SUBTEXT_KO"))
        assertTrue(welcome.contains("WS_TEXT_HEADLINE_SP"))
        assertTrue(welcome.contains("ViewCompat.setAccessibilityHeading(this, true)"))
        assertTrue(welcome.contains("IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(source.contains("const val WS_TEXT_HEADLINE_SP = 38f"))
        assertTrue(source.contains("const val WS_TEXT_SUBTEXT_SP = 15f"))

        // AppDesign Welcome 일러스트. 장식이라 낭독하지 않고 rounded-3xl 로 자른다.
        assertTrue(welcome.contains("R.drawable.ws_img_welcome"))
        assertTrue(welcome.contains("ImageView.ScaleType.FIT_CENTER"))
        assertTrue(welcome.contains("clipToOutline = true"))
        assertEquals("200dp", dimension("WS_WELCOME_ILLUSTRATION_DP"))
    }

    @Test
    fun safetyBannerUsesTheAppDesignAmberAndKeepsTheAccessibleTextFloor() {
        val expected = mapOf(
            "WS_COLOR_SAFETY_BANNER_FILL" to "#FFFEF9EE",
            "WS_COLOR_SAFETY_BANNER_BORDER" to "#FF9A6800",
            "WS_COLOR_SAFETY_BANNER_TEXT" to "#FF7A5300",
        )
        expected.forEach { (name, value) -> assertEquals(name, value, argb(name)) }

        val build = functionBlock("private fun buildContentView()")
        val banner = functionBlock("private fun applyWsSafetyBannerStyle(")
        // secondary 스타일이 배경을 덮어쓰므로 앰버는 그 뒤에 와야 한다.
        assertTrue(
            Regex(
                """\)\.forEach\(::applyWsSecondaryButtonStyle\)[\s\S]*?""" +
                    """applyWsSafetyBannerStyle\(firstRunNoticeToggleButton\)\s*""" +
                    """applyWsSafetyNoticeBody\(productPurposeText\)""",
            ).containsMatchIn(build),
        )
        // 디자인의 12sp 를 따라가지 않는다. 크기는 접근성 기본값이 정한다.
        assertFalse(banner.contains("textSize"))
        assertFalse(banner.contains("minimumHeight"))
        assertTrue(source.contains("const val MIN_INTERACTIVE_TEXT_SP = 16f"))
    }

    @Test
    fun homeCardTokensMatchTheAppDesignHomeScreen() {
        // AppDesign 홈이 카드 4장에서 3장으로 줄었다. ARCore·신고는 보행 흐름 안으로 갔다.
        val expected = mapOf(
            "WS_COLOR_CARD_NAV" to "#FF1B4CD8",
            "WS_COLOR_CARD_MIC" to "#FF1E6B38",
            "WS_COLOR_CARD_SET" to "#FF3D3B38",
            "WS_COLOR_CARD_TEXT" to "#FFFFFFFF",
        )
        expected.forEach { (name, value) -> assertEquals(name, value, argb(name)) }
        assertEquals("148dp", dimension("WS_CARD_HEIGHT_DP"))
        assertEquals("20dp", dimension("WS_CARD_PADDING_DP"))
        assertEquals("24dp", dimension("WS_CARD_CORNER_RADIUS_DP"))
        assertEquals("12dp", dimension("WS_CARD_GAP_DP"))
        assertEquals("34dp", dimension("WS_CARD_ICON_DP"))
        assertEquals("26dp", dimension("WS_CARD_BADGE_DP"))
        assertTrue(source.contains("const val WS_TEXT_CARD_TITLE_SP = 17f"))
        assertTrue(source.contains("const val WS_TEXT_CARD_SUBTITLE_SP = 14f"))
    }

    @Test
    fun homeCardGridIsBuiltAfterDefaultsAndRefreshedWithTheRestOfTheUi() {
        val build = functionBlock("private fun buildContentView()")
        val grid = functionBlock("private fun buildHomeCardGrid()")
        val card = functionBlock("private fun wsHomeCard(")
        val refresh = functionBlock("private fun refreshHomeCards()")
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")

        // 기본 컨트롤 스타일 재귀가 카드 내부를 덮어쓰지 않도록 그 뒤에 붙인다.
        assertTrue(
            Regex(
                """applyAccessibleControlDefaults\(overlay\)\s*""" +
                    """homeCardGrid = buildHomeCardGrid\(\)\s*""" +
                    """overlay\.addView\(homeCardGrid, overlay\.indexOfChild\(walkStatusSection\)\)""",
            ).containsMatchIn(build),
        )
        // AppDesign 은 flex flex-col — 카드가 화면 폭을 채우며 세로로 쌓인다.
        assertTrue(grid.contains("orientation = LinearLayout.VERTICAL"))
        assertFalse(grid.contains("columnCount"))
        listOf(
            "R.drawable.ws_ic_card_nav",
            "R.drawable.ws_ic_card_mic",
            "R.drawable.ws_ic_card_settings",
        ).forEach { assertTrue(it, grid.contains(it)) }
        // 잠긴 카드는 사유를 알리고, 열린 카드만 기존 컨트롤을 호출한다.
        assertTrue(card.contains("if (unlocked()) {"))
        assertTrue(card.contains("showHomeCardLockNotice(lockTitle, lockDetail)"))
        assertTrue(card.contains(". 잠김. "))
        assertTrue(card.contains("R.drawable.ws_ic_card_lock"))
        assertTrue(refresh.contains("firstRunOnboardingComplete()"))
        assertTrue(update.contains("refreshHomeCards()"))
    }

    @Test
    fun accountStepScreensFoldTheStandingChrome() {
        val fold = functionBlock("private fun onAccountStepScreen()")
        val chrome = functionBlock("private fun syncAccountScreenChrome()")
        val notice = functionBlock("private fun refreshFirstRunNoticeUi()")

        // AppDesign signup-1..3 에는 안전 배너와 스테퍼가 모두 있어 접지 않는다.
        assertTrue(fold.contains("Boolean = false"))
        assertTrue(chrome.contains("val foldChrome = landing || onAccountStepScreen()"))
        assertTrue(chrome.contains("if (foldChrome) View.GONE else View.VISIBLE"))

        // 안전 배너 소유자는 그대로 하나다. 보행 화면과 같은 자리에서 함께 판단한다.
        assertTrue(notice.contains("if (walkScreenVisible || onAccountStepScreen())"))
        // 배너 가시성 대입은 전부 이 함수 안에만 있어야 한다. 밖으로 새면 소유자가 둘이 된다.
        val assignment = Regex("firstRunNoticeToggleButton\\.visibility =")
        assertEquals(
            assignment.findAll(notice).count(),
            assignment.findAll(source).count(),
        )
        // 모드가 바뀌는 경로가 배너 갱신을 놓치지 않도록 크롬 동기화가 직접 부른다.
        assertTrue(chrome.contains("refreshFirstRunNoticeUi()"))
    }

    @Test
    fun homeWalkPauseFollowsTheAppDesignHomeScreen() {
        val refresh = functionBlock("private fun refreshHomeCards()")
        val pause = functionBlock("private fun requestHomeWalkPause()")

        // 첫 실행이 끝나기 전에는 카드와 함께 감춘다.
        assertTrue(refresh.contains("homeWalkPauseButton.visibility = visibility"))

        // 진행 중인 보행이 없으면 시스템 뒤로가기와 같은 판정으로 사유만 알린다.
        assertTrue(pause.contains("if (!handleWalkScreenBackPressed())"))
        assertTrue(pause.contains("showHomeCardLockNotice("))

        // AppDesign 홈에서 신고 내역 행과 현재 상태 행은 빠졌다.
        assertFalse(source.contains("buildHomeInfoRows"))
        assertFalse(source.contains("wsHomeRow("))
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
    fun componentStylesAreAppliedToTheActualScreenControls() {
        val build = functionBlock("private fun buildContentView()")
        val userReports = functionBlock("private fun renderUserReportList(")
        val destinations = functionBlock("private fun updateDestinationSearchUi()")

        assertTrue(build.contains("setBackgroundColor(WS_COLOR_OVERLAY_FILL)"))
        assertTrue(build.contains("setBackgroundColor(WS_COLOR_WALK_OVERLAY_FILL)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(overlay)"))
        assertTrue(build.contains("applyAccessibleControlDefaults(walkSafetyOverlay)"))
        assertTrue(
            Regex(
                """applyWsButtonStyle\(\s*startupCapabilityConfirmButton,\s*""" +
                    """WS_TOUCH_WALK_PRIMARY_DP,\s*primary = true,\s*\)""",
            ).containsMatchIn(build),
        )
        assertTrue(
            Regex(
                """listOf\(\s*officialEnvironmentStatusText,\s*""" +
                    """phoneMountingStatusText,\s*startupCapabilityText,\s*""" +
                    """postLoginDeviceCheckLiveStatusText,\s*\)""" +
                    """\.forEach\(::applyWsStatusCard\)""",
            ).containsMatchIn(build),
        )
        assertTrue(
            Regex(
                """listOf\(\s*firstRunNoticeToggleButton,[\s\S]*?""" +
                    """accountSignupBackButton,[\s\S]*?\)""" +
                    """\.forEach\(::applyWsSecondaryButtonStyle\)""",
            ).containsMatchIn(build),
        )
        assertTrue(userReports.contains("applyWsButtonStyle(detailButton, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(
            destinations.contains(
                ".also { applyWsButtonStyle(it, WS_TOUCH_WALK_ACTION_DP) }",
            ),
        )
    }

    @Test
    fun controlStatesAndMixedStackSpacingKeepTheNativeUiFixes() {
        val defaults = functionBlock("private fun applyAccessibleControlDefaults(")
        val spacing = functionBlock("private fun applyWsStackSpacing(")
        val secondary = functionBlock("private fun applyWsSecondaryButtonStyle(")

        assertTrue(defaults.contains("applyWsButtonStyle(root, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(defaults.contains("if (root is EditText) applyWsFieldStyle(root)"))
        assertTrue(
            defaults.contains("intArrayOf(WS_COLOR_EMPHASIS, WS_COLOR_BUTTON_BORDER)"),
        )
        assertFalse(defaults.contains("intArrayOf(WS_COLOR_EMPHASIS, WS_COLOR_LINE)"))
        assertTrue(spacing.contains("children.dropLast(1).forEach"))
        assertTrue(spacing.contains("if (params.bottomMargin == 0) params.bottomMargin = gap"))
        assertFalse(spacing.contains("children.any"))
        assertTrue(secondary.contains("button.background = StateListDrawable().apply"))
        assertTrue(secondary.contains("intArrayOf(-android.R.attr.state_enabled)"))
        assertTrue(secondary.contains("WS_COLOR_BUTTON_DISABLED_FILL"))
        assertTrue(secondary.contains("intArrayOf(android.R.attr.state_pressed)"))
        assertTrue(secondary.contains("WS_COLOR_BUTTON_PRESSED_FILL"))
        assertTrue(secondary.contains("intArrayOf(android.R.attr.state_focused)"))
        assertTrue(secondary.contains("wsButtonFace(0x00000000, density, focused = true)"))
        assertTrue(secondary.contains("button.setTextColor("))
        assertTrue(secondary.contains("WS_COLOR_BUTTON_DISABLED_TEXT"))
    }

    @Test
    fun clausesAndShortScreensKeepTheFinalLightDesignFixes() {
        val clause = functionBlock("private fun applyWsClauseBox(")
        val firstRunConsent = sourceBlock(
            "firstRunIntegratedConsentButtons.clear()",
            "firstRunIntegratedConsentSaveButton =",
        )
        val build = functionBlock("private fun buildContentView()")
        val firstRunUpdate = functionBlock("private fun updateFirstRunOnboardingUi(")

        assertTrue(clause.contains("setColor(WS_COLOR_GROUND)"))
        assertTrue(clause.contains("view.textSize = 18f"))
        assertTrue(clause.contains("view.setLineSpacing(0f, 1.75f)"))
        assertTrue(
            firstRunConsent.contains(
                "IntegratedConsentItem.RAW_SOURCE_COLLECTION -> \"원본·진단수집 raw v2\"",
            ),
        )
        assertTrue(firstRunConsent.contains("applyWsClauseBox(this)"))
        assertTrue(firstRunConsent.contains("firstRunConsentAllButton ="))
        assertTrue(build.contains("addView(firstRunConsentAllButton)"))
        assertTrue(firstRunUpdate.contains("firstRunConsentAllButton.visibility ="))
        assertTrue(source.contains("controlsScroll = ScrollView(this).apply {\n            isFillViewport = true"))
    }

    @Test
    fun waitingCardKeepsTheTeammateAnimationAndOnlyShowsForActiveEmailRequests() {
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
        assertTrue(build.contains("addView(firstRunWaitingCard)"))
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
