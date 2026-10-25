package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MetricDepthDecision
import kr.co.hanium.dreamup.walksafe.depth.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S13-18: a risk that stays visible for minutes. Each 100 ms camera frame is evaluated before the
 * route guidance check, and every delivered alert takes 1 s to speak.
 */
class WalkSafeFeedbackPersistentRiskNavigationTest {
    private data class Run(val alerts: List<Pair<Long, MessageLevel>>, val navigationWindows: List<Long>)

    private fun simulate(
        durationMs: Long,
        levelAt: (Long) -> MessageLevel,
        distanceAt: (Long) -> Float? = { 4.0f },
        messageAt: ((Long) -> String)? = null,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        confidence: Float = 0.9f,
    ): Run {
        val policy = WalkSafeFeedbackPolicy()
        val alerts = mutableListOf<Pair<Long, MessageLevel>>()
        val navigation = mutableListOf<Long>()
        var speakingUntil = Long.MIN_VALUE
        var pending: Pair<String, Long>? = null
        var now = 1_000L
        while (now <= 1_000L + durationMs) {
            pending?.let { (trackId, evaluatedAt) ->
                if (now >= speakingUntil) {
                    policy.confirmFeedbackDelivery(trackId, evaluatedAt, speakingUntil)
                    pending = null
                }
            }
            val level = levelAt(now)
            val candidate = FeedbackCandidate(
                trackId = "track-1",
                message = messageAt?.invoke(now)
                    ?: if (level == MessageLevel.STOP) "멈추세요. 주변을 확인하세요." else "앞에 사람. 속도를 늦추고 주변을 확인하세요.",
                level = level,
                source = source,
                confidence = confidence,
                timestampMs = now,
                trackAgeFrames = 10,
                trackStableMs = 1_000L,
                stale = false,
                validUntilMs = now + 400L,
                observedAtElapsedRealtimeMs = now,
                observationMaxAgeMs = 400L,
                riskDistanceM = distanceAt(now),
            )
            if (pending == null) {
                policy.evaluate(candidate, true, now)?.let { action ->
                    assertTrue(policy.claimFeedbackDelivery(action.trackId, now, now))
                    alerts += now to action.level
                    pending = action.trackId to now
                    speakingUntil = now + 1_000L
                }
            } else {
                policy.evaluate(candidate, true, now)
            }
            if (pending == null && policy.canSpeakNavigation(now)) navigation += now
            now += 100L
        }
        return Run(alerts, navigation)
    }

