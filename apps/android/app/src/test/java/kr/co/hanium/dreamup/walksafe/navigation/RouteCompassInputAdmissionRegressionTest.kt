package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADD-MOTION-032: reject invalid vector evidence before it changes compass guidance state. */
class RouteCompassInputAdmissionRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val streams = setOf(RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD)
    private val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, 1_000L, EarthOrientationAccuracy.HIGH)
    private val magnetic = RouteCompassVectorSample(0.0, -40.0, -30.0, 1_000L, EarthOrientationAccuracy.HIGH)

    @Test
    fun nonFiniteMagneticCallbackCannotPoisonANormalSampleAtTheSameMeasurementTime() {
        // Each invalid value gets a fresh history. One failing method counts as one regression,
        // even when the first (NaN) assertion prevents the Infinity iteration from running.
        for (invalidX in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val state = RouteCompassSensorState()
            state.start(1, 0L, streams)
            state.onVector(1, RouteCompassStream.GRAVITY, gravity, 1_000L)
            state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(x = invalidX), 1_000L)
            state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic, 1_001L)

            // Receipt times remain callback facts, independently of guidance admission.
            assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
            assertEquals(1_001L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
            val result = state.evaluate(1_010L, reference)
            assertEquals("invalid x=$invalidX must not win the same-time magnetic pairing",
                RouteCompassHeadingReason.VALID, result.reason)
            val observation = requireNotNull(result.observation)
            assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
            assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
            assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
        }
    }

    @Test
    fun negativeGravityMeasurementCannotReplaceThePreviouslyValidGuidanceEvidence() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, streams)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity, 1_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic, 1_000L)
        val before = state.evaluate(1_001L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, before.reason)
        val originalObservation = requireNotNull(before.observation)
        assertEquals(1_000L, originalObservation.observedAtElapsedRealtimeMs)

        state.onVector(1, RouteCompassStream.GRAVITY, gravity.copy(observedAtMs = -1L), 1_001L)

        assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.GRAVITY))
        assertEquals(1_001L, state.lastReceiptAt(RouteCompassStream.GRAVITY))
        val after = state.evaluate(1_001L, reference)
        assertEquals("a received negative measurement must not invalidate accepted guidance",
            RouteCompassHeadingReason.VALID, after.reason)
        assertEquals(originalObservation, after.observation)
        assertEquals(1_000L, state.latestGravityAt())
        assertEquals(1_000L, state.latestMagneticAt())
    }

    @Test
    fun magneticMeasurementAfterItsReceiptCannotEnterHistoryOrBecomeValidLater() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, streams)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity, 1_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = 999L), 1_000L)
        val before = state.evaluate(1_000L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, before.reason)
        val originalObservation = requireNotNull(before.observation)
        assertEquals(999L, originalObservation.observedAtElapsedRealtimeMs)

        // Both magnetic times are 1 ms from gravity; the existing tie rule prefers the newer
        // point. Its measurement-to-receipt contradiction cannot be repaired by waiting.
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = 1_001L), 1_000L)

        assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        assertEquals(1_000L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        val immediate = state.evaluate(1_000L, reference)
        assertEquals("a future-at-receipt point must not displace the valid magnetic pair",
            RouteCompassHeadingReason.VALID, immediate.reason)
        assertEquals(originalObservation, immediate.observation)
        assertEquals(999L, state.latestMagneticAt())
        val later = state.evaluate(1_010L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, later.reason)
        assertEquals("later evaluation must retain the original admitted observation",
            originalObservation, later.observation)
        assertEquals(999L, requireNotNull(later.observation).observedAtElapsedRealtimeMs)
        assertEquals(999L, state.latestMagneticAt())
    }

    @Test
    fun finiteVectorsAtTheirReceiptTimeRemainAcceptedWithActualReceiptFacts() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, streams)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity, 1_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic, 1_000L)

        assertEquals(1_000L, state.latestGravityAt())
        assertEquals(1_000L, state.latestMagneticAt())
        assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.GRAVITY))
        assertEquals(1_000L, state.lastReceiptAt(RouteCompassStream.GRAVITY))
        assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        assertEquals(1_000L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        val result = state.evaluate(1_010L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        val observation = requireNotNull(result.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK, observation.sensorQuality)
        assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
        assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
    }
}
