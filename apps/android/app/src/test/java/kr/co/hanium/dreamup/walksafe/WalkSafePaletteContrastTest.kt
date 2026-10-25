package kr.co.hanium.dreamup.walksafe

import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafePaletteContrastTest {
    private val mainSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val styleSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/WalkSafeViewStyle.kt").readText()

    private val argbColors =
        Regex("""const val (WS_COLOR_[A-Z_]+) = 0x([0-9a-fA-F]{8})\.toInt\(\)""")
            .findAll(styleSource)
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
        return composite(argb, underlay)
    }

    private fun composite(argb: Long, underlay: Int): Int {
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

    /** Reads the selected disabled center fill and label, including their separate alpha. */
    private fun disabledButtonArgb(primary: Boolean): Pair<Long, Long> {
        val start = styleSource.indexOf("fun applyWsButtonStyle(")
        require(start >= 0) { "missing actual button style" }
        val end = styleSource.indexOf("fun walkDivider(", start)
        require(end > start) { "missing actual button style boundary" }
        val style = styleSource.substring(start, end)
        fun selected(token: String): Long {
            val name = if (token.startsWith("WS_COLOR_")) token else {
                val choice = requireNotNull(Regex(
                    """val $token = if \(primary\) (WS_COLOR_[A-Z_]+) else (WS_COLOR_[A-Z_]+)""",
                ).find(style)) { "missing button color selector: $token" }
                choice.groupValues[if (primary) 1 else 2]
            }
            return requireNotNull(argbColors[name]) { "missing color: $name" }
        }
        fun withAlpha(argb: Long, alpha: Int): Long =
            (argb and 0xFFFFFF) or (alpha.toLong() shl 24)

        val faceArgs = requireNotNull(Regex(
            """addState\(intArrayOf\(-android\.R\.attr\.state_enabled\), face\(([^)]*)\)\)""",
        ).find(style)) { "missing disabled button face" }.groupValues[1]
        var fill = selected(faceArgs.substringBefore(',').trim())
        if (faceArgs.contains("faded = true")) {
            val alpha = requireNotNull(Regex("""if \(faded\) alpha = (\d+)""").find(style))
                .groupValues[1].toInt()
            fill = withAlpha(fill, ((fill ushr 24 and 0xFF) * alpha / 255.0).roundToInt())
        }
        val textColors = style.substringAfter("button.setTextColor(")
            .substringBefore("button.setPadding(")
        val firstText = requireNotNull(Regex(
            """intArrayOf\(\s*(WS_COLOR_[A-Z_]+|android\.graphics\.Color\.argb\()""",
        ).find(textColors)) { "missing disabled button text" }.groupValues[1]
        val text = if (firstText.startsWith("WS_COLOR_")) selected(firstText) else {
            val alpha = requireNotNull(Regex("""Color\.argb\(\s*(\d+)""").find(textColors))
                .groupValues[1].toInt()
            val token = requireNotNull(Regex("""Color\.red\((\w+)\)""").find(textColors)).groupValues[1]
            withAlpha(selected(token), alpha)
        }
        return fill to text
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
    fun mainUsesTheStyleOwnerWhosePaletteIsMeasured() {
        assertTrue(mainSource.contains("WalkSafeViewStyle(this)"))
        assertTrue(mainSource.contains("= viewStyle.applyWsButtonStyle(button, minHeightDp, primary)"))
        assertTrue(mainSource.contains("= viewStyle.applyAccessibleControlDefaults(root)"))
        assertTrue(mainSource.contains("= viewStyle.applyWsSafetyNoticeBody(view)"))
        assertTrue(
            mainSource.contains(
                "applyWsSafetyNoticeBody(firstRunOnboardingPresenter.registrationBinding().productPurposeText)",
            ),
        )
        val statusEntryStart = styleSource.indexOf("fun applyWsSafetyNoticeBody(")
        val statusEntryEnd = styleSource.indexOf("\n    }", statusEntryStart)
        assertTrue(statusEntryStart >= 0 && statusEntryEnd > statusEntryStart)
        assertTrue(
            styleSource.substring(statusEntryStart, statusEntryEnd)
                .contains("applyWsStatusCard(view)"),
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
        // Enabled secondary controls render blue labels on these actual surfaces.
        assertRatio("WS_COLOR_PRIMARY_ACTION_FILL", "WS_COLOR_BUTTON_FILL", 4.5)
        assertRatio("WS_COLOR_PRIMARY_ACTION_FILL", "WS_COLOR_BUTTON_PRESSED_FILL", 4.5)
        assertRatio("WS_COLOR_BUTTON_BORDER", "WS_COLOR_OVERLAY_FILL", 3.0)
        assertRatio("WS_COLOR_FOCUS", "WS_COLOR_OVERLAY_FILL", 3.0)
        assertTrue(
            ratio("WS_COLOR_BUTTON_DISABLED_TEXT", "WS_COLOR_BUTTON_DISABLED_FILL") >= 2.7,
        )
    }

    @Test
    fun disabledButtonsRenderedByActualStyleKeepExistingContrastOverLightAndDarkUnderlays() {
        val failures = mutableListOf<String>()
        for (primary in listOf(true, false)) {
            val (fill, text) = disabledButtonArgb(primary)
            for (underlay in listOf(0xFFFFFF, 0x000000)) {
                val background = composite(fill, underlay)
                val label = composite(text, background)
                val measured = ratio(label, background)
                // Preserve the existing disabled palette expectation; this is no new WCAG rule.
                if (measured < 2.7) failures +=
                    "primary=$primary over ${underlay.toString(16)} is $measured:1"
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun safetyBannerAndHomeCardsMeetTheirSemanticContrastThresholds() {
        assertRatio("WS_COLOR_SAFETY_BANNER_TEXT", "WS_COLOR_SAFETY_BANNER_FILL", 4.5)
        assertRatio("WS_COLOR_SAFETY_BANNER_BORDER", "WS_COLOR_SAFETY_BANNER_FILL", 3.0)
        listOf(
            "WS_COLOR_CARD_NAV",
            "WS_COLOR_CARD_VOICE",
            "WS_COLOR_CARD_REPORT",
        ).forEach { cardFill ->
            assertRatio("WS_COLOR_CARD_TEXT", cardFill, 4.5)
            assertRatio("WS_COLOR_CARD_FOCUS", cardFill, 3.0)
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