    /**
     * The only metric CAUTION the message layer produces: a weak metric source (confidence below
     * the source's metric threshold, 0.60 for full depth) within 2.75 m. It still passes the
     * 0.55 output gate. The phrase carries no distance, so it does not change while approaching.
     */
    private fun weakMetricCaution(messagePolicy: MessagePolicy, distanceM: Float, nowMs: Long): String {
        val decision = messagePolicy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = WEAK_METRIC_SOURCE,
                riskDistanceM = distanceM,
                trend = Trend.APPROACHING,
                confidenceFinal = WEAK_METRIC_CONFIDENCE,
                trackKey = "track-1",
            ),
            nowMs,
        )
        assertEquals("distance=$distanceM", MessageLevel.CAUTION, decision.userFacing.messageLevel)
        return requireNotNull(decision.userFacing.message)
    }

    private fun simulateWeakMetricCaution(durationMs: Long, distanceAt: (Long) -> Float): Run {
        val messagePolicy = MessagePolicy().forFeedbackQueue()
        return simulate(
            durationMs = durationMs,
            levelAt = { MessageLevel.CAUTION },
            distanceAt = distanceAt,
            messageAt = { at -> weakMetricCaution(messagePolicy, distanceAt(at), at) },
            source = WEAK_METRIC_SOURCE,
            confidence = WEAK_METRIC_CONFIDENCE,
        )
    }

    @Test
    fun persistentSameCautionIsSpokenOnceAndRouteGuidanceGetsItsTurnForMinutes() {
        val run = simulate(durationMs = 5 * 60_000L, levelAt = { MessageLevel.CAUTION })

        assertEquals(listOf(MessageLevel.CAUTION), run.alerts.map { it.second })
        // Route guidance is available again after the risk cooldown, and stays available.
        val lastMinute = run.navigationWindows.filter { it > 1_000L + 4 * 60_000L }
        assertTrue("navigation windows in last minute=${lastMinute.size}", lastMinute.size > 500)
    }

    @Test
    fun persistentWeakMetricCautionAtTheSameDistanceIsSpokenOnce() {
        val run = simulateWeakMetricCaution(durationMs = 60_000L) { 2.7f }

        assertEquals(listOf(MessageLevel.CAUTION), run.alerts.map { it.second })
    }

    @Test
    fun cautionThatMovesMarkedlyCloserIsSpokenAgain() {
        // S13-18-b: a producible (weak metric) CAUTION, 2.7 m -> 2.2 m, not a trusted-metric input.
        val run = simulateWeakMetricCaution(durationMs = 20_000L) { at -> if (at < 11_000L) 2.7f else 2.2f }

        assertEquals(2, run.alerts.size)
        assertTrue(run.alerts.all { it.second == MessageLevel.CAUTION })
        assertTrue(run.alerts[1].first >= 11_000L)
    }

    @Test
    fun weakMetricCautionApproachingToOneMetreIsSpokenAgain() {
        // S13-18-a: the same "가까운 X 가능성" phrase from 2.7 m down to 1.0 m is re-announced once.
        val messagePolicy = MessagePolicy().forFeedbackQueue()
        assertEquals(
            weakMetricCaution(messagePolicy, 2.7f, 1_000L),
            weakMetricCaution(messagePolicy, 1.0f, 1_100L),
        )
        val run = simulateWeakMetricCaution(durationMs = 30_000L) { at -> if (at < 11_000L) 2.7f else 1.0f }

        assertEquals(listOf(MessageLevel.CAUTION, MessageLevel.CAUTION), run.alerts.map { it.second })
        assertTrue(run.alerts[1].first in 11_000L..11_100L)
    }

    @Test
    fun pseudoDepthCautionWithoutDistanceIsStillSpokenOnce() {
        val run = simulate(
            durationMs = 60_000L,
            levelAt = { MessageLevel.CAUTION },
            distanceAt = { null },
            messageAt = { "사람와의 거리가 줄어드는 것 같습니다. 속도를 늦추고 주변을 확인하세요." },
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            confidence = 0.3f,
        )

        assertEquals(listOf(MessageLevel.CAUTION), run.alerts.map { it.second })
    }

    @Test
    fun cautionWhosePositionPhraseChangesIsSpokenAgain() {
        val run = simulate(
            durationMs = 20_000L,
            levelAt = { MessageLevel.CAUTION },
            messageAt = { at ->
                if (at < 11_000L) "왼쪽 앞에 사람. 속도를 늦추고 주변을 확인하세요."
                else "오른쪽 앞에 사람. 속도를 늦추고 주변을 확인하세요."
            },
        )

        assertEquals(2, run.alerts.size)
        assertTrue(run.alerts[1].first >= 11_000L)
    }

    @Test
    fun persistentStopStillRepeatsAndRouteGuidanceIsNotInserted() {
        val run = simulate(durationMs = 60_000L, levelAt = { MessageLevel.STOP }, distanceAt = { 0.6f })

        assertTrue(run.alerts.size >= 10)
        assertTrue(run.alerts.all { it.second == MessageLevel.STOP })
        assertTrue(run.navigationWindows.isEmpty())
    }

    @Test
    fun cautionEscalatingToStopIsAnnouncedImmediately() {
        val run = simulate(
            durationMs = 60_000L,
            levelAt = { at -> if (at < 31_000L) MessageLevel.CAUTION else MessageLevel.STOP },
            distanceAt = { at -> if (at < 31_000L) 4.0f else 0.6f },
        )

        val stop = run.alerts.firstOrNull { it.second == MessageLevel.STOP }
        assertNotNull(stop)
        assertTrue(stop!!.first - 31_000L <= 100L)
        assertFalse(run.navigationWindows.any { it > stop.first })
    }

    private companion object {
        val WEAK_METRIC_SOURCE = DepthSource.ARCORE_FULL_DEPTH
        const val WEAK_METRIC_CONFIDENCE = 0.57f
    }
}
