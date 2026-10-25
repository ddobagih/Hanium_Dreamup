package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-9: RV cross-check uses the latest original magnetic measurement, not latest arrival. */
class RouteCompassDelayedMagneticObservationRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val streams = setOf(RouteCompassStream.ROTATION_VECTOR, RouteCompassStream.MAGNETIC_FIELD)
    private val north = RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    @Test
    fun delayedNormalMeasurementCannotRestoreAPreciseClockAfterANewerStrongAnomaly() {
        val state = anomalousState()
        val before = state.evaluate(1_050L, reference)
        assertTrue(requireNotNull(before.observation).magneticFieldSuspect)
        assertEquals(150.0, requireNotNull(before.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, before.checkEvidence?.rejectedCheck)
        assertNull(RouteFacingGuidance.evaluate(0.0, requireNotNull(before.observation), 1_050L).clockHour)

        state.magnetic(normal(900L), receiptMs = 1_100L)
        val after = state.evaluate(1_100L, reference)
        val observation = requireNotNull(after.observation)
        assertNull("late 900 ms normal input must not restore clock 12 over the 1000 ms anomaly",
            RouteFacingGuidance.evaluate(0.0, observation, 1_100L).clockHour)
        assertTrue(observation.magneticFieldSuspect)
        assertEquals(150.0, requireNotNull(after.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, after.checkEvidence?.rejectedCheck)
        assertEquals(RouteCompassHeadingReason.VALID, after.reason)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, observation.source)
        assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
    }

    @Test
    fun latestMagneticMeasurementDiagnosticCannotRegressToALateOlderArrival() {
        val state = anomalousState()
        state.magnetic(normal(900L), receiptMs = 1_100L)
        assertEquals("latest magnetic time is the newest original measurement", 1_000L, state.latestMagneticAt())
        assertEquals(1_100L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
    }

    @Test
    fun aGenuinelyNewerNormalMeasurementStillRestoresTheExistingPreciseClock() {
        val state = anomalousState()
        state.magnetic(normal(1_100L), 1_100L)
        val after = state.evaluate(1_110L, reference)
        assertFalse(requireNotNull(after.observation).magneticFieldSuspect)
        assertEquals(50.0, requireNotNull(after.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
        assertNull(after.checkEvidence?.rejectedCheck)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, requireNotNull(after.observation), 1_110L).clockHour)
        assertEquals(1_100L, state.latestMagneticAt())
    }

    @Test
    fun aDelayedOlderAnomalyCannotOverrideANewerNormalMeasurement() {
        val state = state()
        state.magnetic(normal(1_000L), 1_000L)
        state.magnetic(anomaly(900L), 1_100L)
        val after = state.evaluate(1_100L, reference)
        assertFalse(requireNotNull(after.observation).magneticFieldSuspect)
        assertEquals(50.0, requireNotNull(after.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, requireNotNull(after.observation), 1_100L).clockHour)
    }

    @Test
    fun aGenuinelyNewerAnomalyStillSuppressesThePreciseClock() {
        val state = state()
        state.magnetic(normal(900L), 1_000L)
        state.magnetic(anomaly(1_000L), 1_100L)
        val after = state.evaluate(1_100L, reference)
        assertTrue(requireNotNull(after.observation).magneticFieldSuspect)
        assertNull(RouteFacingGuidance.evaluate(0.0, requireNotNull(after.observation), 1_100L).clockHour)
    }

    @Test
    fun equalMeasurementMillisecondsRetainTheLastArrivalForBothRecoveryDirections() {
        val recovered = anomalousState()
        recovered.magnetic(normal(1_000L), 1_100L)
        // A duplicate original measurement is replay; current quality changes have a separate callback.
        assertTrue(requireNotNull(recovered.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        assertEquals(1_000L, recovered.latestMagneticAt())
        assertEquals(1_100L, recovered.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        val rejected = state()
        rejected.magnetic(normal(1_000L), 1_000L)
        rejected.magnetic(anomaly(1_000L), 1_100L)
        assertFalse(requireNotNull(rejected.evaluate(1_100L, reference).observation).magneticFieldSuspect)
    }

    @Test
    fun distinctOriginalNanosecondsDoNotInventANewEqualMillisecondTiePolicy() {
        val recovered = state()
        recovered.magnetic(anomaly(1_000L, 900_000L), 1_001L)
        recovered.magnetic(normal(1_000L, 100_000L), 1_100L)
        assertTrue("older original ns cannot recover a newer anomaly",
            requireNotNull(recovered.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        recovered.magnetic(normal(1_000L, 950_000L), 1_100L)
        assertFalse("genuinely newer same-ms ns still recovers",
            requireNotNull(recovered.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        val rejected = state()
        rejected.magnetic(normal(1_000L, 900_000L), 1_001L)
        rejected.magnetic(anomaly(1_000L, 100_000L), 1_100L)
        assertFalse("older original ns cannot override a newer normal field",
            requireNotNull(rejected.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        rejected.magnetic(anomaly(1_000L, 950_000L), 1_100L)
        assertTrue("genuinely newer same-ms ns still rejects",
            requireNotNull(rejected.evaluate(1_100L, reference).observation).magneticFieldSuspect)
    }

    @Test
    fun anAbsentMagneticMeasurementDoesNotBlanketBlockANormalRotationVector() {
        val result = state().evaluate(1_100L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertFalse(requireNotNull(result.observation).magneticFieldSuspect)
        assertNull(result.checkEvidence?.magneticFieldMicrotesla)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, requireNotNull(result.observation), 1_100L).clockHour)
    }

    @Test
    fun aStaleMagneticAnomalyDoesNotBlanketBlockANormalRotationVector() {
        val state = state()
        state.magnetic(anomaly(500L), 1_000L)
        val result = state.evaluate(1_100L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertFalse(requireNotNull(result.observation).magneticFieldSuspect)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, requireNotNull(result.observation), 1_100L).clockHour)
    }

    @Test
    fun magneticFreshnessStillExpiresAtTheExistingFiveHundredMillisecondBoundary() {
        val state = state()
        state.magnetic(anomaly(600L), 1_000L)
        assertTrue(requireNotNull(state.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        assertFalse(requireNotNull(state.evaluate(1_101L, reference).observation).magneticFieldSuspect)
    }

    @Test
    fun fallbackStillUsesThePastMagneticMeasurementClosestToItsGravityBasis() {
        val state = fallbackState()
        state.magnetic(normal(380L), 380L)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity(400L), 400L)
        state.magnetic(normal(700L), 700L)
        val result = state.evaluate(710L, reference)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, requireNotNull(result.observation).source)
        assertEquals(380L, requireNotNull(result.observation).observedAtElapsedRealtimeMs)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK, result.observation?.sensorQuality)
        assertEquals(700L, state.latestMagneticAt())
    }

    @Test
    fun aLatePastNormalMeasurementRemainsAvailableForFallbackPairing() {
        val state = fallbackState()
        state.magnetic(anomaly(1_000L), 1_000L)
        state.magnetic(normal(900L), 1_100L)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity(900L), 1_100L)
        val result = state.evaluate(1_100L, reference)
        assertNull("late normal cannot recover the newer accepted anomaly", result.observation)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertEquals(150.0, requireNotNull(result.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
        assertEquals(1_000L, state.latestMagneticAt())
        // Previously accepted chronological history remains available; recovery needs new measurements.
        state.magnetic(normal(1_100L), 1_100L)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity(1_100L), 1_100L)
        val fresh = state.evaluate(1_100L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, fresh.reason)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, requireNotNull(fresh.observation).source)
        assertEquals(1_100L, requireNotNull(fresh.observation).observedAtElapsedRealtimeMs)
        assertEquals(50.0, requireNotNull(fresh.checkEvidence).magneticFieldMicrotesla!!, 0.00001)
    }

    @Test
    fun pastAnomalousMeasurementsAreNotDroppedToMakeFallbackLookNormal() {
        val state = fallbackState()
        state.magnetic(anomaly(380L), 380L)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity(400L), 400L)
        state.magnetic(normal(700L), 700L)
        val result = state.evaluate(710L, reference)
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, result.checkEvidence?.rejectedCheck)
    }

    @Test
    fun legacyAndRejectedTimestampInputsKeepTheirExistingAdmissionContracts() {
        val state = state()
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, anomaly(1_000L).copy(sensorTimestampNanos = null), 1_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, normal(900L).copy(sensorTimestampNanos = null), 1_100L)
        assertTrue(requireNotNull(state.evaluate(1_100L, reference).observation).magneticFieldSuspect)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, normal(1_100L).copy(sensorTimestampNanos = 1_099_000_000L), 1_100L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, normal(1_101L), 1_100L)
        state.onSensorCallback(1, RouteCompassStream.MAGNETIC_FIELD, 1_100_500_000L, 1_100_100_000L) { receivedMs ->
            state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, normal(1_100L, 500_000L), receivedMs)
        }
        assertEquals(1_000L, state.latestMagneticAt())
        assertTrue(requireNotNull(state.evaluate(1_100L, reference).observation).magneticFieldSuspect)
    }

    private fun fallbackState() = RouteCompassSensorState().apply {
        start(1, 0L, setOf(RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD))
    }

    private fun gravity(atMs: Long) = RouteCompassVectorSample(0.0, 9.80665, 0.0, atMs, EarthOrientationAccuracy.HIGH, atMs * 1_000_000L)

    private fun state() = RouteCompassSensorState().apply {
        start(1, 0L, streams)
        onRotationVector(1, north, 5f, 1_000L, EarthOrientationAccuracy.HIGH, 1_000L)
    }

    private fun anomalousState() = state().apply { magnetic(anomaly(1_000L), 1_000L) }

    private fun RouteCompassSensorState.magnetic(sample: RouteCompassVectorSample, receiptMs: Long) {
        onSensorCallback(1, RouteCompassStream.MAGNETIC_FIELD, requireNotNull(sample.sensorTimestampNanos), receiptMs * 1_000_000L) { receivedMs ->
            onVector(1, RouteCompassStream.MAGNETIC_FIELD, sample, receivedMs)
        }
    }

    private fun normal(atMs: Long, nanosWithinMs: Long = 0L) = RouteCompassVectorSample(
        0.0, -40.0, -30.0, atMs, EarthOrientationAccuracy.HIGH, atMs * 1_000_000L + nanosWithinMs,
    )

    private fun anomaly(atMs: Long, nanosWithinMs: Long = 0L) = normal(atMs, nanosWithinMs).copy(y = -120.0, z = -90.0)
}
