package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

class CameraTestFeedbackCrossSourceTest {
    private val primary = CameraTestFeedbackCoordinator.Source.PRIMARY
    private val unknown = CameraTestFeedbackCoordinator.Source.UNKNOWN

    @Test fun acceptedUnknownStopKeepsOriginalStartAndCompletionWhenPrimaryCatchesUp() {
        val c = anchored()
        val delivery = claimUnknown(c)
        offer(c, primary, 200L)
        assertNull(c.next(NOW + 200L))
        assertTrue(c.isDeliverable(delivery, NOW + 200L))
        assertEquals(NOW + 800L, delivery.action.validUntilMs)
        assertTrue(c.complete(delivery, NOW + 300L))
        assertFalse(c.complete(delivery, NOW + 301L))
    }

    @Test fun completedUnknownHistorySurvivesOriginalSourceExpiryWithoutImmediateRepeat() {
        val c = anchored()
        val delivery = claimUnknown(c)
        offer(c, primary, 200L)
        assertNull(c.next(NOW + 200L))
        assertTrue(c.complete(delivery, NOW + 300L))
        for (delta in 300L..2700L step 100L) {
            offer(c, primary, delta)
            assertNull("Repeat at $delta", c.next(NOW + delta))
        }
        offer(c, primary, 2800L)
        val repeated = requireNotNull(c.next(NOW + 2800L))
        assertEquals("primary-1", repeated.action.trackId)
        assertTrue(c.claim(repeated, NOW + 2800L))
    }

    @Test fun completionCarriesHistoryBeforeNextTickAfterSourceExpiry() {
        val c = anchored()
        val delivery = claimUnknown(c)
        offer(c, primary, 200L)
        assertTrue(c.complete(delivery, NOW + 300L))
        offer(c, primary, 1000L)
        assertNull(c.next(NOW + 1000L))
    }

