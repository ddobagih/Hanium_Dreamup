package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPostLoginDeviceCheckStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun loginDoesNotAutomaticallyStartTheDeviceCheck() {
        val login = source.substringAfter("private fun loginEmailAccount(")
            .substringBefore("private fun postAccountFailure(")

        assertFalse(login.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(source.contains("setOnClickListener { showPostLoginDeviceCheckExplanation() }"))
        assertTrue(source.contains("PostLoginDeviceCheckPolicy.beginFromUserAction("))
    }

    @Test
    fun permissionAndDeviceStagesUseLocalTransitionsAndGateAllWalkFeatures() {
        assertTrue(source.contains("PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK"))
        assertTrue(source.contains("recordEmailJitPermissionObservation("))
        assertTrue(source.contains("recordEmailDeviceCheckPassed("))
        assertTrue(source.contains("postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(source.contains("firstRunOnboardingSnapshot.mayEnterWalk &&"))
    }

    @Test
    fun limitedRequiresOneIsolatedCameraXFrameAndDetectorSuccess() {
        assertTrue(
            source.contains(
                "Proves the LIMITED fallback with one isolated CameraX frame and no user-facing output",
            ),
        )
        val preflight = source.substringAfter(
            "private fun startPostLoginCameraFallbackPreflight(",
        ).substringBefore("private fun isPostLoginCameraFallbackPreflightCurrent(")
        assertTrue(preflight.contains("ProcessCameraProvider.getInstance(this)"))
        assertTrue(preflight.contains("frameClaimed.compareAndSet(false, true)"))
        assertTrue(preflight.contains("frameDetector.detect("))
        assertTrue(preflight.contains("finishPostLoginCameraFallbackPreflight("))
        assertFalse(preflight.contains("dispatchFeedback("))
        assertFalse(preflight.contains("processReportCandidate("))
        assertFalse(preflight.contains("recordRawCollectionDetectionMetadata("))
    }

    @Test
    fun statusIsAccessibleAndFailureOffersSettingsRecovery() {
        assertTrue(source.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(source.contains("postLoginDeviceCheckSettingsButton"))
        assertTrue(source.contains("Settings.ACTION_LOCATION_SOURCE_SETTINGS"))
        assertTrue(source.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
    }
}
