package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStepTrackerStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
    ).readText()

    @Test
    fun exposesOptionalTimestampedStepEventWithoutBreakingExistingCallers() {
        assertTrue(source.contains("data class StepEvent("))
        assertTrue(source.contains("val deltaSteps: Int"))
        assertTrue(source.contains("val timestampMs: Long"))
        assertTrue(source.contains("val source: StepEventSource"))
        assertTrue(source.contains("val confidence: StepEventConfidence"))
        assertTrue(source.contains("private val onStepEvent: (StepEvent) -> Unit = {}"))
        assertTrue(source.contains("timestampMs = timestampNs / NANOS_PER_MILLISECOND"))
    }

    @Test
    fun separatesUiCountFromPreferredPdrSource() {
        assertTrue(source.contains("Sensor.TYPE_STEP_COUNTER"))
        assertTrue(source.contains("Sensor.TYPE_STEP_DETECTOR"))
        assertTrue(source.contains("if (!stepDetectorRegistered && deltaSteps > 0)"))
        assertTrue(source.contains("if (!stepCounterRegistered)"))
        assertTrue(source.contains("source = StepEventSource.STEP_DETECTOR"))
        assertTrue(source.contains("confidence = StepEventConfidence.HIGH"))
        assertTrue(source.contains("source = StepEventSource.STEP_COUNTER"))
        assertTrue(source.contains("confidence = StepEventConfidence.MEDIUM"))
    }

    @Test
    fun usesLowConfidenceAccelerometerOnlyWhenHardwareStepSensorsAreUnavailable() {
        assertTrue(source.contains("if (!stepCounterRegistered && !stepDetectorRegistered)"))
        assertTrue(source.contains("source = StepEventSource.ACCELEROMETER_FALLBACK"))
        assertTrue(source.contains("confidence = StepEventConfidence.LOW"))
    }

    @Test
    fun preservesRegistrationLeaseAgainstQueuedCallbacks() {
        assertTrue(source.contains("generation != registrationGeneration"))
        assertTrue(source.contains("currentListener !== listener"))
        assertTrue(source.contains("registrationGeneration += 1"))
        assertTrue(source.contains("listener?.let(sensorManager::unregisterListener)"))
    }
}
