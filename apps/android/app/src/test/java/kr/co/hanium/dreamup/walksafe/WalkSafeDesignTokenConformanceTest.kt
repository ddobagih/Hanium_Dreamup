package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 디자인이 낸 `WALKSAFE_TOKENS_V1` 표와 앱 상수를 1:1로 대조한다.
 *
 * 이 테스트가 있는 이유는 이식이 사람의 눈과 판단에 맡겨졌을 때 색과 간격이 조용히 달라졌기
 * 때문이다. 「제대로 옮겼다」가 주장이 아니라 통과·실패가 되어야 한다.
 *
 * 표를 바꾸려면 **디자인 쪽 값이 먼저 바뀌어야 한다.** 앱에서 색이 마음에 안 든다고 여기를 고치는
 * 것은 이 테스트의 목적을 없애는 일이다.
 *
 * 출처: `~/Desktop/WalkMate/Accessible project for visually impaired`, `src/App.tsx`의 토큰 화면.
 * 저장소 밖 산출물이라 값을 여기에 옮겨 적는다.
 */
class WalkSafeDesignTokenConformanceTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun constant(name: String): String {
        val match = Regex("""const val $name = 0x([0-9a-fA-F]{8})\.toInt\(\)""").find(source)
        requireNotNull(match) { "missing constant: $name" }
        // 앞 두 자리는 불투명도다. 색 자체만 비교한다.
        return "#" + match.groupValues[1].substring(2).uppercase()
    }

    private fun dimension(name: String): String {
        val match = Regex("""const val $name = ([0-9.]+)f""").find(source)
        requireNotNull(match) { "missing constant: $name" }
        return match.groupValues[1].removeSuffix(".0") + "dp"
    }

    @Test
    fun colorsMatchTheDesignTokenTable() {
        val expected = mapOf(
            "color.ground" to ("WS_COLOR_OVERLAY_FILL" to "#FFF9F0"),
            "color.surface" to ("WS_COLOR_NOTICE_FILL" to "#FFFFFF"),
            "color.line" to ("WS_COLOR_LINE" to "#C8BFB0"),
            "color.text" to ("WS_COLOR_BUTTON_TEXT" to "#1A1916"),
            "color.text.muted" to ("WS_COLOR_NOTICE_TEXT" to "#5C5853"),
            "color.emphasis" to ("WS_COLOR_EMPHASIS" to "#0A0906"),
            "color.warning" to ("WS_COLOR_WARNING" to "#C0340E"),
            "button.fill" to ("WS_COLOR_BUTTON_FILL" to "#FFFFFF"),
            "button.border" to ("WS_COLOR_BUTTON_BORDER" to "#7A7570"),
            "button.pressed" to ("WS_COLOR_BUTTON_PRESSED_FILL" to "#E8E5DF"),
            "button.disabled.fill" to ("WS_COLOR_BUTTON_DISABLED_FILL" to "#E4E1DB"),
            "button.disabled.text" to ("WS_COLOR_BUTTON_DISABLED_TEXT" to "#8C8782"),
            "primary.fill" to ("WS_COLOR_PRIMARY_ACTION_FILL" to "#1C1A17"),
            "primary.text" to ("WS_COLOR_PRIMARY_ACTION_TEXT" to "#FFF9F0"),
            "primary.pressed" to ("WS_COLOR_PRIMARY_ACTION_PRESSED_FILL" to "#3A3730"),
            "primary.disabled.fill" to ("WS_COLOR_PRIMARY_ACTION_DISABLED_FILL" to "#6B6761"),
            "primary.disabled.text" to ("WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT" to "#FFF9F0"),
            "focus.fill" to ("WS_COLOR_FOCUS" to "#1A4FBF"),
        )
        expected.forEach { (token, pair) ->
            val (constantName, value) = pair
            assertEquals("$token", value, constant(constantName))
        }
    }

    @Test
    fun dimensionsMatchTheDesignTokenTable() {
        assertEquals("size.touch.min", "48dp", dimension("WS_TOUCH_MIN_DP"))
        assertEquals("size.touch.action", "56dp", dimension("WS_TOUCH_WALK_ACTION_DP"))
        assertEquals("size.touch.primary", "80dp", dimension("WS_TOUCH_WALK_PRIMARY_DP"))
        assertEquals("size.corner", "10dp", dimension("WS_CORNER_RADIUS_DP"))
        assertEquals("size.gap.section", "24dp", dimension("WS_SECTION_GAP_DP"))
        assertEquals("size.gap.group", "8dp", dimension("WS_GROUP_GAP_DP"))
    }

    @Test
    fun theTermsBoxKeepsTheBodyFloor() {
        // ver1 은 15px 이었고 ver2 에서 18px 로 올라왔다. 약관 본문은 저시력 사용자가 실제로 읽는
        // 글이라 본문 하한 아래로 내려가면 안 된다. 다시 내려가면 여기서 걸린다.
        val box = source.substringAfter("private fun applyWsClauseBox(")
            .substringBefore("\n    private fun ")
        assertEquals("clause box body size", true, box.contains("view.textSize = 18f"))
    }

    @Test
    fun theWalkOverlayUsesTheGroundColourToo() {
        // 보행 화면은 카메라 위에 뜨지만 같은 바탕색을 쓴다. 화면마다 바탕이 다르면 안 된다.
        assertEquals("#FFF9F0", constant("WS_COLOR_WALK_OVERLAY_FILL"))
    }
}
