package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class WalkingCalibrationDiagnosticsTest {
    @Test
    fun rawCredibilityFailuresRemainDistinctEvenThoughEachProducesNoCalibrationSample() {
        val collector = TimestampAlignedWalkingCalibration()
        listOf(
            fix(0).copy(horizontalAccuracyM = 4.0) to WalkingCalibrationReason.UNSUITABLE_FIX_ACCURACY,
            fix(0).copy(receivedAtElapsedRealtimeMs = 1_501L) to WalkingCalibrationReason.STALE_FIX,
            fix(0).copy(courseAccuracyDegrees = 15.1) to WalkingCalibrationReason.UNSUITABLE_COURSE_ACCURACY,
        ).forEach { (input, reason) ->
            assertTrue(collector.onFix(input).isEmpty())
            assertEquals(decision(WalkingCalibrationStage.RAW_FIX_CREDIBILITY, WalkingCalibrationOutcome.REJECTED, reason),
                collector.diagnosticSnapshot().latestDecision)
        }
        assertEquals(3, collector.diagnosticSnapshot().decisionCounts.size)
        assertTrue(collector.diagnosticSnapshot().decisionCounts.values.all { it == 1L })
    }

    @Test
    fun rawCredibilityReportsTheFirstActuallyEvaluatedMissingOrInvalidField() {
        val policy = WalkingCalibrationSegmentPolicy()
        listOf(
            fix(0).copy(trustedRawFix = false, latitude = Double.NaN) to WalkingCalibrationReason.UNTRUSTED_RAW_FIX,
            fix(0).copy(latitude = Double.NaN, horizontalAccuracyM = null) to WalkingCalibrationReason.INVALID_FIX_COORDINATE,
            fix(0).copy(horizontalAccuracyM = null, speedMps = null) to WalkingCalibrationReason.MISSING_FIX_ACCURACY,
            fix(0).copy(horizontalAccuracyM = -1.0, speedMps = null) to WalkingCalibrationReason.MISSING_SPEED,
            fix(0).copy(speedAccuracyMps = null, courseDegrees = null) to WalkingCalibrationReason.MISSING_SPEED_ACCURACY,
            fix(0).copy(courseDegrees = null, courseAccuracyDegrees = null) to WalkingCalibrationReason.MISSING_COURSE,
            fix(0).copy(courseAccuracyDegrees = null) to WalkingCalibrationReason.MISSING_COURSE_ACCURACY,
        ).forEach { (input, reason) ->
            assertEquals(reason, policy.credibilityRejectionReason(input))
            assertFalse(policy.isCredible(input))
        }
    }

    @Test
    fun waitingForARealStepWatermarkIsNotCountedAsRejection() {
        val collector = TimestampAlignedWalkingCalibration()
        assertTrue(collector.onFix(fix(0)).isEmpty())
        assertEquals(decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.WAITING,
            WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK), collector.diagnosticSnapshot().latestDecision)
        assertTrue(collector.onStep(1_000L, 1, true, 1_200L).isEmpty())
        assertTrue(collector.onStep(1_500L, 1, true, 1_510L).isEmpty())
        val snapshot = collector.diagnosticSnapshot()
        assertEquals(decision(WalkingCalibrationStage.STRAIGHT_SEGMENT, WalkingCalibrationOutcome.WAITING,
            WalkingCalibrationReason.NEW_SEGMENT_ANCHOR), snapshot.latestDecision)
        assertTrue(snapshot.decisionCounts.keys.none { it.outcome == WalkingCalibrationOutcome.REJECTED })
    }

    @Test
    fun counterFallbackAndBatchesKeepTheirDistinctEligibilityFailuresWithoutInventedTimestamps() {
        val collector = TimestampAlignedWalkingCalibration()
        assertTrue(collector.onStep(1_000L, 1, false, 1_010L).isEmpty())
        assertTrue(collector.onStep(2_000L, 4, false, 2_010L).isEmpty())
        assertTrue(collector.onStep(3_000L, 2, true, 3_010L).isEmpty())
        val counts = collector.diagnosticSnapshot().decisionCounts
        assertEquals(2L, counts[decision(WalkingCalibrationStage.STEP_ELIGIBILITY, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.STEP_NOT_INDIVIDUAL)])
        assertEquals(1L, counts[decision(WalkingCalibrationStage.STEP_ELIGIBILITY, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.STEP_NOT_SINGLE)])
        assertEquals(2, counts.size)
    }

    @Test
    fun staleAndFutureStepsKeepTheirOriginalTimingRejections() {
        val collector = TimestampAlignedWalkingCalibration()
        assertTrue(collector.onStep(1_000L, 1, true, 999L).isEmpty())
        assertEquals(WalkingCalibrationReason.INVALID_STEP_TIME, collector.diagnosticSnapshot().latestDecision?.reason)
        assertTrue(collector.onStep(1_000L, 1, true, 3_001L).isEmpty())
        assertEquals(WalkingCalibrationReason.STALE_STEP, collector.diagnosticSnapshot().latestDecision?.reason)
    }

    @Test
    fun duplicateStepAndFixRejectionsSurviveTheirRequiredHistoryResets() {
        val collector = TimestampAlignedWalkingCalibration()
        collector.onStep(750L, 1, true, 760L)
        assertTrue(collector.onStep(750L, 1, true, 800L).isEmpty())
        collector.onFix(fix(0))
        assertTrue(collector.onFix(fix(0)).isEmpty())
        val counts = collector.diagnosticSnapshot().decisionCounts
        assertEquals(1L, counts[decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.STEP_ORDER)])
        assertEquals(1L, counts[decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.FIX_ORDER)])
    }

    @Test
    fun pendingConfirmationExpiryAndRegressedDeliveryHaveDifferentAlignmentReasons() {
        val collector = TimestampAlignedWalkingCalibration()
        collector.onFix(fix(0))
        collector.onStep(3_000L, 1, true, 3_111L)
        assertTrue(collector.onFix(fix(2)).isEmpty())
        val counts = collector.diagnosticSnapshot().decisionCounts
        assertEquals(1L, counts[decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.FIX_CONFIRMATION_EXPIRED)])
        assertEquals(1L, counts[decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.DELIVERY_ORDER)])
    }

    @Test
    fun anEndpointWithNoEarlierIndividualStepIsRejectedRatherThanDeclaredNormallyReady() {
        val collector = TimestampAlignedWalkingCalibration()
        collector.onFix(fix(0))
        assertTrue(collector.onStep(1_250L, 1, true, 1_260L).isEmpty())
        assertEquals(decision(WalkingCalibrationStage.TIMESTAMP_ALIGNMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.MISSING_STEP_BRACKET), collector.diagnosticSnapshot().latestDecision)
    }

    @Test
    fun aNormalStraightWalkSeparatesAnchorAndMinimumDistanceWaitingFromSampleReadiness() {
        val policy = WalkingCalibrationSegmentPolicy()
        assertNull(policy.observe(fix(0), 0))
        assertEquals(WalkingCalibrationReason.NEW_SEGMENT_ANCHOR, policy.lastDecision?.reason)
        for (second in 1..10) assertNull(policy.observe(fix(second), second * 2))
        assertEquals(decision(WalkingCalibrationStage.STRAIGHT_SEGMENT, WalkingCalibrationOutcome.WAITING,
            WalkingCalibrationReason.INSUFFICIENT_DISTANCE), policy.lastDecision)
        val sample = requireNotNull(policy.observe(fix(11), 22))
        assertEquals(15.4, sample.distanceM, 0.001)
        assertEquals(22, sample.stepCount)
        assertEquals(11_000L, sample.durationMs)
        assertEquals(decision(WalkingCalibrationStage.STRAIGHT_SEGMENT, WalkingCalibrationOutcome.SAMPLE_READY,
            WalkingCalibrationReason.STRAIGHT_SEGMENT_READY), policy.lastDecision)
    }

    @Test
    fun segmentInterruptionsRemainRejectionsEvenWhenTheyBeginANewAnchor() {
        val gap = WalkingCalibrationSegmentPolicy()
        gap.observe(fix(0), 0)
        assertNull(gap.observe(fix(3), 6))
        assertEquals(decision(WalkingCalibrationStage.STRAIGHT_SEGMENT, WalkingCalibrationOutcome.REJECTED,
            WalkingCalibrationReason.FIX_GAP), gap.lastDecision)
        assertNull(gap.observe(fix(4), 8))
        assertEquals(WalkingCalibrationOutcome.WAITING, gap.lastDecision?.outcome)
        val regressed = WalkingCalibrationSegmentPolicy()
        regressed.observe(fix(0), 10)
        assertNull(regressed.observe(fix(1), 9))
        assertEquals(WalkingCalibrationReason.STEP_COUNT_REGRESSED, regressed.lastDecision?.reason)
    }

    @Test
    fun realAlignedEventsProduceTheSameSampleWhileProfileWarmupRemainsUncalibrated() {
        val collector = TimestampAlignedWalkingCalibration()
        data class Delivery(val receivedAt: Long, val stepAt: Long? = null, val rawFix: RawWalkingCalibrationFix? = null)
        val deliveries = (750L..12_750L step 500L).map { Delivery(it + 10L, stepAt = it) } +
            (0..11).map { second -> fix(second).let { Delivery(it.receivedAtElapsedRealtimeMs, rawFix = it) } }
        val samples = deliveries.sortedBy { it.receivedAt }.flatMap { delivery ->
            delivery.stepAt?.let { collector.onStep(it, 1, true, delivery.receivedAt) }
                ?: collector.onFix(requireNotNull(delivery.rawFix))
        }
        val sample = samples.single()
        assertEquals(22, sample.stepCount)
        assertEquals(15.4, sample.distanceM, 0.001)
        assertEquals(11_000L, sample.durationMs)
        assertEquals(1L, collector.diagnosticSnapshot().decisionCounts[decision(
            WalkingCalibrationStage.STRAIGHT_SEGMENT, WalkingCalibrationOutcome.SAMPLE_READY,
            WalkingCalibrationReason.STRAIGHT_SEGMENT_READY)])
        val profile = PedestrianMotionProfile()
        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, profile.calibrate(sample).status)
        assertFalse(profile.currentSnapshot().isCalibrated)
    }

    @Test
    fun queriesAreImmutableAndHistoryResetsPreserveCountsUntilAnExplicitScopeReset() {
        val collector = TimestampAlignedWalkingCalibration()
        collector.onFix(fix(0).copy(horizontalAccuracyM = 4.0))
        val captured = collector.diagnosticSnapshot()
        assertEquals(captured, collector.diagnosticSnapshot())
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.decisionCounts as MutableMap<WalkingCalibrationDecision, Long>).clear()
        }
        collector.onStep(1_000L, 1, false, 1_010L)
        assertEquals(1, captured.decisionCounts.size)
        val current = collector.diagnosticSnapshot()
        assertEquals(2, current.decisionCounts.size)
        collector.reset()
        assertEquals(current, collector.diagnosticSnapshot())
        collector.resetForScope()
        assertTrue(collector.diagnosticSnapshot().decisionCounts.isEmpty())
        assertNull(collector.diagnosticSnapshot().latestDecision)
    }

    private fun decision(stage: WalkingCalibrationStage, outcome: WalkingCalibrationOutcome, reason: WalkingCalibrationReason) =
        WalkingCalibrationDecision(stage, outcome, reason)

    private fun fix(second: Int): RawWalkingCalibrationFix {
        val at = 1_000L + second * 1_000L
        return RawWalkingCalibrationFix(
            latitude = 37.5,
            longitude = 127.0 + second * 1.4 / (LocalGeodesy.METERS_PER_DEGREE * cos(Math.toRadians(37.5))),
            horizontalAccuracyM = 1.0,
            elapsedRealtimeMs = at,
            receivedAtElapsedRealtimeMs = at + 100L,
            speedMps = 1.4,
            speedAccuracyMps = 0.1,
            courseDegrees = 90.0,
            courseAccuracyDegrees = 5.0,
            trustedRawFix = true,
        )
    }
}
