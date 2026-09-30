package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** S09-9: a step/ZUPT delivered shortly after a newer GNSS fix is not lost; older ones are counted. */
class PedestrianPositionEstimatorLateMotionTest {
    @Test
    fun stepArrivingAfterANewerFixMatchesTimeOrderedProcessing() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observePdrStep(step(9_600L))
        ordered.observeGnss(gnss(10_000L))

        delayed.observeGnss(gnss(10_000L))
        val late = delayed.observePdrStep(step(9_600L, receivedAt = 10_050L))

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, late.disposition)
        assertEquals(400L, late.measurementLagMs)
        assertEquals(1, late.replayedEventCount)
        assertEquivalent(ordered, delayed, 10_000L)
        assertEquals(LateMotionStatistics(replayedCount = 1L, maximumReplayedLagMs = 400L), delayed.lateMotionStatistics())
    }

    @Test
    fun zuptArrivingAfterANewerFixMatchesTimeOrderedProcessing() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observePdrStep(step(9_200L))
        ordered.observeZupt(ZuptObservation(9_700L))
        ordered.observeGnss(gnss(10_000L))

        delayed.observePdrStep(step(9_200L))
        delayed.observeGnss(gnss(10_000L))
        val late = delayed.observeZupt(ZuptObservation(9_700L, receivedAtElapsedRealtimeMs = 10_100L))

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, late.disposition)
        assertEquivalent(ordered, delayed, 10_000L)
    }

    @Test
    fun motionLaterThanTheReplayWindowIsStillRefusedAndCounted() {
        val estimator = initialized()
        estimator.observeGnss(gnss(10_000L))
        val covariance = estimator.covarianceSnapshot()

        val late = estimator.observePdrStep(step(7_000L, receivedAt = 10_100L))

        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, late.hardRejectReason)
        assertEquals(3_000L, late.measurementLagMs)
        assertArrayEquals(covariance, estimator.covarianceSnapshot(), 0.0)
        assertEquals(LateMotionStatistics(rejectedCount = 1L, maximumRejectedLagMs = 3_000L), estimator.lateMotionStatistics())
    }

    @Test
    fun replayedLateStepKeepsDuplicateGuards() {
        val estimator = initialized()
        estimator.observeGnss(gnss(10_000L))
        estimator.observePdrStep(step(9_600L, receivedAt = 10_050L))
        val covariance = estimator.covarianceSnapshot()

        val duplicate = estimator.observePdrStep(step(9_600L, receivedAt = 10_060L))

        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, duplicate.hardRejectReason)
        assertArrayEquals(covariance, estimator.covarianceSnapshot(), 0.0)
    }

    @Test
    fun lateGnssStillReplaysAfterAnEarlierLateStepReplay() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observePdrStep(step(9_600L))
        ordered.observeGnss(gnss(10_000L))
        ordered.observePdrStep(step(10_400L))
        ordered.observeGnss(gnss(10_600L))

        delayed.observeGnss(gnss(10_000L))
        delayed.observePdrStep(step(9_600L, receivedAt = 10_050L))
        delayed.observePdrStep(step(10_400L))
        delayed.observeGnss(gnss(10_600L))

        assertEquivalent(ordered, delayed, 10_600L)
    }

    private fun initialized() = PedestrianPositionEstimator().also {
        it.observeGnss(gnss(9_000L, longitude = 127.0))
    }

    private fun gnss(time: Long, longitude: Double = 127.00002) =
        GnssPositionObservation(37.0, longitude, 3.0, time, time)

    private fun step(time: Long, receivedAt: Long = time) = PdrStepObservation(
        stepCount = 1,
        stepLengthM = 0.65,
        headingDegreesTrueNorth = 90.0,
        headingAccuracyDegrees = 5.0,
        quality = PdrStepQuality.HIGH,
        elapsedRealtimeMs = time,
        receivedAtElapsedRealtimeMs = receivedAt,
    )

    private fun assertEquivalent(expected: PedestrianPositionEstimator, actual: PedestrianPositionEstimator, time: Long) {
        assertEquals(expected.estimateAt(time), actual.estimateAt(time))
        assertArrayEquals(expected.covarianceSnapshot(), actual.covarianceSnapshot(), 1e-12)
    }
}
