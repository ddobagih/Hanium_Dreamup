package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun carryCopiesTheCompletedMessageAndCautionBaselineButNotMetricRiskOrReservations() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(globalIntervalMs = 0L))
        val source = candidate("source", 1_000L).copy(level = MessageLevel.CAUTION, riskDistanceM = 3f)
        assertNotNull(policy.evaluate(source, true, 1_000L))
        policy.carryCompletedFeedbackHistory("source", "target")
        assertTrue(completedHistory(policy).isEmpty())
        assertTrue(policy.confirmFeedbackDelivery("source", 1_000L, 1_050L))
        val completedSource = completedHistory(policy).getValue("source")
        assertNotNull(fact(completedSource, "riskEvidence"))

        assertNotNull(policy.evaluate(candidate("target", 1_100L), true, 1_100L))
        policy.carryCompletedFeedbackHistory("source", "target")
        assertFalse(completedHistory(policy).containsKey("target"))
        policy.rejectUndeliveredFeedback("target", 1_100L)
        policy.carryCompletedFeedbackHistory("source", "target")
        val carried = completedHistory(policy).getValue("target")
        for (field in listOf("confirmedAtMs", "level", "message", "cautionDistanceM", "deliveryOrder")) {
            assertEquals(field, fact(completedSource, field), fact(carried, field))
        }
        assertNull(fact(carried, "riskEvidence"))
        assertSame(completedSource, completedHistory(policy)["source"])
        assertEquals(1_050L, emitAt(policy, "target"))
    }

    @Test
    fun olderCarryAndLateTerminalEventsCannotReplaceANewerCompletedHistory() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(globalIntervalMs = 0L))
        deliver(policy, "target", 1_000L)
        deliver(policy, "source", 1_100L)
        val stop = requireNotNull(policy.evaluate(
            candidate("target", 1_500L).copy(level = MessageLevel.STOP, message = "New stop"), true, 1_500L,
        ))
        assertTrue(policy.claimFeedbackDelivery(stop, 1_500L, 1_500L))
        assertTrue(policy.confirmFeedbackDelivery(stop, 1_500L, 1_600L))
        val newest = completedHistory(policy).getValue("target")

        policy.carryCompletedFeedbackHistory("source", "target")
        assertFalse(policy.confirmFeedbackDelivery("target", 1_000L, 2_000L))
        policy.rejectUndeliveredFeedback("target", 1_000L)
        assertSame(newest, completedHistory(policy)["target"])
        assertEquals("New stop", fact(newest, "message"))
        assertEquals(1_600L, emitAt(policy, "target"))
    }

    @Test
    fun rejectedReservationRollsBackItsClockWithoutChangingCompletedFactsOrANewerOwner() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(globalIntervalMs = 0L))
        deliver(policy, "track", 1_000L)
        val original = completedHistory(policy).getValue("track")
        assertNotNull(policy.evaluate(candidate("track", 1_200L).copy(level = MessageLevel.STOP), true, 1_200L))
        assertSame(original, completedHistory(policy)["track"])
        policy.rejectUndeliveredFeedback("track", 1_200L)
        assertSame(original, completedHistory(policy)["track"])
        assertEquals(1_000L, emitAt(policy, "track"))

        val retry = requireNotNull(policy.evaluate(
            candidate("track", 1_201L).copy(level = MessageLevel.STOP), true, 1_201L,
        ))
        assertTrue(policy.claimFeedbackDelivery(retry, 1_201L, 1_201L))
        policy.rejectUndeliveredFeedback("track", 1_200L)
        assertEquals(1_201L, emitAt(policy, "track"))
        assertTrue(policy.confirmFeedbackDelivery(retry, 1_201L, 1_300L))
        val newest = completedHistory(policy).getValue("track")
        assertFalse(policy.confirmFeedbackDelivery("track", 1_200L, 1_400L))
        assertSame(newest, completedHistory(policy)["track"])
        assertEquals(1_300L, emitAt(policy, "track"))
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
        validUntilMs = nowMs + 800L,
        observedAtElapsedRealtimeMs = nowMs,
        observationMaxAgeMs = 800L,
    )

    private fun assertHistory(
        policy: WalkSafeFeedbackPolicy,
        emitIds: Set<String>,
        completedIds: Set<String> = emitIds,
    ) {
        assertEquals("lastTrackEmitAt", emitIds, historyIds(policy, "lastTrackEmitAt"))
        val completed = completedHistory(policy)
        assertEquals("completed confirmation times", completedIds, completed.filterValues {
            it.javaClass.getDeclaredField("confirmedAtMs").apply { isAccessible = true }.get(it) != null
        }.keys)
        assertEquals("completed levels", completedIds, completed.filterValues {
            it.javaClass.getDeclaredField("level").apply { isAccessible = true }.get(it) != null
        }.keys)
        assertEquals("completed delivery order", completedIds, completed.filterValues {
            it.javaClass.getDeclaredField("deliveryOrder").apply { isAccessible = true }.get(it) != null
        }.keys)
    }

    private fun completedHistory(policy: WalkSafeFeedbackPolicy): Map<String, Any> {
        val field = WalkSafeFeedbackPolicy::class.java.getDeclaredField("completedTrackHistory")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(policy) as Map<String, Any>
    }

    private fun fact(history: Any, name: String): Any? =
        history.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(history)

    private fun emitAt(policy: WalkSafeFeedbackPolicy, trackId: String): Long? {
        val field = WalkSafeFeedbackPolicy::class.java.getDeclaredField("lastTrackEmitAt")
            .apply { isAccessible = true }
        return (field.get(policy) as Map<*, *>)[trackId] as Long?
    }

    private fun historyIds(policy: WalkSafeFeedbackPolicy, fieldName: String): Set<String> {
        val field = WalkSafeFeedbackPolicy::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(policy) as Map<*, *>).keys.map { it as String }.toSet()
    }
}
