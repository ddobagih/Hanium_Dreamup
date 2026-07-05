package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccessibilityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun screenReaderModeSuppressesAppTtsButKeepsRiskHaptic() {
        assertTrue(source.contains("private fun isScreenReaderActive()"))
        assertTrue(source.contains("isTouchExplorationEnabled"))
        assertTrue(source.contains("actuator.vibrateRiskOnly(action)"))
        assertTrue(source.contains("if (!isScreenReaderActive()) {\n            ensureFeedbackActuator().speakNavigation(message)"))
    }

    @Test
    fun liveRegionAndDebugCaptureAccessibilityContractIsStable() {
        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(source.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(source.contains("statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertTrue(source.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(source.contains("debugFrameCaptureButton.visibility = View.GONE"))
    }

    @Test
    fun feedbackActuatorIsReleasedOnPause() {
        assertTrue(source.contains("override fun onPause()"))
        assertTrue(source.contains("feedbackActuator?.close()\n        feedbackActuator = null\n        surfaceView.onPause()"))
    }
}
