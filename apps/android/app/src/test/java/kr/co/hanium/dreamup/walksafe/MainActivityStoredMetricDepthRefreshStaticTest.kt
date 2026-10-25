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
        assertTrue(bind.contains("prepareStoredMetricRefresh("))
        assertFalse(bind.contains("startPostLoginDeviceCheckRuntime("))
        assertFalse(bind.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(prepare.contains("snapshot.disabledFeatures.toSet()"))
        assertTrue(prepare.contains("deferred"))
        assertTrue(start.contains("facts.actor().foreground"))
        assertTrue(start.contains("probes.mayStartStoredMetricRefresh(context.binding)"))
        val native = MainActivityRefactorSource.property(source, "postLoginDeviceProbesHolder")
        assertTrue(native.contains("hasCameraPermission() && !isWalkSessionRuntimeActive()"))
        assertTrue(native.contains("!runtimeCameraStartBlockedByAnotherOwner()"))
        assertTrue(native.contains("runtimeMetricSession.beginStoredDepthRefresh(binding)"))
        assertTrue(start.contains("probes.startStoredMetricRefresh(context.binding)"))
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

        assertTrue(current.contains("current.bindingOrNull == context.binding"))
        assertTrue(current.contains("current.state == context.state"))
        assertTrue(current.contains("current.disabledFeatures == context.disabledFeatures"))
        assertTrue(current.contains("binding.actorId == context.binding.actorId"))
        assertTrue(
            current.contains(
                "binding.sessionGeneration == context.binding.sessionGeneration",
            ),
        )
        assertTrue(preflightCurrent.contains("attempt.ownerCurrent(device)"))
        assertTrue(preflightCurrent.contains("device.bindingLive(binding)"))
        val deviceOwner = metric.substringAfter("private class DeviceCheckOwner").substringBefore("private class StoredRefreshOwner")
        assertTrue(deviceOwner.contains("RuntimeMetricPreflightOwner.DEVICE_CHECK"))
        assertTrue(deviceOwner.contains("device.firstRunAllowsPreflight"))
        val storedOwner = metric.substringAfter("private class StoredRefreshOwner").substringBefore("private class MetricPreflightAttempt")
        assertTrue(storedOwner.contains("RuntimeMetricPreflightOwner.STORED_DEPTH_REFRESH"))
        assertTrue(storedOwner.contains("device.storedRefreshCurrent(binding)"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("isStoredMetricDepthRefreshContextCurrent(it)"))

    }

    @Test
    fun conclusiveSupportResultChangesOnlyMetricFeatureAndPublishesAfterSave() {
        val complete = functionBlock("private fun completeStoredMetricDepthRefresh(")
        val save = complete.indexOf("savedResults.save(")
        val publish = complete.indexOf("publish(next)")
        val override = complete.indexOf("effects.metricCapabilityChanged(available)")

        assertTrue(complete.contains("val available = supported ?: return false"))
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
        val invalidate = MainActivityRefactorSource.member(metric, "invalidateEvidence")
        val resume = functionBlock(
            "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()",
        )
        val allowRetry = resume.indexOf("postLoginDeviceCheckController.allowStoredMetricRetry()")
        val retry = resume.indexOf("maybeStartStoredMetricDepthRefresh()")

        assertTrue(supportCheck.contains("availability != ArCoreApk.Availability.SUPPORTED_INSTALLED"))
        assertTrue(supportCheck.contains("probe.automaticDepthSupported()"))
        val sdkProbe = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/AndroidRuntimeMetricPreflight.kt").readText()
        assertTrue(sdkProbe.contains("session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)"))
        assertTrue(supportCheck.contains("finishDeviceMetricDepthSupportCheck("))
        assertFalse(supportCheck.contains("requestInstall("))
        assertFalse(supportCheck.contains("startRuntimeMetricPreflightSession("))
        assertTrue(invalidate.contains("metricPreflightAttempt = metricPreflightAttempt.retired()"))
        assertFalse(invalidate.contains("clearStoredMetricDepthRefresh()"))
        assertTrue(allowRetry >= 0)
        assertTrue(retry > allowRetry)
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
    private val metric = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
}
