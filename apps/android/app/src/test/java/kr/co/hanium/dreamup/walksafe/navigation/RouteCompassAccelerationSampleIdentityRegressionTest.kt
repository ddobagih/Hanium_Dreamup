package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** S08-11: repeated wrappers of one acceleration measurement cannot establish stability. */
class RouteCompassAccelerationSampleIdentityRegressionTest {
    private val now = 1_000L
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val acceleration = RouteCompassVectorSample(0.0, 9.80665, 0.0, now, EarthOrientationAccuracy.HIGH)
    private val magnetic = RouteCompassVectorSample(0.0, -40.0, -30.0, now, EarthOrientationAccuracy.HIGH)

    @Test
    fun threeWrappersOfTheSameAccelerationMeasurementCannotEstablishStability() {
        val result = RouteCompassHeading.evaluateFallback(
            null, List(3) { acceleration.copy() }, listOf(magnetic), reference, now, null,
        )
        assertNull("one physical acceleration sample is not three independent measurements", result.observation)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, result.reason)
    }

    @Test
    fun repeatedOriginalNanosecondTimestampCannotEstablishStability() {
        val measured = rawSample(now, 100L)
        assertUnstable(List(3) { measured.copy() })
    }

    @Test
    fun twoIndependentMeasurementsAndADuplicateCannotEstablishStability() {
        val first = rawSample(now, 100L)
        val second = rawSample(now, 200L)
        assertUnstable(listOf(first, second, first.copy()))
        assertUnstable(listOf(acceleration.copy(observedAtMs = now - 1L), acceleration, acceleration.copy()))
    }

    @Test
    fun threeDistinctNanosecondMeasurementsInTheSameMillisecondRemainUsable() {
        val observation = requireNotNull(fallback(rawWindow()).observation)
        assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, observation.source)
        assertEquals(now, observation.observedAtElapsedRealtimeMs)
        assertEquals(0.0, observation.degreesTrueNorth, 0.01)
    }

    @Test
    fun legacyMeasurementsWithThreeDistinctMillisecondsRemainUsable() {
        val legacy = listOf(now - 180L, now - 90L, now).map { acceleration.copy(observedAtMs = it) }
        assertEquals(now - 180L, requireNotNull(fallback(legacy).observation).observedAtElapsedRealtimeMs)
    }

    @Test
    fun mixedTimestampPrecisionRequiresThreeDistinctMilliseconds() {
        val mixedSameMillisecond = rawWindow().toMutableList().apply { this[1] = acceleration }
        assertUnstable(mixedSameMillisecond)
        val mixedDistinctMilliseconds = listOf(rawSample(now - 180L), acceleration.copy(observedAtMs = now - 90L), rawSample(now))
        assertNotNull(fallback(mixedDistinctMilliseconds).observation)
    }

    @Test
    fun negativeOriginalNanosecondTimestampIsRejected() {
        val invalid = rawWindow().toMutableList().apply {
            this[0] = this[0].copy(sensorTimestampNanos = -1L)
        }
        val result = fallback(invalid)
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.INVALID_TIMESTAMP, result.reason)
    }

    @Test
    fun originalNanosecondsMustAgreeWithTheMeasurementMillisecond() {
        val invalid = rawWindow().toMutableList().apply {
            this[0] = this[0].copy(sensorTimestampNanos = (now - 1L) * 1_000_000L)
        }
        val result = fallback(invalid)
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.INVALID_TIMESTAMP, result.reason)
    }

    @Test
    fun duplicateWithBadNormIsStillChecked() {
        val distinct = rawWindow()
        val badDuplicate = distinct.first().copy(y = 15.0)
        assertUnstable(listOf(badDuplicate) + distinct)
        assertUnstable(distinct + badDuplicate)
    }

    @Test
    fun duplicateWithBadAccuracyStillDowngradesTheWholeWindow() {
        val distinct = rawWindow()
        val badDuplicate = distinct.first().copy(accuracy = EarthOrientationAccuracy.LOW)
        listOf(listOf(badDuplicate) + distinct, distinct + badDuplicate).forEach {
            val result = fallback(it)
            assertNull(result.observation)
            assertEquals(RouteCompassHeadingReason.QUALITY_LOW, result.reason)
        }
    }

    @Test
    fun independentMeasurementsStillHaveToRemainWithinTheTiltLimit() {
        val moving = rawWindow().mapIndexed { index, sample -> sample.copy(x = if (index == 1) -3.0 else 3.0) }
        assertUnstable(moving)
    }

    @Test
    fun independentSampleWindowKeepsTheExistingTwoHundredMillisecondBoundary() {
        assertNotNull(fallback(listOf(rawSample(800L), rawSample(900L), rawSample(now))).observation)
        assertUnstable(listOf(rawSample(799L), rawSample(900L), rawSample(now)))
    }

    @Test
    fun oldestContributingMeasurementStillControlsTheFiveHundredMillisecondFreshness() {
        val window = listOf(rawSample(820L), rawSample(910L), rawSample(now))
        val atBoundary = requireNotNull(fallback(window, 1_320L).observation)
        assertEquals(820L, atBoundary.observedAtElapsedRealtimeMs)
        val stale = fallback(window, 1_321L)
        assertNull(stale.observation)
        assertEquals(RouteCompassHeadingReason.STALE, stale.reason)
    }

    @Test
    fun invalidNanosecondProvenanceCannotChangeAcceptedSamplesOrReceiptState() {
        val state = stateWith(rawWindow())
        listOf(-1L, 1_099_000_000L).forEach { invalidNanos ->
            state.onVector(1, RouteCompassStream.ACCELEROMETER,
                acceleration.copy(observedAtMs = 1_100L, sensorTimestampNanos = invalidNanos), 1_100L)
            assertEquals(now, state.latestAccelerometerAt())
            assertEquals(now, state.firstReceiptAt(RouteCompassStream.ACCELEROMETER))
            assertEquals(now, state.lastReceiptAt(RouteCompassStream.ACCELEROMETER))
            assertEquals(now, requireNotNull(state.evaluate(1_100L, reference).observation).observedAtElapsedRealtimeMs)
        }
        val empty = stateWith(emptyList())
        empty.onVector(1, RouteCompassStream.ACCELEROMETER, acceleration.copy(sensorTimestampNanos = -1L), now)
        assertNull(empty.latestAccelerometerAt())
        assertNull(empty.firstReceiptAt(RouteCompassStream.ACCELEROMETER))
        assertNull(empty.lastReceiptAt(RouteCompassStream.ACCELEROMETER))
    }

    @Test
    fun lateRegistrationCannotSupplyTheThirdIndependentMeasurement() {
        val distinct = rawWindow()
        val state = stateWith(distinct.take(2))
        state.onVector(0, RouteCompassStream.ACCELEROMETER, distinct.last(), now)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, state.evaluate(now, reference).reason)
        state.onVector(1, RouteCompassStream.ACCELEROMETER, distinct.last(), now)
        assertNotNull(state.evaluate(now, reference).observation)
    }

    @Test
    fun stopAndStartCannotReuseThePreviousRegistrationsIndependentWindow() {
        val distinct = rawWindow()
        val state = stateWith(distinct)
        assertNotNull(state.evaluate(now, reference).observation)
        state.stop()
        state.start(2, now, fallbackStreams)
        distinct.forEach { state.onVector(1, RouteCompassStream.ACCELEROMETER, it, now) }
        state.onVector(2, RouteCompassStream.MAGNETIC_FIELD, magnetic, now)
        assertNull(state.latestAccelerometerAt())
        distinct.take(2).forEach { state.onVector(2, RouteCompassStream.ACCELEROMETER, it, now) }
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, state.evaluate(now, reference).reason)
        state.onVector(2, RouteCompassStream.ACCELEROMETER, distinct.last(), now)
        assertNotNull(state.evaluate(now, reference).observation)
    }

    private fun rawSample(atMs: Long, nanosWithinMillisecond: Long = 100L) = acceleration.copy(
        observedAtMs = atMs, sensorTimestampNanos = atMs * 1_000_000L + nanosWithinMillisecond,
    )

    private fun rawWindow() = listOf(100L, 200L, 300L).map { rawSample(now, it) }

    private fun fallback(window: List<RouteCompassVectorSample>, atMs: Long = now) =
        RouteCompassHeading.evaluateFallback(null, window, listOf(magnetic), reference, atMs, null)

    private fun assertUnstable(window: List<RouteCompassVectorSample>) {
        val result = fallback(window)
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, result.reason)
    }

    private val fallbackStreams = setOf(RouteCompassStream.ACCELEROMETER, RouteCompassStream.MAGNETIC_FIELD)

    private fun stateWith(window: List<RouteCompassVectorSample>) = RouteCompassSensorState().apply {
        start(1, now, fallbackStreams)
        onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic, now)
        window.forEach { onVector(1, RouteCompassStream.ACCELEROMETER, it, now) }
    }
}
