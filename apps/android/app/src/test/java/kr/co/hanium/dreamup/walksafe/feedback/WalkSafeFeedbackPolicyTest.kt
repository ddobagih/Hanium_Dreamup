package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.assertEquals
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
    fun emitsOnlyAfterBothFrameAndElapsedStabilityGatesPass() {
        val policy = WalkSafeFeedbackPolicy()

        val framesOnly = policy.evaluate(candidate(trackAgeFrames = 3, trackStableMs = 699L), true, 1_000L)
        val timeOnly = policy.evaluate(candidate(trackAgeFrames = 2, trackStableMs = 900L), true, 1_800L)
        val stable = policy.evaluate(candidate(trackAgeFrames = 3, trackStableMs = 700L), true, 2_600L)

        assertNull(framesOnly)
        assertNull(timeOnly)
        assertNotNull(stable)
    }

    @Test
    fun rateLimitsPerTrackAndGlobalAlerts() {
        val policy = WalkSafeFeedbackPolicy()

        val first = policy.evaluate(candidate(trackId = "a"), true, 1_000L)
        policy.confirmFeedbackDelivery(first!!.trackId, 1_000L)
        val sameTrack = policy.evaluate(candidate(trackId = "a"), true, 2_000L)
        val otherTrackBeforeGlobalWindow = policy.evaluate(candidate(trackId = "b"), true, 1_500L)
        val otherTrackAfterGlobalWindow = policy.evaluate(candidate(trackId = "b"), true, 1_800L)

        assertNotNull(first)
        assertNull(sameTrack)
        assertNull(otherTrackBeforeGlobalWindow)
        assertNotNull(otherTrackAfterGlobalWindow)
    }

    @Test
    fun staleUntrustedUncertainOrMissingDecisionNeverEmits() {
        val policy = WalkSafeFeedbackPolicy()

        assertNull(policy.evaluate(candidate(stale = true), true, 1_000L))
        assertNull(policy.evaluate(candidate(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH), true, 2_000L))
        assertNull(policy.evaluate(candidate(confidence = 0.54f), true, 3_000L))
        assertNull(policy.evaluate(null, true, 4_000L))
    }

    @Test
    fun warningUsesDistinctVibrationWhileLowerLevelsRemainSilent() {
        val stop = VibrationPatterns.forLevel(MessageLevel.STOP)
        val warning = VibrationPatterns.forLevel(MessageLevel.WARNING)

        assertNotNull(stop)
        assertNotNull(warning)
        assertFalse(stop!!.contentEquals(warning!!))
        assertNull(VibrationPatterns.forLevel(MessageLevel.CAUTION))
        assertNull(VibrationPatterns.forLevel(MessageLevel.AWARE))
        assertNull(VibrationPatterns.forLevel(MessageLevel.INFO))
    }

    @Test
    fun navigationAnnouncementsAreSuppressedRightAfterRiskAlert() {
        val policy = WalkSafeFeedbackPolicy()

        val action = policy.evaluate(candidate(), true, 1_000L)!!
        policy.confirmFeedbackDelivery(action.trackId, 1_000L)

        assertFalse(policy.canSpeakNavigation(2_200L))
        assertTrue(policy.canSpeakNavigation(3_600L))
    }

    @Test
    fun emitsMetricInfoGuidanceWithoutRiskVibrationOrRiskCooldown() {
        val policy = WalkSafeFeedbackPolicy()

        val action = policy.evaluate(
            candidate(level = MessageLevel.INFO, message = "점자블록을 따라 이동하세요."),
            deviceGateAllowsAlerts = true,
            nowMs = 1_000L,
        )

        assertNotNull(action)
        assertEquals("fresh_metric_path_guidance", action!!.reason)
        assertNull(action.vibrationPatternMs)
        assertTrue(policy.canSpeakNavigation(1_800L))
    }

    @Test
    fun interruptedInformationSpeechReleasesClaimAndIgnoresLateCompletion() {
        val policy = WalkSafeFeedbackPolicy()
        val info = candidate(level = MessageLevel.INFO, message = "점자블록을 따라 이동하세요.")
        val action = requireNotNull(policy.evaluate(info, true, 1_000L))
        assertTrue(policy.claimFeedbackDelivery(action.trackId, 1_000L))
        val callbacks = UtteranceCallbackRegistry()
        var completed = 0
        var failures = 0
        callbacks.register(
            "info",
            onCompleted = {
                completed += 1
                policy.confirmFeedbackDelivery(action.trackId, 1_000L)
            },
            onFailed = {
                failures += 1
                policy.rejectUndeliveredFeedback(action.trackId, 1_000L)
            },
        )
        assertNull(policy.evaluate(info, true, 6_000L))

        callbacks.takeTerminalCallback("info", completed = false, notifyFailure = true)?.invoke()
        callbacks.takeTerminalCallback("info", completed = true, notifyFailure = true)?.invoke()
        callbacks.takeTerminalCallback("info", completed = false, notifyFailure = true)?.invoke()

        assertEquals(0, completed)
        assertEquals(1, failures)
        assertNotNull(policy.evaluate(info, true, 6_100L))
    }

    @Test
    fun fallsThroughToAnotherTrackWhenHighestPriorityTrackIsRateLimited() {
        val policy = WalkSafeFeedbackPolicy()
        val highest = candidate(trackId = "stop-track", level = MessageLevel.STOP, message = "멈추세요.")
        val next = candidate(trackId = "warning-track", level = MessageLevel.WARNING)

        val first = policy.evaluateCandidates(listOf(highest, next), true, 1_000L)
        policy.confirmFeedbackDelivery(first!!.trackId, 1_000L)
        val second = policy.evaluateCandidates(listOf(highest, next), true, 1_800L)

        assertEquals("stop-track", first.trackId)
        assertEquals("warning-track", second!!.trackId)
    }

    @Test
    fun allowsOnlyOnePendingDeliveryDecisionAtATime() {
        val policy = WalkSafeFeedbackPolicy()
        val first = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!

        assertNull(
            policy.evaluateCandidates(
                listOf(candidate(trackId = "a"), candidate(trackId = "b")),
                true,
                2_000L,
            ),
        )
        policy.rejectUndeliveredFeedback(first.trackId, 1_000L)
        assertEquals("b", policy.evaluate(candidate(trackId = "b"), true, 2_000L)!!.trackId)
    }

    @Test
    fun equalSeverityRisksUseBoundedFifoIncludingTheActiveDelivery() {
        val policy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(
                perTrackIntervalMs = 0L,
                globalIntervalMs = 0L,
                pendingQueueCapacity = 3,
            ),
        )
        val candidates = listOf(
            candidate(trackId = "a"),
            candidate(trackId = "b"),
            candidate(trackId = "c"),
            candidate(trackId = "d"),
        )

        val first = policy.evaluateCandidates(candidates, true, 1_000L)!!
        assertEquals("a", first.trackId)
        assertTrue(policy.claimFeedbackDelivery(first.trackId, 1_000L))
        assertNull(policy.evaluateCandidates(candidates.drop(1), true, 1_010L))

        policy.confirmFeedbackDelivery(first.trackId, 1_000L, 1_020L)
        val second = policy.evaluateCandidates(candidates.drop(1), true, 1_021L)!!
        assertEquals("b", second.trackId)
        policy.confirmFeedbackDelivery(second.trackId, 1_021L)
        val third = policy.evaluateCandidates(candidates.drop(2), true, 1_022L)!!
        assertEquals("c", third.trackId)
    }

    @Test
    fun fullEqualSeverityQueuePreservesExistingFifoAndDropsTheNewArrival() {
        val policy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(
                perTrackIntervalMs = 0L,
                globalIntervalMs = 0L,
                pendingQueueCapacity = 3,
            ),
        )
        val first = policy.evaluateCandidates(
            listOf(candidate(trackId = "a"), candidate(trackId = "b"), candidate(trackId = "c")),
            true,
            1_000L,
        )!!

        assertNull(
            policy.evaluateCandidates(
                listOf(
                    candidate(trackId = "a"),
                    candidate(trackId = "b"),
                    candidate(trackId = "c"),
                    candidate(trackId = "d"),
                ),
                true,
                1_010L,
            ),
        )
        policy.rejectUndeliveredFeedback(first.trackId, 1_000L)
        val next = policy.evaluateCandidates(
            listOf(candidate(trackId = "b"), candidate(trackId = "c"), candidate(trackId = "d")),
            true,
            1_020L,
        )!!

        assertEquals("b", next.trackId)
    }

    @Test
    fun higherSeverityPreemptsTheActiveRiskAndEvictsLowerQueueEntries() {
        val policy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(
                perTrackIntervalMs = 0L,
                globalIntervalMs = 0L,
                pendingQueueCapacity = 3,
            ),
        )
        val warningA = candidate(trackId = "warning-a")
        val warningB = candidate(trackId = "warning-b")
        val warningC = candidate(trackId = "warning-c")
        val active = policy.evaluateCandidates(listOf(warningA, warningB, warningC), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(active.trackId, 1_000L))

        val stop = policy.evaluateCandidates(
            listOf(
                warningA,
                warningB,
                warningC,
                candidate(trackId = "stop", level = MessageLevel.STOP, message = "멈추세요."),
            ),
            true,
            1_010L,
        )!!

        assertEquals("stop", stop.trackId)
        assertFalse(policy.confirmFeedbackDelivery(active.trackId, 1_000L))
        policy.confirmFeedbackDelivery(stop.trackId, 1_010L)
        assertEquals(
            "warning-b",
            policy.evaluateCandidates(listOf(warningA, warningB, warningC), true, 1_020L)!!.trackId,
        )
    }

    @Test
    fun queuedRiskMustBeCurrentAndWithinLastSeenTtlImmediatelyBeforeReservation() {
        val policy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(
                perTrackIntervalMs = 0L,
                globalIntervalMs = 0L,
                pendingQueueTtlMs = 100L,
            ),
        )
        val first = policy.evaluateCandidates(
            listOf(candidate(trackId = "a"), candidate(trackId = "stale-next")),
            true,
            1_000L,
        )!!
        policy.confirmFeedbackDelivery(first.trackId, 1_000L)

        val current = policy.evaluate(candidate(trackId = "current"), true, 1_101L)

        assertEquals("current", current!!.trackId)
    }

    @Test
    fun rejectedFailedSpeechAndLifecycleCancellationCannotBlockTheQueue() {
        val failedPolicy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(perTrackIntervalMs = 0L, globalIntervalMs = 0L),
        )
        val failed = failedPolicy.evaluateCandidates(
            listOf(candidate(trackId = "failed"), candidate(trackId = "next")),
            true,
            1_000L,
        )!!
        assertTrue(failedPolicy.claimFeedbackDelivery(failed.trackId, 1_000L))
        failedPolicy.rejectUndeliveredFeedback(failed.trackId, 1_000L)
        assertEquals(
            "next",
            failedPolicy.evaluate(candidate(trackId = "next"), true, 1_001L)!!.trackId,
        )

        val lifecyclePolicy = WalkSafeFeedbackPolicy(
            FeedbackPolicyConfig(perTrackIntervalMs = 0L, globalIntervalMs = 0L),
        )
        val cancelled = lifecyclePolicy.evaluateCandidates(
            listOf(candidate(trackId = "cancelled"), candidate(trackId = "after-resume")),
            true,
            2_000L,
        )!!
        assertTrue(lifecyclePolicy.claimFeedbackDelivery(cancelled.trackId, 2_000L))
        lifecyclePolicy.cancelPendingFeedbackDeliveries()
        assertEquals(
            "after-resume",
            lifecyclePolicy.evaluate(candidate(trackId = "after-resume"), true, 2_001L)!!.trackId,
        )
    }

    @Test
    fun safeStateInvalidatesPendingDeliveryAndRollsBackItsCooldown() {
        val policy = WalkSafeFeedbackPolicy()
        val pending = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!

        policy.evaluate(candidate(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH), true, 1_100L)
        policy.evaluate(candidate(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH), true, 1_200L)

        assertFalse(policy.claimFeedbackDelivery(pending.trackId, 1_000L))
        assertEquals("b", policy.evaluate(candidate(trackId = "b"), true, 1_300L)!!.trackId)
    }

    @Test
    fun higherSeverityInvalidatesAnUnclaimedLowerSeverityDecision() {
        val policy = WalkSafeFeedbackPolicy()
        val warning = policy.evaluate(candidate(trackId = "warning"), true, 1_000L)!!

        val stop = policy.evaluateCandidates(
            listOf(
                candidate(trackId = "warning"),
                candidate(trackId = "stop", level = MessageLevel.STOP, message = "멈추세요."),
            ),
            true,
            1_100L,
        )!!

        assertFalse(policy.claimFeedbackDelivery(warning.trackId, 1_000L))
        assertEquals("stop", stop.trackId)
    }

    @Test
    fun higherSeverityInvalidatesAClaimedDecisionStillAwaitingSpeechCompletion() {
        val policy = WalkSafeFeedbackPolicy()
        val warning = policy.evaluate(candidate(trackId = "warning"), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(warning.trackId, 1_000L))

        val stop = policy.evaluateCandidates(
            listOf(
                candidate(trackId = "warning"),
                candidate(trackId = "stop", level = MessageLevel.STOP, message = "멈추세요."),
            ),
            true,
            1_100L,
        )!!

        assertFalse(policy.confirmFeedbackDelivery(warning.trackId, 1_000L))
        assertEquals("stop", stop.trackId)
    }

    @Test
    fun claimedDecisionCannotBeClearedBeforeItsDeliveryIsResolved() {
        val policy = WalkSafeFeedbackPolicy()
        val pending = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(pending.trackId, 1_000L))

        policy.evaluate(null, deviceGateAllowsAlerts = false, nowMs = 1_100L)

        policy.confirmFeedbackDelivery(pending.trackId, 1_000L)
        assertFalse(policy.canSpeakNavigation(1_200L))
    }

    @Test
    fun lifecycleCancellationRollsBackAClaimedUndeliveredDecision() {
        val policy = WalkSafeFeedbackPolicy()
        val pending = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(pending.trackId, 1_000L))

        policy.cancelPendingFeedbackDeliveries()

        assertFalse(policy.confirmFeedbackDelivery(pending.trackId, 1_000L))
        assertTrue(policy.canSpeakNavigation(1_100L))
    }

    @Test
    fun freshWalkResetClearsPendingWorkAndPriorWalkCooldowns() {
        val policy = WalkSafeFeedbackPolicy()
        val delivered = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery(delivered.trackId, 1_000L))

        policy.resetForNewWalk()

        assertFalse(policy.confirmFeedbackDelivery(delivered.trackId, 1_000L))
        assertNotNull(policy.evaluate(candidate(trackId = "a"), true, 1_100L))
    }

    @Test
    fun cooldownStartsWhenDelayedSpeechDeliveryCompletes() {
        val policy = WalkSafeFeedbackPolicy()
        val pending = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(pending.trackId, 1_000L))

        assertTrue(policy.confirmFeedbackDelivery(pending.trackId, 1_000L, deliveredAtMs = 4_000L))

        assertNull(policy.evaluate(candidate(trackId = "a"), true, 5_000L))
        assertNotNull(policy.evaluate(candidate(trackId = "a"), true, 6_500L))
    }

    @Test
    fun stopEscalationBypassesLowerCompletedWarningButNotTheNextStopCooldown() {
        val policy = WalkSafeFeedbackPolicy()
        val warning = policy.evaluate(candidate(trackId = "a"), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery(warning.trackId, 1_000L))
        assertTrue(policy.confirmFeedbackDelivery(warning.trackId, 1_000L, 4_000L))

        val stop = policy.evaluate(candidate(trackId = "a", level = MessageLevel.STOP), true, 4_100L)!!
        assertEquals(MessageLevel.STOP, stop.level)
        assertEquals(5_600L, stop.validUntilMs)
        assertTrue(policy.claimFeedbackDelivery(stop.trackId, 4_100L))
        assertTrue(policy.confirmFeedbackDelivery(stop.trackId, 4_100L, 4_200L))

        assertNull(policy.evaluate(candidate(trackId = "a", level = MessageLevel.STOP), true, 4_300L))
        assertNull(policy.evaluate(candidate(trackId = "b", level = MessageLevel.STOP), true, 4_300L))
        assertNotNull(policy.evaluate(candidate(trackId = "a", level = MessageLevel.STOP), true, 6_700L))
    }

    @Test
    fun onlyWarningAndStopEscalationsBypassCompletedCooldowns() {
        for (previous in listOf(MessageLevel.INFO, MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)) {
            for (next in listOf(MessageLevel.INFO, MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)) {
                val policy = WalkSafeFeedbackPolicy()
                policy.evaluate(candidate(level = previous), true, 1_000L)!!
                assertTrue(policy.confirmFeedbackDelivery("track-1", 1_000L, 4_000L))
                val action = policy.evaluate(candidate(level = next), true, 4_100L)
                val expected = (next == MessageLevel.WARNING || next == MessageLevel.STOP) && next.ordinal > previous.ordinal
                assertEquals("$previous -> $next", expected, action != null)
            }
        }
    }

    @Test
    fun stopOnAnotherTrackCanBypassTheGlobalWarningCooldown() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(trackId = "warning"), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("warning", 1_000L, 4_000L))

        assertEquals(
            "stop",
            policy.evaluate(candidate(trackId = "stop", level = MessageLevel.STOP), true, 4_100L)!!.trackId,
        )
    }

    @Test
    fun previousStopOnTheSameTrackIsNotBypassedBecauseAnotherWarningCompletedLater() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(trackId = "stop", level = MessageLevel.STOP), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("stop", 1_000L))
        policy.evaluate(candidate(trackId = "warning"), true, 1_800L)!!
        assertTrue(policy.confirmFeedbackDelivery("warning", 1_800L))

        assertNull(policy.evaluate(candidate(trackId = "stop", level = MessageLevel.STOP), true, 1_900L))
    }

    @Test
    fun failedClaimedEscalationRestoresThePreviousWarningAndAllowsAStopRetry() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("track-1", 1_000L, 4_000L))
        policy.evaluate(candidate(level = MessageLevel.STOP), true, 4_100L)!!
        assertTrue(policy.claimFeedbackDelivery("track-1", 4_100L))

        policy.rejectUndeliveredFeedback("track-1", 4_100L)

        assertFalse(policy.confirmFeedbackDelivery("track-1", 4_100L, 4_150L))
        assertNotNull(policy.evaluate(candidate(level = MessageLevel.STOP), true, 4_200L))
    }

    @Test
    fun cancelledEscalationPreservesTheCompletedCooldownAndLateCallbackCannotCommitIt() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("track-1", 1_000L, 4_000L))
        policy.evaluate(candidate(level = MessageLevel.STOP), true, 4_100L)!!
        assertTrue(policy.claimFeedbackDelivery("track-1", 4_100L))

        policy.cancelPendingFeedbackDeliveries()

        assertFalse(policy.confirmFeedbackDelivery("track-1", 4_100L, 4_150L))
        assertNull(policy.evaluate(candidate(), true, 4_200L))
        assertNotNull(policy.evaluate(candidate(level = MessageLevel.STOP), true, 4_300L))
    }

    @Test
    fun expiredMissingEscalationCannotBeClaimedAndFreshWalkClearsDeliveredSeverity() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("track-1", 1_000L, 4_000L))
        policy.evaluate(candidate(level = MessageLevel.STOP), true, 4_100L)!!

        policy.evaluate(null, true, 5_601L)
        assertFalse(policy.claimFeedbackDelivery("track-1", 4_100L))
        policy.resetForNewWalk()
        assertNotNull(policy.evaluate(candidate(level = MessageLevel.CAUTION), true, 5_602L))
    }

    @Test
    fun cancellingUnknownTracksPreservesAPrimaryClaimAndClearsOnlyTheirQueuedEntries() {
        val policy = WalkSafeFeedbackPolicy(FeedbackPolicyConfig(pendingQueueCapacity = 2))
        policy.evaluateCandidates(listOf(candidate(trackId = "primary"), candidate(trackId = "unknown")), true, 1_000L)!!
        assertTrue(policy.claimFeedbackDelivery("primary", 1_000L))

        policy.cancelFeedbackForTracks(setOf("unknown"))

        assertTrue(policy.confirmFeedbackDelivery("primary", 1_000L))
        assertEquals(
            "next",
            policy.evaluateCandidates(listOf(candidate(trackId = "next"), candidate(trackId = "unknown")), true, 1_800L)!!.trackId,
        )
    }

    @Test
    fun cancellingAnUnknownEscalationRestoresItsWarningCooldownAndPreservesAnotherQueuedTrack() {
        val policy = WalkSafeFeedbackPolicy()
        policy.evaluate(candidate(trackId = "unknown"), true, 1_000L)!!
        assertTrue(policy.confirmFeedbackDelivery("unknown", 1_000L, 4_000L))
        policy.evaluateCandidates(
            listOf(candidate(trackId = "unknown", level = MessageLevel.STOP), candidate(trackId = "primary")),
            true,
            4_100L,
        )!!
        assertTrue(policy.claimFeedbackDelivery("unknown", 4_100L))

        policy.cancelFeedbackForTracks(setOf("unknown"))

        assertFalse(policy.confirmFeedbackDelivery("unknown", 4_100L, 4_200L))
        assertNull(policy.evaluate(candidate(trackId = "unknown"), true, 4_200L))
        assertEquals(
            "primary",
            policy.evaluateCandidates(listOf(candidate(trackId = "third"), candidate(trackId = "primary")), true, 4_700L)!!.trackId,
        )
    }

    @Test
    fun rejectedDeliveryDoesNotConsumeTrackOrGlobalCooldown() {
        val policy = WalkSafeFeedbackPolicy()
        val first = policy.evaluate(candidate(trackId = "a", level = MessageLevel.STOP), true, 1_000L)!!
        policy.confirmFeedbackDelivery(first.trackId, 1_000L)
        val suppressed = policy.evaluate(candidate(trackId = "b"), true, 1_800L)!!

        policy.rejectUndeliveredFeedback(suppressed.trackId, 1_800L)

        assertEquals("b", policy.evaluate(candidate(trackId = "b"), true, 2_000L)!!.trackId)
    }

    @Test
    fun activeRiskTracksIncludeOnlyStableTrustedAlertCandidates() {
        val policy = WalkSafeFeedbackPolicy()

        assertEquals(
            setOf("active"),
            policy.activeRiskTrackIds(
                listOf(
                    candidate(trackId = "active"),
                    candidate(trackId = "stale", stale = true),
                    candidate(trackId = "unstable", trackStableMs = 699L),
                    candidate(trackId = "info", level = MessageLevel.INFO),
                ),
                deviceGateAllowsAlerts = true,
            ),
        )
        assertTrue(policy.activeRiskTrackIds(listOf(candidate()), false).isEmpty())
    }

    private fun candidate(
        trackId: String = "track-1",
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        trackAgeFrames: Int = 3,
        trackStableMs: Long = 700L,
        stale: Boolean = false,
        level: MessageLevel = MessageLevel.WARNING,
        message: String = "전방 약 2보 앞 사람. 멈출 준비를 하세요.",
        confidence: Float = 0.80f,
    ): FeedbackCandidate {
        return FeedbackCandidate(
            trackId = trackId,
            message = message,
            level = level,
            source = source,
            confidence = confidence,
            timestampMs = 1_000L,
            trackAgeFrames = trackAgeFrames,
            trackStableMs = trackStableMs,
            stale = stale,
        )
    }
}
