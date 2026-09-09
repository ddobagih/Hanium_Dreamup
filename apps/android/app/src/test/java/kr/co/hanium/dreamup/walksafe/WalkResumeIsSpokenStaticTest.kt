package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resuming a walk only updated the screen. A walker who cannot see it had no way to know guidance
 * had come back, and the practice screen had already taught them a resume sentence that never
 * arrived — the one rehearsal whose fault was silence rather than wording.
 *
 * Both resume paths speak it now: the spoken confirmation and the on-screen button.
 */
class WalkResumeIsSpokenStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun theSpokenResumePathAnnouncesTheResume() {
        val block = functionBlock("private fun handleWalkSessionResumeRecognition(")

        assertTrue(block.contains("speakInteraction(PriorityUserPractice.RESUME.instructionKo)"))
    }

    @Test
    fun theButtonResumePathAnnouncesTheResume() {
        val block = functionBlock("private fun confirmWalkSessionResumeFromButton()")

        assertTrue(block.contains("speakInteraction(PriorityUserPractice.RESUME.instructionKo)"))
    }

    @Test
    fun bothPathsSpeakOnlyAfterTheRuntimeIsActuallyRunning() {
        // Announcing a resume that did not happen is worse than announcing nothing.
        listOf(
            functionBlock("private fun handleWalkSessionResumeRecognition("),
            functionBlock("private fun confirmWalkSessionResumeFromButton()"),
        ).forEach { block ->
            val guard = block.indexOf("if (isWalkSessionRuntimeActive())")
            val speak = block.indexOf("speakInteraction(PriorityUserPractice.RESUME")
            assertTrue(block, guard in 0 until speak)
        }
    }

    @Test
    fun theSpokenLineIsTheRehearsedOne() {
        // Reading it from the rehearsal keeps the two from drifting apart again.
        assertTrue(
            PriorityUserPractice.RESUME.instructionKo,
            PriorityUserPractice.RESUME.instructionKo ==
                "주변을 확인했습니다. 보행 안내를 다시 시작합니다.",
        )
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing source marker: $marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        return source.substring(bodyStart)
    }
}
