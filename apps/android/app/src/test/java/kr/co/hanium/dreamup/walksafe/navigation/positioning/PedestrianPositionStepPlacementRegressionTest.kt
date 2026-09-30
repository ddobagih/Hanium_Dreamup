package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * S09-10 follow-up (w25 review pos-F1, pos-F4): placing interior GNSS fixes on a step
 * ([PedestrianPositionEstimator] step-interval placement) must not make the result depend on the arrival order
 * of a ZUPT inside that step interval (originals/09.txt:171 "수신 순서별 일치"), and a step that is rejected after
 * the placement must leave no placement behind.
 */
class PedestrianPositionStepPlacementRegressionTest {
    @Test
    fun aZuptInsideAStepIntervalGivesTheSameResultInOrderAndWhenItArrivesAfterTheStep() {
        fun run(lateZupt: Boolean): EstimatedLocation {
            val estimator = PedestrianPositionEstimator()
            estimator.observeGnss(fix(0.0, 1_000L))
            estimator.observePdrStep(step(1_556L))
            estimator.observeGnss(fix(1.2, 2_000L))
            if (!lateZupt) estimator.observeZupt(ZuptObservation(2_050L))
            assertEquals(PedestrianMotionUpdateDisposition.APPLIED, estimator.observePdrStep(step(2_111L)).disposition)
            if (lateZupt) {
                val late = estimator.observeZupt(ZuptObservation(2_050L, receivedAtElapsedRealtimeMs = 2_150L))
                assertEquals(PedestrianMotionUpdateDisposition.APPLIED, late.disposition)
                assertEquals(61L, late.measurementLagMs)
            }
            return requireNotNull(estimator.estimateAt(2_111L))
        }
        val inOrder = run(lateZupt = false)
        val late = run(lateZupt = true)
        assertEquals("east in order vs late ZUPT", east(inOrder), east(late), ORDER_TOLERANCE_M)
        assertEquals("north in order vs late ZUPT", north(inOrder), north(late), ORDER_TOLERANCE_M)
        assertEquals(inOrder.horizontalUncertaintyM, late.horizontalUncertaintyM, ORDER_TOLERANCE_M)
    }

    @Test
    fun aLateZuptBetweenTwoPlacedFixesAlsoGivesTheInOrderResult() {
        // Slow cadence: two 1 Hz fixes fall inside one step interval before the ZUPT arrives late.
        fun run(lateZupt: Boolean): EstimatedLocation {
            val estimator = PedestrianPositionEstimator()
            estimator.observeGnss(fix(0.0, 1_000L))
            estimator.observePdrStep(step(2_250L, length = 0.8))
            estimator.observePdrStep(step(3_500L, length = 0.8))
            estimator.observeGnss(fix(2.0, 3_700L))
            if (!lateZupt) estimator.observeZupt(ZuptObservation(4_200L))
            estimator.observeGnss(fix(2.6, 4_700L))
            estimator.observePdrStep(step(4_750L, length = 0.8))
            if (lateZupt) {
                val late = estimator.observeZupt(ZuptObservation(4_200L, receivedAtElapsedRealtimeMs = 4_800L))
                assertEquals(PedestrianMotionUpdateDisposition.APPLIED, late.disposition)
            }
            return requireNotNull(estimator.estimateAt(4_750L))
        }
        val inOrder = run(lateZupt = false)
        val late = run(lateZupt = true)
        assertEquals("east in order vs late ZUPT", east(inOrder), east(late), ORDER_TOLERANCE_M)
        assertEquals("north in order vs late ZUPT", north(inOrder), north(late), ORDER_TOLERANCE_M)
    }

    @Test
    fun aStepRejectedAfterThePlacementRestoresTheStateBeforeThePlacement() {
        fun prefix(estimator: PedestrianPositionEstimator) {
            estimator.observeGnss(fix(0.0, 1_000L))
            estimator.observePdrStep(step(1_556L))
            estimator.observeGnss(fix(1.2, 2_000L))
        }
        val reference = PedestrianPositionEstimator().also(::prefix)
        val estimator = PedestrianPositionEstimator().also(::prefix)
        // A finite but overflowing step-length sigma fails only after the interior fix has been placed.
        val rejected = estimator.observePdrStep(step(2_111L).copy(stepLengthSigmaM = 1e200))
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, rejected.disposition)
        assertEquals(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE, rejected.hardRejectReason)
        val expected = requireNotNull(reference.estimateAt(2_000L))
        val actual = requireNotNull(estimator.estimateAt(2_000L))
        assertEquals(east(expected), east(actual), 1e-9)
        assertEquals(north(expected), north(actual), 1e-9)
        assertEquals(expected.horizontalUncertaintyM, actual.horizontalUncertaintyM, 1e-9)
        assertEquals(2_000L, requireNotNull(rejected.estimate).estimatedAtElapsedRealtimeMs)
        assertEquals(east(expected), east(requireNotNull(rejected.estimate)), 1e-9)
        // The next valid step behaves exactly as if the rejected one had never arrived.
        val next = estimator.observePdrStep(step(2_111L))
        val referenceNext = reference.observePdrStep(step(2_111L))
        assertEquals(east(requireNotNull(referenceNext.estimate)), east(requireNotNull(next.estimate)), 1e-9)
        assertEquals(
            requireNotNull(referenceNext.estimate).horizontalUncertaintyM,
            requireNotNull(next.estimate).horizontalUncertaintyM,
            1e-9,
        )
        assertTrue(abs(east(requireNotNull(next.estimate)) - 1.2 * 1.111) < 0.05)
    }

    private fun fix(eastM: Double, at: Long) = GnssPositionObservation(
        latitude = LAT0,
        longitude = LON0 + Math.toDegrees(eastM / (R * cos(Math.toRadians(LAT0)))),
        horizontalAccuracyM = 3.0,
        elapsedRealtimeMs = at,
    )

    private fun step(at: Long, length: Double = 0.6667) =
        PdrStepObservation(1, length, 0.08, 90.0, 5.0, PdrStepQuality.HIGH, at)

    private fun east(location: EstimatedLocation) =
        Math.toRadians(location.longitude - LON0) * R * cos(Math.toRadians(LAT0))

    private fun north(location: EstimatedLocation) = Math.toRadians(location.latitude - LAT0) * R

    private companion object {
        const val LAT0 = 37.0
        const val LON0 = 127.0
        const val R = 6_371_000.0
        const val ORDER_TOLERANCE_M = 1e-6
    }
}
