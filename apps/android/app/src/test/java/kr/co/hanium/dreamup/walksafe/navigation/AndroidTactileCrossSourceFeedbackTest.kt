package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackBatch
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackPolicyConfig
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTactileCrossSourceFeedbackTest {
    private val policy = WalkSafeFeedbackPolicy()
    private val coordinator = coordinator(policy)

    @Test
    fun identicalCurrentCaptureProducesOneWarningAcrossSources() {
        val delivered = mutableListOf<String>()
        for (now in START..START + 700L step 100L) {
            val dispatch = dispatch(now = now, primary = listOf(output(PRIMARY, now)), unknown = listOf(output(UNKNOWN, now)))
            assertEquals(setOf(PRIMARY), dispatch.activeRiskTrackIds)
            assertEquals(setOf(output(PRIMARY, now).let { "$PRIMARY|WARNING|${it.userFacing.message}" }),
                dispatch.activeFeedbackDeliveryKeys)
            dispatch.action?.let {
                assertTrue(policy.claimFeedbackDelivery(it.trackId, now, now))
                assertTrue(policy.confirmFeedbackDelivery(it.trackId, now, now))
                delivered += it.trackId
            }
        }
        assertEquals(listOf(PRIMARY), delivered)
    }

    @Test
    fun equalSeverityPrefersPrimaryEvenWithLowerConfidence() {
        val primary = output(PRIMARY).copy(confidence = confidence(.75f))
        val result = dispatch(primary = listOf(primary))
        assertEquals(PRIMARY, result.action?.trackId)
        assertEquals(setOf(PRIMARY), result.activeRiskTrackIds)
    }

    @Test
    fun unknownStopOutranksPrimaryWarningAndKeepsOriginalSourceDeadline() {
        val result = dispatch(now = START + 300L, unknown = listOf(output(UNKNOWN, level = MessageLevel.STOP)))
        assertEquals(UNKNOWN, result.action?.trackId)
        assertEquals(MessageLevel.STOP, result.action?.level)
        assertEquals(START + 800L, result.action?.validUntilMs)
        assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun primaryStopOutranksUnknownWarning() {
        val result = dispatch(primary = listOf(output(PRIMARY, level = MessageLevel.STOP)))
        assertEquals(PRIMARY, result.action?.trackId)
        assertEquals(setOf(PRIMARY), result.activeRiskTrackIds)
    }

    @Test
    fun sameBoxAtDifferentDistanceRetainsBothHazards() {
        assertEquals(setOf(PRIMARY, UNKNOWN), dispatch(unknown = listOf(output(UNKNOWN).copy(riskDistanceM = 1.31f))).activeRiskTrackIds)
    }

    @Test
    fun separateCurrentBoxesRetainBothHazards() {
        assertEquals(setOf(PRIMARY, UNKNOWN), dispatch(unknown = listOf(output(UNKNOWN).copy(bboxNorm = RectNorm(.65f, .3f, .2f, .4f)))).activeRiskTrackIds)
    }

    @Test
    fun nearbyCaptureIsNotRelabelledAsCurrentGeometry() {
        assertEquals(setOf(PRIMARY, UNKNOWN), dispatch(now = START + 1L,
            primary = listOf(output(PRIMARY, START + 1L))).activeRiskTrackIds)
    }

    @Test
    fun batchCaptureMismatchCannotSuppressAnOtherwiseEligiblePrimary() {
        val result = coordinator.dispatchFeedback(frame(listOf(output(PRIMARY))), false, true, START,
            independentFreshOutputs = listOf(batch(listOf(output(UNKNOWN, level = MessageLevel.STOP)), frameId = FRAME_ID - 1L)))
        assertEquals(setOf(PRIMARY, UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun outputTimestampMismatchCannotProveSameCapture() {
        val result = dispatch(unknown = listOf(output(UNKNOWN).copy(timestampMs = FRAME_ID / 1_000_000L + 1L)))
        assertEquals(setOf(PRIMARY, UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun distinctSameSourceTracksArePreservedEvenWithIdenticalBoxes() {
        val result = dispatch(primary = listOf(output(PRIMARY), output("primary-2")), unknown = emptyList())
        assertEquals(setOf(PRIMARY, "primary-2"), result.activeRiskTrackIds)
    }

    @Test
    fun ambiguousTwoToOneOverlapDoesNotEraseAnyHazard() {
        val result = dispatch(primary = listOf(output(PRIMARY), output("primary-2")))
        assertEquals(setOf(PRIMARY, "primary-2", UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun ambiguousOneToTwoOverlapDoesNotEraseAnyHazard() {
        val result = dispatch(unknown = listOf(output(UNKNOWN), output("unknown:1:v")))
        assertEquals(setOf(PRIMARY, UNKNOWN, "unknown:1:v"), result.activeRiskTrackIds)
    }

    @Test
    fun stalePrimaryCannotSuppressFreshUnknown() {
        val result = dispatch(stale = true)
        assertEquals(UNKNOWN, result.action?.trackId)
        assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun unstableOrLowConfidencePrimaryCannotSuppressFreshUnknown() {
        listOf(output(PRIMARY).copy(trackAgeFrames = 2), output(PRIMARY).copy(trackStableMs = 699L),
            output(PRIMARY).copy(confidence = confidence(.54f))).forEach { primary ->
            policy.resetForNewWalk()
            val result = dispatch(primary = listOf(primary))
            assertEquals(UNKNOWN, result.action?.trackId)
            assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
        }
    }

    @Test
    fun invalidMetricOrBoxEvidenceCannotSuppressAnotherCandidate() {
        listOf(output(PRIMARY).copy(riskDistanceM = null), output(PRIMARY).copy(riskDistanceM = Float.NaN),
            output(PRIMARY).copy(confidence = confidence().copy(hardGate = 0f)),
            output(PRIMARY).copy(confidence = confidence().copy(freshnessQuality = 0f)),
            output(PRIMARY).copy(bboxNorm = RectNorm(-.01f, .3f, .2f, .4f)),
            output(PRIMARY).copy(bboxNorm = RectNorm(.1f, .3f, Float.NaN, .4f))).forEach { primary ->
            policy.resetForNewWalk()
            assertTrue(UNKNOWN in dispatch(primary = listOf(primary)).activeRiskTrackIds)
        }
    }

    @Test
    fun expiredOrNotCompletedUnknownCannotSuppressPrimaryStop() {
        for (unknownBatch in listOf(batch(listOf(output(UNKNOWN, level = MessageLevel.STOP)), capturedAt = START - 801L),
            batch(listOf(output(UNKNOWN, level = MessageLevel.STOP)), completedAt = START + 1L))) {
            policy.resetForNewWalk()
            val result = coordinator.dispatchFeedback(frame(listOf(output(PRIMARY))), false, true, START,
                independentFreshOutputs = listOf(unknownBatch))
            assertEquals(PRIMARY, result.action?.trackId)
            assertEquals(setOf(PRIMARY), result.activeRiskTrackIds)
        }
    }

    @Test
    fun coalescingUsesTheActualFeedbackPolicyEligibilityConfiguration() {
        val customPolicy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(minStableFrames = 25))
        val result = coordinator(customPolicy).dispatchFeedback(frame(listOf(output(PRIMARY))), false, true, START,
            independentFreshOutputs = listOf(batch(listOf(output(UNKNOWN).copy(trackAgeFrames = 30)))))
        assertEquals(UNKNOWN, result.action?.trackId)
        assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun suppressedReservationCannotBeClaimedOrCompleteLate() {
        val old = requireNotNull(dispatch(primary = emptyList()).action)
        val replacement = dispatch(now = START + 100L)
        assertEquals(PRIMARY, replacement.action?.trackId)
        assertFalse(policy.claimFeedbackDelivery(old.trackId, START, START + 100L))
        assertFalse(policy.confirmFeedbackDelivery(old.trackId, START, START + 300L))
        assertFalse(old.deliveryKey in replacement.activeFeedbackDeliveryKeys)
    }

    @Test
    fun lowerClaimCannotConsumeCooldownAfterHigherRiskReplacement() {
        val old = requireNotNull(dispatch(primary = emptyList()).action)
        assertTrue(policy.claimFeedbackDelivery(old.trackId, START, START))
        val replacement = dispatch(now = START + 100L, primary = listOf(output(PRIMARY, level = MessageLevel.STOP)))
        assertEquals(PRIMARY, replacement.action?.trackId)
        assertFalse(policy.confirmFeedbackDelivery(old.trackId, START, START + 300L))
        assertTrue(policy.claimFeedbackDelivery(PRIMARY, START + 100L, START + 100L))
        assertTrue(policy.confirmFeedbackDelivery(PRIMARY, START + 100L, START + 300L))
    }

    @Test
    fun unknownStopCancelsClaimedLowerPrimaryAndCanStartImmediately() {
        val old = requireNotNull(dispatch(unknown = emptyList()).action)
        assertTrue(policy.claimFeedbackDelivery(old.trackId, START, START))
        val stop = dispatch(now = START + 100L, unknown = listOf(output(UNKNOWN, level = MessageLevel.STOP)))
        assertEquals(UNKNOWN, stop.action?.trackId)
        assertEquals(MessageLevel.STOP, stop.action?.level)
        assertFalse(policy.confirmFeedbackDelivery(old.trackId, START, START + 200L))
        assertTrue(policy.claimFeedbackDelivery(UNKNOWN, START + 100L, START + 100L))
    }

    @Test
    fun completedPrimaryWarningDoesNotDelayUnknownStopEscalation() {
        val old = requireNotNull(dispatch().action)
        assertTrue(policy.claimFeedbackDelivery(old.trackId, START, START))
        assertTrue(policy.confirmFeedbackDelivery(old.trackId, START, START))
        val stop = dispatch(now = START + 100L, unknown = listOf(output(UNKNOWN, level = MessageLevel.STOP)))
        assertEquals(UNKNOWN, stop.action?.trackId)
        assertEquals(MessageLevel.STOP, stop.action?.level)
    }

    @Test
    fun sourceRemovalAndNewEpochDoNotRetainRegionSuppression() {
        assertEquals(PRIMARY, dispatch().action?.trackId)
        policy.resetForNewWalk() // Main owns the walk lifecycle and validates each source epoch.
        val result = coordinator.dispatchFeedback(frame(emptyList()), false, true, START + 100L,
            independentFreshOutputs = listOf(batch(listOf(output(UNKNOWN)), epoch = WalkRuntimeEpoch("next-walk", 1L))))
        assertEquals(UNKNOWN, result.action?.trackId)
        assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun deviceGateStillBlocksBothSourcesAfterCoalescing() {
        val result = coordinator.dispatchFeedback(frame(listOf(output(PRIMARY))), false, false, START,
            independentFreshOutputs = listOf(batch(listOf(output(UNKNOWN)))))
        assertNull(result.action)
        assertTrue(result.activeRiskTrackIds.isEmpty())
        assertTrue(result.activeFeedbackDeliveryKeys.isEmpty())
    }

    @Test
    fun expiredPrimaryCannotSuppressFreshIndependentRegion() {
        val expired = output(PRIMARY).copy(sourceValidUntilElapsedRealtimeMs = START - 1L)
        val result = dispatch(primary = listOf(expired))
        assertEquals(UNKNOWN, result.action?.trackId)
        assertEquals(setOf(UNKNOWN), result.activeRiskTrackIds)
    }

    @Test
    fun expiredIndependentStopCannotSuppressFreshPrimaryWarning() {
        val expired = output(UNKNOWN, level = MessageLevel.STOP)
            .copy(sourceValidUntilElapsedRealtimeMs = START - 1L)
        val result = dispatch(unknown = listOf(expired))
        assertEquals(PRIMARY, result.action?.trackId)
        assertEquals(setOf(PRIMARY), result.activeRiskTrackIds)
    }

    @Test
    fun missingPrimaryCaptureLeaseRemainsFailClosed() {
        val unleased = output(PRIMARY).copy(sourceValidUntilElapsedRealtimeMs = null)
        val result = dispatch(primary = listOf(unleased), unknown = emptyList())
        assertNull(result.action)
        assertTrue(result.activeFeedbackDeliveryKeys.isEmpty())
    }

    @Test
    fun missingIndependentOutputLeaseIsNotCreatedFromDispatchTime() {
        val unleased = output(UNKNOWN).copy(sourceValidUntilElapsedRealtimeMs = null)
        val result = dispatch(primary = emptyList(), unknown = listOf(unleased))
        assertNull(result.action)
        assertTrue(result.activeRiskTrackIds.isEmpty())
    }

    @Test
    fun independentBatchClampsCandidateBeforePolicyReservation() {
        val longer = output(UNKNOWN).copy(sourceValidUntilElapsedRealtimeMs = START + 2_000L)
        val result = dispatch(now = START + 700L, primary = emptyList(), unknown = listOf(longer))
        assertEquals(START + 800L, requireNotNull(result.action).validUntilMs)
    }

    @Test
    fun shorterIndependentOutputLeaseSurvivesFreshBatchAndRepeatedDispatch() {
        val shorter = output(UNKNOWN).copy(sourceValidUntilElapsedRealtimeMs = START + 100L)
        val initial = dispatch(now = START + 50L, primary = emptyList(), unknown = listOf(shorter))
        assertEquals(START + 100L, requireNotNull(initial.action).validUntilMs)
        val expired = dispatch(now = START + 101L, primary = emptyList(), unknown = listOf(shorter))
        assertNull(expired.action)
        assertTrue(expired.activeFeedbackDeliveryKeys.isEmpty())
    }

    @Test
    fun distanceUpdatesWithoutChangingPendingWarningAndHonorsSourceLeaseAndDeduplication() {
        val first = dispatch(primary = listOf(output(PRIMARY).copy(measuredSurfaceRangeM = 2.5f)), unknown = emptyList())
        val action = requireNotNull(first.action)
        assertTrue(policy.claimFeedbackDelivery(action.trackId, START, START))
        val next = dispatch(START + 100, listOf(output(PRIMARY, START + 100).copy(measuredSurfaceRangeM = 1.8f)), emptyList())
        assertNull(next.action)
        assertEquals(first.activeFeedbackDeliveryKeys, next.activeFeedbackDeliveryKeys)
        val latest = requireNotNull(next.distanceOutputsByDeliveryKey[action.deliveryKey])
        assertEquals(1.8f, requireNotNull(latest.measuredSurfaceRangeM), .00001f)
        assertEquals(START + 900, latest.sourceValidUntilElapsedRealtimeMs)
        val duplicate = dispatch(START + 200)
        assertEquals(1, duplicate.distanceOutputsByDeliveryKey.size)
        val expired = dispatch(START + 801, emptyList(), listOf(output(UNKNOWN)))
        assertTrue(expired.distanceOutputsByDeliveryKey.isEmpty())
        val stale = dispatch(START + 300, listOf(output(PRIMARY)), emptyList(), stale = true)
        assertTrue(stale.distanceOutputsByDeliveryKey.isEmpty())
    }

    private fun dispatch(
        now: Long = START,
        primary: List<TrackedObjectDepth> = listOf(output(PRIMARY)),
        unknown: List<TrackedObjectDepth> = listOf(output(UNKNOWN)),
        stale: Boolean = false,
    ) = coordinator.dispatchFeedback(frame(primary), stale, true, now,
        independentFreshOutputs = if (unknown.isEmpty()) emptyList() else listOf(batch(unknown,
            frameId = unknown.first().frameId, capturedAt = START + (unknown.first().frameId - FRAME_ID) / 1_000_000L)))

    private fun coordinator(policy: WalkSafeFeedbackPolicy) =
        AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), policy, TactileFrameFeedbackActuator { })

    private fun frame(outputs: List<TrackedObjectDepth>) = MainActivity.TactileSnapshotFrameResult(
        null, emptyList(), false,
        TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "test", null)),
    )

    private fun batch(
        outputs: List<TrackedObjectDepth>,
        frameId: Long = FRAME_ID,
        capturedAt: Long = START,
        completedAt: Long = capturedAt,
        epoch: WalkRuntimeEpoch = WalkRuntimeEpoch("cross-source-test", 0L),
    ) = UnknownObjectFeedbackBatch(frameId, frameId / 1_000_000L, capturedAt * 1_000_000L, completedAt, epoch, outputs)

    private fun output(id: String, now: Long = START, level: MessageLevel = MessageLevel.WARNING): TrackedObjectDepth {
        val box = RectNorm(.1f, .3f, .2f, .4f)
        val frameId = FRAME_ID + (now - START) * 1_000_000L
        return TrackedObjectDepth(
            frameId = frameId, timestampMs = frameId / 1_000_000L, trackId = id,
            className = if (id.startsWith("unknown")) "unnamed-obstacle" else "person",
            detectionConfidence = .95f, bboxNorm = box, polygonNorm = emptyList(), maskAreaNorm = box.area,
            centerNorm = box.center, bottomContactNorm = null, source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1f, rayDistanceM = 1f, groundDistanceM = 1f, riskDistanceM = 1f,
            validSampleCount = 80, validSampleRatio = .9f, depthMedianM = 1f, depthP20M = 1f, depthIqrM = .05f,
            trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = confidence(), userFacing = UserFacingDepth(null, level, "위험 $id"),
            trackAgeFrames = 20, trackStableMs = 1_900L,
            sourceValidUntilElapsedRealtimeMs = now + UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS,
        )
    }

    private fun confidence(score: Float = .95f) = DepthConfidenceBreakdown(score, 1f, 1f, 1f, 1f, 1f, 1f, 1f)

    private companion object {
        const val START = 10_000L
        const val FRAME_ID = 5_000_000_000_000L
        const val PRIMARY = "primary-1"
        const val UNKNOWN = "unknown:1:u"
    }
}
