package kr.co.hanium.dreamup.walksafe

import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RQ-FP-030-001` 은 「충분한 색 대비」를 공식 지원 범위로 요구한다. 색을 하나 바꾸는 것만으로
 * 저시력 사용자가 못 읽는 화면이 될 수 있으므로, 팔레트의 대비를 계산해서 고정한다.
 *
 * 값은 소스의 `WS_COLOR_*` 상수에서 직접 읽는다. 테스트가 따로 색을 들고 있으면 소스와 어긋난다.
 */
class WalkSafePaletteContrastTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val colors: Map<String, Int> =
        Regex("""const val (WS_COLOR_[A-Z_]+) = 0x([0-9a-fA-F]{8})\.toInt\(\)""")
            .findAll(source)
            .associate { m -> m.groupValues[1] to (m.groupValues[2].toLong(16).toInt() and 0xFFFFFF) }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun ratio(a: String, b: String): Double {
        val first = requireNotNull(colors[a]) { "missing color: $a" }
        val second = requireNotNull(colors[b]) { "missing color: $b" }
        val high = maxOf(luminance(first), luminance(second))
        val low = minOf(luminance(first), luminance(second))
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertRatio(a: String, b: String, minimum: Double) {
        val value = ratio(a, b)
        assertTrue("$a on $b is $value:1, needs $minimum:1", value >= minimum)
    }

    @Test
    fun bodyAndHeadingTextStayReadableOnEverySurface() {
        // 본문 4.5:1, 큰 글자와 UI 경계 3:1 — WCAG AA.
        assertRatio("WS_COLOR_NOTICE_TEXT", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_NOTICE_TEXT", "WS_COLOR_NOTICE_FILL", 4.5)
        assertRatio("WS_COLOR_EMPHASIS", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_WARNING", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_WARNING", "WS_COLOR_NOTICE_FILL", 4.5)
    }

    @Test
    fun controlsAreDistinguishableFromTheSurfaceBehindThem() {
        assertRatio("WS_COLOR_BUTTON_TEXT", "WS_COLOR_BUTTON_FILL", 4.5)
        assertRatio("WS_COLOR_PRIMARY_ACTION_TEXT", "WS_COLOR_PRIMARY_ACTION_FILL", 4.5)
        assertRatio("WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT", "WS_COLOR_PRIMARY_ACTION_DISABLED_FILL", 4.5)

        // 밝은 버튼 면은 바탕과 거의 같은 밝기다. 경계를 만드는 것은 테두리이므로 그 선이 보여야
        // 한다. 조작할 수 있는 것의 경계는 `WS_COLOR_BUTTON_BORDER` 이고, 구분선(`WS_COLOR_LINE`)은
        // 장식이라 이 기준을 적용하지 않는다 — 디자인이 둘을 나눠 준 이유다.
        assertRatio("WS_COLOR_BUTTON_BORDER", "WS_COLOR_OVERLAY_FILL", 3.0)
    }

    @Test
    fun theDisabledLabelIsRecordedEvenThoughTheStandardExemptsIt() {
        // WCAG 1.4.3 은 비활성 컨트롤을 대비 요구에서 제외한다. 그래서 실패로 두지 않는다.
        // 다만 이 앱의 사용자에게는 「왜 못 누르는지」를 읽는 일이 실제 조작이므로, 지금 값을 적어
        // 두어 **모르는 사이에 더 흐려지는 것**은 막는다. 지금은 2.73:1 이며 4.5:1 에 못 미친다.
        // 디자인 쪽 값이라 임의로 올리지 않는다. 올리려면 토큰 표가 먼저 바뀌어야 한다.
        val measured = ratio("WS_COLOR_BUTTON_DISABLED_TEXT", "WS_COLOR_BUTTON_DISABLED_FILL")
        assertTrue("disabled label fell below its recorded 2.73:1 (now $measured)", measured >= 2.7)
    }

    @Test
    fun thePrimaryActionStaysTheHeaviestControlEvenWhileDisabled() {
        val normal = luminance(requireNotNull(colors["WS_COLOR_BUTTON_FILL"]))
        val primary = luminance(requireNotNull(colors["WS_COLOR_PRIMARY_ACTION_FILL"]))
        val primaryDisabled = luminance(requireNotNull(colors["WS_COLOR_PRIMARY_ACTION_DISABLED_FILL"]))

        // 밝은 판에서 「무겁다」는 더 어둡다는 뜻이다. 꺼져 있어도 주 행동이 가장 무거워야 한다.
        assertTrue("primary must be darker than a normal button", primary < normal)
        assertTrue("disabled primary must stay darker than a normal button", primaryDisabled < normal)
    }
}
