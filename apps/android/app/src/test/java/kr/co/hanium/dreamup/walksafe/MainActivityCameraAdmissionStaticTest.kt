package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityCameraAdmissionStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun walkingCallsUseAdmissionAndTheDevicePreflightUsesItsOwnCurrentBindingFence() {
        assertEquals(3, source.windowed("frameDetector.detect".length)
            .count { it == "frameDetector.detect" })
        assertTrue(source.contains("val admission = CameraDetectorAdmissionPolicy.admit("))
        assertTrue(source.contains("if (!admission.detectorInvocationAllowed) return"))
        assertTrue(source.contains("if (!admission.detectorInvocationAllowed) {"))
        val preflight = source.substringAfter(
            "private fun startPostLoginCameraFallbackPreflight(",
        ).substringBefore("private fun isPostLoginCameraFallbackPreflightCurrent(")
        assertTrue(
            preflight.contains(
                "if (!isPostLoginCameraFallbackPreflightCurrent(binding, generation)) {",
            ),
        )
        assertTrue(preflight.indexOf("isPostLoginCameraFallbackPreflightCurrent") <
            preflight.indexOf("frameDetector.detect"))
    }

    @Test
    fun arAdmissionUsesCaptureTimeAndCurrentElapsedTimeSeparately() {
        assertTrue(source.contains("nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),"))
        assertTrue(source.contains("observedAtElapsedRealtimeMs = elapsedRealtimeMs,"))
    }
}
