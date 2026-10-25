package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032: impossible RV measurement times cannot mutate accepted compass state. */
class RouteCompassRotationTimestampAdmissionRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)

    @Test
    fun negativeMeasurementTimeCannotReplaceTheAcceptedDirection() {
        assertDirectionSurvives(-1L)
    }

    @Test
    fun futureMeasurementTimeCannotReplaceTheAcceptedDirection() {
        assertDirectionSurvives(2_000L)
    }

    @Test
    fun negativeMeasurementTimeCannotPoisonTheNextRotationRateBaseline() {
        assertRateSurvives(-1L)
    }

    @Test
    fun futureMeasurementTimeCannotPoisonTheNextRotationRateBaseline() {
        assertRateSurvives(2_000L)
    }

    @Test
    fun impossibleTimesLeaveTheLatestMeasurementAndExistingRateUnchanged() {
        listOf(-1L, 2_000L).forEach { impossibleAtMs ->
            val state = state()
            state.rv(1_050L, 1_050L, 5.0)
            val before = state.evaluate(1_050L, reference)
            val beforeRate = state.latestRotationRateDegreesPerSecond()
            assertNull(state.rv(impossibleAtMs, 1_100L, 180.0, EarthOrientationAccuracy.LOW))
            assertEquals(1_050L, state.latestRotationEventAt())
            assertEquals(beforeRate, state.latestRotationRateDegreesPerSecond())
            assertEquals(before, state.evaluate(1_100L, reference))
        }
    }

    @Test
    fun impossibleMeasurementsStillRecordActualCallbackLiveness() {
        val state = state()
        assertNull(state.rv(4_000L, 3_000L, 180.0))
        assertEquals(1_000L, state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(3_000L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertFalse(state.isStalled(3_001L))
        assertEquals(1_000L, state.latestRotationEventAt())
        assertNull("receipt must not refresh the accepted direction", state.evaluate(3_001L, reference).observation)
    }

    @Test
    fun actualReceiptCannotExtendTheExistingFiveHundredMillisecondGuidanceLifetime() {
        val state = state()
        state.rv(2_000L, 1_500L, 180.0)
        assertEquals(1_000L, requireNotNull(state.evaluate(1_500L, reference).observation).observedAtElapsedRealtimeMs)
        assertNull(state.evaluate(1_501L, reference).observation)
    }

    @Test
    fun negativeLowQualityMeasurementCannotStartAFallbackRecoveryEpisode() {
        assertImpossibleLowCannotStartRecovery(-1L)
    }

    @Test
    fun futureLowQualityMeasurementCannotStartAFallbackRecoveryEpisode() {
        assertImpossibleLowCannotStartRecovery(2_000L)
    }

    @Test
    fun impossibleLowQualityMeasurementCannotRefreshAnExistingRecoveryEpisode() {
        listOf(-1L, 2_000L).forEach { impossibleAtMs ->
            val state = state(allStreams)
            state.rv(1_100L, 1_100L, 20.0, EarthOrientationAccuracy.LOW)
            state.checkedFallback(1_601L)
            val before = requireNotNull(state.evaluate(1_601L, reference).observation?.fallbackRecoveryEpisode)
            state.rv(impossibleAtMs, 1_700L, 180.0, EarthOrientationAccuracy.LOW)
            state.checkedFallback(1_700L)
            val after = requireNotNull(state.evaluate(1_700L, reference).observation?.fallbackRecoveryEpisode)
            assertEquals(before, after)
            assertEquals(1_100L, after.startedAtElapsedRealtimeMs)
            assertEquals(1_100L, after.lastRejectedAtElapsedRealtimeMs)
        }
    }

    @Test
    fun validMeasurementWithLowAccuracyStillWithholdsDirectionAndStartsRecovery() {
        val state = state(allStreams)
        assertEquals(EarthOrientationAccuracy.LOW, state.rv(1_100L, 1_100L, 20.0, EarthOrientationAccuracy.LOW))
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING, state.evaluate(1_100L, reference).reason)
        assertNull(state.evaluate(1_100L, reference).observation)
        state.checkedFallback(1_601L)
        val episode = requireNotNull(state.evaluate(1_601L, reference).observation?.fallbackRecoveryEpisode)
        assertEquals(1_100L, episode.startedAtElapsedRealtimeMs)
        assertEquals(1_100L, episode.lastRejectedAtElapsedRealtimeMs)
    }

    @Test
    fun validMeasurementWithInvalidMatrixStillClearsDirectionAndTheRotationBaseline() {
        val state = state()
        state.rv(1_050L, 1_050L, 5.0)
        assertNotNull(state.latestRotationRateDegreesPerSecond())
        assertNull(state.onRotationVector(1, null, 5f, 1_100L, EarthOrientationAccuracy.HIGH, 1_100L))
        assertEquals(1_100L, state.latestRotationEventAt())
        assertNull(state.latestRotationRateDegreesPerSecond())
        assertEquals(RouteCompassHeadingReason.INVALID_ROTATION, state.evaluate(1_100L, reference).reason)
        assertNull(state.evaluate(1_100L, reference).observation)
        state.rv(1_200L, 1_200L, 20.0)
        assertNull("an invalid matrix intentionally clears the rate baseline", state.latestRotationRateDegreesPerSecond())
        assertNotNull(state.evaluate(1_200L, reference).observation)
    }

    @Test
    fun validMeasurementWithInvalidMatrixAndLowAccuracyStillStartsRecovery() {
        val state = state(allStreams)
        assertNull(state.onRotationVector(1, null, 5f, 1_100L, EarthOrientationAccuracy.LOW, 1_100L))
        assertEquals(RouteCompassHeadingReason.INVALID_ROTATION, state.evaluate(1_100L, reference).reason)
        state.checkedFallback(1_601L)
        assertEquals(1_100L,
            requireNotNull(state.evaluate(1_601L, reference).observation?.fallbackRecoveryEpisode).lastRejectedAtElapsedRealtimeMs)
    }

    @Test
    fun accuracyCallbackRecoveryStillRequiresAFreshValidMeasurement() {
        val state = state()
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1_050L)
        state.rv(2_000L, 1_100L, 180.0)
        assertTrue(state.rotationQualityWait())
        assertEquals(1_050L, state.rotationQualityRejectedAt())
        assertEquals(1_000L, state.latestRotationEventAt())
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.MEDIUM, 1_150L)
        assertTrue(state.rotationQualityWait())
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING, state.evaluate(1_150L, reference).reason)
        assertEquals(EarthOrientationAccuracy.MEDIUM, state.rv(1_160L, 1_160L, 16.0))
        assertFalse(state.rotationQualityWait())
        assertNull(state.rotationQualityRejectedAt())
        assertNotNull(state.evaluate(1_160L, reference).observation)
    }

    @Test
    fun oldGenerationCallbacksCannotChangeMeasurementOrReceiptState() {
        val state = state()
        listOf(-1L, 1_100L, 2_000L).forEach { atMs ->
            assertNull(state.rv(atMs, 1_100L, 180.0, EarthOrientationAccuracy.LOW, generation = 0))
        }
        assertEquals(1_000L, state.latestRotationEventAt())
        assertEquals(1_000L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(0.0, requireNotNull(state.evaluate(1_100L, reference).observation).degreesTrueNorth, 0.001)
    }

    @Test
    fun stopAndStartStillClearThePreviousRegistrationBeforeNewValidMeasurements() {
        val state = state()
        state.stop()
        state.start(2, 1_100L, setOf(RouteCompassStream.ROTATION_VECTOR))
        assertNull(state.rv(1_100L, 1_100L, 180.0, generation = 1))
        assertNull(state.latestRotationEventAt())
        assertNull(state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertNull(state.rv(2_000L, 1_200L, 180.0, generation = 2))
        assertNull(state.latestRotationEventAt())
        assertEquals(1_200L, state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertNotNull(state.rv(1_200L, 1_200L, 20.0, generation = 2))
        assertNotNull(state.evaluate(1_200L, reference).observation)
        assertNull(state.latestRotationRateDegreesPerSecond())
    }

    private fun assertDirectionSurvives(impossibleAtMs: Long) {
        val state = state()
        state.rv(impossibleAtMs, 1_100L, 180.0)
        val result = state.evaluate(1_100L, reference)
        assertEquals("impossible measurement time must leave the accepted direction usable",
            RouteCompassHeadingReason.VALID, result.reason)
        val observation = requireNotNull(result.observation)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, observation.source)
        assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
        assertEquals(0.0, observation.degreesTrueNorth, 0.001)
    }

    private fun assertRateSurvives(impossibleAtMs: Long) {
        val state = state()
        state.rv(impossibleAtMs, 1_100L, 180.0)
        state.rv(1_100L, 1_100L, 10.0)
        assertNotNull("rate must still use the last accepted RV at 1000 ms",
            state.latestRotationRateDegreesPerSecond())
        assertEquals(100.0, requireNotNull(state.latestRotationRateDegreesPerSecond()), 0.001)
        val observation = requireNotNull(state.evaluate(1_100L, reference).observation)
        assertEquals(1_100L, observation.observedAtElapsedRealtimeMs)
        assertEquals(10.0, observation.degreesTrueNorth, 0.001)
    }

    private fun state(streams: Set<RouteCompassStream> = setOf(RouteCompassStream.ROTATION_VECTOR)) = RouteCompassSensorState().apply {
        start(1, 1_000L, streams)
        rv(1_000L, 1_000L, 0.0)
    }

    private fun assertImpossibleLowCannotStartRecovery(impossibleAtMs: Long) {
        val state = state(allStreams)
        assertNull(state.rv(impossibleAtMs, 1_100L, 180.0, EarthOrientationAccuracy.LOW))
        state.checkedFallback(1_601L)
        val fallback = requireNotNull(state.evaluate(1_601L, reference).observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, fallback.source)
        assertNull(fallback.fallbackRecoveryEpisode)
    }

    private val allStreams = RouteCompassStream.entries.toSet()

    private fun RouteCompassSensorState.checkedFallback(atMs: Long) {
        onVector(1, RouteCompassStream.GRAVITY, RouteCompassVectorSample(0.0, 9.80665, 0.0, atMs, EarthOrientationAccuracy.HIGH), atMs)
        onVector(1, RouteCompassStream.MAGNETIC_FIELD, RouteCompassVectorSample(0.0, -40.0, -30.0, atMs, EarthOrientationAccuracy.HIGH), atMs)
    }

    private fun RouteCompassSensorState.rv(atMs: Long, receiptMs: Long, heading: Double,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH, generation: Int = 1) =
        onRotationVector(generation, matrix(heading), 5f, atMs, accuracy, receiptMs)

    /** Device -Z/rear camera faces the requested heading while the phone is upright. */
    private fun matrix(heading: Double): RotationMatrix3 {
        val h = Math.toRadians(heading)
        return RotationMatrix3(
            cos(h).toFloat(), 0f, (-sin(h)).toFloat(),
            (-sin(h)).toFloat(), 0f, (-cos(h)).toFloat(),
            0f, 1f, 0f,
        )
    }
}
