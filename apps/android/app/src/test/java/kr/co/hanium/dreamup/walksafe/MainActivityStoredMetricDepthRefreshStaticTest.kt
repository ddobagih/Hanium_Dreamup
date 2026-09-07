package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStoredMetricDepthRefreshStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun legacyStoredDepthStartsOnlyTheSelectiveAutomaticPreflight() {
        val bind = functionBlock("private fun bindPostLoginDeviceCheckSession(")
        val prepare = functionBlock("private fun prepareStoredMetricDepthRefresh(")
        val start = functionBlock("private fun maybeStartStoredMetricDepthRefresh()")

        assertTrue(bind.contains("restored.metricDepthProbePolicyCurrent"))
        assertTrue(bind.contains("prepareStoredMetricDepthRefresh("))
        assertFalse(bind.contains("startPostLoginDeviceCheckRuntime("))
        assertFalse(bind.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(prepare.contains("snapshot.disabledFeatures.toSet()"))
        assertTrue(prepare.contains("cameraDependentChecksDeferred"))
        assertTrue(start.contains("isActivityForeground"))
        assertTrue(start.contains("hasCameraPermission()"))
        assertTrue(start.contains("runtimeCameraStartBlockedByAnotherOwner()"))
        assertTrue(start.contains("isWalkSessionRuntimeActive()"))
        assertTrue(start.contains("startRuntimeMetricPreflight("))
        assertFalse(start.contains("requestPermissionsWithLease("))
        assertFalse(start.contains("startPostLoginDeviceCheckRuntime("))
    }

    @Test
    fun selectiveAttemptIsFencedBySessionAttemptAndOriginalSnapshot() {
        val current = functionBlock(
            "private fun isStoredMetricDepthRefreshContextCurrent(",
        )
        val preflightCurrent = functionBlock(
            "private fun isRuntimeMetricPreflightAttemptCurrent(",
        )

        assertTrue(current.contains("snapshot.bindingOrNull == context.binding"))
        assertTrue(current.contains("snapshot.state == context.state"))
        assertTrue(current.contains("snapshot.disabledFeatures == context.disabledFeatures"))
        assertTrue(current.contains("sessionBinding.actorId == context.binding.actorId"))
        assertTrue(
            current.contains(
                "sessionBinding.sessionGeneration == context.binding.sessionGeneration",
            ),
        )
        assertTrue(preflightCurrent.contains("RuntimeMetricPreflightOwner.DEVICE_CHECK"))
        assertTrue(
            preflightCurrent.contains(
                "RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH",
            ),
        )
        assertTrue(preflightCurrent.contains("firstRunDeviceCheckAllowsPreflight()"))
        assertTrue(preflightCurrent.contains("isStoredMetricDepthRefreshContextCurrent(it)"))
    }

    @Test
    fun conclusiveSupportResultChangesOnlyMetricFeatureAndPublishesAfterSave() {
        val complete = functionBlock("private fun completeStoredMetricDepthRefresh(")
        val save = complete.indexOf("postLoginDeviceCheckResultStore.save(")
        val publish = complete.indexOf("postLoginDeviceCheckSnapshot = updatedSnapshot")
        val override = complete.indexOf("metricDistanceCapabilityOverride =")

        assertTrue(complete.contains("val metricDistanceAvailable = metricDistanceSupported ?: return false"))
        assertTrue(complete.contains("PostLoginMetricDepthState.SUPPORTED"))
        assertTrue(complete.contains("PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED"))
        assertFalse(complete.contains("RuntimeMetricPreflightStatus."))
        assertTrue(
            complete.contains(
                "context.disabledFeatures - PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE",
            ),
        )
        assertTrue(
            complete.contains(
                "context.disabledFeatures + PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE",
            ),
        )
        assertTrue(complete.contains("PostLoginDeviceCheckState.FULL"))
        assertTrue(complete.contains("PostLoginDeviceCheckState.LIMITED"))
        assertTrue(complete.contains("context.cameraDependentChecksDeferred"))
        assertTrue(save >= 0)
        assertTrue(publish > save)
        assertTrue(override > save)
        assertFalse(complete.contains("maybeContinuePostLoginDeviceCheck("))
        assertFalse(complete.contains("completePostLoginDeviceCheckPass("))
    }

    @Test
    fun supportOnlyCheckAndLifecycleKeepAutomaticRetryPending() {
        val supportCheck = functionBlock("private fun continueRuntimeMetricPreflightStart(")
        val invalidate = functionBlock("private fun invalidateRuntimeMetricEvidence(")
        val resume = functionBlock(
            "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()",
        )
        val allowRetry = resume.indexOf("storedMetricDepthRefreshRetryAllowed = true")
        val retry = resume.indexOf("maybeStartStoredMetricDepthRefresh()")

        assertTrue(supportCheck.contains("availability != ArCoreApk.Availability.SUPPORTED_INSTALLED"))
        assertTrue(supportCheck.contains("supportSession.isDepthModeSupported("))
        assertTrue(supportCheck.contains("finishDeviceMetricDepthSupportCheck("))
        assertFalse(supportCheck.contains("requestInstall("))
        assertFalse(supportCheck.contains("startRuntimeMetricPreflightSession("))
        assertTrue(invalidate.contains("metricPreflightOwner = RuntimeMetricPreflightOwner.NONE"))
        assertFalse(invalidate.contains("clearStoredMetricDepthRefresh()"))
        assertTrue(allowRetry >= 0)
        assertTrue(retry > allowRetry)
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }
}
