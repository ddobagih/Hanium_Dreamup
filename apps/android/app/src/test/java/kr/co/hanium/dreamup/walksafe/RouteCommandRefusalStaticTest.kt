package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five route commands refused with "지금은 …할 이탈 상태가 아닙니다", which is scaffolding around
 * one fact — what state the walk is actually in. A walker who cannot see the screen has no other
 * way to learn that, and the refusal withheld it while spending more words than saying it would.
 *
 * The replacements state the state and stop. They are shorter than what they replace, which
 * matters because these are heard mid-walk. Only where exactly one command remains open does the
 * app name it; the three-way choice was already read out when the deviation was announced.
 */
class RouteCommandRefusalStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun noRefusalDescribesAnInternalState() {
        listOf("이탈 상태가 아닙니다", "도착 후보가 없습니다").forEach { jargon ->
            assertFalse(jargon, source.contains(jargon))
        }
    }

    @Test
    fun theRefusalsSayWhatTheWalkIsDoing() {
        listOf(
            "경로를 따라가는 중입니다.",
            "길안내 중이 아닙니다.",
            "경로를 벗어난 상태입니다.",
            "아직 도착 안내가 없습니다.",
        ).forEach { line ->
            assertTrue(line, source.contains(line))
        }
    }

    @Test
    fun aSingleRemainingCommandIsNamed() {
        // Being told nothing here would leave the walker stuck mid-deviation.
        assertTrue(source.contains("위치 다시 확인만 할 수 있습니다."))
    }

    @Test
    fun eachRefusalIsShorterThanTheOneItReplaced() {
        val replaced = "지금은 새 경로를 요청할 이탈 상태가 아닙니다.".length
        listOf(
            "경로를 따라가는 중입니다.",
            "길안내 중이 아닙니다.",
            "경로를 벗어난 상태입니다.",
            "아직 도착 안내가 없습니다.",
            "위치 다시 확인만 할 수 있습니다.",
        ).forEach { line ->
            assertTrue("$line (${line.length}자)", line.length < replaced)
        }
    }

    @Test
    fun allFiveCommandsShareOneExplanation() {
        // Five bespoke refusals is how they drifted into jargon in the first place.
        val calls = Regex("explainRouteCommandUnavailable\\(").findAll(source).count()

        assertTrue("호출 $calls 회", calls >= 6)
    }
}
