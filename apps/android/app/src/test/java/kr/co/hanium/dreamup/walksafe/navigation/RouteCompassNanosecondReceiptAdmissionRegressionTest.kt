package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Known original receipt ns enters the same callback dispatch that the Android adapter uses. */
class RouteCompassNanosecondReceiptAdmissionRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val futureNanos = 2_000_500_000L
    private val receiptNanos = 2_000_100_000L
    private val allStreams = RouteCompassStream.entries.toSet()

    @Test
    fun sameMillisecondFutureGravityCannotReplaceAcceptedFallbackEvidence() {
        val state = fallbackState()
        val before = requireNotNull(state.evaluate(1_999L, reference).observation)
        state.vector(RouteCompassStream.GRAVITY, gravity(futureNanos).copy(y = 15.0), receiptNanos)
        val after = state.evaluate(2_000L, reference)
        assertEquals("known future ns must not replace the valid gravity baseline", RouteCompassHeadingReason.VALID, after.reason)
        assertEquals(before, after.observation)
        assertEquals(1_999L, state.latestGravityAt())
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.GRAVITY))
    }

    @Test
    fun sameMillisecondFutureMagneticFieldCannotDowngradeTheAcceptedRotationVector() {
        val state = rotationState()
        state.vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(1_999_000_000L), 1_999_000_000L)
        val before = requireNotNull(state.evaluate(1_999L, reference).observation)
        assertFalse(before.magneticFieldSuspect)
        state.vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(futureNanos).copy(y = -120.0, z = -90.0), receiptNanos)
        val after = requireNotNull(state.evaluate(2_000L, reference).observation)
        assertFalse("known future ns must not become the RV magnetic cross-check", after.magneticFieldSuspect)
        assertEquals(before, after)
        assertEquals(1_999L, state.latestMagneticAt())
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
    }

    @Test
    fun sameMillisecondFutureAccelerationCannotContaminateTheStableWindow() {
        val state = state()
        listOf(1_997_000_000L, 1_998_000_000L, 1_999_000_000L).forEach { at ->
            state.vector(RouteCompassStream.ACCELEROMETER, gravity(at), 1_999_000_000L)
        }
        state.vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(1_998_000_000L), 1_999_000_000L)
        val before = requireNotNull(state.evaluate(1_999L, reference).observation)
        state.vector(RouteCompassStream.ACCELEROMETER, gravity(futureNanos).copy(y = 15.0), receiptNanos)
        val after = state.evaluate(2_000L, reference)
        assertEquals("known future ns must not occupy the acceleration window", RouteCompassHeadingReason.VALID, after.reason)
        assertEquals(before, after.observation)
        assertEquals(1_999L, state.latestAccelerometerAt())
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.ACCELEROMETER))
    }

    @Test
    fun sameMillisecondFutureLowRotationCannotReplaceTheAcceptedDirectionOrQuality() {
        val state = rotationState()
        val before = requireNotNull(state.evaluate(1_999L, reference).observation)
        state.rotation(futureNanos, receiptNanos, 180.0, EarthOrientationAccuracy.LOW)
        val after = state.evaluate(2_000L, reference)
        assertEquals("known future RV ns must not replace the accepted direction", RouteCompassHeadingReason.VALID, after.reason)
        assertEquals(before, after.observation)
        assertEquals(1_999L, state.latestRotationEventAt())
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertFalse(state.rotationQualityWait())
    }

    @Test
    fun sameMillisecondFutureRotationCannotPoisonTheNextRateBaseline() {
        val state = rotationState()
        state.rotation(futureNanos, receiptNanos, 180.0)
        state.rotation(2_100_000_000L, 2_100_000_000L, 10.0)
        assertEquals("the rate still starts at the original accepted 1999 ms rotation",
            10.0 * 1_000.0 / 101.0, requireNotNull(state.latestRotationRateDegreesPerSecond()), 0.01)
    }

    @Test
    fun futureCallbackCannotEnterTheConsumerForStrictHeadingCaches() {
        val state = rotationState()
        var handlerCalls = 0
        // The adapter's whole handler, including strict cache mutations, is this consumer.
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, futureNanos, receiptNanos) {
            handlerCalls += 1
        }
        assertEquals("the known future callback must stop before the Android handler", 0, handlerCalls)
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
    }

    @Test
    fun earlierAndEqualOriginalNanosecondsRemainAcceptedForEveryStream() {
        val pairs = listOf(
            2_000_050_000L to 2_000_100_000L,
            2_000_500_000L to 2_000_900_000L,
            2_000_100_000L to 2_000_100_000L,
        )
        allStreams.forEach { stream ->
            pairs.forEach { (measuredNs, receivedNs) ->
                val state = state()
                var consumedNanos: Long? = null
                var consumedReceiptMs: Long? = null
                state.onSensorCallback(1, stream, measuredNs, receivedNs) { receivedMs ->
                    consumedNanos = measuredNs
                    consumedReceiptMs = receivedMs
                    if (stream == RouteCompassStream.ROTATION_VECTOR) {
                        state.onRotationVector(1, matrix(0.0), 5f, measuredNs / 1_000_000L,
                            EarthOrientationAccuracy.HIGH, receivedMs)
                    } else {
                        val sample = if (stream == RouteCompassStream.MAGNETIC_FIELD) magnetic(measuredNs) else gravity(measuredNs)
                        state.onVector(1, stream, sample, receivedMs)
                        assertEquals(measuredNs, sample.sensorTimestampNanos)
                    }
                }
                assertEquals(measuredNs, consumedNanos)
                assertEquals(receivedNs / 1_000_000L, consumedReceiptMs)
                assertEquals(receivedNs / 1_000_000L, state.firstReceiptAt(stream))
                assertEquals(receivedNs / 1_000_000L, state.lastReceiptAt(stream))
                assertEquals(measuredNs / 1_000_000L, latestAt(state, stream))
            }
        }
    }

    @Test
    fun rejectedMeasurementDoesNotReserveItsOriginalTimestampAgainstAValidRetry() {
        allStreams.forEach { stream ->
            val state = state()
            var handlerCalls = 0
            state.onSensorCallback(1, stream, futureNanos, receiptNanos) { handlerCalls += 1 }
            assertEquals(0, handlerCalls)
            assertNull(latestAt(state, stream))
            // A new callback now has a genuinely later receipt; evaluation alone cannot admit it.
            state.onSensorCallback(1, stream, futureNanos, 2_000_900_000L) { receivedMs ->
                handlerCalls += 1
                if (stream == RouteCompassStream.ROTATION_VECTOR) {
                    state.onRotationVector(1, matrix(0.0), 5f, futureNanos / 1_000_000L,
                        EarthOrientationAccuracy.HIGH, receivedMs)
                } else {
                    state.onVector(1, stream,
                        if (stream == RouteCompassStream.MAGNETIC_FIELD) magnetic(futureNanos) else gravity(futureNanos), receivedMs)
                }
            }
            assertEquals(1, handlerCalls)
            assertEquals(2_000L, latestAt(state, stream))
        }
    }

    @Test
    fun rejectedNanosecondsRemainOnlyCallbackLivenessEvidence() {
        val state = RouteCompassSensorState().apply { start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR)) }
        var handlerCalls = 0
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, futureNanos, receiptNanos) { handlerCalls += 1 }
        assertEquals(0, handlerCalls)
        assertEquals(2_000L, state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertFalse(state.isStalled(4_000L))
        assertTrue(state.isStalled(4_001L))
        assertTrue(state.reregistrationDue(4_001L))
        assertNull(state.latestRotationEventAt())
        assertNull(state.evaluate(2_000L, reference).observation)
    }

    @Test
    fun negativeOriginalMeasurementStillRecordsOnlyTheActualReceipt() {
        allStreams.forEach { stream ->
            val state = state()
            var handlerCalls = 0
            state.onSensorCallback(1, stream, -1L, receiptNanos) { handlerCalls += 1 }
            assertEquals(0, handlerCalls)
            assertEquals(2_000L, state.lastReceiptAt(stream))
            assertNull(latestAt(state, stream))
        }
    }

    @Test
    fun negativeReceiptCannotBecomeAnInternalReceiptClock() {
        val state = rotationState()
        var handlerCalls = 0
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, 0L, -1L) { handlerCalls += 1 }
        assertEquals(0, handlerCalls)
        assertEquals(1_999L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(1_999L, state.latestRotationEventAt())
    }

    @Test
    fun oldGenerationAndStoppedCallbacksCannotRecordReceiptOrConsumeMeasurements() {
        val state = rotationState()
        var handlerCalls = 0
        state.onSensorCallback(0, RouteCompassStream.ROTATION_VECTOR, 2_000_050_000L, receiptNanos) { handlerCalls += 1 }
        assertEquals(1_999L, state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        state.stop()
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, 2_000_050_000L, receiptNanos) { handlerCalls += 1 }
        assertNull(state.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        state.start(2, 2_000L, allStreams)
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, 2_000_050_000L, receiptNanos) { handlerCalls += 1 }
        assertNull(state.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        state.onSensorCallback(2, RouteCompassStream.ROTATION_VECTOR, 2_000_050_000L, receiptNanos) { receivedMs ->
            handlerCalls += 1
            state.onRotationVector(2, matrix(0.0), 5f, 2_000L, EarthOrientationAccuracy.HIGH, receivedMs)
        }
        assertEquals(1, handlerCalls)
        assertEquals(2_000L, state.latestRotationEventAt())
    }

    @Test
    fun impossibleLowRotationCannotStartAFallbackRecoveryEpisode() {
        val state = rotationState()
        state.rotation(futureNanos, receiptNanos, 180.0, EarthOrientationAccuracy.LOW)
        state.vector(RouteCompassStream.GRAVITY, gravity(2_501_000_000L), 2_501_000_000L)
        state.vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(2_501_000_000L), 2_501_000_000L)
        val fallback = requireNotNull(state.evaluate(2_501L, reference).observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, fallback.source)
        assertNull(fallback.fallbackRecoveryEpisode)
    }

    @Test
    fun validLowRotationAndAccuracyCallbacksStillRetainTheirRecoveryContract() {
        val state = rotationState()
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 2_000L)
        assertTrue(state.rotationQualityWait())
        state.rotation(futureNanos, receiptNanos, 180.0)
        assertTrue(state.rotationQualityWait())
        assertEquals(2_000L, state.rotationQualityRejectedAt())
        assertEquals(1_999L, state.latestRotationEventAt())
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.HIGH, 2_000L)
        state.rotation(2_000_050_000L, receiptNanos, 10.0)
        assertFalse(state.rotationQualityWait())
        assertEquals(RouteCompassHeadingReason.VALID, state.evaluate(2_000L, reference).reason)
        state.rotation(2_100_050_000L, 2_100_100_000L, 10.0, EarthOrientationAccuracy.LOW)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING, state.evaluate(2_100L, reference).reason)
        state.vector(RouteCompassStream.GRAVITY, gravity(2_601_000_000L), 2_601_000_000L)
        state.vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(2_601_000_000L), 2_601_000_000L)
        assertNotNull(requireNotNull(state.evaluate(2_601L, reference).observation).fallbackRecoveryEpisode)
    }

    @Test
    fun validTimedAnomalousMagneticInputStillMarksTheRotationVectorSuspect() {
        val state = rotationState()
        state.vector(RouteCompassStream.MAGNETIC_FIELD,
            magnetic(2_000_050_000L).copy(y = -120.0, z = -90.0), receiptNanos)
        assertTrue(requireNotNull(state.evaluate(2_000L, reference).observation).magneticFieldSuspect)
    }

    @Test
    fun legacyMillisecondApisKeepTheirOriginalPrecisionAndGuards() {
        val state = state()
        val gravity = gravity(2_000_000_000L).copy(sensorTimestampNanos = null)
        val magnetic = magnetic(2_000_000_000L).copy(sensorTimestampNanos = null)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity, 2_000L)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic, 2_000L)
        val before = requireNotNull(state.evaluate(2_000L, reference).observation)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity.copy(observedAtMs = 2_001L), 2_000L)
        assertEquals(before, state.evaluate(2_000L, reference).observation)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(sensorTimestampNanos = 1_999_000_000L), 2_001L)
        assertEquals(before, state.evaluate(2_001L, reference).observation)
        assertEquals(2_000L, state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
    }

    private fun latestAt(state: RouteCompassSensorState, stream: RouteCompassStream): Long? = when (stream) {
        RouteCompassStream.ROTATION_VECTOR -> state.latestRotationEventAt()
        RouteCompassStream.MAGNETIC_FIELD -> state.latestMagneticAt()
        RouteCompassStream.GRAVITY -> state.latestGravityAt()
        RouteCompassStream.ACCELEROMETER -> state.latestAccelerometerAt()
    }

    private fun state() = RouteCompassSensorState().apply { start(1, 0L, allStreams) }

    private fun fallbackState() = state().apply {
        vector(RouteCompassStream.GRAVITY, gravity(1_999_000_000L), 1_999_000_000L)
        vector(RouteCompassStream.MAGNETIC_FIELD, magnetic(1_999_000_000L), 1_999_000_000L)
    }

    private fun rotationState() = state().apply { rotation(1_999_000_000L, 1_999_000_000L, 0.0) }

    private fun RouteCompassSensorState.vector(stream: RouteCompassStream, sample: RouteCompassVectorSample, receiptNs: Long) {
        onSensorCallback(1, stream, requireNotNull(sample.sensorTimestampNanos), receiptNs) { receiptMs ->
            onVector(1, stream, sample, receiptMs)
        }
    }

    private fun RouteCompassSensorState.rotation(atNs: Long, receiptNs: Long, heading: Double,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH) {
        onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, atNs, receiptNs) { receiptMs ->
            onRotationVector(1, matrix(heading), 5f, atNs / 1_000_000L, accuracy, receiptMs)
        }
    }

    private fun gravity(atNs: Long) = RouteCompassVectorSample(
        0.0, 9.80665, 0.0, atNs / 1_000_000L, EarthOrientationAccuracy.HIGH, atNs,
    )

    private fun magnetic(atNs: Long) = RouteCompassVectorSample(
        0.0, -40.0, -30.0, atNs / 1_000_000L, EarthOrientationAccuracy.HIGH, atNs,
    )

    private fun matrix(heading: Double): RotationMatrix3 {
        val h = Math.toRadians(heading)
        return RotationMatrix3(
            cos(h).toFloat(), 0f, (-sin(h)).toFloat(),
            (-sin(h)).toFloat(), 0f, (-cos(h)).toFloat(),
            0f, 1f, 0f,
        )
    }
}
