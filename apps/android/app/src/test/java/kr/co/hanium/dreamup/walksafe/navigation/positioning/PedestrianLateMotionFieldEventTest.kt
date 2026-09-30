package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S09-9 (originals/09.txt:34): a late step/ZUPT that was replayed or still refused yields field-log
 * fields with its lag and replayed-event count; an in-order update yields none.
 */
class PedestrianLateMotionFieldEventTest {
    @Test
    fun replayedAndRefusedLateStepsAreMeasuredAndInOrderStepsAreNot() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, 9_000L))
        val inOrder = estimator.observePdrStep(step(9_300L))
        assertNull(inOrder.lateMotionEventFieldsOrNull())

        estimator.observeGnss(GnssPositionObservation(37.5, 127.00001, 3.0, 10_000L))
        val replayed = estimator.observePdrStep(step(9_600L, receivedAt = 10_050L))
        assertEquals(
            mapOf("result" to "REPLAYED", "reason" to null, "measurement_lag_ms" to 400L, "replayed_event_count" to 1),
            replayed.lateMotionEventFieldsOrNull(),
        )

        estimator.observeGnss(GnssPositionObservation(37.5, 127.00002, 3.0, 13_000L))
        val refused = estimator.observePdrStep(step(10_500L, receivedAt = 13_100L))
        assertEquals(
            mapOf(
                "result" to "REJECTED",
                "reason" to PedestrianMotionHardRejectReason.OUT_OF_ORDER.name,
                "measurement_lag_ms" to 2_500L,
                "replayed_event_count" to 0,
            ),
            refused.lateMotionEventFieldsOrNull(),
        )
        // A duplicate of an already applied step is not a late measurement.
        assertNull(estimator.observePdrStep(step(9_600L, receivedAt = 13_200L)).lateMotionEventFieldsOrNull())
    }

    private fun step(at: Long, receivedAt: Long = at) = PdrStepObservation(
        stepCount = 1,
        stepLengthM = 0.65,
        headingDegreesTrueNorth = 90.0,
        headingAccuracyDegrees = 5.0,
        quality = PdrStepQuality.HIGH,
        elapsedRealtimeMs = at,
        receivedAtElapsedRealtimeMs = receivedAt,
    )
}
