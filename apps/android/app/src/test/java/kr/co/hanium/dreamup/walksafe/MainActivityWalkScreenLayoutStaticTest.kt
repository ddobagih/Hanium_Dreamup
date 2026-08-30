package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보행 화면은 ver2 설계 초안을 따라 7구역 + 구분선으로 나뉜다. 이전에는 컨트롤 17개가 여백도
 * 구분선도 없이 세로로 균등하게 나열돼, 주 행동과 「진행음 볼륨」이 같은 무게로 보였다.
 *
 * 이 테스트가 고정하는 것은 세 가지다. 구역이 다시 평평해지지 않을 것, 내부 상태 문자열이 제품
 * 화면으로 돌아오지 않을 것, 설계 노트가 「미정」으로 남긴 항목을 코드가 지어내지 않을 것.
 */
class MainActivityWalkScreenLayoutStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val runtimeControlsBlock =
        source.substringAfter("        runtimeControls = LinearLayout(this).apply {")
            .substringBefore("\n        }\n")

    @Test
    fun walkScreenIsGroupedIntoSectionsWithDividers() {
        // 7구역과 그 사이 구분선. DEBUG 구역이 하나 더 붙으므로 하한으로 단언한다.
        assertTrue(runtimeControlsBlock.split("walkSection(").size - 1 >= 7)
        assertTrue(runtimeControlsBlock.split("walkDivider()").size - 1 >= 6)

        // 짝을 이루는 행동은 한 줄에 둔다.
        assertTrue(
            runtimeControlsBlock.contains(
                "walkTwoColumnRow(destinationSearchButton, destinationCancelButton)",
            ),
        )
        assertTrue(
            runtimeControlsBlock.contains("walkTwoColumnRow(routeButton, destinationResetButton)"),
        )
    }

    @Test
    fun primaryActionIsHeavierThanEveryOtherControl() {
        assertTrue(
            source.contains(
                "applyWsButtonStyle(actionButton, WS_TOUCH_WALK_PRIMARY_DP, primary = true)",
            ),
        )
        assertTrue(source.contains("const val WS_TOUCH_WALK_PRIMARY_DP = 80f"))
        assertTrue(source.contains("const val WS_TOUCH_WALK_ACTION_DP = 56f"))

        // 주 행동은 배경과 글자를 뒤집어 구분한다.
        assertTrue(source.contains("WS_COLOR_PRIMARY_ACTION_FILL = 0xffffffff.toInt()"))
        assertTrue(source.contains("WS_COLOR_PRIMARY_ACTION_TEXT = 0xff000000.toInt()"))

        // 꺼져 있는 시간이 길다. 비활성일 때도 다른 버튼보다 밝아야 크기로 만든 위계가 유지된다.
        assertTrue(source.contains("WS_COLOR_PRIMARY_ACTION_DISABLED_FILL = 0xff9a9a9a.toInt()"))
        val style = source.substringAfter("private fun applyWsButtonStyle(")
            .substringBefore("private fun walkDivider()")
        assertTrue(style.contains("WS_COLOR_PRIMARY_ACTION_DISABLED_FILL"))
        assertTrue(style.contains("WS_COLOR_BUTTON_DISABLED_FILL"))

        // 보행 화면에서 primary 는 주 행동 하나뿐이다. 나머지 한 곳은 온보딩 각 단계의 강조
        // 버튼을 같은 마감으로 덮어쓰는 자리이고, 그 단계에도 강조 버튼은 하나씩만 있다.
        assertTrue(source.split("primary = true").size - 1 == 2)
        assertTrue(
            source.contains(
                "wsEmphasisButtons.forEach { applyWsButtonStyle(it, WS_TOUCH_PRIMARY_DP, " +
                    "primary = true) }",
            ),
        )
    }

    @Test
    fun internalNavigationStateNeverReachesTheProductScreen() {
        assertFalse(runtimeControlsBlock.contains("navigationStatusText"))

        val debugBlock = source.substringAfter("        runtimeDebugControls = LinearLayout(this)")
            .substringBefore("\n        }\n")
        assertTrue(debugBlock.contains("if (BuildConfig.DEBUG) {"))
        assertTrue(debugBlock.contains("addView(navigationStatusText)"))
    }

    @Test
    fun thePortDidNotInventWhatTheDesignNoteLeftUndecided() {
        // 설계 노트가 미정으로 남긴 다섯 항목. 앱이 문구를 지어내면 안 된다.
        listOf(
            "미정",
            "보행 중 상태",
            "위험 안내가 화면에",
            "신고 진행 상태",
            "검색 결과 표시 형식",
        ).forEach { assertFalse(runtimeControlsBlock.contains(it)) }
    }

    @Test
    fun theCodeStringsSurvivedThePort() {
        // 브리프 1순위 규칙은 코드 문자열 원문 유지다. ver2 가 줄인 문구를 따라가지 않는다.
        assertTrue(source.contains("\"서버 음성 명령\""))
        assertTrue(source.contains("\"손상 점자블록 신고 요청\""))
        assertTrue(source.contains("\"경로 시작\""))
        assertTrue(source.contains("\"경로 초기화\""))
    }

    @Test
    fun theDeviationNoticeIsVisibleAndNotOnlySpoken() {
        val update = source.substringAfter("private fun updateRouteDeviationActions(")
            .substringBefore("\n    private fun ")
        assertTrue(update.contains("offRouteNoticeText.text = when {"))
        assertTrue(source.contains("setTextColor(WS_COLOR_WARNING)"))

        // 이탈 선택 노출 분기는 그대로다. 의심이면 「위치 다시 확인」 하나뿐이다.
        assertTrue(update.contains("routeDeviationNewRouteButton.visibility = if (confirmed)"))
        assertTrue(
            update.contains(
                "routeDeviationRecheckButton.visibility = if (suspected || confirmed)",
            ),
        )
        assertTrue(update.contains("routeDeviationEndButton.visibility = if (confirmed)"))
    }

    @Test
    fun readinessSurfacesCollapseOnlyWhenNothingBlocksWalking() {
        val section = source.substringAfter("private fun updateWalkReadinessSection() {")
            .substringBefore("\n    private fun ")

        // 접기를 제안할 조건은 보행을 막는 것이 하나도 없을 때뿐이다.
        assertTrue(
            section.contains(
                "val everythingPassed = walkSafetyOutputsAllowed() && isStartupCapabilityConfirmed()",
            ),
        )

        // 막는 것이 있으면 펼친 채 두고 토글을 내주지 않는다. 안전 사유는 사용자가 치울 수 없다.
        val blocked = section.substringAfter("if (!everythingPassed) {")
            .substringBefore("}")
        assertTrue(blocked.contains("walkReadinessToggleButton.visibility = View.GONE"))
        assertTrue(blocked.contains("walkReadinessControls.visibility = View.VISIBLE"))
        assertFalse(blocked.contains("walkReadinessExpanded = "))
    }

    @Test
    fun theWalkScreenSitsBelowOneCollapsibleReadinessGroup() {
        val overlay = source.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")

        // 준비 표면은 낱개로 오버레이에 붙지 않는다. 하나로 묶여야 접을 수 있다.
        listOf(
            "addView(officialEnvironmentStatusText)",
            "addView(phoneMountingStatusText)",
            "addView(startupCapabilityText)",
            "addView(priorityUserOnboardingControls)",
        ).forEach { assertFalse(overlay.contains(it)) }

        assertTrue(
            overlay.indexOf("addView(walkReadinessControls)") <
                overlay.indexOf("addView(runtimeControls)"),
        )
    }

    @Test
    fun statusTextIsStructuredWithoutRewritingTheMessage() {
        val format = source.substringAfter("private fun applyWsStatusText(")
            .substringBefore("\n    /** 상태 카드의 면.")

        // 문구는 요구와 테스트가 고정한다. 구조만 입히고 글자는 건드리지 않는다.
        assertTrue(format.contains("val lines = message.split"))
        assertFalse(format.contains("replace("))

        // 상태 줄은 「원인:」 바로 앞 줄이다. 막는 상태만 warning 색을 받는다.
        assertTrue(format.contains("lines.indexOfFirst { it.startsWith(\"원인:\") }"))
        assertTrue(format.contains("WS_COLOR_WARNING"))
        assertTrue(source.contains("val BLOCKING_STATUS_WORDS = setOf(\"사용 불가\", \"제한\", \"교정 필요\")"))

        // TalkBack 은 지금처럼 원문 한 덩어리로 읽어야 한다.
        listOf(
            "officialEnvironmentStatusText.contentDescription = message",
            "phoneMountingStatusText.contentDescription = message",
            "startupCapabilityText.contentDescription = capabilityMessage",
        ).forEach { assertTrue(source.contains(it)) }
    }

    @Test
    fun theBundledTypefaceIsUsedEverywhereWeSetAWeight() {
        // 테마가 모든 텍스트 위젯의 기본 글꼴을 잡는다.
        val theme = java.io.File("src/main/res/values/styles.xml").readText()
        assertTrue(theme.contains("<item name=\"android:fontFamily\">@font/pretendard</item>"))

        // 코드에서 굵기를 지정할 때 시스템 글꼴 이름으로 되돌아가면 그 자리만 다른 글꼴이 된다.
        assertFalse(source.contains("sans-serif-medium"))
        assertFalse(source.contains("Typeface.create(\"sans-serif"))
        assertTrue(source.contains("private fun wsTypeface("))

        // 라이선스 전문을 저장소에 함께 둔다. OFL 1.1 의 요구다.
        assertTrue(java.io.File("../licenses/pretendard-OFL-1.1.txt").exists())
    }

    @Test
    fun theStatusLineSpeaksItsValueNotJustItsLabel() {
        val update = source.substringAfter("private fun updateStatus(status: String, detail: String)")
            .substringBefore("\n    private fun ")

        // 고정 라벨만 붙어 있으면 스크린리더가 그 말만 하고 실제 상태는 영영 읽지 않는다.
        assertTrue(update.contains("statusText.contentDescription = \"WalkSafe 상태. \$status\""))

        // 자동 낭독은 계속 꺼 둔다. 500ms 마다 말이 끼어들면 안 된다.
        assertTrue(source.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
    }

    @Test
    fun theDestinationFieldAndItsButtonDoNotShareOneName() {
        val field = source.substringAfter("destinationQueryInput = EditText(this).apply")
            .substringBefore("destinationSearchButton =")
        assertTrue(field.contains("hint = \"목적지 검색\""))
        assertTrue(field.contains("contentDescription = \"목적지 입력\""))
    }
}
