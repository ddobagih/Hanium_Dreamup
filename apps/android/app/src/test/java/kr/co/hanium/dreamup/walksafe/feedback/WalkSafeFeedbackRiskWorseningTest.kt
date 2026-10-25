package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.*
import org.junit.Test

class WalkSafeFeedbackRiskWorseningTest {
    @Test fun stableMetricTtcBandWorseningReplaysTheSameStopBeforeTrackCooldown() {
        for (source in listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH, DepthSource.WEBXR_DEPTH)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = 8_000L, distance = 1.1f, source = source), 1_000L)
            val worse = risk(1_800L, ttc = 2_000L, distance = 1.1f, source = source)
            val action = requireNotNull(policy.evaluate(worse, true, 1_800L)) { "source=$source" }
            assertEquals(MessageLevel.STOP, action.level)
            assertEquals(worse.validUntilMs, action.validUntilMs)
            assertTrue(policy.claimFeedbackDelivery("risk", 1_800L, 1_800L))
            assertTrue(policy.confirmFeedbackDelivery("risk", 1_800L))
            assertNull(policy.evaluate(risk(2_600L, ttc = 2_000L, distance = 1.1f, source = source), true, 2_600L))
        }
    }

    @Test fun ordinaryStableFullDepthCanEnterSubsecondTtcBandOutsideRawFastPathDistance() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 900L, distance = 1.1f, source = DepthSource.ARCORE_FULL_DEPTH), 1_000L)
        assertNotNull(policy.evaluate(risk(1_800L, ttc = 300L, distance = 1.1f,
            source = DepthSource.ARCORE_FULL_DEPTH), true, 1_800L))
    }

    @Test fun sameStopEnteringCloserDistanceBandNeedsMoreThanDistanceHysteresis() {
        for ((previous, current) in listOf(1.6f to 1.1f, 1.2f to .8f)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = 1_000L, distance = previous), 1_000L)
            assertNotNull("$previous -> $current", policy.evaluate(risk(1_800L, ttc = 1_000L, distance = current), true, 1_800L))
        }
    }

    @Test fun boundaryJitterAndLargeChangesWithinTheSameRiskBandCannotReplay() {
        for ((previous, current) in listOf(30_001L to 30_000L, 10_001L to 10_000L,
                3_001L to 3_000L, 301L to 300L, 2_000L to 400L)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = previous, distance = 1.1f), 1_000L)
            assertNull("TTC $previous -> $current", policy.evaluate(risk(1_800L, ttc = current, distance = 1.1f), true, 1_800L))
        }
        for ((previous, current) in listOf(3.01f to 2.99f, 2.51f to 2.49f,
                1.21f to 1.19f, .91f to .89f, .8f to .4f)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = 1_000L, distance = previous), 1_000L)
            assertNull("distance $previous -> $current", policy.evaluate(risk(1_800L, ttc = 1_000L, distance = current), true, 1_800L))
        }
    }

    @Test fun unreliableNonCurrentOrUnstableEvidenceCannotClaimWorsening() {
        val good = risk(1_800L, ttc = 2_000L, distance = 1.1f)
        val bad = listOf(good.copy(confidence = .54f), good.copy(confidence = Float.NaN),
            good.copy(confidence = Float.POSITIVE_INFINITY), good.copy(stale = true),
            good.copy(validUntilMs = 1_799L), good.copy(validUntilMs = Long.MAX_VALUE),
            good.copy(validUntilMs = 1_800L), good.copy(timestampMs = 51_000L),
            good.copy(trackAgeFrames = 2), good.copy(trackStableMs = 699L),
            good.copy(timeToCollisionMs = null), good.copy(timeToCollisionMs = -1L),
            good.copy(riskDistanceM = Float.NaN), good.copy(riskDistanceM = 0f),
            good.copy(source = DepthSource.ARCORE_FULL_DEPTH),
            good.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH))
        for (candidate in bad) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = 8_000L, distance = 1.1f), 1_000L)
            assertNull("$candidate", policy.evaluate(candidate, true, 1_800L))
        }
        val full = WalkSafeFeedbackPolicy()
        deliver(full, risk(1_000L, ttc = 8_000L, distance = 1.1f, source = DepthSource.ARCORE_FULL_DEPTH), 1_000L)
        assertNull(full.evaluate(good.copy(source = DepthSource.ARCORE_FULL_DEPTH, confidence = .59f), true, 1_800L))
    }

    @Test fun successfulDeliveryTimeStillControlsGlobalSpacingIncludingOtherTracks() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 8_000L, distance = 1.1f), 1_000L, 4_000L)
        assertNull(policy.evaluate(risk(4_699L, ttc = 2_000L, distance = 1.1f), true, 4_699L))
        assertNotNull(policy.evaluate(risk(4_700L, ttc = 2_000L, distance = 1.1f), true, 4_700L))

        val another = WalkSafeFeedbackPolicy()
        deliver(another, risk(1_000L, ttc = 8_000L, distance = 1.1f), 1_000L)
        deliver(another, risk(1_800L, ttc = 8_000L, distance = 1.1f).copy(trackId = "other"), 1_800L)
        assertNull(another.evaluate(risk(2_000L, ttc = 2_000L, distance = 1.1f), true, 2_000L))
        assertNotNull(another.evaluate(risk(2_500L, ttc = 2_000L, distance = 1.1f), true, 2_500L))
    }

    @Test fun failedCancelledAndExpiredRetriesDoNotConsumeTheLastSuccessfulRiskBaseline() {
        for (mode in listOf("reject", "cancel", "expired")) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, ttc = 8_000L, distance = 1.1f), 1_000L)
            val worse = risk(1_800L, ttc = 2_000L, distance = 1.1f).copy(validUntilMs = 1_900L)
            assertNotNull(policy.evaluate(worse, true, 1_800L))
            when (mode) {
                "reject" -> { assertTrue(policy.claimFeedbackDelivery("risk", 1_800L, 1_800L)); policy.rejectUndeliveredFeedback("risk", 1_800L) }
                "cancel" -> policy.cancelPendingFeedbackDeliveries()
                else -> assertFalse(policy.claimFeedbackDelivery("risk", 1_800L, 1_901L))
            }
            assertFalse(policy.confirmFeedbackDelivery("risk", 1_800L, 1_901L))
            assertNull(policy.evaluate(risk(2_000L, ttc = 8_000L, distance = 1.1f), true, 2_000L))
            assertNotNull("mode=$mode", policy.evaluate(risk(2_100L, ttc = 2_000L, distance = 1.1f), true, 2_100L))
        }
    }

    @Test fun reservationsAndCrossSourceHistoryDoNotManufactureCompletedRiskEvidence() {
        val policy = WalkSafeFeedbackPolicy()
        assertNotNull(policy.evaluate(risk(1_000L, ttc = 8_000L, distance = 1.1f), true, 1_000L))
        assertNull(policy.evaluate(risk(1_800L, ttc = 2_000L, distance = 1.1f), true, 1_800L))
        assertTrue(policy.confirmFeedbackDelivery("risk", 1_000L, 1_800L))
        assertNull(policy.evaluate(risk(2_400L, ttc = 2_000L, distance = 1.1f), true, 2_400L))
        assertNotNull(policy.evaluate(risk(2_500L, ttc = 2_000L, distance = 1.1f), true, 2_500L))
        policy.cancelPendingFeedbackDeliveries()
        policy.carryCompletedFeedbackHistory("risk", "target")
        assertNull(policy.evaluate(risk(2_500L, ttc = 2_000L, distance = 1.1f).copy(trackId = "target"), true, 2_500L))
    }

    @Test fun warningRiskCanWorsenByTtcOrDistanceWithoutChangingItsLevel() {
        for ((previousTtc, nextTtc, previousDistance, nextDistance) in listOf(
            listOf(20_000f, 9_000f, 2.4f, 2.4f), listOf(8_000f, 8_000f, 2.9f, 2.4f))) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, previousTtc.toLong(), previousDistance)
                .copy(level = MessageLevel.WARNING, confidence = .65f), 1_000L)
            assertEquals(MessageLevel.WARNING, policy.evaluate(risk(1_800L, nextTtc.toLong(), nextDistance)
                .copy(level = MessageLevel.WARNING, confidence = .65f), true, 1_800L)?.level)
        }
    }

    @Test fun halvingAndDistanceMarginsHaveExplicitBoundariesAndZeroTtcIsValid() {
        for ((current, expected) in listOf(2_501L to false, 2_500L to true, 2_499L to true, 0L to true)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, 5_000L, 1.1f), 1_000L)
            assertEquals("TTC5000 -> $current", expected,
                policy.evaluate(risk(1_800L, current, 1.1f), true, 1_800L) != null)
        }
        for ((current, expected) in listOf(1.0001f to false, 1f to true, .9999f to true)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, null, 1.25f), 1_000L)
            assertEquals("distance1.25 -> $current", expected,
                policy.evaluate(risk(1_800L, null, current), true, 1_800L) != null)
        }
        for (previous in listOf(null, -1L, 0L)) {
            val policy = WalkSafeFeedbackPolicy()
            deliver(policy, risk(1_000L, previous, 1.1f), 1_000L)
            assertNull(policy.evaluate(risk(1_800L, 0L, 1.1f), true, 1_800L))
        }
    }

    @Test fun lowerOtherTrackCannotTurnSameStopWorseningIntoGlobalEscalation() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, 8_000L, 1.1f), 1_000L)
        deliver(policy, risk(1_800L, null, 2f).copy(trackId = "other", level = MessageLevel.CAUTION), 1_800L)
        assertNull(policy.evaluate(risk(1_900L, 2_000L, 1.1f), true, 1_900L))
        assertNotNull(policy.evaluate(risk(2_500L, 2_000L, 1.1f), true, 2_500L))
    }

    @Test fun improvingThenReturningToDeliveredRiskDoesNotResetTheWorseningBaseline() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, 8_000L, 1.1f), 1_000L)
        deliver(policy, risk(1_800L, 2_000L, 1.1f), 1_800L)
        assertNull(policy.evaluate(risk(2_600L, 8_000L, 1.1f), true, 2_600L))
        assertNull(policy.evaluate(risk(2_700L, 2_000L, 1.1f), true, 2_700L))
        // Further entry into the existing imminent band is an independent worsening event.
        assertNotNull(policy.evaluate(risk(2_800L, 300L, 1.1f), true, 2_800L))
    }

    @Test fun weakPreviousSuccessDoesNotBecomeATrustedComparison() {
        val previous = risk(1_000L, 8_000L, 1.1f, DepthSource.ARCORE_FULL_DEPTH).copy(confidence = .59f)
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, previous, 1_000L)
        assertNull(policy.evaluate(risk(1_800L, 2_000L, 1.1f, previous.source), true, 1_800L))
    }

    @Test fun unleasedObservationCannotEstablishCompletedHistoryOrBlockTheFirstValidRisk() {
        val policy = WalkSafeFeedbackPolicy()
        val unleased = risk(1_000L, 8_000L, 1.1f).copy(validUntilMs = Long.MAX_VALUE)
        assertNull(policy.evaluate(unleased, true, 1_000L))
        assertFalse(policy.claimFeedbackDelivery(unleased.trackId, 1_000L, 1_000L))
        assertFalse(policy.confirmFeedbackDelivery(unleased.trackId, 1_000L, 1_000L))
        assertNotNull(policy.evaluate(risk(1_800L, 2_000L, 1.1f), true, 1_800L))
    }

    @Test fun carryingNewerHistoryInvalidatesTheTargetsOlderRiskSnapshot() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, 8_000L, 1.1f).copy(trackId = "target"), 1_000L)
        deliver(policy, risk(1_800L, 8_000L, 1.1f).copy(trackId = "source"), 1_800L)
        policy.carryCompletedFeedbackHistory("source", "target")
        assertNull(policy.evaluate(risk(2_500L, 2_000L, 1.1f).copy(trackId = "target"), true, 2_500L))
        policy.resetForNewWalk()
        assertNotNull(policy.evaluate(risk(2_501L, 2_000L, 1.1f).copy(trackId = "target"), true, 2_501L))
    }

    @Test fun regularRepeatAndActualSeverityEscalationKeepTheirExistingGlobalBehavior() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, 8_000L, 1.1f), 1_000L)
        deliver(policy, risk(3_600L, null, 2f).copy(trackId = "other", level = MessageLevel.CAUTION), 3_600L)
        assertNotNull(policy.evaluate(risk(3_700L, 2_000L, 1.1f), true, 3_700L))

        val severity = WalkSafeFeedbackPolicy()
        deliver(severity, risk(1_000L, 8_000L, 2.4f).copy(level = MessageLevel.WARNING), 1_000L)
        assertEquals(MessageLevel.STOP, severity.evaluate(risk(1_100L, 2_000L, 1.1f), true, 1_100L)?.level)
    }

    @Test fun oppositeJitterAfterAReplayCannotKeepBypassingTheTrackCooldown() {
        // Stop by distance, then stop by TTC, alternating: only the first alternation is new risk.
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 3_100L, distance = 1.05f), 1_000L)
        deliver(policy, risk(1_700L, ttc = 1_500L, distance = 1.35f), 1_700L)
        assertNull(policy.evaluate(risk(2_400L, ttc = 3_100L, distance = 1.05f), true, 2_400L))
        assertNull(policy.evaluate(risk(3_100L, ttc = 1_500L, distance = 1.35f), true, 3_100L))
        assertNull(policy.evaluate(risk(3_800L, ttc = 3_100L, distance = 1.05f), true, 3_800L))
        // The ordinary per-track repeat after 2.5 s is unchanged and starts a new episode baseline.
        deliver(policy, risk(4_200L, ttc = 1_500L, distance = 1.35f), 4_200L)
        assertNotNull(policy.evaluate(risk(4_900L, ttc = 3_100L, distance = 1.05f), true, 4_900L))
    }

    @Test fun oppositeJitterStartingFromTheTtcSideIsAlsoBoundedToOneReplay() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 1_500L, distance = 1.35f), 1_000L)
        deliver(policy, risk(1_700L, ttc = 3_100L, distance = 1.05f), 1_700L)
        assertNull(policy.evaluate(risk(2_400L, ttc = 1_500L, distance = 1.35f), true, 2_400L))
        assertNull(policy.evaluate(risk(3_100L, ttc = 3_100L, distance = 1.05f), true, 3_100L))
    }

    @Test fun escalationToStopInsideTheWindowStartsAFreshStopBaseline() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 1_500L, distance = 2.4f).copy(level = MessageLevel.WARNING), 1_000L)
        deliver(policy, risk(1_200L, ttc = 20_000L, distance = 1.1f), 1_200L)
        // The WARNING-time TTC must not mask a later STOP-band worsening.
        assertEquals(MessageLevel.STOP, policy.evaluate(risk(1_900L, ttc = 4_000L, distance = 1.1f), true, 1_900L)?.level)
    }

    @Test fun laterSuccessWithoutTrustedMetricEvidenceDropsTheOlderBaseline() {
        val policy = WalkSafeFeedbackPolicy()
        deliver(policy, risk(1_000L, ttc = 8_000L, distance = 1.1f), 1_000L)
        deliver(policy, risk(3_600L, ttc = 8_000L, distance = 1.1f, source = DepthSource.ARCORE_FULL_DEPTH)
            .copy(confidence = .59f), 3_600L)
        assertNull(policy.evaluate(risk(4_400L, ttc = 2_000L, distance = 1.1f), true, 4_400L))
    }

    private fun deliver(policy: WalkSafeFeedbackPolicy, candidate: FeedbackCandidate, at: Long, complete: Long = at) {
        assertNotNull(policy.evaluate(candidate, true, at))
        assertTrue(policy.claimFeedbackDelivery(candidate.trackId, at, at))
        assertTrue(policy.confirmFeedbackDelivery(candidate.trackId, at, complete))
    }

    private fun risk(at: Long, ttc: Long?, distance: Float, source: DepthSource = DepthSource.ARCORE_RAW_DEPTH) = FeedbackCandidate(
        trackId = "risk", message = "위험합니다. 멈추세요.", level = MessageLevel.STOP,
        source = source, confidence = when (source) {
            DepthSource.ARCORE_FULL_DEPTH -> .75f
            DepthSource.WEBXR_DEPTH -> .65f
            else -> .9f
        }, timestampMs = at + 50_000L,
        trackAgeFrames = 20, trackStableMs = 4_000L, stale = false,
        validUntilMs = at + 800L, timeToCollisionMs = ttc, riskDistanceM = distance,
        observedAtElapsedRealtimeMs = at, observationMaxAgeMs = 800L,
    )
}
