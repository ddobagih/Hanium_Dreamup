package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPedestrianMotionTrackerStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/AndroidPedestrianMotionTracker.kt",
    ).readText()

    @Test
    fun registersBothAvailableMotionSensorsAndAcceptsPartialAvailability() {
        assertTrue(source.contains("Sensor.TYPE_ACCELEROMETER"))
        assertTrue(source.contains("Sensor.TYPE_GYROSCOPE"))
        assertTrue(source.contains("accelerometerRegistered = accelerometer?.let"))
        assertTrue(source.contains("gyroscopeRegistered = gyroscope?.let"))
        assertTrue(source.contains("started = accelerometerRegistered || gyroscopeRegistered"))
    }

    @Test
    fun convertsMonotonicSensorWindowsIntoDetectorSamples() {
        assertTrue(source.contains("timestampNs <= lastAccelerometerTimestampNs"))
        assertTrue(source.contains("timestampNs <= lastGyroscopeTimestampNs"))
        assertTrue(source.contains("ArrayDeque<TimedMagnitude>"))
        assertTrue(source.contains("trimBefore(accelerationWindow"))
        assertTrue(source.contains("trimBefore(gyroscopeWindow"))
        assertTrue(source.contains("elapsedRealtimeMs = timestampMs"))
        assertTrue(source.contains("detector.observe("))
        assertTrue(source.contains("accelerationMagnitudeMeanMps2 = acceleration?.mean"))
        assertTrue(source.contains("gyroscopeRmsRadPerSecond = angularVelocity?.rootMeanSquare"))
    }

    @Test
    fun acceptsCrossSensorReorderingAgainstIndependentHighWatermarks() {
        assertTrue(source.contains("private var lastAccelerometerTimestampNs = NO_TIMESTAMP"))
        assertTrue(source.contains("private var lastGyroscopeTimestampNs = NO_TIMESTAMP"))
        assertTrue(source.contains("if (!acceptTimestamp(event.sensor.type, timestampNs)) return"))
        assertTrue(source.contains("lastAccelerometerTimestampNs = timestampNs"))
        assertTrue(source.contains("lastGyroscopeTimestampNs = timestampNs"))
        assertFalse(source.contains("private var lastAcceptedTimestampNs"))
    }

    @Test
    fun consumesRecentStepEvidenceOnceInTheActiveRegistrationGeneration() {
        assertTrue(source.contains("fun recordStep(timestampMs: Long): Boolean"))
        assertTrue(source.contains("if (timestampMs < 0L) return false"))
        assertTrue(source.contains("if (timestampMs <= lastRecordedStepTimestampMs)"))
        assertTrue(source.contains("!started ||"))
        assertTrue(source.contains("generation != registrationGeneration"))
        assertTrue(source.contains("pendingStepGeneration = generation"))
        assertTrue(source.contains("stepDetected = consumeRecentStep(timestampMs)"))
        assertTrue(source.contains("sampleTimestampMs - stepTimestampMs <= RECENT_STEP_WINDOW_MS"))
        assertTrue(source.contains("clearPendingStepLocked()"))
        assertTrue(source.contains("const val RECENT_STEP_WINDOW_MS = 2_000L"))
    }

    @Test
    fun clearsStepEvidenceWithMotionStateOnStopAndRestart() {
        assertTrue(source.contains("private fun clearMotionState()"))
        assertTrue(source.contains("clearStepEvidenceLocked()"))
        assertTrue(source.contains("lastRecordedStepTimestampMs = NO_TIMESTAMP"))
        assertTrue(source.contains("pendingStepTimestampMs = null"))
        assertTrue(source.contains("pendingStepGeneration = NO_GENERATION"))
    }

    @Test
    fun protectsEveryLifecycleBoundaryFromQueuedCallbacks() {
        assertTrue(source.contains("registrationGeneration == generation"))
        assertTrue(source.contains("currentListener === listener"))
        assertTrue(source.contains("registrationGeneration += 1"))
        assertTrue(source.contains("listener?.let(sensorManager::unregisterListener)"))
        assertTrue(source.contains("override fun close()"))
        assertTrue(source.contains("if (closed) return false"))
    }

    @Test
    fun keepsStationaryThresholdPolicyInTheDetector() {
        assertTrue(source.contains("private val detector: PedestrianStationaryDetector"))
        assertTrue(source.contains("detector.reset()"))
        assertFalse(source.contains("maximumStationaryAccelerationVarianceMps4"))
        assertFalse(source.contains("stationaryDwellMs"))
    }
}
