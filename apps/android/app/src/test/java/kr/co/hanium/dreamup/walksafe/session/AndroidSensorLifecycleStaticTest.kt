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
    }
}
