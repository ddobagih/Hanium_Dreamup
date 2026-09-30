package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorRuntimeSupervisorTest {
    @Test
    fun repeatedUnifiedFailureDuringActiveWalkNeverLoadsLegacyLive() {
        val supervisor = owned(maximumConsecutiveFailures = 3)

        assertEquals(
            DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        assertEquals(
            DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        assertEquals(
            DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true),
        )
        supervisor.succeed()
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun legacyCandidateCanOnlyBeSelectedOutsideAnActiveWalk() {
        val supervisor = owned(maximumConsecutiveFailures = 1)

        assertEquals(
            DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK,
            supervisor.onFailure("unified_walksafe", true, activeWalk = false),
        )
    }

    @Test
    fun repeatedLegacyOrNoFallbackFailureBecomesUnavailable() {
        val legacy = owned(maximumConsecutiveFailures = 1)
        val unifiedWithoutFallback = owned(maximumConsecutiveFailures = 1)

        assertEquals(
            DetectorRuntimeFailureAction.DISABLE,
            legacy.onFailure("legacy_two_model", true, activeWalk = false),
        )
        assertEquals(
            DetectorRuntimeFailureAction.DISABLE,
            unifiedWithoutFallback.onFailure("unified_walksafe", false, activeWalk = false),
        )
    }

    @Test
    fun intermittentFailuresCannotBeHiddenByOneSuccessDuringAnActiveWalk() {
        val supervisor = owned(maximumConsecutiveFailures = 3)

        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK))
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK))
        supervisor.succeed()
        assertEquals(0, supervisor.consecutiveFailures)
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK))
        assertEquals(2, supervisor.consecutiveFailures)
    }

    @Test
    fun sparseFailuresAgeOutOfTheRecentOutcomeWindow() {
        val supervisor = owned(maximumConsecutiveFailures = 3)

        repeat(3) {
            assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
                supervisor.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK))
            supervisor.succeed()
            supervisor.succeed()
        }
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun frequentFailuresOutsideAWalkKeepTheExistingFallbackDecision() {
        val supervisor = owned(maximumConsecutiveFailures = 3)

        repeat(2) {
            assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
                supervisor.onFailure("unified_walksafe", true, activeWalk = false, run = 1 to WALK))
        }
        supervisor.succeed()
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = false, run = 1 to WALK))
        assertEquals(DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK,
            supervisor.onFailure("unified_walksafe", true, activeWalk = false, run = 1 to WALK))
    }

    @Test
    fun contractAssetAndNativeCleanupFailuresStopWithoutWaitingForTheCount() {
        val persistent = listOf(
            DetectorTensorContractViolation(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, "input"),
            DetectorAssetIntegrityFailure(DetectorLoadFailureCode.HASH_MISMATCH, "hash"),
            DetectorNativeCleanupFailure(IllegalStateException("cleanup")),
        )
        persistent.forEach { error ->
            assertEquals(DetectorRuntimeFailureKind.NON_RETRYABLE, detectorRuntimeFailureKind(error))
            val supervisor = owned(maximumConsecutiveFailures = 3)
            assertEquals(
                DetectorRuntimeFailureAction.SAFE_STOP,
                supervisor.onFailure("unified_walksafe", true, activeWalk = true,
                    failureKind = detectorRuntimeFailureKind(error), atElapsedRealtimeMs = 1_000L),
            )
            assertEquals(
                DetectorRuntimeFailureAction.DISABLE,
                owned(maximumConsecutiveFailures = 3).onFailure(
                    "unified_walksafe", false, activeWalk = false,
                    failureKind = detectorRuntimeFailureKind(error), atElapsedRealtimeMs = 1_000L,
                ),
            )
        }
        assertEquals(DetectorRuntimeFailureKind.RETRYABLE, detectorRuntimeFailureKind(IllegalStateException("gpu")))
    }

    @Test
    fun retryableFailuresInsideTheFreshnessBoundStillUseTheCount() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 1_000L)
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_250L))
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_500L))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_750L))
    }

    @Test
    fun slowInferenceStopsOnceTheLastValidOutputIsOlderThanTheFreshnessBound() {
        // At a 600 ms cadence the third failure would arrive 1.8 s after the last valid output.
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 1_000L)
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_600L))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 2_200L))
    }

    @Test
    fun failureStreakWithoutAnyValidOutputIsTimedFromItsFirstFailure() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_000L))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 1_900L))
        // A reset without a valid output (stale generation) does not arm the clock by itself.
        val reset = owned(maximumConsecutiveFailures = 3)
        reset.disarmValidOutputClock()
        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            reset.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_000L))
    }

    @Test
    fun disarmStartsANewRunStreakSoALeftoverCountCannotStopTheFirstFailure() {
        // S14-37-a: two failures ended the previous run; the new run's first failure is not the third.
        val supervisor = owned(maximumConsecutiveFailures = 3)
        repeat(2) { index ->
            assertEquals(
                DetectorRuntimeFailureAction.KEEP_CURRENT,
                supervisor.onFailure("unified", legacyFallbackAvailable = false, activeWalk = true, atElapsedRealtimeMs = 100L + index * 100L),
            )
        }
        supervisor.disarmValidOutputClock()
        assertEquals(0, supervisor.consecutiveFailures)
        assertEquals(
            DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified", legacyFallbackAvailable = false, activeWalk = true, atElapsedRealtimeMs = 10_000L),
        )
        // The new run's first failure armed the clock: a streak past 800 ms stops without waiting for the count.
        assertEquals(
            DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified", legacyFallbackAvailable = false, activeWalk = true, atElapsedRealtimeMs = 10_900L),
        )
    }

    @Test
    fun disarmedClockLetsTheFirstFailureOfANewRunUseTheCountAndThenTimesItsStreak() {
        // S14-37-a: the previous run's last output (t=0) is not the new run's freshness origin.
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)
        supervisor.disarmValidOutputClock()

        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_000L))
        // The first failure armed the clock; 900 ms later the streak exceeds the 800 ms bound.
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_900L))
        assertEquals(2, supervisor.consecutiveFailures)
    }

    @Test
    fun withoutDisarmTheOldSuccessStillBoundsTheFirstFailure() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)

        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_000L))
    }

    @Test
    fun aSuccessAfterDisarmIsTheNewFreshnessOrigin() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)
        supervisor.disarmValidOutputClock()
        supervisor.succeed(atElapsedRealtimeMs = 5_000L)

        assertEquals(DetectorRuntimeFailureAction.KEEP_CURRENT,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_500L))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_900L))
    }

    // ---- S14-36: the supervisor itself checks the call's run and pacing ticket ----

    @Test
    fun aLateOutputOfAnEarlierTicketCannotResetTheFailureStreak() {
        val pacing = AdaptiveInferencePacingPolicy()
        var currentGeneration = 1
        val supervisor = DetectorRuntimeSupervisor(
            maximumConsecutiveFailures = 3,
            ownsCurrentWork = { claim -> claim.detectorGeneration == currentGeneration && pacing.ownsSlot(claim.ticket) },
        )
        val lateTicket = requireNotNull(pacing.tryStart(0L))
        // The detector run changes while the earlier call is still running (pacing reset + new generation).
        pacing.reset()
        currentGeneration = 2
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L, run = 2 to WALK))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 200L, run = 2 to WALK))
        val late = supervisor.onDetectorOutput(
            DetectorWorkClaim(1, WALK, lateTicket), AndroidDetectionResult.empty(), 250L, 250L,
        )
        assertFalse(late.freshOutputAccepted)
        assertEquals(2, supervisor.consecutiveFailures)
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 300L, run = 2 to WALK))
    }

    @Test
    fun anOutputOfTheCurrentRunWhoseTicketWasReleasedDoesNotCount() {
        val pacing = AdaptiveInferencePacingPolicy()
        val supervisor = DetectorRuntimeSupervisor(ownsCurrentWork = { claim -> pacing.ownsSlot(claim.ticket) })
        val ticket = requireNotNull(pacing.tryStart(0L))
        assertTrue(pacing.ownsSlot(ticket))
        supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 10L, run = 1 to WALK)
        pacing.cancel(ticket)
        assertFalse(pacing.ownsSlot(ticket))
        assertFalse(supervisor.onDetectorOutput(DetectorWorkClaim(1, WALK, ticket), AndroidDetectionResult.empty(), 20L, 20L).freshOutputAccepted)
        assertEquals(1, supervisor.consecutiveFailures)
        val current = requireNotNull(pacing.tryStart(10_000L))
        assertTrue(supervisor.onDetectorOutput(DetectorWorkClaim(1, WALK, current), AndroidDetectionResult.empty(), 10_000L, 10_010L).freshOutputAccepted)
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun withoutAnOwnershipCheckNoOutputCounts() {
        val supervisor = DetectorRuntimeSupervisor()
        supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 0L, run = 1 to WALK)
        assertFalse(supervisor.onDetectorOutput(claim(), AndroidDetectionResult.empty(), 10L, 10L).freshOutputAccepted)
        assertEquals(1, supervisor.consecutiveFailures)
    }

    @Test
    fun onlyACompletedInferenceIsAValidOutput() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 0L, run = 1 to WALK)
        listOf(AndroidDetectionResult.unavailable(), AndroidDetectionResult.failed()).forEach { notCompleted ->
            assertFalse(supervisor.onDetectorOutput(claim(), notCompleted, 100L, 100L).freshOutputAccepted)
            assertEquals(1, supervisor.consecutiveFailures)
        }
        // An empty completed inference is "nothing detected in this frame", a valid output.
        assertTrue(supervisor.onDetectorOutput(claim(), AndroidDetectionResult.empty(), 150L, 200L).freshOutputAccepted)
        assertEquals(0, supervisor.consecutiveFailures)
    }

    @Test
    fun aNewRunReArmsTheClockOnceAndLaterEventsOfTheRunKeepIt() {
        // S14-37-a, now inside the supervisor: run = detector generation and walk epoch.
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L, run = 1 to WALK)
        // Run 2's first failure is not timed from run 1's output.
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_000L, run = 2 to WALK))
        // Its no-output event keeps the clock that failure armed (not re-armed).
        assertEquals(KEEP, supervisor.onDetectorOutput(claim(run = 2), AndroidDetectionResult.unavailable(), 5_400L, 5_400L).action)
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 5_900L, run = 2 to WALK))
    }

    @Test
    fun aNewRunDoesNotInheritThePreviousRunsFailureWindows() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        val oldRun = 1 to WALK
        val newRun = 2 to WALK
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L, run = oldRun))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 200L, run = oldRun))
        supervisor.succeed(atElapsedRealtimeMs = 300L, run = oldRun)

        // The new run starts its own time and count windows, even while the old events are recent.
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 400L, run = newRun))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 500L, run = newRun))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 600L, run = newRun))
    }

    @Test
    fun explicitDisarmDoesNotCarryThePreviousRunsTimedFailures() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 200L))
        supervisor.disarmValidOutputClock()

        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 300L))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 400L))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 500L))
    }

    // ---- S10-4: F-F-S repetition, old successes only, no success for a long time ----

    @Test
    fun failFailSuccessRepeatedAHundredTimesStopsWithinTheFirstCycles() {
        val untimed = owned(maximumConsecutiveFailures = 3)
        val timed = owned(maximumConsecutiveFailures = 3)
        var untimedStopAt = -1
        var timedStopAt = -1
        var now = 0L
        for (index in 0 until 300) {
            now += 100L
            if (index % 3 == 2) {
                untimed.succeed(atElapsedRealtimeMs = now)
                timed.succeed(atElapsedRealtimeMs = now)
                continue
            }
            if (untimedStopAt < 0 && untimed.onFailure("unified_walksafe", true, activeWalk = true, run = 1 to WALK) == DetectorRuntimeFailureAction.SAFE_STOP) {
                untimedStopAt = index
            }
            if (timedStopAt < 0 &&
                timed.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = now, run = 1 to WALK) == DetectorRuntimeFailureAction.SAFE_STOP
            ) {
                timedStopAt = index
            }
        }
        // Count window: F,F,S,F,F (fourth failure). Time window: F,F,S,F within 3.2 s (third failure).
        assertEquals(4, untimedStopAt)
        assertEquals(3, timedStopAt)
    }

    @Test
    fun oldCapturesOnlyStopOnceTheLastFreshOutputIsOlderThanTheFreshnessBound() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L))
        // Completed, but captured 900 ms earlier: not a fresh output, so it neither resets the streak nor the clock.
        val old = supervisor.onDetectorOutput(claim(), AndroidDetectionResult.empty(), capturedAtElapsedRealtimeMs = -600L, completedAtElapsedRealtimeMs = 300L)
        assertFalse(old.freshOutputAccepted)
        assertEquals(KEEP, old.action)
        assertEquals(1, supervisor.consecutiveFailures)
        assertEquals(KEEP, supervisor.onDetectorOutput(claim(), AndroidDetectionResult.empty(), -300L, 600L).action)
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            supervisor.onDetectorOutput(claim(), AndroidDetectionResult.empty(), 0L, 900L).action)
    }

    @Test
    fun unavailableCallsOnlyDuringAWalkStopAfterTheFreshnessBoundWithoutAddingFailures() {
        val afterSuccess = owned(maximumConsecutiveFailures = 3)
        afterSuccess.succeed(atElapsedRealtimeMs = 0L)
        assertEquals(KEEP, afterSuccess.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 300L, 300L).action)
        assertEquals(KEEP, afterSuccess.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 600L, 600L).action)
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            afterSuccess.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 900L, 900L).action)
        assertEquals(0, afterSuccess.consecutiveFailures)

        // No output at all in the run: the first unavailable call arms the clock, it does not stop at once.
        val neverSucceeded = owned(maximumConsecutiveFailures = 3)
        assertEquals(KEEP, neverSucceeded.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 1_000L, 1_000L).action)
        assertEquals(KEEP, neverSucceeded.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 1_500L, 1_500L).action)
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            neverSucceeded.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), 1_900L, 1_900L).action)

        // Outside an active walk (for example a detector not yet configured) it never stops by itself.
        val notWalking = owned(maximumConsecutiveFailures = 3)
        listOf(0L, 1_000L, 5_000L).forEach { at ->
            assertEquals(KEEP, notWalking.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), at, at, activeWalk = false).action)
        }
    }

    @Test
    fun noOutputEventsDoNotChangeTheCountRulesOfLaterFailures() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)
        repeat(5) { index ->
            val at = 50L + index * 50L
            supervisor.onDetectorOutput(claim(), AndroidDetectionResult.unavailable(), at, at)
        }
        assertEquals(0, supervisor.consecutiveFailures)
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 400L))
        assertEquals(1, supervisor.consecutiveFailures)
    }

    // ---- S14-37: configuration mismatches are not retried by the count ----

    @Test
    fun rawOutputShapeAndLetterboxSizeMismatchesAreNonRetryableContractViolations() {
        val parser = YoloRawOutputParser(
            inputSize = 32, classCount = 1, classNameForId = { "person" }, thresholdForClass = { 0.5f },
        )
        val shape = assertThrows(DetectorTensorContractViolation::class.java) { parser.parse(FloatArray(3)) }
        assertEquals(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, shape.code)
        assertEquals(DetectorRuntimeFailureKind.NON_RETRYABLE, detectorRuntimeFailureKind(shape))
        assertEquals(DetectorRuntimeFailureAction.SAFE_STOP,
            owned(maximumConsecutiveFailures = 3).onFailure("unified_walksafe", true, activeWalk = true,
                failureKind = detectorRuntimeFailureKind(shape), atElapsedRealtimeMs = 1_000L))
        assertEquals(DetectorRuntimeFailureKind.NON_RETRYABLE, detectorRuntimeFailureKind(DetectorValidOutputExpired()))
    }

    // ---- S14-35: time-window failure rate next to the count window ----

    @Test
    fun failuresOlderThanTheTimeWindowAgeOut() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 0L, run = 1 to WALK))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L, run = 1 to WALK))
        var now = 150L
        while (now < 4_000L) {
            supervisor.succeed(atElapsedRealtimeMs = now)
            now += 500L
        }
        // Both windows now hold one failure: the old pair aged out of the 3.2 s window.
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 4_000L))
    }

    @Test
    fun theTimeWindowNeedsAtLeastTheThresholdCountOfFailures() {
        val supervisor = owned(maximumConsecutiveFailures = 3)
        supervisor.succeed(atElapsedRealtimeMs = 0L)
        // Two failures of three outcomes (67%) stay below the count threshold.
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 100L))
        assertEquals(KEEP, supervisor.onFailure("unified_walksafe", true, activeWalk = true, atElapsedRealtimeMs = 200L))
    }

    private companion object {
        val KEEP = DetectorRuntimeFailureAction.KEEP_CURRENT
        const val WALK = "walk-1"

        fun owned(maximumConsecutiveFailures: Int) =
            DetectorRuntimeSupervisor(maximumConsecutiveFailures = maximumConsecutiveFailures, ownsCurrentWork = { true })

        fun claim(run: Int = 1) = DetectorWorkClaim(run, WALK, InferencePacingTicket(0L, 0L))

        /** A current-ticket, completed and fresh inference (the old untimed onSuccess() is now timed). */
        fun DetectorRuntimeSupervisor.succeed(atElapsedRealtimeMs: Long = 0L, run: Pair<Int, String> = 1 to WALK) {
            assertTrue(
                onDetectorOutput(
                    DetectorWorkClaim(run.first, run.second, InferencePacingTicket(0L, 0L)),
                    AndroidDetectionResult.empty(), atElapsedRealtimeMs, atElapsedRealtimeMs,
                ).freshOutputAccepted,
            )
        }
    }
}
