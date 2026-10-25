package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RotationMatrix3
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassSensorState
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassStream
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassVectorSample
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSource
import kr.co.hanium.dreamup.walksafe.navigation.selectStrictMagneticMeasurement
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invalid final heading evidence must not complete the existing magnetic recovery episode. */
class ChestMountedHeadingRecoveryFinalGateRegressionTest {
    @Test
    fun aRateSkewRejectedRecoveryCompletionCannotPromoteTheNextSingleNormalSample() {
        val callbacks = rejectedRecoveryCompletion()

        assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING,
            callbacks.magnetic(2_151L).gateReason)
    }

    @Test
    fun aRateSkewRejectedRecoveryCompletionCannotPublishTheNextSingleNormalPdrHeading() {
        val callbacks = rejectedRecoveryCompletion()
        callbacks.magnetic(2_151L)

        assertNull("the rejected completion must leave strict PDR history empty",
            callbacks.history.atOrBefore(2_151L))
    }

    @Test
    fun rejectedCompletionNeedsANewContinuousNormalWindowBeforeRecovery() {
        val callbacks = rejectedRecoveryCompletion()
        assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING,
            callbacks.magnetic(2_151L).gateReason)
        // Re-evaluating the same physical magnetic observation supplies no new recovery sample.
        assertNull("The native owner rejects a duplicate before the recovery evaluator",
            callbacks.magneticCallback(2_151L, receivedAtMs = 2_200L))
        assertNull(callbacks.history.atOrBefore(2_200L))
        for (at in listOf(2_351L, 2_551L, 2_751L, 2_951L)) {
            callbacks.rotation(at)
            assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING, callbacks.magnetic(at).gateReason)
            assertNull(callbacks.history.atOrBefore(at))
        }
        callbacks.rotation(3_151L)
        assertTrue(callbacks.magnetic(3_151L).isValid)
        assertEquals(3_151L, callbacks.history.atOrBefore(3_151L)?.elapsedRealtimeMs)
    }

    @Test
    fun fiveUsableSamplesOverTheExisting1000MsWindowStillRecover() {
        val callbacks = Callbacks()
        callbacks.rotation(1_000L)
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            callbacks.magnetic(1_000L, strength = 150.0).gateReason)
        for (at in listOf(1_100L, 1_350L, 1_600L, 1_850L)) {
            callbacks.rotation(at)
            assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING, callbacks.magnetic(at).gateReason)
        }
        callbacks.rotation(2_100L)
        val fifth = callbacks.magnetic(2_100L)
        assertTrue(fifth.isValid)
        assertEquals(5.0, requireNotNull(fifth.accuracyDegrees), 0.00001)
        assertEquals(2_100L, fifth.observedAtMs)
    }

    @Test
    fun aNormalSteadyHeadingWithoutAnAnomalyIsImmediatelyUsable() {
        val callbacks = Callbacks()
        callbacks.rotation(1_000L)
        val normal = callbacks.magnetic(1_000L)

        assertTrue(normal.isValid)
        assertEquals(0.0, requireNotNull(normal.trueHeadingDegrees), 0.00001)
        assertEquals(5.0, requireNotNull(normal.accuracyDegrees), 0.00001)
        assertEquals(1_000L, callbacks.history.atOrBefore(1_000L)?.elapsedRealtimeMs)
    }

    @Test
    fun explicitResetStillEndsThePreviousFieldAnomaly() {
        val callbacks = rejectedRecoveryCompletion()
        callbacks.core.reset()
        callbacks.history.clear()

        assertTrue(callbacks.magnetic(2_151L).isValid)
    }

    @Test
    fun normalRouteOnlyFallbackKeepsItsOriginalSourceAndMeasurementTime() {
        val state = RouteCompassSensorState().apply {
            start(1, 0L, setOf(RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD))
        }
        state.onVector(1, RouteCompassStream.GRAVITY,
            RouteCompassVectorSample(0.0, 9.80665, 0.0, 1_000L, EarthOrientationAccuracy.HIGH,
                1_000_000_000L), 1_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD,
            RouteCompassVectorSample(0.0, -40.0, -30.0, 1_000L, EarthOrientationAccuracy.HIGH,
                1_000_000_000L), 1_000L)

        val fallback = state.evaluate(1_001L, ChestMountedGeomagneticReference(0.0, 53.13, 50.0))
        assertEquals(RouteCompassHeadingReason.VALID, fallback.reason)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, fallback.observation?.source)
        assertEquals(1_000L, fallback.observation?.observedAtElapsedRealtimeMs)
    }

    private fun rejectedRecoveryCompletion(): Callbacks {
        val callbacks = recovering()
        callbacks.rotation(2_130L, headingDegrees = -12.0)
        callbacks.rotation(2_150L)
        val measuredRate = requireNotNull(callbacks.state.latestRotationRateDegreesPerSecond())
        assertEquals("rate is calculated by production State from the two RV matrices",
            600.0, measuredRate, 0.01)
        val rejected = callbacks.magnetic(2_100L, receivedAtMs = 2_151L)
        assertEquals(ChestMountedHeadingGateReason.HEADING_ACCURACY, rejected.gateReason)
        assertFalse(rejected.isValid)
        assertEquals(2_150L, rejected.observedAtMs)
        assertEquals(2_100_000_000L, callbacks.state.latestMagneticMeasurement()?.sensorTimestampNanos)
        assertEquals(2_151L, callbacks.state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        assertTrue("5 deg + measured rate x 50 ms exceeds the existing 30 deg limit",
            5.0 + measuredRate * 50.0 / 1_000.0 > 30.0)
        assertNull(callbacks.history.atOrBefore(2_151L))
        return callbacks
    }

    private fun recovering(): Callbacks = Callbacks().apply {
        rotation(1_000L)
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            magnetic(1_000L, strength = 150.0).gateReason)
        // All callbacks are evaluated. Five samples in only 800 ms cannot finish recovery.
        for (at in listOf(1_100L, 1_300L, 1_500L, 1_700L, 1_900L)) {
            rotation(at)
            assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING, magnetic(at).gateReason)
        }
    }

    private class Callbacks {
        val state = RouteCompassSensorState().apply {
            start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR, RouteCompassStream.MAGNETIC_FIELD))
        }
        val core = ChestMountedHeading()
        val history = HeadingObservationHistory()
        private var rotation: ChestMountedRotationSample? = null
        private var magnetic: ChestMountedMagneticFieldSample? = null
        private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)

        fun rotation(at: Long, headingDegrees: Double = 0.0): ChestMountedHeadingResult {
            var result: ChestMountedHeadingResult? = null
            state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, at * 1_000_000L,
                at * 1_000_000L) { receipt ->
                val radians = Math.toRadians(headingDegrees)
                val east = sin(radians).toFloat()
                val north = cos(radians).toFloat()
                val matrix = RotationMatrix3(north, 0f, -east, -east, 0f, -north, 0f, 1f, 0f)
                val accuracy = state.onRotationVector(1, matrix, 5f, at,
                    EarthOrientationAccuracy.HIGH, receipt)
                rotation = ChestMountedRotationSample(matrix, at, 5.0, requireNotNull(accuracy),
                    state.latestRotationRateDegreesPerSecond())
                result = evaluate(receipt)
            }
            return requireNotNull(result)
        }

        fun magnetic(at: Long, receivedAtMs: Long = at, strength: Double = 50.0): ChestMountedHeadingResult =
            requireNotNull(magneticCallback(at, receivedAtMs, strength))

        fun magneticCallback(at: Long, receivedAtMs: Long = at, strength: Double = 50.0): ChestMountedHeadingResult? {
            var result: ChestMountedHeadingResult? = null
            state.onSensorCallback(1, RouteCompassStream.MAGNETIC_FIELD, at * 1_000_000L,
                receivedAtMs * 1_000_000L) { receipt ->
                val sample = RouteCompassVectorSample(0.0, -0.8 * strength, -0.6 * strength,
                    at, EarthOrientationAccuracy.HIGH, at * 1_000_000L)
                state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, sample, receipt)
                magnetic = selectStrictMagneticMeasurement(state.latestMagneticMeasurement(),
                    ChestMountedMagneticFieldSample(sample.x, sample.y, sample.z, at, sample.accuracy))
                result = evaluate(receipt)
            }
            return result
        }

        private fun evaluate(receipt: Long): ChestMountedHeadingResult {
            val result = core.evaluate(receipt, rotation, magnetic, reference)
            if (result.isValid) {
                history.add(HeadingObservation(requireNotNull(result.trueHeadingDegrees), result.accuracyDegrees,
                    requireNotNull(result.observedAtMs)))
            } else {
                history.clear()
            }
            return result
        }
    }
}