    @Test fun higherPrimaryStopPreemptsClaimedUnknownWarningAndRejectsOldCompletion() {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, primary, 0L, level = MessageLevel.WARNING)
        offer(c, unknown, 0L, level = MessageLevel.WARNING)
        c.clear(primary)
        val warning = requireNotNull(c.next(NOW))
        assertTrue(c.claim(warning, NOW))
        // A higher warning must preempt even when the cleared context cannot prove a region match.
        offer(c, primary, 0L)
        val stop = requireNotNull(c.next(NOW + 100L))
        assertEquals("primary-1", stop.action.trackId)
        assertFalse(c.isDeliverable(warning, NOW + 100L))
        assertFalse(c.complete(warning, NOW + 100L))
        assertTrue(c.claim(stop, NOW + 100L))
    }

    @Test fun reservationDoesNotBecomeCompletedHistoryDuringSourceHandoff() {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, unknown, 0L)
        val reserved = requireNotNull(c.next(NOW))
        offer(c, primary, 0L)
        val replacement = requireNotNull(c.next(NOW + 100L))
        assertFalse(c.claim(reserved, NOW + 100L))
        assertFalse(c.complete(reserved, NOW + 100L))
        assertTrue(c.claim(replacement, NOW + 100L))
    }

    @Test fun claimedSourceIsNotCancelledOrRenewedAfterOriginalDeadline() {
        val c = anchored()
        val delivery = claimUnknown(c)
        for (delta in 200L..900L step 100L) offer(c, primary, delta)
        assertFalse(c.isDeliverable(delivery, NOW + 801L))
        assertNull(c.next(NOW + 900L))
        assertTrue(c.complete(delivery, NOW + 1000L))
    }

    @Test fun completedHistoryCanFollowAProvenPrimaryAfterUnknownStartDeadlineExpires() {
        val c = anchored()
        val delivery = claimUnknown(c)
        for (delta in 200L..1400L step 100L) {
            offer(c, primary, delta)
            assertNull(c.next(NOW + delta))
        }
        assertEquals(NOW + 800L, delivery.action.validUntilMs)
        assertFalse(c.isDeliverable(delivery, NOW + 1400L))
        assertTrue(c.complete(delivery, NOW + 1500L))
        for (delta in 1500L..3900L step 100L) {
            offer(c, primary, delta)
            assertNull(c.next(NOW + delta))
        }
        offer(c, primary, 4000L)
        assertEquals("primary-1", requireNotNull(c.next(NOW + 4000L)).action.trackId)
    }

    @Test fun lateCompletionDoesNotCarryHistoryAcrossGapLossAmbiguityOrDistanceChange() {
        for (reason in listOf("gap", "lost", "ambiguous", "distance", "completion-gap")) {
            val c = anchored()
            val delivery = claimUnknown(c)
            for (delta in 200L..800L step 100L) {
                offer(c, primary, delta)
                assertNull(c.next(NOW + delta))
            }
            val changed = when (reason) {
                "lost" -> emptyList()
                "ambiguous" -> listOf(output(), output("primary-2"))
                "distance" -> listOf(output().copy(riskDistanceM = 1.4f))
                else -> listOf(output())
            }
            if (reason != "completion-gap") offer(c, primary, if (reason == "gap") 921L else 900L, outputs = changed)
            assertTrue(reason, c.complete(delivery, NOW + 1000L))
            offer(c, primary, 1700L)
            assertEquals(reason, "primary-1", requireNotNull(c.next(NOW + 1700L)).action.trackId)
        }
    }

    @Test fun primaryClearCannotReviveAnOldCaptureHandoffWhenTheSameIdReturns() {
        val c = anchored()
        val delivery = claimUnknown(c)
        c.clear(primary)
        // A producer replay after clear cannot create a new capture-aligned history link.
        offer(c, primary, 0L, completedDelta = 100L)
        for (delta in 100L..1400L step 100L) {
            offer(c, primary, delta)
            assertNull(c.next(NOW + delta))
        }
        assertTrue(c.complete(delivery, NOW + 1500L))
        offer(c, primary, 2200L)
        assertEquals("primary-1", requireNotNull(c.next(NOW + 2200L)).action.trackId)
    }

    @Test fun separateRegionAndDifferentDepthKeepIndependentDelivery() {
        listOf(output("primary-1").copy(riskDistanceM = 2f),
            output("primary-1").copy(bboxNorm = RectNorm(0.75f, 0.3f, 0.2f, 0.4f))).forEach { different ->
            val c = CameraTestFeedbackCoordinator().apply { start(1) }
            offer(c, unknown, 0L)
            val delivery = requireNotNull(c.next(NOW))
            assertTrue(c.claim(delivery, NOW))
            offer(c, primary, 0L, outputs = listOf(different))
            assertTrue(c.isDeliverable(delivery, NOW + 100L))
            assertTrue(c.complete(delivery, NOW + 100L))
            assertEquals("primary-1", requireNotNull(c.next(NOW + 800L)).action.trackId)
        }
    }

    @Test fun ambiguousPrimaryPeersAreNeverConsumedByOneUnknown() {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, unknown, 0L)
        val delivery = requireNotNull(c.next(NOW))
        assertTrue(c.claim(delivery, NOW))
        offer(c, primary, 0L, outputs = listOf(output(), output("primary-2")))
        assertTrue(c.isDeliverable(delivery, NOW + 100L))
        assertTrue(c.complete(delivery, NOW + 100L))
        assertEquals("primary-1", requireNotNull(c.next(NOW + 800L)).action.trackId)
    }

    @Test fun ambiguousUnknownPeersAreNotConsumedByOnePrimary() {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, primary, 0L)
        offer(c, unknown, 0L, outputs = listOf(output("unknown-1"), output("unknown-2")))
        val first = requireNotNull(c.next(NOW))
        assertTrue(c.claim(first, NOW))
        assertTrue(c.complete(first, NOW + 100L))
        assertTrue(requireNotNull(c.next(NOW + 800L)).action.trackId.startsWith("unknown"))
    }

    @Test fun missingCaptureAnchorDoesNotInventCompletedContinuity() {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, unknown, 0L)
        val delivery = requireNotNull(c.next(NOW))
        assertTrue(c.claim(delivery, NOW))
        offer(c, primary, 100L)
        assertTrue(c.isDeliverable(delivery, NOW + 100L))
        assertTrue(c.complete(delivery, NOW + 100L))
        offer(c, primary, 800L)
        assertEquals("primary-1", requireNotNull(c.next(NOW + 800L)).action.trackId)
    }

    @Test fun missingFrameOrExcessiveGapBreaksContinuousHistory() {
        listOf(true, false).forEach { emptyFrame ->
            val c = anchored()
            val delivery = claimUnknown(c)
            if (emptyFrame) offer(c, primary, 200L, outputs = emptyList())
            offer(c, primary, if (emptyFrame) 300L else 221L)
            assertTrue(c.complete(delivery, NOW + 400L))
            offer(c, primary, 1100L)
            assertEquals("primary-1", requireNotNull(c.next(NOW + 1100L)).action.trackId)
        }
    }

    @Test fun classOrMetricDiscontinuityDoesNotCopyCompletedCooldown() {
        listOf(output().copy(className = "bicycle"), output().copy(riskDistanceM = 1.4f)).forEach { changed ->
            val c = anchored()
            val delivery = claimUnknown(c)
            offer(c, primary, 200L, outputs = listOf(changed))
            assertTrue(c.complete(delivery, NOW + 300L))
            offer(c, primary, 1000L, outputs = listOf(changed))
            assertEquals("primary-1", requireNotNull(c.next(NOW + 1000L)).action.trackId)
        }
    }

    @Test fun explicitSourceClearAndSessionGeometryChangeRejectOldClaims() {
        val c = anchored()
        val delivery = claimUnknown(c)
        c.clear(unknown)
        assertFalse(c.complete(delivery, NOW + 200L))
        offer(c, primary, 200L)
        assertNotNull(c.next(NOW + 200L))
        c.start(2)
        assertFalse(c.isDeliverable(delivery, NOW + 200L))
        assertFalse(c.complete(delivery, NOW + 200L))
    }

    private fun anchored(): CameraTestFeedbackCoordinator {
        val c = CameraTestFeedbackCoordinator().apply { start(1) }
        offer(c, primary, 0L, level = MessageLevel.WARNING)
        val initial = requireNotNull(c.next(NOW))
        assertTrue(c.claim(initial, NOW))
        assertTrue(c.complete(initial, NOW))
        offer(c, primary, 100L, level = MessageLevel.WARNING)
        return c
    }

    private fun claimUnknown(c: CameraTestFeedbackCoordinator): CameraTestFeedbackCoordinator.Delivery {
        offer(c, unknown, 0L, completedDelta = 100L)
        return requireNotNull(c.next(NOW + 100L)).also {
            assertEquals("unknown-1", it.action.trackId)
            assertTrue(c.claim(it, NOW + 100L))
        }
    }

    private fun offer(c: CameraTestFeedbackCoordinator, source: CameraTestFeedbackCoordinator.Source,
                      delta: Long, level: MessageLevel = MessageLevel.STOP,
                      outputs: List<TrackedObjectDepth> = listOf(output(if (source == primary) "primary-1" else "unknown-1")),
                      completedDelta: Long = delta) {
        val frame = FRAME + delta * 1_000_000L
        assertTrue(c.offer(CameraTestFeedbackCoordinator.Sample(source, 1, frame, NOW + delta,
            NOW + completedDelta, "portrait", outputs.map { it.copy(frameId = frame, timestampMs = frame / 1_000_000L,
                userFacing = it.userFacing.copy(messageLevel = level)) })))
    }

    private fun output(trackId: String = "primary-1"): TrackedObjectDepth {
        val bbox = RectNorm(0.2f, 0.3f, 0.4f, 0.4f)
        return TrackedObjectDepth(
            frameId = FRAME, timestampMs = FRAME / 1_000_000L, trackId = trackId,
            className = if (trackId.startsWith("unknown")) "unnamed-obstacle" else "person",
            detectionConfidence = 0.95f, bboxNorm = bbox, polygonNorm = emptyList(), maskAreaNorm = bbox.area,
            centerNorm = bbox.center, bottomContactNorm = null, source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1f, rayDistanceM = 1f, groundDistanceM = 1f, riskDistanceM = 1f,
            validSampleCount = 80, validSampleRatio = 0.9f, depthMedianM = 1f, depthP20M = 1f, depthIqrM = 0.05f,
            trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.STOP, "전방 물체. 멈추세요. 주변을 확인하세요."),
            trackAgeFrames = 4, trackStableMs = 900L,
        )
    }

    private companion object {
        const val NOW = 10_000L
        const val FRAME = 5_000_000_000_000L
    }
}
