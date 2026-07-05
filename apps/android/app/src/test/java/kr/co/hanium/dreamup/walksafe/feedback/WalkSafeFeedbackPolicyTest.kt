package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeFeedbackPolicyTest {
    @Test
    fun blocksAlertsBeforeDeviceGatePass() {
        val policy = WalkSafeFeedbackPolicy()

        val action = policy.evaluate(candidate(), deviceGateAllowsAlerts = false, nowMs = 1_000L)

        assertNull(action)
    }

    @Test
    fun emitsOnlyAfterStableFreshMetricDepth() {
        val policy = WalkSafeFeedbackPolicy()

        val unstable = policy.evaluate(candidate(trackAgeFrames = 2, trackStableMs = 500L), true, 1_000L)
        val stable = policy.evaluate(candidate(trackAgeFrames = 3, trackStableMs = 600L), true, 1_800L)

        assertNull(unstable)
        assertNotNull(stable)
    }

    @Test
    fun rateLimitsPerTrackAndGlobalAlerts() {
        val policy = WalkSafeFeedbackPolicy()

        val first = policy.evaluate(candidate(trackId = "a"), true, 1_000L)
        val sameTrack = policy.evaluate(candidate(trackId = "a"), true, 2_000L)
        val otherTrackBeforeGlobalWindow = policy.evaluate(candidate(trackId = "b"), true, 1_500L)
        val otherTrackAfterGlobalWindow = policy.evaluate(candidate(trackId = "b"), true, 1_800L)

        assertNotNull(first)
        assertNull(sameTrack)
        assertNull(otherTrackBeforeGlobalWindow)
        assertNotNull(otherTrackAfterGlobalWindow)
    }

    @Test
    fun staleOrUntrustedDepthNeverEmits() {
        val policy = WalkSafeFeedbackPolicy()

        assertNull(policy.evaluate(candidate(stale = true), true, 1_000L))
        assertNull(policy.evaluate(candidate(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH), true, 2_000L))
    }

    @Test
    fun vibrationIsReservedForStopAlerts() {
        assertNull(VibrationPatterns.forLevel(MessageLevel.WARNING))
        assertNull(VibrationPatterns.forLevel(MessageLevel.CAUTION))
        assertNull(VibrationPatterns.forLevel(MessageLevel.AWARE))
        assertNotNull(VibrationPatterns.forLevel(MessageLevel.STOP))
    }

    @Test
    fun navigationAnnouncementsAreSuppressedRightAfterRiskAlert() {
        val policy = WalkSafeFeedbackPolicy()

        policy.evaluate(candidate(), true, 1_000L)

        assertFalse(policy.canSpeakNavigation(2_200L))
        assertTrue(policy.canSpeakNavigation(3_600L))
    }

    private fun candidate(
        trackId: String = "track-1",
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        trackAgeFrames: Int = 3,
        trackStableMs: Long = 700L,
        stale: Boolean = false,
    ): FeedbackCandidate {
        return FeedbackCandidate(
            trackId = trackId,
            message = "전방 약 2보 앞 사람. 속도를 줄이세요.",
            level = MessageLevel.WARNING,
            source = source,
            confidence = 0.80f,
            timestampMs = 1_000L,
            trackAgeFrames = trackAgeFrames,
            trackStableMs = trackStableMs,
            stale = stale,
        )
    }
}
