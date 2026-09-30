package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeFeedbackHistoryRetentionTest {
    @Test
    fun completedTracksExpireWhileTheWalkRemainsUnsafeWithoutShorteningCooldown() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(globalIntervalMs = 0L))
        repeat(128) { index ->
            val nowMs = 1_000L + index * 10L
            deliver(policy, "old-$index", nowMs)
        }

        deliver(policy, "current", 5_000L)
        assertHistory(policy, setOf("current"))
        assertNull(policy.evaluate(candidate("current", 6_000L), true, 6_000L))
        deliver(policy, "current", 7_500L)
        deliver(policy, "old-0", 7_501L)
        assertHistory(policy, setOf("current", "old-0"))
    }

    @Test
    fun currentAndClaimedTracksSurvivePruningWhileAnAbsentCompletedPeerExpires() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(globalIntervalMs = 0L))
        deliver(policy, "active", 1_000L)
        deliver(policy, "recent", 3_000L)

        val pending = requireNotNull(policy.evaluateCandidates(
            listOf(candidate("active", 4_000L), candidate("pending", 4_000L)),
            true,
            4_000L,
        ))
        assertEquals("pending", pending.trackId)
        assertTrue(policy.claimFeedbackDelivery("pending", 4_000L, 4_000L))
        assertHistory(policy, setOf("active", "recent", "pending"), setOf("active", "recent"))

        assertNull(policy.evaluate(candidate("active", 6_500L), true, 6_500L))
        assertHistory(policy, setOf("active", "pending"), setOf("active"))
        assertTrue(policy.confirmFeedbackDelivery("pending", 4_000L, 6_500L))
        assertHistory(policy, setOf("active", "pending"))
    }

    private fun deliver(policy: WalkSafeFeedbackPolicy, trackId: String, nowMs: Long) {
        val action = requireNotNull(policy.evaluate(candidate(trackId, nowMs), true, nowMs))
        assertEquals(trackId, action.trackId)
        assertTrue(policy.claimFeedbackDelivery(trackId, nowMs, nowMs))
        assertTrue(policy.confirmFeedbackDelivery(trackId, nowMs, nowMs))
    }

    private fun candidate(trackId: String, nowMs: Long) = FeedbackCandidate(
        trackId = trackId,
        message = "Warning $trackId",
        level = MessageLevel.WARNING,
        source = DepthSource.ARCORE_RAW_DEPTH,
        confidence = 0.9f,
        timestampMs = nowMs,
        trackAgeFrames = 3,
        trackStableMs = 700L,
        stale = false,
    )

    private fun assertHistory(
        policy: WalkSafeFeedbackPolicy,
        emitIds: Set<String>,
        completedIds: Set<String> = emitIds,
    ) {
        assertEquals("lastTrackEmitAt", emitIds, historyIds(policy, "lastTrackEmitAt"))
        assertEquals("lastTrackConfirmedAt", completedIds, historyIds(policy, "lastTrackConfirmedAt"))
        assertEquals("lastTrackDeliveredLevel", completedIds, historyIds(policy, "lastTrackDeliveredLevel"))
        assertEquals("lastTrackDeliveryOrder", completedIds, historyIds(policy, "lastTrackDeliveryOrder"))
    }

    private fun historyIds(policy: WalkSafeFeedbackPolicy, fieldName: String): Set<String> {
        val field = WalkSafeFeedbackPolicy::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(policy) as Map<*, *>).keys.map { it as String }.toSet()
    }
}
