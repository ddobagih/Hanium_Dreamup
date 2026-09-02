package kr.co.hanium.dreamup.walksafe

import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafePaletteContrastTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val argbColors =
        Regex("""const val (WS_COLOR_[A-Z_]+) = 0x([0-9a-fA-F]{8})\.toInt\(\)""")
            .findAll(source)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2].toLong(16)
            }

    private fun rgb(name: String): Int =
        (requireNotNull(argbColors[name]) { "missing color: $name" } and 0xFFFFFF).toInt()

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun ratio(firstName: String, secondName: String): Double {
        return ratio(rgb(firstName), rgb(secondName))
    }

    private fun ratio(first: Int, second: Int): Double {
        val high = maxOf(luminance(first), luminance(second))
        val low = minOf(luminance(first), luminance(second))
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertRatio(first: String, second: String, minimum: Double) {
        val measured = ratio(first, second)
        assertTrue("$first on $second is $measured:1", measured >= minimum)
    }

    private fun composite(overlayName: String, underlay: Int): Int {
        val argb = requireNotNull(argbColors[overlayName]) { "missing color: $overlayName" }
        val overlay = (argb and 0xFFFFFF).toInt()
        val alpha = ((argb ushr 24) and 0xFF).toDouble() / 255.0
        var result = 0
        listOf(16, 8, 0).forEach { shift ->
            val overlayChannel = overlay shr shift and 0xFF
            val underlayChannel = underlay shr shift and 0xFF
            val channel =
                (overlayChannel * alpha + underlayChannel * (1.0 - alpha)).roundToInt()
            result = result or (channel shl shift)
        }
        return result
    }

    private fun assertCompositeRatio(
        foregroundName: String,
        overlayName: String,
        underlay: Int,
        minimum: Double,
    ) {
        val measured = ratio(rgb(foregroundName), composite(overlayName, underlay))
        assertTrue(
            "$foregroundName on $overlayName over ${underlay.toString(16)} is $measured:1",
            measured >= minimum,
        )
    }

    @Test
    fun bodyAndHeadingTextStayReadable() {
        assertRatio("WS_COLOR_NOTICE_TEXT", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_NOTICE_TEXT", "WS_COLOR_NOTICE_FILL", 4.5)
        assertRatio("WS_COLOR_EMPHASIS", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_WARNING", "WS_COLOR_OVERLAY_FILL", 4.5)
        assertRatio("WS_COLOR_WARNING", "WS_COLOR_NOTICE_FILL", 4.5)
    }

    @Test
    fun controlsRemainDistinctAndReadable() {
        assertRatio("WS_COLOR_BUTTON_TEXT", "WS_COLOR_BUTTON_FILL", 4.5)
        assertRatio("WS_COLOR_BUTTON_TEXT", "WS_COLOR_BUTTON_PRESSED_FILL", 4.5)
        assertRatio("WS_COLOR_PRIMARY_ACTION_TEXT", "WS_COLOR_PRIMARY_ACTION_FILL", 4.5)
        assertRatio(
            "WS_COLOR_PRIMARY_ACTION_TEXT",
            "WS_COLOR_PRIMARY_ACTION_PRESSED_FILL",
            4.5,
        )
        assertRatio(
            "WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT",
            "WS_COLOR_PRIMARY_ACTION_DISABLED_FILL",
            4.5,
        )
        assertRatio("WS_COLOR_BUTTON_BORDER", "WS_COLOR_OVERLAY_FILL", 3.0)
        assertRatio("WS_COLOR_FOCUS", "WS_COLOR_OVERLAY_FILL", 3.0)
        assertTrue(
            ratio("WS_COLOR_BUTTON_DISABLED_TEXT", "WS_COLOR_BUTTON_DISABLED_FILL") >= 2.7,
        )
    }

    @Test
    fun safetyBannerStaysReadableOnItsAmberFill() {
        assertRatio("WS_COLOR_SAFETY_BANNER_TEXT", "WS_COLOR_SAFETY_BANNER_FILL", 4.5)
        assertRatio("WS_COLOR_SAFETY_BANNER_BORDER", "WS_COLOR_SAFETY_BANNER_FILL", 3.0)
        // 배너는 ground 위에 놓인다. 눌림 상태에서도 글자가 읽혀야 한다.
        assertRatio("WS_COLOR_SAFETY_BANNER_TEXT", "WS_COLOR_BUTTON_PRESSED_FILL", 4.5)
        assertRatio("WS_COLOR_SAFETY_BANNER_FILL", "WS_COLOR_GROUND", 1.0)
    }

    @Test
    fun homeCardTextStaysReadableOnEveryCardColor() {
        // 디자인의 부제 white/60 은 card.arc 위에서 2.77:1 이라 쓰지 않는다.
        // 제목·부제 모두 흰색 100% 이고, 위계는 크기와 굵기로 낸다.
        listOf(
            "WS_COLOR_CARD_NAV",
            "WS_COLOR_CARD_ARC",
            "WS_COLOR_CARD_MIC",
            "WS_COLOR_CARD_REPORT",
        ).forEach { card ->
            assertRatio("WS_COLOR_CARD_TEXT", card, 4.5)
        }
    }

    @Test
    fun homeCardLockBadgeGlyphStaysVisible() {
        listOf(
            "WS_COLOR_CARD_NAV",
            "WS_COLOR_CARD_ARC",
            "WS_COLOR_CARD_MIC",
            "WS_COLOR_CARD_REPORT",
        ).forEach { card ->
            assertCompositeRatio(
                "WS_COLOR_CARD_TEXT",
                "WS_COLOR_CARD_LOCK_BADGE_FILL",
                rgb(card),
                3.0,
            )
        }
    }

    @Test
    fun translucentOverlaysKeepContrastOverDarkAndLightCameraFrames() {
        listOf("WS_COLOR_OVERLAY_FILL", "WS_COLOR_WALK_OVERLAY_FILL").forEach { overlay ->
            listOf(0x000000, 0xFFFFFF).forEach { underlay ->
                assertCompositeRatio("WS_COLOR_NOTICE_TEXT", overlay, underlay, 4.5)
                assertCompositeRatio("WS_COLOR_EMPHASIS", overlay, underlay, 4.5)
                assertCompositeRatio("WS_COLOR_WARNING", overlay, underlay, 4.5)
                assertCompositeRatio("WS_COLOR_BUTTON_BORDER", overlay, underlay, 3.0)
            }
        }
    }
}
