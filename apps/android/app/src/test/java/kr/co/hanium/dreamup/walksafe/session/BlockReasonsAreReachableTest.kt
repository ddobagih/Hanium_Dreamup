package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block reason nobody produces is a sentence the walker is promised and never hears, and worse,
 * a sentence a reader believes is enforced. `VIBRATION_UNAVAILABLE` was one: vibration stopped
 * being a start requirement in 37dd42c, but the wording and the field feeding it stayed, so the
 * source read as though a phone without a vibrator could not walk.
 *
 * Every reason has to be constructed somewhere other than its own declaration.
 */
class BlockReasonsAreReachableTest {
    private val sources = File("src/main/java/kr/co/hanium/dreamup/walksafe")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.path to it.readText() }

    @Test
    fun everyBlockReasonIsProducedSomewhere() {
        val declaration = "session/PriorityUserOnboardingPolicy.kt"

        val unreachable = PriorityUserBlockReason.entries.filter { reason ->
            sources.none { (path, text) ->
                if (path.endsWith(declaration)) {
                    // Its own file counts only where the policy actually returns it.
                    text.contains("PriorityUserBlockReason.${reason.name}")
                } else {
                    text.contains(reason.name)
                }
            }
        }

        assertTrue(
            "아무도 만들지 않는 차단 사유: ${unreachable - KNOWN_UNREACHABLE}" +
                " — ObstacleLabels 처럼 생산자를 붙이거나 사유를 지우세요",
            (unreachable - KNOWN_UNREACHABLE).isEmpty(),
        )
    }

    @Test
    fun vibrationIsNoLongerAStartRequirement() {
        // Vibration restricts HAPTIC_FEEDBACK; speech is the primary channel and still works.
        assertTrue(
            PriorityUserBlockReason.entries.none { it.name.contains("VIBRATION") },
        )
    }

    @Test
    fun theSupportEnvironmentCarriesNoUnreadSignal() {
        val policy = sources.entries
            .first { it.key.endsWith("session/PriorityUserOnboardingPolicy.kt") }
            .value

        assertTrue(policy, !policy.contains("vibrationAvailable"))
    }

    private companion object {
        /**
         * Also produced by nobody, but unlike vibration the requirement is genuinely enforced —
         * `WalkSafeStartupCapabilityResolver` blocks on OFFLINE_KOREAN_TTS with near-identical
         * wording. Removing the duplicate is pending a decision, so it is named here rather than
         * silently tolerated; the guard still catches any new dead reason.
         */
        val KNOWN_UNREACHABLE = setOf(
            PriorityUserBlockReason.OFFLINE_KOREAN_VOICE_UNAVAILABLE,
        )
    }
}
