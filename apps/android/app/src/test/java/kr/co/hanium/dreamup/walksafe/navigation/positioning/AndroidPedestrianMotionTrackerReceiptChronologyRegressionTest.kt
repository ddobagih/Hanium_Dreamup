package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032/048: actual serial callback receipts precede all motion state mutations. */
class AndroidPedestrianMotionTrackerReceiptChronologyRegressionTest {
    @Test
    fun backwardsSameSourceReceiptCannotConsumeMeasurementOrPendingStepBeforeRetry() {
        val fixture = Fixture()
        assertTrue(fixture.sample(ACCELEROMETER, SECOND, 2 * SECOND))
        assertTrue(fixture.tracker.recordStep(1_500L))
        fixture.assertRejectedWithoutPublication(ACCELEROMETER, 1_500 * MS, 1_900 * MS)
        assertTrue(fixture.sample(ACCELEROMETER, 1_500 * MS, 2_100 * MS))
        assertEquals(listOf(1_000L, 1_500L), fixture.decisions.map { it.sample?.elapsedRealtimeMs })
        assertTrue(requireNotNull(fixture.decisions.last().sample).stepDetected)
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_500 * MS, 2_100 * MS)
        assertTrue(fixture.sample(ACCELEROMETER, 1_600 * MS, 2_200 * MS))
        assertFalse(requireNotNull(fixture.decisions.last().sample).stepDetected)
    }

    @Test
    fun backwardsCrossSourceReceiptCannotConsumeTheOtherSourcesMeasurementRetry() {
        val fixture = Fixture()
        assertTrue(fixture.sample(ACCELEROMETER, SECOND, 2 * SECOND))
        fixture.assertRejectedWithoutPublication(GYROSCOPE, 1_500 * MS, 1_900 * MS)
        assertTrue(fixture.sample(GYROSCOPE, 1_500 * MS, 2_100 * MS))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, SECOND, 2 * SECOND)
        fixture.assertSample(TrackedSensorSource.GYROSCOPE, 1_500 * MS, 2_100 * MS)
        assertEquals(listOf(1_000L, 1_500L), fixture.decisions.map { it.sample?.elapsedRealtimeMs })
    }

    @Test
    fun unknownReceiptsAcrossBothSourcesDoNotEraseTheLastKnownCallbackReceipt() {
        val fixture = Fixture()
        assertTrue(fixture.sample(ACCELEROMETER, 500 * MS))
        assertTrue(fixture.sample(GYROSCOPE, SECOND, 2 * SECOND))
        assertTrue(fixture.sample(ACCELEROMETER, 1_200 * MS))
        assertTrue(fixture.sample(GYROSCOPE, 1_300 * MS))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_200 * MS, null)
        fixture.assertSample(TrackedSensorSource.GYROSCOPE, 1_300 * MS, null)
        fixture.assertRejectedWithoutPublication(ACCELEROMETER, 1_500 * MS, 1_900 * MS)
        assertTrue(fixture.sample(ACCELEROMETER, 1_500 * MS, 2_100 * MS))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_500 * MS, 2_100 * MS)
    }

    @Test
    fun originalReceiptNanosecondsRejectASameMillisecondReversalButAcceptEquality() {
        val fixture = Fixture()
        val measurement = 1_500_123_456L
        val receipt = 2_000_987_654L
        assertTrue(fixture.sample(ACCELEROMETER, 1_000_123_456L, receipt))
        fixture.assertRejectedWithoutPublication(GYROSCOPE, measurement, receipt - 1L)
        assertTrue(fixture.sample(GYROSCOPE, measurement, receipt))
        fixture.assertSample(TrackedSensorSource.GYROSCOPE, measurement, receipt)
        assertEquals(1_500L, fixture.decisions.last().sample?.elapsedRealtimeMs)
    }

    @Test
    fun earlierCrossSourceMeasurementWithAnOrderedActualReceiptRemainsAccepted() {
        val fixture = Fixture()
        assertTrue(fixture.sample(ACCELEROMETER, 1_000_987_654L, 1_100_123_456L))
        assertTrue(fixture.sample(GYROSCOPE, 900_123_456L, 1_200_987_654L))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_000_987_654L, 1_100_123_456L)
        fixture.assertSample(TrackedSensorSource.GYROSCOPE, 900_123_456L, 1_200_987_654L)
        assertEquals(1, fixture.decisions.size)
        assertTrue(fixture.sample(GYROSCOPE, 1_050_123_456L, 1_200_987_654L))
        assertEquals(2, fixture.decisions.size)
        assertTrue(fixture.sample(ACCELEROMETER, 1_100_987_654L))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_100_987_654L, null)
    }

    @Test
    fun invalidValuesOrMeasurementTimesDoNotConsumeTheirLaterKnownReceipt() {
        val invalidInputs = listOf(
            floatArrayOf(Float.NaN, 0f, 0f) to 1_500 * MS,
            floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f) to 1_500 * MS,
            floatArrayOf(1f) to 1_500 * MS,
            ACCELERATION to SECOND,
            ACCELERATION to -1L,
        )
        for ((values, measuredAt) in invalidInputs) {
            val fixture = Fixture()
            assertTrue(fixture.sample(ACCELEROMETER, SECOND, 2 * SECOND))
            fixture.assertRejectedWithoutPublication(ACCELEROMETER, measuredAt, 10 * SECOND, values)
            assertTrue(fixture.sample(ACCELEROMETER, 1_500 * MS, 2_100 * MS))
            fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_500 * MS, 2_100 * MS)
        }
    }

    @Test
    fun rejectedMagnitudeCannotEnterCoverageOrStatisticsBeforeTheValidRetry() {
        val fixture = Fixture()
        for (at in 1_000L..1_200L step 50L) {
            assertTrue(fixture.sample(ACCELEROMETER, at * MS, 2 * SECOND))
        }
        val before = requireNotNull(fixture.decisions.last().sample).accelerationWindow
        assertEquals(PedestrianMotionWindowCoverage(5, 1_000L, 1_200L, 50L), before)
        fixture.assertRejectedWithoutPublication(ACCELEROMETER, 1_250 * MS, 1_900 * MS,
            floatArrayOf(100f, 0f, 0f))
        assertTrue(fixture.sample(ACCELEROMETER, 1_250 * MS, 2_100 * MS))
        val accepted = requireNotNull(fixture.decisions.last().sample)
        assertEquals(PedestrianMotionWindowCoverage(6, 1_000L, 1_250L, 50L), accepted.accelerationWindow)
        assertEquals(9.81f.toDouble(), requireNotNull(accepted.accelerationMagnitudeMeanMps2), 1e-9)
        assertEquals(0.0, requireNotNull(accepted.accelerationMagnitudeVarianceMps4), 1e-12)
        assertNull(accepted.gyroscopeWindow)
        assertNull(accepted.gyroscopeRmsRadPerSecond)
    }

    @Test
    fun stopStartClearsCallbackReceiptAlongWithThePerSourceMeasurementClocks() {
        val fixture = Fixture()
        assertTrue(fixture.sample(ACCELEROMETER, SECOND, 20 * SECOND))
        val oldHealth = fixture.tracker.operationalHealth
        fixture.tracker.stop()
        assertTrue(fixture.tracker.start())
        assertTrue(fixture.sample(ACCELEROMETER, SECOND, 1_100 * MS))
        fixture.assertSample(TrackedSensorSource.ACCELEROMETER, SECOND, 1_100 * MS)
        assertEquals(3, fixture.tracker.operationalHealth.registrationGeneration)
        assertEquals(20 * SECOND, oldHealth.source(TrackedSensorSource.ACCELEROMETER)?.lastAcceptedSample?.receivedAtElapsedRealtimeNanos)
        assertTrue(fixture.sample(GYROSCOPE, 900 * MS, 1_200 * MS))
        assertEquals(2, fixture.decisions.size)
    }

    @Test
    fun reentrantDecisionRestartCannotRestoreAnOldReceiptIntoTheNewGeneration() {
        lateinit var tracker: AndroidPedestrianMotionTracker
        var restart = true
        val decisions = mutableListOf<PedestrianStationaryDecision>()
        tracker = AndroidPedestrianMotionTracker(Registration, onDecision = {
            decisions += it
            if (restart) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        })
        assertTrue(tracker.start())
        assertTrue(tracker.onSensorSample(ACCELEROMETER, ACCELERATION, SECOND, 20 * SECOND))
        assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
        assertTrue(tracker.onSensorSample(ACCELEROMETER, ACCELERATION, 2 * SECOND, 2_100 * MS))
        assertEquals(3, tracker.operationalHealth.registrationGeneration)
        assertEquals(2, decisions.size)
        assertEquals(2_100 * MS, tracker.operationalHealth.source(TrackedSensorSource.ACCELEROMETER)
            ?.lastAcceptedSample?.receivedAtElapsedRealtimeNanos)
    }

    @Test
    fun nonzeroRawVectorsPublishExactWindowStatisticsAndCoverage() {
        val fixture = Fixture()
        // Exact magnitudes 9, 10, 11, 10, 9; the two 10s use scaled 3-4-5 vectors.
        val acceleration = arrayOf(floatArrayOf(0f, 0f, 9f), floatArrayOf(6f, 8f, 0f),
            floatArrayOf(0f, 11f, 0f), floatArrayOf(0f, 6f, 8f), floatArrayOf(9f, 0f, 0f))
        val angularMagnitudes = floatArrayOf(0f, 1f, 0f, 1f, 0f)
        try {
            for (index in acceleration.indices) {
                val measuredAtNs = (1_000L + index * 50L) * MS
                assertTrue(fixture.tracker.onSensorSample(ACCELEROMETER, acceleration[index],
                    measuredAtNs, 2 * SECOND))
                // Offset the gyro by 1 ms so its last callback emits both complete windows.
                assertTrue(fixture.tracker.onSensorSample(GYROSCOPE,
                    floatArrayOf(0f, angularMagnitudes[index], 0f), measuredAtNs + MS, 2 * SECOND))
            }
            assertEquals(10, fixture.decisions.size)
            val decision = fixture.decisions.last()
            assertTrue(decision.accepted)
            val sample = requireNotNull(decision.sample)
            assertEquals(1_201L, sample.elapsedRealtimeMs)
            assertEquals(PedestrianMotionWindowCoverage(5, 1_000L, 1_200L, 50L), sample.accelerationWindow)
            assertEquals(PedestrianMotionWindowCoverage(5, 1_001L, 1_201L, 50L), sample.gyroscopeWindow)
            assertEquals(9.8, requireNotNull(sample.accelerationMagnitudeMeanMps2), 1e-12)
            assertEquals(0.56, requireNotNull(sample.accelerationMagnitudeVarianceMps4), 1e-12)
            assertEquals(kotlin.math.sqrt(0.4), requireNotNull(sample.gyroscopeRmsRadPerSecond), 1e-12)
            assertEquals(1.0, requireNotNull(sample.gyroscopePeakRadPerSecond), 1e-12)
            fixture.assertSample(TrackedSensorSource.ACCELEROMETER, 1_200 * MS, 2 * SECOND)
            fixture.assertSample(TrackedSensorSource.GYROSCOPE, 1_201 * MS, 2 * SECOND)
            assertFalse(decision.shouldApplyZeroVelocityUpdate)
        } finally {
            fixture.tracker.close()
        }
    }

    private class Fixture {
        val decisions = mutableListOf<PedestrianStationaryDecision>()
        val tracker = AndroidPedestrianMotionTracker(Registration, onDecision = decisions::add)
            .also { assertTrue(it.start()) }

        fun sample(type: Int, measurementNs: Long, receiptNs: Long? = null): Boolean =
            tracker.onSensorSample(type, if (type == ACCELEROMETER) ACCELERATION else GYROSCOPE_VALUES,
                measurementNs, receiptNs)

        fun assertSample(source: TrackedSensorSource, measurementNs: Long, receiptNs: Long?) {
            val actual = requireNotNull(tracker.operationalHealth.source(source)?.lastAcceptedSample)
            assertEquals(measurementNs, actual.measurementElapsedRealtimeNanos)
            assertEquals(receiptNs, actual.receivedAtElapsedRealtimeNanos)
        }

        fun assertRejectedWithoutPublication(type: Int, measurementNs: Long, receiptNs: Long,
            values: FloatArray = if (type == ACCELEROMETER) ACCELERATION else GYROSCOPE_VALUES) {
            val oldHealth = tracker.operationalHealth
            val oldDecisions = decisions.toList()
            assertFalse("Impossible callback receipt must be rejected before motion state changes",
                tracker.onSensorSample(type, values, measurementNs, receiptNs))
            assertSame(oldHealth, tracker.operationalHealth)
            assertEquals(oldDecisions, decisions)
        }
    }

    private object Registration : MotionSensorRegistration {
        override fun hasSensor(type: Int) = type == ACCELEROMETER || type == GYROSCOPE
        override fun register(listener: SensorEventListener, type: Int) = hasSensor(type)
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val ACCELEROMETER = Sensor.TYPE_ACCELEROMETER
        const val GYROSCOPE = Sensor.TYPE_GYROSCOPE
        val ACCELERATION = floatArrayOf(0f, 0f, 9.81f)
        val GYROSCOPE_VALUES = floatArrayOf(0f, 0f, 0f)
    }
}
