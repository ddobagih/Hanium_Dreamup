package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackBatch
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTactileFeedbackFairnessTest {
    @Test
    fun mainDispatchGivesFourPrimaryOutputsATurnBeforeRepeating() {
        assertFairDispatch(unknownFourth = false)
    }

    @Test
    fun mainDispatchGivesFreshIndependentUnknownATurnAlongsideThreePrimaryOutputs() {
        assertFairDispatch(unknownFourth = true)
    }

    @Test
    fun expiredUnknownIsExcludedWhileStalePrimaryCannotClaimItsTurn() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), policy, TactileFrameFeedbackActuator { })
        val expired = batch(START, listOf(output("unknown-4", START)))
        val now = START + 900L
        val dispatch = coordinator.dispatchFeedback(
            frame(listOf(output("primary-1", now))), stale = true, deviceGateAllowsAlerts = true,
            nowMs = now, independentFreshOutputs = listOf(expired),
        )
        assertNull(dispatch.action)
        assertTrue(dispatch.activeRiskTrackIds.isEmpty())

        val fresh = coordinator.dispatchFeedback(frame(emptyList()), stale = true, deviceGateAllowsAlerts = true,
            nowMs = now, independentFreshOutputs = listOf(batch(now, listOf(output("unknown-4", now)))))
        assertEquals("unknown-4", requireNotNull(fresh.action).trackId)
        assertEquals(setOf("unknown-4"), fresh.activeRiskTrackIds)
        assertTrue(policy.claimFeedbackDelivery("unknown-4", now))
        assertTrue(policy.confirmFeedbackDelivery("unknown-4", now, now))
    }

    private fun assertFairDispatch(unknownFourth: Boolean) {
        val policy = WalkSafeFeedbackPolicy()
        val emitted = mutableListOf<TactileFrameFeedbackDispatch>()
        val coordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), policy,
            TactileFrameFeedbackActuator { emitted += it })
        val primaryIds = (1..if (unknownFourth) 3 else 4).map { "primary-$it" }
        val ids = primaryIds + if (unknownFourth) listOf("unknown-4") else emptyList()
        val completed = mutableListOf<Pair<String, Long>>()
        for (now in START..START + 10_000L step 100L) {
            val currentFrame = frame(primaryIds.map { output(it, now) })
            val unknown = if (unknownFourth) listOf(batch(now, listOf(output("unknown-4", now)))) else emptyList()
            val dispatch = coordinator.dispatchFeedback(currentFrame, stale = false, deviceGateAllowsAlerts = true,
                nowMs = now, independentFreshOutputs = unknown)
            assertSame(dispatch, emitted.last())
            assertEquals(ids.toSet(), dispatch.activeRiskTrackIds)
            dispatch.action?.let { action ->
                assertEquals("위험 ${action.trackId} @$now", action.message)
                if (action.trackId == "unknown-4") assertEquals(now + 800L, action.validUntilMs)
                assertTrue(policy.claimFeedbackDelivery(action.trackId, now))
                assertFalse(policy.claimFeedbackDelivery(action.trackId, now))
                val whileClaimed = coordinator.dispatchFeedback(currentFrame, false, true, now,
                    independentFreshOutputs = unknown)
                assertNull(whileClaimed.action)
                assertTrue(policy.confirmFeedbackDelivery(action.trackId, now, now))
                assertFalse(policy.confirmFeedbackDelivery(action.trackId, now, now))
                completed += action.trackId to now
            }
        }
        assertTrue("completed=$completed", completed.size >= 8)
        assertEquals("first round=$completed", ids.toSet(), completed.take(4).map { it.first }.toSet())
        val counts = ids.map { id -> completed.count { it.first == id } }
        assertTrue("counts=$counts", counts.max() - counts.min() <= 1)
        completed.zipWithNext().forEach { (earlier, later) -> assertTrue(later.second - earlier.second >= 700L) }
        ids.forEach { id -> completed.filter { it.first == id }.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later.second - earlier.second >= 2_500L)
        } }
    }

    private fun frame(outputs: List<TrackedObjectDepth>) = MainActivity.TactileSnapshotFrameResult(
        null, emptyList(), false,
        TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "test", null)),
    )

    private fun batch(now: Long, outputs: List<TrackedObjectDepth>) = UnknownObjectFeedbackBatch(
        frameId(now), frameId(now) / 1_000_000L, now * 1_000_000L, now, WalkRuntimeEpoch("fairness-test", 0L), outputs,
    )

    private fun output(id: String, now: Long): TrackedObjectDepth {
        val box = RectNorm(if (id.startsWith("unknown")) 0.7f else 0.1f, 0.3f, 0.2f, 0.4f)
        return TrackedObjectDepth(
            frameId = frameId(now), timestampMs = frameId(now) / 1_000_000L, trackId = id,
            className = if (id.startsWith("unknown")) "unnamed-obstacle" else "person",
            detectionConfidence = 0.95f, bboxNorm = box, polygonNorm = emptyList(), maskAreaNorm = box.area,
            centerNorm = box.center, bottomContactNorm = null, source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1f, rayDistanceM = 1f, groundDistanceM = 1f, riskDistanceM = 1f,
            validSampleCount = 80, validSampleRatio = 0.9f, depthMedianM = 1f, depthP20M = 1f, depthIqrM = 0.05f,
            trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.WARNING, "위험 $id @$now"),
            trackAgeFrames = 20, trackStableMs = 1_900L,
        )
    }

    private fun frameId(now: Long) = 5_000_000_000_000L + (now - START) * 1_000_000L

    private companion object {
        const val START = 10_000L
    }
}
