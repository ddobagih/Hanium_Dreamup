package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hazard warning must not state how far away the hazard is.
 *
 * Route guidance can: a turn stays where it is and only the walker closes on it. A hazard cannot,
 * because three clocks run at once — the model spends time recognising, the walker keeps moving
 * during it, and the hazard may be moving toward the walker. Measured on SM-A716S the detector
 * takes 651 ms, which is 0.78 m of walking before the sentence starts, and the sentence itself
 * costs more; "약 2보" is roughly 1.4 m and is already wrong by the time it finishes being said.
 *
 * Distance still decides the warning — it picks the urgency, and the action phrase carries that.
 * It is simply never spoken, and never shown.
 */
class ObstacleWarningStatesNoDistanceTest {
    @Test
    fun aNearObstacleNamesItselfAndTheActionOnly() {
        val message = requireNotNull(warningFor(riskDistanceM = 0.6f))

        assertEquals("전방 사람. 멈추세요. 주변을 확인하세요.", message)
    }

    @Test
    fun everyWarningEndsWithAnActionRatherThanAFigure() {
        // Which action a given distance earns is the threshold table's business; what matters here
        // is that the sentence closes on something to do.
        val actions = listOf("멈추세요. 주변을 확인하세요.", "멈출 준비를 하세요.", "속도를 늦추고 주변을 확인하세요.")
        val distances = generateSequence(0.3f) { it + 0.3f }.takeWhile { it <= 4f }

        distances.forEach { distance ->
            val message = warningFor(distance) ?: return@forEach
            assertTrue("$distance -> $message", message.startsWith("전방 "))
            assertTrue("$distance -> $message", actions.any { message.endsWith(it) })
        }
    }

    @Test
    fun noWarningAtAnyDistanceSpeaksStepsOrMetres() {
        // Walks the band that used to produce "약 N보 이내" and "약 N보 앞".
        val distances = generateSequence(0.3f) { it + 0.15f }.takeWhile { it <= 5f }
        distances.forEach { distance ->
            val message = warningFor(distance) ?: return@forEach
            assertFalse("$distance -> $message", message.contains("보 "))
            assertFalse("$distance -> $message", message.contains("보."))
            assertFalse("$distance -> $message", message.contains("m"))
            assertFalse("$distance -> $message", message.contains("미터"))
        }
    }

    @Test
    fun proximityIsNotRestatedInWords() {
        // "바로 앞" carries the same claim the numbers did; the action already says it is close.
        val distances = generateSequence(0.2f) { it + 0.1f }.takeWhile { it <= 3f }
        distances.forEach { distance ->
            val message = warningFor(distance) ?: return@forEach
            assertFalse("$distance -> $message", message.contains("바로 앞"))
            assertFalse("$distance -> $message", message.contains("이내"))
        }
    }

    private fun warningFor(riskDistanceM: Float): String? = MessagePolicy().evaluate(
        MetricDepthDecision(
            className = "person",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = riskDistanceM,
            trend = Trend.STABLE,
            confidenceFinal = 0.9f,
            trackKey = "person-$riskDistanceM",
        ),
        nowMs = 0L,
    ).userFacing.message
}
