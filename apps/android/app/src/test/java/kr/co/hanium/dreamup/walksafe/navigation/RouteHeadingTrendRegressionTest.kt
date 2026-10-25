package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteHeadingTrendRegressionTest {
    private fun sample(heading: Double, at: Long, source: RouteFacingSource = RouteFacingSource.GRAVITY_MAGNETIC) =
        RouteFacingObservation(heading, null, at, source, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
            reportedHeadingAccuracyDegrees = null)
    private fun angle(a: Double, b: Double) = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)

    @Test fun smallWalkingSwayKeepsTheRepresentativeAndOriginalMeasurementSeparate() {
        val trend = RouteHeadingTrend()
        var latest: RouteHeadingTrendEstimate? = null
        val inputs = listOf(82.0, 98.0, 82.0, 98.0)
        inputs.forEachIndexed { i, h -> latest = trend.observe(sample(h, 1000L + i * 100L), 1000L + i * 100L) }
        assertEquals(90.0, latest!!.representativeDegreesTrueNorth, 0.001)
        assertEquals(1300L, latest!!.latestActualObservedAtMs)
        assertEquals(300L, latest!!.windowSpanMs)
        assertEquals(4, latest!!.sampleCount)
        val raw = sample(98.0, 1300L)
        assertEquals(98.0, raw.degreesTrueNorth, 0.0)
        assertNull(raw.headingAccuracyDegrees); assertNull(raw.reportedHeadingAccuracyDegrees)
    }

    @Test fun thirteenHighRateSamplesAcrossTwoHundredMillisecondsCannotConfirmAModerateSpike() {
        for (heading in listOf(60.0, 90.0, 120.0)) {
            val trend = RouteHeadingTrend()
            repeat(7) { i -> trend.observe(sample(0.0, 1_000L + i * 20L), 1_000L + i * 20L) }
            repeat(13) { i ->
                val at = 1_140L + i * 16L
                val spike = requireNotNull(trend.observe(sample(heading, at), at))
                assertTrue(spike.largeTurnPending)
                assertFalse(spike.followsActualLargeTurn)
                assertEquals(0.0, spike.representativeDegreesTrueNorth, 0.001)
            }
            val recovered = requireNotNull(trend.observe(sample(0.0, 1_350L), 1_350L))
            assertFalse(recovered.largeTurnPending)
            assertFalse(recovered.followsActualLargeTurn)
            assertEquals(0.0, recovered.representativeDegreesTrueNorth, 0.001)
        }
    }

    @Test fun duplicateSensorReceiptsCannotConfirmAModerateTurn() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(0.0, 1_000L), 1_000L)
        val candidate = requireNotNull(trend.observe(sample(90.0, 1_100L), 1_100L))
        repeat(20) {
            assertEquals(candidate, trend.observe(sample(90.0, 1_100L), 1_100L + it * 10L))
        }
        assertTrue(trend.observe(sample(90.0, 1_399L), 1_399L)!!.largeTurnPending)
        assertTrue(trend.observe(sample(90.0, 1_400L), 1_400L)!!.followsActualLargeTurn)
    }

    @Test fun inconsistentModerateSamplesRestartConfirmationButARealReversalIsImmediate() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(0.0, 1_000L), 1_000L)
        assertTrue(trend.observe(sample(60.0, 1_100L), 1_100L)!!.largeTurnPending)
        assertTrue(trend.observe(sample(120.0, 1_300L), 1_300L)!!.largeTurnPending)
        assertTrue(trend.observe(sample(120.0, 1_400L), 1_400L)!!.largeTurnPending)
        val reverse = requireNotNull(trend.observe(sample(180.0, 1_450L), 1_450L))
        assertTrue(reverse.followsActualLargeTurn)
        assertFalse(reverse.largeTurnPending)
        assertEquals(180.0, reverse.representativeDegreesTrueNorth, 0.001)
    }

    @Test fun aRealTurnConfirmsAtSlowButStillFreshSensorCadences() {
        for (cadence in listOf(301L, 400L, 500L)) {
            val trend = RouteHeadingTrend()
            trend.observe(sample(0.0, 1_000L), 1_000L)
            trend.observe(sample(0.0, 1_000L + cadence), 1_000L + cadence)
            val firstAt = 1_000L + 2L * cadence
            assertTrue(trend.observe(sample(90.0, firstAt), firstAt)!!.largeTurnPending)
            val confirmed = requireNotNull(trend.observe(sample(90.0, firstAt + cadence), firstAt + cadence))
            assertFalse("cadence=$cadence", confirmed.largeTurnPending)
            assertTrue(confirmed.followsActualLargeTurn)
            assertEquals(90.0, confirmed.representativeDegreesTrueNorth, 0.001)
            val settled = requireNotNull(trend.observe(sample(90.0, firstAt + 2L * cadence), firstAt + 2L * cadence))
            assertFalse(settled.largeTurnPending)
            assertEquals(90.0, settled.representativeDegreesTrueNorth, 0.001)
        }
    }

    @Test fun northWrapStaysNearNorthRatherThanAveragingIntoSouth() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(359.0, 1000L), 1000L)
        val result = trend.observe(sample(1.0, 1100L), 1100L)!!
        assertTrue(angle(result.representativeDegreesTrueNorth, 0.0) < 0.001)
    }

    @Test fun aNinetyDegreeTurnNeedsAnActualTimeSpanWhileOneEightyImmediatelyDiscardsOldSway() {
        val trend = RouteHeadingTrend()
        for ((i, heading) in listOf(358.0, 2.0, 358.0, 2.0).withIndex()) {
            trend.observe(sample(heading, 1000L + i * 100L), 1000L + i * 100L)
        }
        val turn = trend.observe(sample(90.0, 1400L), 1400L)!!
        assertTrue(turn.largeTurnPending)
        assertEquals(0.0, turn.representativeDegreesTrueNorth, 0.001)
        assertEquals(1400L, turn.latestActualObservedAtMs)
        assertEquals(4, turn.sampleCount); assertEquals(300L, turn.windowSpanMs)
        assertEquals(turn, trend.observe(sample(90.0, 1400L), 1450L))
        assertTrue(trend.observe(sample(92.0, 1500L), 1500L)!!.largeTurnPending)
        assertTrue(trend.observe(sample(92.0, 1600L), 1600L)!!.largeTurnPending)
        val sustained = trend.observe(sample(92.0, 1700L), 1700L)!!
        assertEquals(92.0, sustained.representativeDegreesTrueNorth, 0.001)
        assertFalse(sustained.largeTurnPending)
        assertTrue(sustained.followsActualLargeTurn)
        assertEquals(1, sustained.sampleCount); assertEquals(0L, sustained.windowSpanMs)
        val reverse = trend.observe(sample(270.0, 1800L), 1800L)!!
        assertEquals(270.0, reverse.representativeDegreesTrueNorth, 0.0)
        assertEquals(1, reverse.sampleCount)
    }

    @Test fun continuousNinetyDegreeTurnThenRealSteadySamplesLeaveTheOldMeanBehind() {
        val trend = RouteHeadingTrend()
        repeat(7) { i -> trend.observe(sample(0.0, 1000L + i * 100L), 1000L + i * 100L) }
        var turn: RouteHeadingTrendEstimate? = null
        for (step in 1..9) turn = trend.observe(sample(step * 10.0, 1600L + step * 100L), 1600L + step * 100L)
        assertTrue(turn!!.followsActualLargeTurn)
        val settled = trend.observe(sample(90.0, 2600L), 2600L)!!
        assertEquals(90.0, settled.representativeDegreesTrueNorth, 0.001)
        assertEquals(1, settled.sampleCount)
        val next = trend.observe(sample(90.0, 2700L), 2700L)!!
        assertEquals(90.0, next.representativeDegreesTrueNorth, 0.001)
        assertEquals(2, next.sampleCount)
    }

    @Test fun aLargePartialSwayWaitsForItsWindowWithoutPretendingItIsARealTurn() {
        val trend = RouteHeadingTrend()
        var last: RouteHeadingTrendEstimate? = null
        for (i in 0..25) {
            val at = 1000L + i * 20L
            last = trend.observe(sample((360.0 + 25.0 * kotlin.math.sin(2.0 * Math.PI * i * 20.0 / 1000.0)) % 360.0, at), at)
        }
        assertFalse(last!!.followsActualLargeTurn)
        assertEquals(500L, last!!.windowSpanMs)
        val raw = sample(0.0, 1500L).copy(headingTrend = last)
        assertNull(RouteFacingGuidance.evaluate(0.0, raw, 1540L).clockHour)
        assertFalse(RouteFacingGuidance.evaluate(0.0, raw, 1540L).hasClockReference)
    }

    @Test fun duplicateAndOlderSamplesCannotExtendTheHistoryOrItsTime() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(0.0, 1000L), 1000L)
        val good = trend.observe(sample(2.0, 1100L), 1100L)!!
        assertNull(trend.observe(sample(180.0, 1050L), 1150L))
        val repeated = trend.observe(sample(2.0, 1100L), 1200L)!!
        assertEquals(good, repeated)
        assertEquals(2, repeated.sampleCount); assertEquals(1100L, repeated.latestActualObservedAtMs)
    }

    @Test fun aBadSpikeCannotBecomeTheNewRepresentative() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(0.0, 1000L), 1000L)
        val bad = RouteFacingObservation(180.0, 31.0, 1100L)
        assertNull(trend.observe(bad, 1100L))
        val recovered = trend.observe(sample(1.0, 1200L), 1200L)!!
        assertEquals(1.0, recovered.representativeDegreesTrueNorth, 0.001)
        assertEquals(1, recovered.sampleCount)
    }

    @Test fun sourceChangeGapAndExplicitOwnerResetCannotCarryOldHistory() {
        val trend = RouteHeadingTrend()
        trend.observe(sample(0.0, 1000L), 1000L)
        trend.observe(sample(2.0, 1100L), 1100L)
        val changed = trend.observe(sample(2.0, 1200L, RouteFacingSource.ACCELEROMETER_MAGNETIC), 1200L)!!
        assertEquals(1, changed.sampleCount)
        val afterGap = trend.observe(sample(4.0, 1701L, RouteFacingSource.ACCELEROMETER_MAGNETIC), 1701L)!!
        assertEquals(1, afterGap.sampleCount)
        trend.reset()
        assertEquals(1, trend.observe(sample(5.0, 1800L), 1800L)!!.sampleCount)
    }

    @Test fun currentAnomalyStaleFutureAndRollbackCannotSupplyARepresentative() {
        for ((observation, now) in listOf(
            sample(0.0, 1000L).copy(magneticFieldSuspect = true) to 1000L,
            sample(0.0, 1000L) to 1501L, sample(0.0, 1100L) to 1000L,
            sample(0.0, -1L) to 1000L)) {
            assertNull(RouteHeadingTrend().observe(observation, now))
        }
    }

    @Test fun windowIsBoundedByRealSampleTimesAndSevenEntries() {
        val trend = RouteHeadingTrend(); var result: RouteHeadingTrendEstimate? = null
        repeat(20) { i -> result = trend.observe(sample(0.0, 1000L + i * 100L), 1000L + i * 100L) }
        assertEquals(7, result!!.sampleCount); assertEquals(600L, result!!.windowSpanMs)
        assertEquals(2900L, result!!.latestActualObservedAtMs)
    }

    @Test fun nonDividingSensorCadencesMatureWithoutChangingTheActualRetainedSpan() {
        for (cadence in listOf(13L, 19L, 21L, 23L)) {
            val trend = RouteHeadingTrend(); var latest: RouteHeadingTrendEstimate? = null
            var elapsed = 0L
            while (elapsed <= 1500L) {
                latest = trend.observe(sample((360.0 + 20.0 * kotlin.math.sin(2.0 * Math.PI * elapsed / 1000.0)) % 360.0,
                    1000L + elapsed), 1000L + elapsed)
                elapsed += cadence
            }
            assertTrue(latest!!.hasCompleteTimeWindow)
            assertTrue(latest!!.windowSpanMs < 600L)
            assertEquals(latest!!.sampleCount.toLong() - 1L, latest!!.windowSpanMs / cadence)
            assertEquals(1000L + elapsed - cadence, latest!!.latestActualObservedAtMs)
        }
    }

    @Test fun aOneMillisecondStreamKeepsTheWholeRealTimeWindow() {
        val trend = RouteHeadingTrend(); var latest: RouteHeadingTrendEstimate? = null
        for (elapsed in 0L..1500L) {
            latest = trend.observe(sample((360.0 + 5.0 * kotlin.math.sin(2.0 * Math.PI * elapsed / 1000.0)) % 360.0,
                1000L + elapsed), 1000L + elapsed)
        }
        assertEquals(601, latest!!.sampleCount)
        assertEquals(600L, latest!!.windowSpanMs)
        assertEquals(2500L, latest!!.latestActualObservedAtMs)
        assertTrue(latest!!.hasCompleteTimeWindow)
    }

    @Test fun malformedTrendCannotRelabelAnOldRawMeasurementAsFresh() {
        val raw = sample(0.0, 1000L)
        val fake = RouteHeadingTrendEstimate(180.0, 1100L, 200L, 3, raw.source)
        val result = RouteFacingGuidance.evaluate(0.0, raw.copy(headingTrend = fake), 1000L)
        assertEquals(RouteFacingDirection.FRONT, result.direction)
        assertFalse(fake.isBoundTo(raw))
        assertNull(RouteFacingGuidance.evaluate(0.0, raw.copy(headingTrend = fake), 1501L).clockHour)
    }
}
