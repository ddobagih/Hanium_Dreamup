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

class CameraTestFeedbackCoordinatorTest {
    @Test fun clearingUnknownPreservesClaimedPrimaryAndRemovesUnknownQueue() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-1").copy(riskDistanceM = 2f))))
        val primary = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(primary, NOW))
        coordinator.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        assertTrue(coordinator.isDeliverable(primary, NOW))
        assertTrue(coordinator.complete(primary, NOW + 10L))
        assertNull(coordinator.next(NOW + 711L))
    }

    @Test fun clearingUnknownWithdrawsItsEarlierClaimAndAllowsPrimaryWithoutFalseCooldown() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1"))))
        val unknown = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(unknown, NOW))
        // The failed service may already have supplied a newer empty frame.
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, emptyList()).copy(
            frameId = FRAME + 100_000_000L, capturedAtMs = NOW + 100L, completedAtMs = NOW + 100L))
        coordinator.offer(sample(outputs = listOf(output())))
        coordinator.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        assertFalse(coordinator.isDeliverable(unknown, NOW + 100L))
        assertFalse(coordinator.complete(unknown, NOW + 100L))
        val primary = requireNotNull(coordinator.next(NOW + 100L))
        assertEquals("primary-1", primary.action.trackId)
        assertTrue(coordinator.claim(primary, NOW + 100L))
    }

    @Test fun usesElapsedCaptureDeadlineWhilePreservingUnrelatedFrameClock() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        val delivery = requireNotNull(coordinator.next(NOW))
        assertEquals("primary-1", delivery.action.trackId)
        assertEquals(NOW + 800L, delivery.action.validUntilMs)
        assertTrue(coordinator.claim(delivery, NOW))
        assertFalse(coordinator.isDeliverable(delivery, NOW + 801L))
        // An onDone after the start deadline still completes a speech started while fresh.
        assertTrue(coordinator.complete(delivery, NOW + 2_000L))
    }

    @Test fun rejectsExpiredFutureAndClockMismatchedSources() {
        val coordinator = coordinator()
        assertFalse(coordinator.offer(sample(outputs = listOf(output().copy(timestampMs = NOW)))))
        assertNull(coordinator.next(NOW))
        coordinator.offer(sample(outputs = listOf(output())))
        assertNull(coordinator.next(NOW - 1L))
        assertNull(coordinator.next(NOW + 801L))
        assertTrue(coordinator.diagnostic(NOW + 801L).contains("만료"))
    }

    @Test fun stoppingWithdrawsDeliveryAndOldResultsCannotEnterResumedCamera() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        val old = requireNotNull(coordinator.next(NOW))
        coordinator.stop()
        assertFalse(coordinator.isDeliverable(old, NOW))
        assertFalse(coordinator.claim(old, NOW))
        assertFalse(coordinator.complete(old, NOW))
        assertFalse(coordinator.offer(sample(outputs = listOf(output()))))
        coordinator.start(2)
        assertFalse(coordinator.offer(sample(outputs = listOf(output()))))
        assertFalse(coordinator.complete(old, NOW + 10L))
        assertTrue(coordinator.offer(sample(outputs = listOf(output())).copy(epoch = 2)))
        assertEquals(2, requireNotNull(coordinator.next(NOW)).epoch)
    }

    @Test fun missingDistanceAndAccumulatingTracksStaySilentWithReason() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output().copy(riskDistanceM = null))))
        assertNull(coordinator.next(NOW))
        assertTrue(coordinator.diagnostic(NOW).contains("거리 미확인"))
        coordinator.start(1)
        coordinator.offer(sample(outputs = listOf(output().copy(trackAgeFrames = 2, trackStableMs = 699L))))
        assertNull(coordinator.next(NOW))
        assertTrue(coordinator.diagnostic(NOW).contains("추적 누적"))
    }

    @Test fun noHazardAndUnqualifiedUnknownObjectsStaySilent() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output().copy(userFacing = UserFacingDepth(null, MessageLevel.NONE, null)))))
        assertNull(coordinator.next(NOW))
        assertTrue(coordinator.diagnostic(NOW).contains("조건 미충족"))
        coordinator.start(1)
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-1").copy(walkingObstacleCandidate = false))))
        assertNull(coordinator.next(NOW))
    }

    @Test fun sameRegionKnownAndUnnamedWarningsShareOneQueueEntry() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1"))))
        val named = requireNotNull(coordinator.next(NOW))
        assertEquals("primary-1", named.action.trackId)
        assertTrue(coordinator.claim(named, NOW))
        assertTrue(coordinator.complete(named, NOW + 10L))
        // The overlapping unknown must not immediately bypass the named track's cooldown.
        assertNull(coordinator.next(NOW + 711L))
    }

    @Test fun differentDepthAndSeparateRegionsRemainQueuedAfterHigherPriorityWarning() {
        listOf(
            output("unknown-1").copy(riskDistanceM = 2.0f),
            output("unknown-1").copy(bboxNorm = RectNorm(0.75f, 0.3f, 0.2f, 0.4f)),
        ).forEach { unknown ->
            val coordinator = coordinator()
            coordinator.offer(sample(outputs = listOf(output())))
            coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(unknown)))
            val first = requireNotNull(coordinator.next(NOW))
            assertTrue(coordinator.claim(first, NOW))
            assertTrue(coordinator.complete(first, NOW + 10L))
            assertEquals("unknown-1", requireNotNull(coordinator.next(NOW + 711L)).action.trackId)
        }
    }

    @Test fun sameSourceTracksRemainIndependentEvenWhenBoxesOverlap() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output(), output("primary-2"))))
        val first = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(first, NOW))
        assertTrue(coordinator.complete(first, NOW + 10L))
        assertEquals("primary-2", requireNotNull(coordinator.next(NOW + 711L)).action.trackId)
    }

    @Test fun unnamedStopPrecedesNamedWarningAndUnstableNamedDoesNotHideStableUnknown() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output().copy(
            userFacing = UserFacingDepth(null, MessageLevel.WARNING, "전방 사람. 멈출 준비를 하세요.")))))
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1"))))
        assertEquals("unknown-1", requireNotNull(coordinator.next(NOW)).action.trackId)
        coordinator.start(1)
        coordinator.offer(sample(outputs = listOf(output().copy(trackAgeFrames = 1))))
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1"))))
        assertEquals("unknown-1", requireNotNull(coordinator.next(NOW)).action.trackId)
    }

    @Test fun newerEmptySourceWithdrawsPendingSpeechBeforeOnStart() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        val delivery = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(delivery, NOW))
        coordinator.offer(sample(outputs = emptyList()).copy(frameId = FRAME + 100_000_000L,
            capturedAtMs = NOW + 100L, completedAtMs = NOW + 100L))
        assertFalse(coordinator.isDeliverable(delivery, NOW + 100L))
    }

    @Test fun geometryChangesRejectLateOldOrientationAndOutOfOrderResults() {
        val coordinator = coordinator()
        coordinator.offer(sample(outputs = listOf(output())))
        val delivery = requireNotNull(coordinator.next(NOW))
        val newerFrame = FRAME + 100_000_000L
        assertTrue(coordinator.offer(sample(outputs = listOf(output().copy(frameId = newerFrame,
            timestampMs = newerFrame / 1_000_000L))).copy(frameId = newerFrame, geometryId = "landscape",
            capturedAtMs = NOW + 100L, completedAtMs = NOW + 100L)))
        assertFalse(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1")))))
        assertFalse(coordinator.offer(sample(outputs = listOf(output())).copy(geometryId = "landscape")))
        assertFalse(coordinator.isDeliverable(delivery, NOW + 100L))
    }

    @Test fun retainingUnknownPreservesItsClaimAndOriginalStartDeadline() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1")))))
        val delivery = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(delivery, NOW))
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), NOW + 500L))
        assertTrue(coordinator.isDeliverable(delivery, NOW + 500L))
        assertNull(coordinator.next(NOW + 500L))
        assertEquals(NOW + 800L, delivery.action.validUntilMs)
        assertTrue(coordinator.complete(delivery, NOW + 700L))
    }

    @Test fun repeatedUnknownRetentionDoesNotRefreshFrameOrExtendSourceFreshness() {
        val coordinator = coordinator()
        val original = sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1")))
        assertTrue(coordinator.offer(original))
        for (time in NOW + 100L..NOW + 800L step 100L) {
            assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), time))
        }
        assertFalse(coordinator.offer(original)) // Retention did not create a new capture slot.
        val delivery = requireNotNull(coordinator.next(NOW + 800L))
        assertEquals(NOW + 800L, delivery.action.validUntilMs)
        assertTrue(coordinator.claim(delivery, NOW + 800L))
        assertFalse(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), NOW + 801L))
        assertFalse(coordinator.isDeliverable(delivery, NOW + 801L))
        assertTrue(coordinator.complete(delivery, NOW + 900L))
        assertNull(coordinator.next(NOW + 1_000L))
    }

    @Test fun partialUnknownRegionLossWithdrawsUnclaimedStartButKeepsRemainingRegion() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-lost"), output("unknown-kept")))))
        val lost = requireNotNull(coordinator.next(NOW))
        assertEquals("unknown-lost", lost.action.trackId)
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-kept"), NOW + 100L))
        assertFalse(coordinator.isDeliverable(lost, NOW + 100L))
        assertFalse(coordinator.claim(lost, NOW + 100L))
        val kept = requireNotNull(coordinator.next(NOW + 100L))
        assertFalse(coordinator.complete(lost, NOW + 500L))
        assertEquals("unknown-kept", kept.action.trackId)
        assertEquals(NOW + 800L, kept.action.validUntilMs)
        assertTrue(coordinator.claim(kept, NOW + 100L))
    }

    @Test fun regionRetentionPreservesEarlierAcceptedDeliveryUntilItsTerminalCallback() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-old")))))
        val old = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(old, NOW))
        val newFrame = FRAME + 100_000_000L
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-current").copy(frameId = newFrame, timestampMs = newFrame / 1_000_000L)))
            .copy(frameId = newFrame, capturedAtMs = NOW + 100L, completedAtMs = NOW + 100L)))
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-current"), NOW + 200L))
        assertFalse(coordinator.isDeliverable(old, NOW + 200L))
        assertNull(coordinator.next(NOW + 200L))
        assertTrue(coordinator.complete(old, NOW + 200L))
        val current = requireNotNull(coordinator.next(NOW + 900L))
        assertEquals("unknown-current", current.action.trackId)
        assertEquals(NOW + 900L, current.action.validUntilMs)
    }

    @Test fun emptyUnsupportedOrExpiredRetentionWithdrawsStartsButPreservesAcceptedCompletion() {
        for ((retainedIds, time) in listOf(emptySet<String>() to NOW + 100L,
            setOf("unknown-missing") to NOW + 100L, setOf("unknown-1") to NOW + 801L)) {
            val coordinator = coordinator()
            assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1")))))
            val unknown = requireNotNull(coordinator.next(NOW))
            assertTrue(coordinator.claim(unknown, NOW))
            val currentFrame = FRAME + (time - NOW) * 1_000_000L
            assertTrue(coordinator.offer(sample(outputs = listOf(output().copy(frameId = currentFrame,
                timestampMs = currentFrame / 1_000_000L))).copy(frameId = currentFrame, capturedAtMs = time, completedAtMs = time)))
            assertFalse(coordinator.retainUnknownRegions(1, "portrait", retainedIds, time))
            assertFalse(coordinator.isDeliverable(unknown, time))
            assertTrue(coordinator.complete(unknown, time))
            assertEquals("primary-1", requireNotNull(coordinator.next(time + 700L)).action.trackId)
        }
    }

    @Test fun unknownRetentionAndLossLeaveClaimedPrimaryUntouched() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(outputs = listOf(output()))))
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-1").copy(riskDistanceM = 2f)))))
        val primary = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(primary, NOW))
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), NOW + 100L))
        assertTrue(coordinator.isDeliverable(primary, NOW + 100L))
        assertFalse(coordinator.retainUnknownRegions(1, "portrait", emptySet(), NOW + 200L))
        assertTrue(coordinator.isDeliverable(primary, NOW + 200L))
        assertTrue(coordinator.complete(primary, NOW + 300L))
    }

    @Test fun oldEpochAndGeometryRetentionCannotClearTheLatestUnknownClaim() {
        val coordinator = coordinator()
        coordinator.start(2)
        val current = sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-current")))
            .copy(epoch = 2, geometryId = "landscape")
        assertTrue(coordinator.offer(current))
        val delivery = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(delivery, NOW))
        assertFalse(coordinator.retainUnknownRegions(1, "landscape", emptySet(), NOW + 100L))
        assertFalse(coordinator.retainUnknownRegions(2, "portrait", emptySet(), NOW + 100L))
        assertTrue(coordinator.isDeliverable(delivery, NOW + 100L))
        assertTrue(coordinator.complete(delivery, NOW + 200L))
    }

    @Test fun freshIndependentSampleReplacesRetainedGeometryAndReceivesItsOwnDeadline() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-1")))))
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), NOW + 400L))
        val nextFrame = FRAME + 500_000_000L
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN,
            listOf(output("unknown-1").copy(frameId = nextFrame, timestampMs = nextFrame / 1_000_000L)))
            .copy(frameId = nextFrame, capturedAtMs = NOW + 500L, completedAtMs = NOW + 500L)))
        assertTrue(coordinator.retainUnknownRegions(1, "portrait", setOf("unknown-1"), NOW + 900L))
        val delivery = requireNotNull(coordinator.next(NOW + 900L))
        assertEquals(NOW + 1_300L, delivery.action.validUntilMs)
    }

    @Test fun explicitUnknownClearStillCancelsClaimsFromBeforeARegionWasRetained() {
        val coordinator = coordinator()
        assertTrue(coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-old")))))
        val old = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(old, NOW))
        assertFalse(coordinator.retainUnknownRegions(1, "portrait", emptySet(), NOW + 100L))
        coordinator.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        assertFalse(coordinator.complete(old, NOW + 200L))
        assertTrue(coordinator.offer(sample(outputs = listOf(output()))))
        assertEquals("primary-1", requireNotNull(coordinator.next(NOW + 200L)).action.trackId)
    }

    @Test fun mixedFreshWarningKeepsRawStopAndItsOriginalDeadline() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        val stop = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(stop, NOW))
        assertTrue(coordinator.offer(mixedSample(300L, listOf(output("unknown-full").copy(
            source = DepthSource.ARCORE_FULL_DEPTH, riskDistanceM = 2f,
            userFacing = UserFacingDepth(null, MessageLevel.WARNING, "전방 물체. 주의하세요.")))),
            setOf("unknown-raw"), NOW + 300L))
        assertTrue(coordinator.isDeliverable(stop, NOW + 300L))
        assertEquals(NOW + 800L, stop.action.validUntilMs)
        assertTrue(coordinator.isDeliverable(stop, NOW + 800L))
        assertFalse(coordinator.isDeliverable(stop, NOW + 801L))
        assertTrue(coordinator.complete(stop, NOW + 900L))
    }

    @Test fun mixedFramesRetainOnlySupportedRegionsWithoutExtendingAnyLease() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        coordinator.offer(mixedSample(300L, listOf(output("unknown-full").copy(
            userFacing = UserFacingDepth(null, MessageLevel.WARNING, "전방 물체. 주의하세요.")))),
            setOf("unknown-raw"), NOW + 300L)
        coordinator.offer(mixedSample(500L, emptyList()), setOf("unknown-raw", "unknown-full"), NOW + 500L)
        val raw = requireNotNull(coordinator.next(NOW + 700L))
        assertEquals("unknown-raw", raw.action.trackId)
        assertEquals(NOW + 800L, raw.action.validUntilMs)
        coordinator.reject(raw)
        val full = requireNotNull(coordinator.next(NOW + 801L))
        assertEquals("unknown-full", full.action.trackId)
        assertEquals(NOW + 1100L, full.action.validUntilMs)
        coordinator.reject(full)
        assertNull(coordinator.next(NOW + 1101L))
    }

    @Test fun mixedFreshSameIdReplacesOldRegionInsteadOfDuplicatingIt() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        coordinator.offer(mixedSample(300L, listOf(output("unknown-raw"))), setOf("unknown-raw"), NOW + 300L)
        val current = requireNotNull(coordinator.next(NOW + 801L))
        assertEquals("unknown-raw", current.action.trackId)
        assertEquals(NOW + 1100L, current.action.validUntilMs)
        assertTrue(coordinator.claim(current, NOW + 801L))
        assertTrue(coordinator.complete(current, NOW + 900L))
        assertNull(coordinator.next(NOW + 950L))
    }

    @Test fun unsupportedMixedRegionDisappearsButAcceptedCompletionSurvives() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        val old = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(old, NOW))
        coordinator.offer(mixedSample(300L, listOf(output("unknown-full"))), setOf("unknown-raw"), NOW + 300L)
        coordinator.offer(mixedSample(400L, emptyList()), setOf("unknown-full"), NOW + 400L)
        assertFalse(coordinator.isDeliverable(old, NOW + 400L))
        assertTrue(coordinator.complete(old, NOW + 500L))
    }

    @Test fun lateMixedFrameCannotRemoveCurrentRetainedClaim() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        val old = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(old, NOW))
        coordinator.offer(mixedSample(300L, emptyList()), setOf("unknown-raw"), NOW + 300L)
        assertFalse(coordinator.offer(mixedSample(200L, emptyList()), emptySet(), NOW + 400L))
        assertFalse(coordinator.offer(mixedSample(400L, emptyList()).copy(epoch = 2), emptySet(), NOW + 400L))
        assertTrue(coordinator.isDeliverable(old, NOW + 400L))
        coordinator.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        assertFalse(coordinator.complete(old, NOW + 500L))
    }

    @Test fun geometryChangeClearsAllMixedLeases() {
        val coordinator = coordinator()
        coordinator.offer(sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, listOf(output("unknown-raw"))))
        val old = requireNotNull(coordinator.next(NOW))
        assertTrue(coordinator.claim(old, NOW))
        coordinator.offer(mixedSample(300L, listOf(output("unknown-full"))), setOf("unknown-raw"), NOW + 300L)
        coordinator.offer(mixedSample(400L, emptyList()).copy(geometryId = "rotated"),
            setOf("unknown-raw", "unknown-full"), NOW + 400L)
        assertFalse(coordinator.isDeliverable(old, NOW + 400L))
        assertFalse(coordinator.complete(old, NOW + 500L))
        assertNull(coordinator.next(NOW + 500L))
    }

    private fun mixedSample(deltaMs: Long, outputs: List<TrackedObjectDepth>): CameraTestFeedbackCoordinator.Sample {
        val frame = FRAME + deltaMs * 1_000_000L
        return sample(CameraTestFeedbackCoordinator.Source.UNKNOWN, outputs.map {
            it.copy(frameId = frame, timestampMs = frame / 1_000_000L)
        }).copy(frameId = frame, capturedAtMs = NOW + deltaMs, completedAtMs = NOW + deltaMs)
    }

    private fun coordinator() = CameraTestFeedbackCoordinator().apply { start(1) }

    private fun sample(source: CameraTestFeedbackCoordinator.Source = CameraTestFeedbackCoordinator.Source.PRIMARY,
                       outputs: List<TrackedObjectDepth>) = CameraTestFeedbackCoordinator.Sample(
        source, 1, FRAME, NOW, NOW, "portrait", outputs)

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
