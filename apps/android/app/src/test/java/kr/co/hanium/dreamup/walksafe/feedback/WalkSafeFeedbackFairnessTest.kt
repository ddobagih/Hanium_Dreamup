package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeFeedbackFairnessTest {
    @Test
    fun everyContinuouslyVisibleEqualRiskReceivesATurnBeforeAnyRepeat() {
        for (level in listOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)) {
            val policy = WalkSafeFeedbackPolicy()
            val ids = listOf("a", "b", "c", "d")
            val delivered = (1_000L..30_000L step 1_000L).map { now ->
                deliver(policy, ids.map { candidate(it, now, level) }, now)
            }
            assertEquals("$level first round", ids.toSet(), delivered.take(4).toSet())
            val counts = ids.map { id -> delivered.count { it == id } }
            assertEquals(30, counts.sum())
            assertTrue("$level counts=$counts", counts.max() - counts.min() <= 1)
        }
    }

    @Test
    fun queueCapacityDoesNotLimitWhichPersistentPeersCanEverBeAnnounced() {
        val ids = (0 until 8).map { "object-$it" }
        for (capacity in listOf(1, 2, 3)) {
            val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(pendingQueueCapacity = capacity))
            val delivered = ids.indices.map { index ->
                val now = 1_000L + index * 1_000L
                val order = ids.drop(index) + ids.take(index)
                deliver(policy, order.map { candidate(it, now) }, now)
            }
            assertEquals("capacity=$capacity delivered=$delivered", ids.toSet(), delivered.toSet())
        }
    }

    @Test
    fun repeatedStopStillPrecedesAnUnheardWarningWhenItsCooldownHasElapsed() {
        val policy = WalkSafeFeedbackPolicy()
        val stop = candidate("stop", 1_000L, MessageLevel.STOP)
        assertEquals("stop", deliver(policy, listOf(stop), 1_000L))
        assertEquals("stop", deliver(policy, listOf(candidate("new-warning", 3_500L),
            stop.copy(timestampMs = 3_500L)), 3_500L))
    }

    @Test
    fun failedOrCancelledReservationDoesNotCountAsACompletedTurn() {
        for (cancel in listOf(false, true)) {
            val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(
                perTrackIntervalMs = 0, globalIntervalMs = 0, pendingQueueCapacity = 1))
            val candidates = listOf(candidate("a", 1_000L), candidate("b", 1_000L))
            val first = requireNotNull(policy.evaluateCandidates(candidates, true, 1_000L))
            assertEquals("a", first.trackId)
            assertTrue(policy.claimFeedbackDelivery(first.trackId, 1_000L))
            if (cancel) policy.cancelPendingFeedbackDeliveries()
            else policy.rejectUndeliveredFeedback(first.trackId, 1_000L)
            assertFalse(policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_010L))
            assertEquals("a", deliver(policy, candidates, 1_020L))
            assertEquals("b", deliver(policy, candidates, 1_030L))
        }
    }

    @Test
    fun noLongerCurrentQueueEntriesCannotBlockFreshLowerRiskCandidates() {
        val policy = WalkSafeFeedbackPolicy()
        val oldStops = listOf("a", "b", "c").map { candidate(it, 1_000L, MessageLevel.STOP) }
        assertEquals("a", deliver(policy, oldStops, 1_000L))
        assertNull(policy.evaluateCandidates(oldStops.drop(1) +
            candidate("d", 1_100L, MessageLevel.STOP), true, 1_100L))

        // The old STOP observations are absent, not merely rate limited. Their queue TTL has not
        // elapsed, but they cannot occupy the capacity needed by the current warning.
        assertEquals("current", deliver(policy, listOf(candidate("current", 1_800L)), 1_800L))
    }

    @Test
    fun staleUnstableAndLowConfidencePeersNeverGainATurnThroughFairness() {
        val policy = WalkSafeFeedbackPolicy()
        val candidates = listOf(candidate("valid", 1_000L), candidate("stale", 1_000L).copy(stale = true),
            candidate("unstable", 1_000L).copy(trackStableMs = 699L),
            candidate("weak", 1_000L).copy(confidence = 0.54f))
        assertEquals("valid", deliver(policy, candidates, 1_000L))
        assertNull(policy.evaluateCandidates(candidates, true, 1_800L))
        assertEquals("valid", deliver(policy, candidates, 3_500L))
    }

    @Test
    fun completedHistoryResetsWithANewWalk() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(perTrackIntervalMs = 0, globalIntervalMs = 0))
        val candidates = listOf(candidate("a", 1_000L), candidate("b", 1_000L))
        assertEquals("a", deliver(policy, candidates, 1_000L))
        assertEquals("b", deliver(policy, candidates, 1_001L))
        policy.resetForNewWalk()
        assertEquals("b", deliver(policy, candidates.reversed(), 1_002L))
    }

    private fun deliver(policy: WalkSafeFeedbackPolicy, candidates: List<FeedbackCandidate>, now: Long): String {
        val action = requireNotNull(policy.evaluateCandidates(candidates, true, now)) { "No action at $now" }
        assertTrue(policy.claimFeedbackDelivery(action.trackId, now))
        assertTrue(policy.confirmFeedbackDelivery(action.trackId, now, now))
        return action.trackId
    }

    private fun candidate(id: String, now: Long, level: MessageLevel = MessageLevel.WARNING) =
        FeedbackCandidate(id, "위험 $id", level, DepthSource.ARCORE_RAW_DEPTH,
            0.9f, now, 20, 1_900L, false)
}
