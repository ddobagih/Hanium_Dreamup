package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 조작 결과는 지금까지 말로만 나갔다. 음성은 흘러가므로 놓치면 다시 확인할 곳이 없었다.
 * 마지막 안내를 화면에도 남긴다 — 문구는 이미 말하는 그 문장 그대로다.
 */
class MainActivityWalkResultLineStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun everySpokenInteractionAlsoStaysOnScreen() {
        val speak = source.substringAfter("private fun speakInteraction(message: String): Boolean {")
            .substringBefore("\n    private fun ")
        assertTrue(speak.contains("showWalkLastResult(message)"))

        // 소리가 억눌린 경우에도 화면에는 남아야 한다. 그때가 화면이 유일한 통로다.
        val suppress = speak.indexOf("shouldSuppressFeedbackDuringVoiceRecognition")
        val show = speak.indexOf("showWalkLastResult(message)")
        assertTrue(show in 0 until suppress)
    }

    @Test
    fun theLineReusesTheSpokenSentenceAndDoesNotSpeakTwice() {
        val fn = source.substringAfter("private fun showWalkLastResult(message: String)")
            .substringBefore("\n    private fun ")

        assertTrue(fn.contains("\"마지막 안내: \$message\""))
        assertTrue(fn.contains("contentDescription = line"))

        // 방금 소리로 나간 문장이라 live region 이면 같은 말을 두 번 듣는다.
        val view = source.substringAfter("walkLastResultText = TextView(this).apply")
            .substringBefore("// 7구역 + 구분선.")
        assertTrue(view.contains("ACCESSIBILITY_LIVE_REGION_NONE"))
        assertFalse(view.contains("ACCESSIBILITY_LIVE_REGION_POLITE"))
    }

    @Test
    fun theLineSitsInTheFirstWalkSection() {
        assertTrue(
            source.contains("walkSection(null, statusText, detailText, walkLastResultText)"),
        )
    }
}
