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
        val capture = owner("session/readiness/WalkReadinessCoordinator.kt", "capture")
        val producer = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText()
        val camera = MainActivityRefactorSource.member(producer, "camera")
        val location = MainActivityRefactorSource.member(producer, "location")
        val model = MainActivityRefactorSource.member(producer, "model")
        val wiring = MainActivityRefactorSource.property(source, "walkReadinessCoordinator")
        assertTrue(wiring.contains("WalkReadinessCoordinator("))
        assertTrue(capture.replace(Regex("\\s+"), "").contains(
            "producers.evaluate(requirement,effectiveDecision,epoch,officialAssessment,mountingAssessment,deviceResources,allowModelPreparationPending)",
        ))
        val features = MainActivityRefactorSource.property(source, "walkReadinessCoordinator")
        assertTrue(features.contains("FeatureReadinessProducer("))
        assertTrue(features.contains("override fun cameraEnabled() = cameraAnalysisFeaturesEnabled()"))
        assertTrue(features.contains("PostLoginDeviceCheckFeature.LOCATION_GUIDANCE"))

        assertTrue(camera.contains("WalkSessionFeatureReadiness.camera("))
        assertTrue(camera.contains("guidanceEnabled = admission.cameraEnabled()"))
        assertTrue(location.contains("WalkSessionFeatureReadiness.location("))
        assertTrue(location.contains("guidanceEnabled = admission.locationEnabled()"))
        assertFalse(camera.contains("WalkSessionReadinessStatus.READY to \"\""))
        assertTrue(model.contains("WalkSessionFeatureReadiness.model("))
        assertTrue(model.contains("val state = detector.modelState()"))
        assertTrue(model.contains("WalkSessionFeatureReadiness.model(admission.cameraEnabled(), state.available, state.failureReason,"))
        assertTrue(model.contains("preparing = allowPreparationPending && state.loadInFlight && admission.cameraPermission()"))
        assertFalse(model.contains("!admission.cameraEnabled() || detector.isAvailable()"))
    }

    @Test
    fun admissionBlocksDisabledCameraOrLocationBeforeCollectingReadiness() {
        val block = functionBlock("private fun walkSessionReadinessBlockReason(")
        val afterPermissions = block.substringAfter("sources.missingRequiredPermissions(action)")

        assertTrue(afterPermissions.contains("if (!sources.cameraEnabled())"))
        assertTrue(afterPermissions.contains("카메라 장애물 안내를 사용할 수 없어 보행 안내를 시작하지 않습니다."))
        assertTrue(
            afterPermissions.contains(
                "if (!sources.locationFeatureEnabled())",
            ),
        )
        assertTrue(afterPermissions.contains("정확한 위치를 사용할 수 없어 보행 안내를 시작하지 않습니다."))
    }

    @Test
    fun storedObstacleDetectionLimitStaysRecheckable() {
        val retryable = MainActivityRefactorSource.function(source, "private fun deferredCameraDependentChecksCanBeRetried()")

        assertTrue(retryable.contains("facts.cameraChecksDeferred ||"))
        assertTrue(
            retryable.contains(
                "PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in\n" +
                    "                    facts.snapshot.disabledFeatures",
            ),
        )
    }

    @Test
    fun cameraLossDuringAWalkKeepsTheSafetyStopNotice() {
        val fallbackFailure = functionBlock("private fun continueAfterCameraFallbackFailure(")
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(
            fallbackFailure.indexOf("readiness.refreshUi()") <
                fallbackFailure.indexOf(
                    "if (walk().snapshot().state != WalkSessionState.ACTIVE) return",
                ),
        )
        assertTrue(
            fallbackFailure.indexOf(
                "if (walk().snapshot().state != WalkSessionState.ACTIVE) return",
            ) < fallbackFailure.indexOf("readiness.startWithoutCamera()"),
        )
        val afterRefresh = observed.substringAfter("runtime.refreshStartup()")
        assertTrue(
            afterRefresh.contains(
                "if (authority.lifecycleOrNull()!!.snapshot().state == WalkSessionState.ACTIVE) {",
            ),
        )
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)

    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )
}
