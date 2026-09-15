package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileFrameCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileRouteGuidance
import kr.co.hanium.dreamup.walksafe.navigation.LocalRouteMode
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackActuator
import kr.co.hanium.dreamup.walksafe.navigation.TactileRouteDecision
import kr.co.hanium.dreamup.walksafe.navigation.TactileRouteGuidanceResult
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownObjectFeedbackPolicyTest {
    @Test fun forwardObstacleSelectionIsRequiredEvenWithMeasuredNearDepth() {
        assertNull(admit(output().copy(walkingObstacleCandidate = false)))
    }
    @Test
    fun admissionRequiresMeasuredRadialRangeWithinThreeMetersEvenWhenAxialDistanceIsNear() {
        assertNotNull(admit(output().copy(rayDistanceM = 3f)))
        listOf(null, 3.001f, 4f, Float.NaN, Float.POSITIVE_INFINITY, 0f).forEach { range ->
            assertNull("radial=$range", admit(output().copy(rayDistanceM = range)))
        }
        assertNull(admit(output().copy(depthAvailability = DepthAvailability.PREDICTED,
            prediction = PredictedDepthEstimate(2f, FRAME_MS - 50L, 50L, .10f, 200L, 2.1f))))
    }

    @Test
    fun measuredObjectApproachUsesPlainObjectNameAndKeepsFrozenSourceIdentity() {
        val original = output()
        val batch = requireNotNull(admit(original))
        val admitted = batch.outputs.single()
        assertTrue(admitted.userFacing.message!!.contains("물체"))
        assertTrue(admitted.userFacing.message!!.contains("물체가 사용자 쪽으로 다가오는"))
        assertFalse(admitted.userFacing.message!!.contains("FastSAM"))
        assertFalse(admitted.userFacing.message!!.contains("unnamed"))
        assertEquals(original.frameId, admitted.frameId)
        assertEquals(original.timestampMs, admitted.timestampMs)
        assertEquals(original.trackId, admitted.trackId)
        assertEquals(original.motionEstimate, admitted.motionEstimate)
        assertEquals(CAPTURED_NS, batch.sourceCapturedAtElapsedRealtimeNs)
        assertEquals(CAPTURED_MS + 800L, batch.validUntilElapsedRealtimeMs)
    }

    @Test
    fun userApproachToStationaryObjectIsDistinctAndNearUnknownMotionStillStops() {
        val stationary = output().copy(
            objectMotion = ObjectMotion.USER_APPROACHING_STATIONARY,
            motionEstimate = motion().copy(direction = ObjectMovementDirection.STATIONARY, objectSpeedMps = 0f),
        )
        val stationaryMessage = requireNotNull(admit(stationary)).outputs.single().userFacing
        assertEquals(MessageLevel.STOP, stationaryMessage.messageLevel)
        assertTrue(stationaryMessage.message!!.contains("현재 이 물체에 가까워지고"))
        assertFalse(stationaryMessage.message!!.contains("다가오는"))
        val unknownMotion = output().copy(
            riskDistanceM = 1f,
            trend = Trend.UNKNOWN,
            objectMotion = ObjectMotion.UNKNOWN,
            motionEstimate = ObjectMotionEstimate(),
        )
        val stop = requireNotNull(admit(unknownMotion)).outputs.single().userFacing
        assertEquals(MessageLevel.STOP, stop.messageLevel)
        assertTrue(stop.message!!.contains("멈추세요"))
        assertFalse(stop.message!!.contains("다가오는"))
    }

    @Test
    fun staleRepeatedOrIncompleteMotionCannotAttributeApproachToObject() {
        val invalid = listOf(
            motion().copy(observedAtMs = FRAME_MS - 1L),
            motion().copy(observedAtMs = FRAME_MS + 1L),
            motion().copy(referenceId = null),
            motion().copy(confidence = Float.NaN),
            motion().copy(confidence = 0.54f),
            motion().copy(elapsedMs = 599L),
            motion().copy(elapsedMs = 4_001L),
            motion().copy(objectVelocityInAnchorMps = null),
            motion().copy(objectSpeedMps = Float.NaN),
            motion().copy(relativeClosingSpeedMps = 0.24f),
            ObjectMotionEstimate(),
        )
        invalid.forEach { invalidMotion ->
            val result = requireNotNull(admit(output().copy(motionEstimate = invalidMotion))).outputs.single()
            assertEquals(MessageLevel.STOP, result.userFacing.messageLevel)
            assertFalse("$invalidMotion", result.userFacing.message!!.contains("다가오는"))
        }
    }

    @Test
    fun lateralOrUnattributedMovementDoesNotAddUnrequestedDirectionSpeech() {
        listOf(ObjectMovementDirection.LEFT, ObjectMovementDirection.RIGHT, ObjectMovementDirection.TOWARD_USER).forEach { direction ->
            val result = requireNotNull(admit(output().copy(
                objectMotion = ObjectMotion.UNKNOWN,
                motionEstimate = motion().copy(direction = direction),
            ))).outputs.single()
            assertTrue(result.userFacing.message!!.contains("거리가 줄어들고"))
            assertFalse(result.userFacing.message!!.contains("카메라 기준"))
            assertFalse(result.userFacing.message!!.contains("다가오는"))
        }
    }

    @Test
    fun noReliableMetricSampleCannotBecomeAnUnknownObjectVoiceCandidate() {
        listOf(
            output().copy(riskDistanceM = null),
            output().copy(riskDistanceM = Float.NaN),
            output().copy(riskDistanceM = 0f),
            output().copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH),
            output().copy(source = DepthSource.MONOCULAR_METRIC_DEPTH),
            output().copy(validSampleCount = 0),
            output().copy(validSampleRatio = 0f),
            output().copy(confidence = confidence().copy(hardGate = 0f)),
            output().copy(confidence = confidence().copy(freshnessQuality = 0f)),
        ).forEach { invalid -> assertNull(admit(invalid)) }
    }

    @Test
    fun eightHundredMillisecondBoundaryUsesSourceCaptureRatherThanCompletion() {
        assertNotNull(admit(output(), nowMs = CAPTURED_MS + 800L))
        assertNull(admit(output(), nowMs = CAPTURED_MS + 801L))
        val batch = requireNotNull(admit(output()))
        assertTrue(batch.isFreshAt(CAPTURED_MS + 800L))
        assertFalse(batch.isFreshAt(CAPTURED_MS + 801L))
    }

    @Test
    fun epochFutureCaptureAndFrameMismatchAreRejected() {
        assertNull(admit(output(), currentEpoch = EPOCH.copy(recoveryGeneration = 1L)))
        assertNull(admit(output(), currentEpoch = null))
        assertNull(admit(output(), nowMs = CAPTURED_MS - 1L))
        assertNull(admit(output().copy(frameId = FRAME_ID + 1L)))
        assertNull(admit(output().copy(timestampMs = FRAME_MS + 1L)))
        assertNull(admit(output().copy(className = "person")))
    }

    @Test
    fun staleCurrentOutputsDoNotSuppressFreshIndependentSourceAndItsDeadlineIsNotExtended() {
        val batch = requireNotNull(admit(output()))
        val coordinator = coordinator()
        val known = output().copy(className = "person", trackId = "known-1")
        val dispatch = coordinator.dispatchFeedback(
            frame = frame(listOf(known)), stale = true, deviceGateAllowsAlerts = true,
            nowMs = CAPTURED_MS + 500L, independentFreshOutputs = listOf(batch),
        )
        assertEquals("unknown-synthetic-1", dispatch.action?.trackId)
        assertEquals(CAPTURED_MS + 800L, dispatch.action?.validUntilMs)
        assertEquals(setOf("unknown-synthetic-1"), dispatch.activeRiskTrackIds)
        val expired = coordinator.dispatchFeedback(
            frame = frame(listOf(known)), stale = true, deviceGateAllowsAlerts = true,
            nowMs = CAPTURED_MS + 801L, independentFreshOutputs = listOf(batch),
        )
        assertNull(expired.action)
        assertTrue(expired.activeFeedbackDeliveryKeys.isEmpty())
    }

    @Test
    fun currentKnownStopWinsOverIndependentWarningAndDeviceGateStillBlocksBoth() {
        val batch = requireNotNull(admit(output().copy(riskDistanceM = 3f, timeToCollisionMs = 7_000L)))
        val known = output().copy(
            className = "person", trackId = "known-stop",
            userFacing = UserFacingDepth(1, MessageLevel.STOP, "전방 사람. 멈추세요."),
        )
        val dispatch = coordinator().dispatchFeedback(
            frame = frame(listOf(known)), stale = false, deviceGateAllowsAlerts = true,
            nowMs = CAPTURED_MS + 500L, independentFreshOutputs = listOf(batch),
        )
        assertEquals("known-stop", dispatch.action?.trackId)
        val blocked = coordinator().dispatchFeedback(
            frame = frame(listOf(known)), stale = false, deviceGateAllowsAlerts = false,
            nowMs = CAPTURED_MS + 500L, independentFreshOutputs = listOf(batch),
        )
        assertNull(blocked.action)
        assertTrue(blocked.activeRiskTrackIds.isEmpty())
    }

    @Test
    fun repeatedSourceSharesTheExistingReservationInsteadOfCreatingAnotherVoice() {
        val batch = requireNotNull(admit(output()))
        val coordinator = coordinator()
        val first = coordinator.dispatchFeedback(frame(emptyList()), false, true, CAPTURED_MS + 500L,
            independentFreshOutputs = listOf(batch))
        val repeated = coordinator.dispatchFeedback(frame(emptyList()), false, true, CAPTURED_MS + 501L,
            independentFreshOutputs = listOf(batch))
        assertNotNull(first.action)
        assertNull(repeated.action)
        assertEquals(first.activeFeedbackDeliveryKeys, repeated.activeFeedbackDeliveryKeys)
    }

    @Test
    fun retainedRegionsKeepOriginalCaptureCompletionEpochAndMeasurements() {
        val first = requireNotNull(admit(output())).outputs.single()
        val second = first.copy(trackId = "unknown-second", riskDistanceM = 1f)
        val original = UnknownObjectFeedbackBatch(FRAME_ID, FRAME_MS, CAPTURED_NS,
            CAPTURED_MS + 450L, EPOCH, listOf(first, second))
        val retained = requireNotNull(original.retainRegions(setOf(second.trackId), CAPTURED_MS + 700L))
        assertEquals(listOf(second), retained.outputs)
        assertEquals(FRAME_ID, retained.sourceFrameId)
        assertEquals(FRAME_MS, retained.sourceTimestampMs)
        assertEquals(CAPTURED_NS, retained.sourceCapturedAtElapsedRealtimeNs)
        assertEquals(CAPTURED_MS + 450L, retained.completedAtElapsedRealtimeMs)
        assertEquals(EPOCH, retained.sourceEpoch)
        assertEquals(CAPTURED_MS + 800L, retained.validUntilElapsedRealtimeMs)
        assertEquals(listOf(first, second), original.outputs)
    }

    @Test
    fun repeatedRegionRetentionNeverExtendsTheOriginalEightHundredMillisecondLease() {
        var retained = requireNotNull(admit(output()))
        for (now in CAPTURED_MS + 500L..CAPTURED_MS + 800L step 100L) {
            retained = requireNotNull(retained.retainRegions(setOf(output().trackId), now))
            assertEquals(CAPTURED_MS + 800L, retained.validUntilElapsedRealtimeMs)
        }
        assertNull(retained.retainRegions(setOf(output().trackId), CAPTURED_MS + 801L))
    }

    @Test
    fun unsupportedOrEmptyRegionIdsAndPreCompletionTimeCannotRetainAnyBatch() {
        val original = requireNotNull(admit(output()))
        assertNull(original.retainRegions(emptySet(), CAPTURED_MS + 500L))
        assertNull(original.retainRegions(setOf("unknown-missing"), CAPTURED_MS + 500L))
        assertNull(original.retainRegions(setOf(output().trackId), CAPTURED_MS + 449L))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun retainedRegionOutputListRemainsFrozen() {
        val retained = requireNotNull(requireNotNull(admit(output())).retainRegions(setOf(output().trackId), CAPTURED_MS + 500L))
        (retained.outputs as MutableList<TrackedObjectDepth>).clear()
    }

    @Test
    fun retainingAnAdmittedRegionPreservesTheExistingClaimAndStartDeliveryKey() {
        val policy = WalkSafeFeedbackPolicy()
        val coordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), policy, TactileFrameFeedbackActuator { })
        val original = requireNotNull(admit(output()))
        val first = coordinator.dispatchFeedback(frame(emptyList()), false, true, CAPTURED_MS + 500L,
            independentFreshOutputs = listOf(original))
        val action = requireNotNull(first.action)
        assertTrue(policy.claimFeedbackDelivery(action.trackId, CAPTURED_MS + 500L))
        val retained = requireNotNull(original.retainRegions(setOf(action.trackId), CAPTURED_MS + 700L))
        val repeated = coordinator.dispatchFeedback(frame(emptyList()), false, true, CAPTURED_MS + 700L,
            independentFreshOutputs = listOf(retained))
        assertNull(repeated.action)
        assertTrue(action.deliveryKey in repeated.activeFeedbackDeliveryKeys)
        assertEquals(action.validUntilMs, retained.validUntilElapsedRealtimeMs)
        assertTrue(policy.confirmFeedbackDelivery(action.trackId, CAPTURED_MS + 500L, CAPTURED_MS + 750L))
    }

    private fun admit(output: TrackedObjectDepth, nowMs: Long = CAPTURED_MS + 500L, currentEpoch: WalkRuntimeEpoch? = EPOCH) =
        UnknownObjectFeedbackPolicy().admit(listOf(output), FRAME_ID, FRAME_MS, CAPTURED_NS,
            CAPTURED_MS + 450L, EPOCH, currentEpoch, nowMs)

    private fun coordinator() = AndroidTactileFrameCoordinator(
        AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator { },
    )

    private fun frame(outputs: List<TrackedObjectDepth>) = MainActivity.TactileSnapshotFrameResult(
        null, emptyList(), false,
        TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "test", null)),
    )

    private fun output() = TrackedObjectDepth(
        frameId = FRAME_ID, timestampMs = FRAME_MS, trackId = "unknown-synthetic-1", className = "unnamed-obstacle",
        detectionConfidence = 0.95f, bboxNorm = RectNorm(0.4f, 0.2f, 0.2f, 0.4f), polygonNorm = emptyList(),
        maskAreaNorm = 0.08f, centerNorm = Point2(0.5f, 0.4f), bottomContactNorm = null,
        source = DepthSource.ARCORE_RAW_DEPTH, zDistanceM = 2f, rayDistanceM = 2f, groundDistanceM = null,
        riskDistanceM = 2f, validSampleCount = 64, validSampleRatio = 1f, depthMedianM = 2f, depthP20M = 2f,
        depthIqrM = 0.1f, trend = Trend.APPROACHING, approachScore = 0.9f, approachSpeedMps = 0.6f,
        timeToCollisionMs = 3_000L, confidence = confidence(),
        userFacing = UserFacingDepth(null, MessageLevel.STOP, "FastSAM unnamed internal output"),
        trackAgeFrames = 4, trackStableMs = 1_000L, objectMotion = ObjectMotion.OBJECT_APPROACHING,
        motionEstimate = motion(),
    )

    private fun confidence() = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)

    private fun motion() = ObjectMotionEstimate(
        referenceId = 1L, observedAtMs = FRAME_MS, elapsedMs = 1_000L,
        objectVelocityInAnchorMps = Vec3(0f, 0f, -0.6f), relativeVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
        cameraVelocityInAnchorMps = Vec3(0f, 0f, 0f), objectSpeedMps = 0.6f,
        relativeClosingSpeedMps = 0.6f, direction = ObjectMovementDirection.TOWARD_USER, confidence = 0.9f,
    )

    private companion object {
        const val FRAME_MS = 1_000L
        const val FRAME_ID = FRAME_MS * 1_000_000L
        const val CAPTURED_MS = 100_000L
        const val CAPTURED_NS = CAPTURED_MS * 1_000_000L
        val EPOCH = WalkRuntimeEpoch("synthetic-feedback", 0L)
    }
}
