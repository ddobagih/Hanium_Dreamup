package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "명령을 이해하지 못했습니다. 다시 말씀해 주세요." tells a walker who cannot read the screen
 * that they failed, and nothing about what would succeed. The destination dialog already does
 * better — it re-offers the numbers — so the general path was the one place a refusal ended in
 * a dead end.
 *
 * The hint is chosen from the state the walk is actually in, the same way route refusals are.
 */
class UnrecognizedCommandNamesAWayOutTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun theRefusalNoLongerEndsWithoutAnOption() {
        assertFalse(source.contains("명령을 이해하지 못했습니다. 다시 말씀해 주세요."))
    }

    @Test
    fun theFailureItselfIsStatedInFewWords() {
        // The walker learns nothing from a long report of not being understood; the hint is the
        // part worth the seconds. Only the destination dialog keeps the fuller wording, because
        // it re-reads the numbered candidates straight after it.
        assertTrue(source.contains("\"못 알아들었습니다. \${unrecognizedCommandHintKo()}\""))
    }

    @Test
    fun everyHintNamesACommandTheParserKnows() {
        val hints = listOf(
            "도착했어 또는 아직 도착 아니야라고 말씀해 주세요.",
            "새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 말씀해 주세요.",
            "위치 다시 확인이라고 말씀해 주세요.",
            "다음 안내 알려줘 또는 길안내 종료라고 말씀해 주세요.",
            "안내 시작 또는 목적지 취소라고 말씀해 주세요.",
            "목적지를 말하거나 도움말이라고 말씀해 주세요.",
        )

        hints.forEach { hint ->
            assertTrue(hint, source.contains(hint))
        }
    }

    @Test
    fun theHintIsBuiltInOnePlace() {
        // Six bespoke refusals is how the route commands drifted into jargon.
        assertTrue(source.contains("private fun unrecognizedCommandHintKo()"))
    }

    @Test
    fun eachHintFitsInOneBreath() {
        val hint = Regex("private fun unrecognizedCommandHintKo\\(\\)[\\s\\S]*?\\n    }")
            .find(source)
            ?.value
            .orEmpty()
        val spoken = Regex("\"([^\"]*말씀해 주세요\\.)\"").findAll(hint).map { it.groupValues[1] }.toList()

        assertTrue("문구 ${spoken.size}개", spoken.size >= 6)
        spoken.forEach { line ->
            val full = "못 알아들었습니다. $line"
            assertTrue("$full (${full.length}자)", full.length <= 50)
        }
    }
}
