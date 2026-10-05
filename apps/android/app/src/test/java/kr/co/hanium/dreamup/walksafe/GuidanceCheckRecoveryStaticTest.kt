package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the wiring that keeps a failed preflight recoverable without starting a walk. */
class GuidanceCheckRecoveryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test fun failureHasTwoLargeControlsAndDoesNotSpeakOnEveryRender() {
        assertTrue(source.contains("guidanceVoiceButton.visibility = if (checkFailed) View.GONE else View.VISIBLE"))
        assertTrue(source.contains("nativeGuidanceRetryButton.text = \"재점검\""))
        assertTrue(source.contains("nativeGuidanceCancelButton.text = \"취소\""))
        assertTrue(source.contains("if (hasWalk || checkFailed) 144"))
        assertTrue(source.contains("if (guidanceFailureAnnouncedForRequest == requestId) return"))
        assertTrue(source.contains("nativePhoneMountingCheckRequestId == requestId && nativeGuidanceCheckFailed()"))
    }

    @Test fun failureExplainsLocationAndCameraAndPreservesReadinessGate() {
        val message = source.substringAfter("private fun nativeGuidanceFailureMessage()").substringBefore("private fun ")
        listOf("GPS_ACCURACY_OUTSIDE_APPROVED_RANGE", "LOCATION_REQUEST_FAILED",
            "OCCLUSION_OUTSIDE_APPROVED_RANGE", "SHAKE_OUTSIDE_APPROVED_RANGE",
            "BRIGHTNESS_OUTSIDE_APPROVED_RANGE", "CAMERA_BIND_FAILED").forEach {
            assertTrue(message.contains(it))
        }
        assertTrue(source.contains("environmentReadiness.first != WalkSessionReadinessStatus.READY"))
        assertTrue(source.contains("mountingReadiness.first != WalkSessionReadinessStatus.READY"))
        assertTrue(source.contains("onClick = ::startNativeDestinationGuidance"))
        assertTrue(source.contains("onClick = ::cancelNativeGuidanceAndReturnHome"))
        assertTrue(source.contains("if (nativeUiPage == NativeUiPage.GUIDANCE && nativeGuidanceCheckFailed()) renderMainUi()"))
    }
}
