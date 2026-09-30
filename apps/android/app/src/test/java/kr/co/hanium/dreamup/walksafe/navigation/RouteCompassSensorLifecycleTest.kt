package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 08.txt #35/#31/#32 and the tracker wiring of #6/#9/#13: sensor events are injected in order
 * into the same state machine that AndroidEarthOrientationTracker drives.
 */
class RouteCompassSensorLifecycleTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val all = RouteCompassStream.entries.toSet()
    private val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, 0L, EarthOrientationAccuracy.HIGH)
    private val magnetic = RouteCompassVectorSample(0.0, -40.0, -30.0, 0L, EarthOrientationAccuracy.HIGH)

    /** Device -Z (rear camera) faces [heading] degrees from magnetic north, phone upright. */
    private fun matrix(heading: Double): RotationMatrix3 {
        val h = Math.toRadians(heading)
        return RotationMatrix3(
            cos(h).toFloat(), 0f, (-sin(h)).toFloat(),
            (-sin(h)).toFloat(), 0f, (-cos(h)).toFloat(),
            0f, 1f, 0f,
        )
    }

    private fun RouteCompassSensorState.rv(generation: Int, at: Long, heading: Double = 0.0,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH) =
        onRotationVector(generation, matrix(heading), 5f, at, accuracy, at)

    private fun RouteCompassSensorState.fallbackInputs(generation: Int, at: Long) {
        onVector(generation, RouteCompassStream.GRAVITY, gravity.copy(observedAtMs = at), at)
        onVector(generation, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = at), at)
    }

    @Test
    fun stopThenStartIgnoresALateCallbackFromThePreviousRegistration() {
        val state = RouteCompassSensorState()
        state.start(1, 1_000L, all)
        state.rv(1, 1_000L)
        assertEquals(RouteCompassHeadingReason.VALID, state.evaluate(1_010L, reference).reason)
        state.stop()
        state.start(2, 1_020L, all)
        state.rv(1, 1_030L) // queued before stop, delivered after the new start
        val afterRestart = state.evaluate(1_040L, reference)
        assertNull("the previous registration's RV must not become current", afterRestart.observation)
        state.rv(2, 1_050L)
        assertNotNull(state.evaluate(1_060L, reference).observation)
    }

    @Test
    fun accuracyDropBlocksUntilRecoveryAndAFreshGoodEvent() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        state.rv(1, 100L)
        state.fallbackInputs(1, 100L)
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 150L)
        assertEquals(RouteCompassHeadingReason.QUALITY_LOW, state.evaluate(200L, reference).reason)
        // New events are still reported LOW until the accuracy callback recovers.
        state.rv(1, 250L)
        state.fallbackInputs(1, 250L)
        assertEquals(RouteCompassHeadingReason.QUALITY_LOW, state.evaluate(260L, reference).reason)
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.MEDIUM, 300L)
        assertEquals("recovery status alone is not a fresh sample",
            RouteCompassHeadingReason.QUALITY_LOW, state.evaluate(310L, reference).reason)
        state.rv(1, 320L)
        val recovered = state.evaluate(330L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, recovered.reason)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, recovered.observation?.source)
    }

    @Test
    fun rotationVectorStopSwitchesToFreshFallbackAndSwitchesBack() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        state.rv(1, 100L)
        state.fallbackInputs(1, 100L)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, state.evaluate(150L, reference).observation?.source)
        // RV stops; gravity/magnetic keep arriving.
        for (at in 200L..700L step 50L) state.fallbackInputs(1, at)
        val fallback = state.evaluate(700L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, fallback.reason)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, fallback.observation?.source)
        state.rv(1, 720L)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, state.evaluate(730L, reference).observation?.source)
    }

    @Test
    fun registeredSensorsWithoutAFirstEventAreReportedAsStalledAndReregisteredWithLimits() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        // Inside the first-event deadline the ordinary missing-input reason is kept.
        assertNotEquals(RouteCompassHeadingReason.SENSOR_STALLED, state.evaluate(900L, reference).reason)
        assertFalse(state.reregistrationDue(900L))
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, state.evaluate(1_001L, reference).reason)
        assertTrue(state.reregistrationDue(1_001L))
        state.onReregistered(2, 1_001L, all)
        assertFalse(state.reregistrationDue(1_500L))
        // Still silent after the new grace period, but the next attempt waits 30 s.
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, state.evaluate(2_100L, reference).reason)
        assertFalse(state.reregistrationDue(2_100L))
        assertFalse(state.reregistrationDue(30_000L))
        assertTrue(state.reregistrationDue(31_001L))
        state.onReregistered(3, 31_001L, all)
        // At most two attempts per start; afterwards the stalled state stays visible.
        assertFalse(state.reregistrationDue(100_000L))
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, state.evaluate(100_000L, reference).reason)
        assertEquals(2, state.reregistrations)
    }

    @Test
    fun deliveringSensorsThatStopForTwoSecondsAreStalledButOrdinaryStalenessIsNot() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        state.rv(1, 100L)
        state.fallbackInputs(1, 100L)
        assertEquals(100L, state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(RouteCompassHeadingReason.STALE, state.evaluate(700L, reference).reason)
        assertFalse(state.reregistrationDue(2_000L))
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, state.evaluate(2_101L, reference).reason)
        assertTrue(state.reregistrationDue(2_101L))
        // A late event of the replaced registration does not revive the stream.
        state.onReregistered(2, 2_101L, all)
        state.rv(1, 2_150L)
        assertNull(state.evaluate(2_160L, reference).observation)
        state.rv(2, 2_200L)
        assertNotNull(state.evaluate(2_210L, reference).observation)
    }

    @Test
    fun rotationRateFromConsecutiveVectorsWidensTheFacingDecision() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        state.rv(1, 100L, heading = 0.0)
        state.rv(1, 120L, heading = 3.6) // 180 deg/s
        val observation = requireNotNull(state.evaluate(120L, reference).observation)
        assertEquals(180.0, observation.angularRateDegreesPerSecond!!, 1.0)
        assertEquals(RouteFacingDirection.UNKNOWN, RouteFacingGuidance.evaluate(3.6, observation, 520L).direction)
        assertEquals(RouteFacingDirection.FRONT,
            RouteFacingGuidance.evaluate(3.6, observation.copy(angularRateDegreesPerSecond = 0.0), 520L).direction)
    }

    @Test
    fun trackerCrossChecksTheRotationVectorWithTheNewestMagneticSample() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, all)
        state.rv(1, 100L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(y = -120.0, z = -90.0, observedAtMs = 100L), 100L)
        assertTrue(requireNotNull(state.evaluate(110L, reference).observation).magneticFieldSuspect)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = 150L), 150L)
        assertFalse(requireNotNull(state.evaluate(160L, reference).observation).magneticFieldSuspect)
    }

    @Test
    fun trackerPairsFallbackWithTheClosestMagneticSampleFromItsHistory() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, setOf(RouteCompassStream.MAGNETIC_FIELD, RouteCompassStream.GRAVITY))
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = 380L), 380L)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity.copy(observedAtMs = 400L), 400L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(observedAtMs = 700L), 700L)
        val observation = requireNotNull(state.evaluate(710L, reference).observation)
        assertEquals(380L, observation.observedAtElapsedRealtimeMs)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK, observation.sensorQuality)
    }

    @Test
    fun geomagneticReferenceKeepsProvenanceAndExpiresWithoutRefresh() {
        val record = GeomagneticReferenceRecord(reference, 1_000L, 37.5, 127.0, 1_700_000_000_000L)
        assertEquals(37.5, record.latitudeDegrees, 0.0)
        assertEquals(127.0, record.longitudeDegrees, 0.0)
        assertEquals(reference, record.usableAt(1_000L))
        assertEquals(reference, record.usableAt(1_000L + GeomagneticReferenceRecord.MAXIMUM_AGE_MS))
        assertNull(record.usableAt(1_001L + GeomagneticReferenceRecord.MAXIMUM_AGE_MS))
        assertNull("a clock older than the record cannot vouch for it", record.usableAt(999L))
    }
}
