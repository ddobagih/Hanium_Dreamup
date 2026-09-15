package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSensorLifecycleStaticTest {
    @Test
    fun stoppedTrackersIgnoreAlreadyQueuedSensorCallbacks() {
        val stepTracker = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
        ).readText()
        val orientationTracker = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
        ).readText()

        assertTrue(
            stepTracker.contains(
                "generation != registrationGeneration",
            ),
        )
        assertTrue(stepTracker.contains("currentListener !== listener"))
        assertTrue(stepTracker.contains("registrationGeneration += 1"))
        assertTrue(stepTracker.contains("fun resetForNewWalk()"))
        assertTrue(stepTracker.contains("private var activeWindowHardwareBaseline: Float? = null"))
        assertTrue(stepTracker.contains("private var totalActiveSteps = 0"))
        assertTrue(
            stepTracker.contains(
                "activeWindowHardwareBaseline = null\n        activeWindowStepOffset = totalActiveSteps",
            ),
        )
        assertTrue(orientationTracker.contains("private var started = false"))
        assertTrue(orientationTracker.contains("registrationGeneration == generation"))
        assertTrue(orientationTracker.contains("currentListener === listener"))
        assertTrue(
            orientationTracker.contains(
                "if (!isCurrentRegistration(listener, generation)) return",
            ),
        )
        assertTrue(
            orientationTracker.contains("registrationGeneration += 1"),
        )
        assertTrue(orientationTracker.contains("listener?.let(sensorManager::unregisterListener)"))
        assertTrue(orientationTracker.contains("Sensor.TYPE_MAGNETIC_FIELD"))
        assertTrue(orientationTracker.contains("val rotationRegistered ="))
        assertTrue(orientationTracker.contains("if (!rotationRegistered)"))
        assertTrue(orientationTracker.contains("chestMountedHeading.reset()"))
    }

    @Test
    fun orientationAccuracyPromotionRequiresANewSensorEvent() {
        val orientationTracker = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
        ).readText()

        assertTrue(
            orientationTracker.contains(
                "sample.copy(accuracy = sample.accuracy.downgradedTo(mappedAccuracy))",
            ),
        )
        assertTrue(orientationTracker.contains("if (reported < this) reported else this"))
    }

    @Test
    fun magneticRegistrationFailureKeepsRotationOnlyStartSuccessful() {
        val orientationTracker = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
        ).readText()

        val start = orientationTracker.substringAfter("fun start(): Boolean {").substringBefore("fun stop()")
        val register = orientationTracker.substringAfter("private fun register(").substringBefore("// Kept as text")
        assertTrue(start.contains("val rotationRegistered = register(listener, rotationVectorSensor)"))
        assertTrue(start.contains("magneticSensorRegistered = register(listener, magneticFieldSensor)"))
        // Rotation success alone is sufficient; the fallback pair is an independent alternative.
        assertTrue(
            start.contains(
                "started = rotationRegistered || (magneticSensorRegistered && (gravityRegistered || accelerometerRegistered))",
            ),
        )
        assertTrue(register.contains("runCatching { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }"))
        assertTrue(register.contains(".getOrDefault(false)"))
        assertTrue(orientationTracker.contains("magneticSensorAvailable = magneticSensorRegistered"))
        assertTrue(start.contains("return true"))
    }
}
