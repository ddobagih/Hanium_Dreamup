package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-6: the readiness collector reports a disabled camera, location or model feature through
 * WalkSessionFeatureReadiness (UNAVAILABLE with its cause), and the admission check blocks such a
 * start before it can turn into a safety stop.
 */
class MainActivityWalkReadinessDisabledFeatureStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun disabledCameraLocationAndModelAreNeverCollectedAsReady() {
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val camera = capture.substringAfter("WalkSessionReadinessRequirement.CAMERA ->")
            .substringBefore("WalkSessionReadinessRequirement.METRIC_DISTANCE ->")
        val model = capture.substringAfter("WalkSessionReadinessRequirement.MODEL ->")
            .substringBefore("WalkSessionReadinessRequirement.VOICE_INPUT ->")

        assertTrue(camera.contains("WalkSessionFeatureReadiness.camera("))
        assertTrue(camera.contains("guidanceEnabled = cameraAnalysisFeaturesEnabled()"))
        assertTrue(camera.contains("WalkSessionFeatureReadiness.location("))
        assertTrue(camera.contains("PostLoginDeviceCheckFeature.LOCATION_GUIDANCE"))
        assertFalse(camera.contains("WalkSessionReadinessStatus.READY to \"\""))
        assertTrue(model.contains("WalkSessionFeatureReadiness.model("))
        assertTrue(model.contains("cameraGuidanceEnabled = cameraAnalysisFeaturesEnabled()"))
        assertFalse(capture.contains("!cameraAnalysisFeaturesEnabled() || detectorAvailable"))
    }

    @Test
    fun admissionBlocksDisabledCameraOrLocationBeforeCollectingReadiness() {
        val block = functionBlock("private fun walkSessionReadinessBlockReason(")
        val afterPermissions = block.substringAfter("missingRequiredWalkSessionPermissions(action)")

        assertTrue(afterPermissions.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(afterPermissions.contains("카메라 장애물 안내를 사용할 수 없어 보행 안내를 시작하지 않습니다."))
        assertTrue(
            afterPermissions.contains(
                "if (!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE))",
            ),
        )
        assertTrue(afterPermissions.contains("정확한 위치를 사용할 수 없어 보행 안내를 시작하지 않습니다."))
    }

    @Test
    fun storedObstacleDetectionLimitStaysRecheckable() {
        val retryable = source
            .substringAfter("private fun deferredCameraDependentChecksCanBeRetried()")
            .substringBefore("private fun cameraAnalysisFeaturesEnabled()")

        assertTrue(retryable.contains("postLoginCameraDependentChecksDeferred ||"))
        assertTrue(
            retryable.contains(
                "PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in\n" +
                    "                    postLoginDeviceCheckSnapshot.disabledFeatures",
            ),
        )
    }

    @Test
    fun cameraLossDuringAWalkKeepsTheSafetyStopNotice() {
        val fallbackFailure = functionBlock("private fun continueAfterCameraFallbackFailure(")
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(
            fallbackFailure.indexOf("refreshStartupCapabilityUi()") <
                fallbackFailure.indexOf(
                    "if (walkSessionLifecycle.snapshot().state != WalkSessionState.ACTIVE) return",
                ),
        )
        assertTrue(
            fallbackFailure.indexOf(
                "if (walkSessionLifecycle.snapshot().state != WalkSessionState.ACTIVE) return",
            ) < fallbackFailure.indexOf("startWalkSessionRuntimeWithoutCamera()"),
        )
        val afterRefresh = observed.substringAfter("refreshStartupCapabilityUi()")
        assertTrue(
            afterRefresh.contains(
                "if (walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE) {",
            ),
        )
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}
