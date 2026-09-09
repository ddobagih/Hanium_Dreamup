package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MetricDepthDecision
import kr.co.hanium.dreamup.walksafe.depth.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The practice screen exists to teach what a sound means before the walker meets it on a street.
 * A blind user recognises the situation by the sentence, so a rehearsal that says something the
 * app never says trains the wrong cue and costs a beat at the moment it matters.
 *
 * These lock the rehearsal to the live wording. The hazard rehearsal names no object because the
 * live warning names whichever one it saw; everything after that must match.
 */
class PracticeMatchesLiveSpeechTest {
    @Test
    fun theHazardRehearsalHasTheShapeOfALiveWarning() {
        val live = requireNotNull(
            MessagePolicy().evaluate(
                MetricDepthDecision(
                    className = "person",
                    source = DepthSource.ARCORE_RAW_DEPTH,
                    riskDistanceM = 0.6f,
                    trend = Trend.STABLE,
                    confidenceFinal = 0.9f,
                    trackKey = "person-live",
                ),
                nowMs = 0L,
            ).userFacing.message,
        )
        val rehearsal = PriorityUserPractice.HAZARD_ALERT.instructionKo

        assertEquals("전방 장애물. 멈추세요. 주변을 확인하세요.", rehearsal)
        // Same opening and same action; only the object differs, which is the point.
        assertTrue(live, live.startsWith("전방 "))
        assertTrue(live, live.endsWith("멈추세요. 주변을 확인하세요."))
        assertTrue(rehearsal, rehearsal.endsWith("멈추세요. 주변을 확인하세요."))
    }

    @Test
    fun theHazardRehearsalNamesNoObject() {
        val rehearsal = PriorityUserPractice.HAZARD_ALERT.instructionKo

        listOf("사람", "자전거", "자동차", "오토바이", "킥보드").forEach {
            assertTrue(rehearsal, !rehearsal.contains(it))
        }
    }

    @Test
    fun theSafeStopRehearsalUsesTheWordingTheAppSpeaks() {
        val rehearsal = PriorityUserPractice.SAFE_STOP.instructionKo

        // MainActivity speaks "보행 기능을 안전 중지했습니다. $reason"; the rehearsal carries the
        // fallback reason so the walker hears the whole shape, reason included.
        assertTrue(rehearsal, rehearsal.startsWith("보행 기능을 안전 중지했습니다."))
        assertTrue(rehearsal, rehearsal.contains("필수 기능 상태가 바뀌어 새 보행 준비가 필요합니다."))
    }

    @Test
    fun theRehearsalsStopUsingWordingTheAppNeverSays() {
        val all = PriorityUserPractice.entries.map { it.instructionKo }

        // "안전정지" was rehearsal-only; the app says "안전 중지".
        all.forEach { assertTrue(it, !it.contains("안전정지")) }
        // "위험 안내입니다" was rehearsal-only; a live warning opens with the object.
        all.forEach { assertTrue(it, !it.contains("위험 안내입니다")) }
    }

    @Test
    fun thePauseRehearsalStillMatchesTheLiveLine() {
        assertEquals(
            "보행 안내를 일시정지했습니다.",
            PriorityUserPractice.PAUSE.instructionKo,
        )
    }
}
