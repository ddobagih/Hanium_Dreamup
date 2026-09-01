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
            "private fun startPostLoginCameraPipelinePreflight(",
        ).substringBefore("private fun isPostLoginCameraPipelinePreflightCurrent(")
        assertTrue(
            preflight.contains(
                "if (!isPostLoginCameraPipelinePreflightCurrent(binding, generation)) {",
            ),
        )
        assertTrue(preflight.indexOf("isPostLoginCameraPipelinePreflightCurrent") <
            preflight.indexOf("frameDetector.detect"))
    }

    @Test
    fun arAdmissionUsesCaptureTimeAndCurrentElapsedTimeSeparately() {
        assertTrue(source.contains("nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),"))
        assertTrue(source.contains("observedAtElapsedRealtimeMs = elapsedRealtimeMs,"))
    }

    @Test
    fun walkingAdmissionsUseMeasuredLumaAndMountingSensorsWithTheActiveProfile() {
        val measurement = source.substringAfter(
            "private fun cameraFrameQualityObservation(",
        ).substringBefore("private fun observeOfficialEnvironmentCameraFrame(")
        val fallback = source.substringAfter(
            "private fun analyzeCameraFallbackFrame(",
        ).substringBefore("private fun isFeedbackLifecycleCurrent(")
        val ar = source.substringAfter(
            "val yPlane = cameraImage.planes.firstOrNull()",
        ).substringBefore("val rawCollectionAllowed")

        assertTrue(measurement.contains("CameraLumaMeasurementPolicy.measure("))
        assertTrue(measurement.contains("phoneMountingSensorProbe.latestNow()"))
        assertTrue(fallback.contains("val yPlane = imageProxy.planes.firstOrNull()"))
        assertTrue(fallback.contains("approvedProfile = activeCameraFrameQualityProfile"))
        assertTrue(ar.contains("cameraFrameQualityObservation("))
        assertTrue(source.contains("approvedProfile = activeCameraFrameQualityProfile"))
        assertTrue(source.contains("WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG)"))
    }
}
