package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTestFeedbackFairnessTest {
    @Test
    fun fourFreshPrimaryPeersAllCompleteBeforeAnyPeerRepeats() {
        for (level in listOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)) {
            val ids = (1..4).map { "primary-$it" }
            val completed = runFreshFrames(ids, emptyList(), level)
            assertFairTurns(ids, completed)
        }
    }

    @Test
    fun threePrimaryPeersAndSeparateUnknownAllCompleteBeforeAnyPeerRepeats() {
        val primaryIds = (1..3).map { "primary-$it" }
        val completed = runFreshFrames(primaryIds, listOf("unknown-4"))
        assertFairTurns(primaryIds + "unknown-4", completed)
    }

    @Test
    fun overlappingUnknownDoesNotGainAnExtraTurnAlongsideFourPrimaryPeers() {
        val ids = (1..4).map { "primary-$it" }
        val completed = runFreshFrames(ids, listOf("unknown-duplicate"), duplicateUnknown = true)
        assertFairTurns(ids, completed)
        assertFalse(completed.any { it.first == "unknown-duplicate" })
    }

    @Test
    fun stopEscalationPrecedesUnheardWarningsDuringGlobalCooldown() {
        val coordinator = coordinator()
        offer(coordinator, START, listOf(output("primary-1", START)))
        finish(coordinator, requireNotNull(coordinator.next(START)), START)
        val now = START + 100L
        offer(coordinator, now, (1..4).map { output("primary-$it", now) })
        offer(coordinator, now, listOf(output("unknown-stop", now, MessageLevel.STOP)), unknown = true)

        val stop = requireNotNull(coordinator.next(now))
        assertEquals("unknown-stop", stop.action.trackId)
        assertEquals(MessageLevel.STOP, stop.action.level)
        finish(coordinator, stop, now)
    }

    @Test
    fun onlyLatestSourceOutputsCanFillVacanciesInTheFairQueue() {
        val coordinator = coordinator()
        offer(coordinator, START, (1..4).map { output("old-$it", START) })
        finish(coordinator, requireNotNull(coordinator.next(START)), START)
        for (now in START + 100L..START + 700L step 100L) {
            offer(coordinator, now, listOf(output("current", now)))
            if (now < START + 700L) assertNull(coordinator.next(now))
        }
        assertFalse(coordinator.offer(sample(START + 600L, listOf(output("late-old", START + 600L)))))
        val current = requireNotNull(coordinator.next(START + 700L))
        assertEquals("current", current.action.trackId)
        assertEquals("위험 current @${START + 700L}", current.action.message)
        finish(coordinator, current, START + 700L)
    }

    @Test
    fun failedDeliveryCanRetryWithoutLosingItsUncompletedTurn() {
        val coordinator = coordinator()
        val ids = (1..4).map { "primary-$it" }
        offer(coordinator, START, ids.map { output(it, START) })
        val failed = requireNotNull(coordinator.next(START))
        assertTrue(coordinator.claim(failed, START))
        coordinator.reject(failed)
        assertFalse(coordinator.complete(failed, START + 10L))

        val completed = mutableListOf<Pair<String, Long>>()
        for (now in START + 100L..START + 2_200L step 100L) {
            offer(coordinator, now, ids.map { output(it, now) })
            coordinator.next(now)?.let { delivery ->
                finish(coordinator, delivery, now)
                completed += delivery.action.trackId to now
            }
        }
        assertEquals(START + 100L, completed.first().second)
        assertEquals(ids.toSet(), completed.map { it.first }.toSet())
        assertEquals(4, completed.size)
    }

    @Test
    fun clearingFailedUnknownCancelsItsClaimAndAllowsFreshPrimaryImmediately() {
        val coordinator = coordinator()
        offer(coordinator, START, listOf(output("unknown-4", START)), unknown = true)
        val failed = requireNotNull(coordinator.next(START))
        assertTrue(coordinator.claim(failed, START))
        val now = START + 100L
        offer(coordinator, now, (1..4).map { output("primary-$it", now) })
        offer(coordinator, now, emptyList(), unknown = true)
        coordinator.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)

        assertFalse(coordinator.isDeliverable(failed, now))
        assertFalse(coordinator.complete(failed, now))
        val fresh = requireNotNull(coordinator.next(now))
        assertTrue(fresh.action.trackId.startsWith("primary-"))
        finish(coordinator, fresh, now)
    }

    @Test
    fun expiredUnknownCannotGainATurnWhenPrimaryFramesContinue() {
        val coordinator = coordinator()
        offer(coordinator, START, listOf(output("unknown-4", START)), unknown = true)
        for (now in START..START + 900L step 100L) {
            offer(coordinator, now, listOf(output("primary-1", now)))
        }
        val fresh = requireNotNull(coordinator.next(START + 900L))
        assertEquals("primary-1", fresh.action.trackId)
        finish(coordinator, fresh, START + 900L)
    }

    private fun runFreshFrames(
        primaryIds: List<String>,
        unknownIds: List<String>,
        level: MessageLevel = MessageLevel.WARNING,
        duplicateUnknown: Boolean = false,
    ): List<Pair<String, Long>> {
        val coordinator = coordinator()
        val completed = mutableListOf<Pair<String, Long>>()
        for (now in START..START + 10_000L step 100L) {
            offer(coordinator, now, primaryIds.mapIndexed { index, id ->
                // The duplicate has one matching primary; peers must not make the association ambiguous.
                output(id, now, level, if (duplicateUnknown && index > 0) UNKNOWN_BOX else PRIMARY_BOX)
            })
            if (unknownIds.isNotEmpty()) {
                offer(coordinator, now, unknownIds.map {
                    output(it, now, level, if (duplicateUnknown) PRIMARY_BOX else UNKNOWN_BOX)
                }, unknown = true)
            }
            coordinator.next(now)?.let { delivery ->
                assertEquals("위험 ${delivery.action.trackId} @$now", delivery.action.message)
                assertEquals(now + 800L, delivery.action.validUntilMs)
                finish(coordinator, delivery, now)
                completed += delivery.action.trackId to now
            }
        }
        return completed
    }

    private fun assertFairTurns(ids: List<String>, completed: List<Pair<String, Long>>) {
        assertTrue("completed=$completed", completed.size >= ids.size * 2)
        assertEquals("first round=$completed", ids.toSet(), completed.take(ids.size).map { it.first }.toSet())
        assertEquals(ids.toSet(), completed.map { it.first }.toSet())
        for ((earlier, later) in completed.zipWithNext()) {
            assertTrue("global cooldown: $earlier -> $later", later.second - earlier.second >= 700L)
        }
        for (id in ids) {
            for ((earlier, later) in completed.filter { it.first == id }.zipWithNext()) {
                assertTrue("track cooldown: $earlier -> $later", later.second - earlier.second >= 2_500L)
            }
        }
        val counts = ids.map { id -> completed.count { it.first == id } }
        assertTrue("counts=$counts", counts.max() - counts.min() <= 1)
    }

    private fun finish(coordinator: CameraTestFeedbackCoordinator, delivery: CameraTestFeedbackCoordinator.Delivery, now: Long) {
        assertTrue(coordinator.isDeliverable(delivery, now))
        assertTrue(coordinator.claim(delivery, now))
        assertFalse(coordinator.claim(delivery, now))
        assertNull(coordinator.next(now))
        assertTrue(coordinator.complete(delivery, now))
        assertFalse(coordinator.complete(delivery, now))
    }

    private fun coordinator() = CameraTestFeedbackCoordinator().apply { start(1) }

    private fun offer(coordinator: CameraTestFeedbackCoordinator, now: Long, outputs: List<TrackedObjectDepth>, unknown: Boolean = false) {
        assertTrue(coordinator.offer(sample(now, outputs, unknown)))
    }

    private fun sample(now: Long, outputs: List<TrackedObjectDepth>, unknown: Boolean = false) = CameraTestFeedbackCoordinator.Sample(
        if (unknown) CameraTestFeedbackCoordinator.Source.UNKNOWN else CameraTestFeedbackCoordinator.Source.PRIMARY,
        1, frameId(now), now, now, "portrait", outputs,
    )

    private fun output(
        id: String,
        now: Long,
        level: MessageLevel = MessageLevel.WARNING,
        box: RectNorm = if (id.startsWith("unknown")) UNKNOWN_BOX else PRIMARY_BOX,
    ) = TrackedObjectDepth(
        frameId = frameId(now), timestampMs = frameId(now) / 1_000_000L, trackId = id,
        className = if (id.startsWith("unknown")) "unnamed-obstacle" else "person",
        detectionConfidence = 0.95f, bboxNorm = box, polygonNorm = emptyList(), maskAreaNorm = box.area,
        centerNorm = box.center, bottomContactNorm = null, source = DepthSource.ARCORE_RAW_DEPTH,
        zDistanceM = 1f, rayDistanceM = 1f, groundDistanceM = 1f, riskDistanceM = 1f,
        validSampleCount = 80, validSampleRatio = 0.9f, depthMedianM = 1f, depthP20M = 1f, depthIqrM = 0.05f,
        trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
        userFacing = UserFacingDepth(null, level, "위험 $id @$now"), trackAgeFrames = 20, trackStableMs = 1_900L,
    )

    private fun frameId(now: Long) = FRAME_BASE + (now - START) * 1_000_000L

    private companion object {
        const val START = 10_000L
        const val FRAME_BASE = 5_000_000_000_000L
        val PRIMARY_BOX = RectNorm(0.1f, 0.3f, 0.2f, 0.4f)
        val UNKNOWN_BOX = RectNorm(0.7f, 0.3f, 0.2f, 0.4f)
    }
}
