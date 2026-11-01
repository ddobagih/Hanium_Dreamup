package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production wiring: selection never runs at camera entry, nor does loading imply verified output. */
class CameraRuntimePreparationContractTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val camera = File("src/debug/java/kr/co/hanium/dreamup/walksafe/CameraTestActivity.kt").readText()
    private val primary = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature).also { check(it >= 0) }
        val next = Regex("\n    (private |internal |override )fun ").find(source, start + signature.length)?.range?.first
        return source.substring(start, next ?: source.length)
    }

    @Test fun devicePreparationRunsOffUiAndKeepsTheLoadingStatePending() {
        assertTrue(body(main, "private fun loadDetectorForCurrentProcess()").contains("primaryDetectorLifecycleController.loadForCurrentProcess()"))
        val load = body(primary, "fun loadForCurrentProcess(allowComparison: Boolean = false)")
        assertTrue(load.indexOf("val loadTask = Runnable loadTask@{") < load.indexOf("platform.select("))
        assertTrue(load.indexOf("platform.select(") < load.indexOf("detector.tuningLane.execute(loadTask)"))
        assertTrue(load.contains("detector.nativeLane.execute retire@{"))
        assertTrue(load.contains("retireLoadedForReloadOnNativeLane("))
        assertTrue(load.contains("allowComparison = allowComparison"))
        assertTrue(body(main, "private fun loadDetectorForPostLoginDeviceCheck()").contains(
            "primaryDetectorLifecycleController.loadForCurrentProcess(allowComparison = true)"))
        assertTrue(body(main, "private fun loadDetectorForCurrentProcess()").contains(
            "primaryDetectorLifecycleController.loadForCurrentProcess()"))
        assertTrue(load.contains("loadInFlight = true"))
        assertTrue(load.contains("loadInFlight = false"))
        assertTrue(main.contains("!primaryDetectorLifecycleController.metadata().loadAttempted || primaryDetectorLifecycleController.metadata().loadInFlight -> PostLoginDeviceCheckSignal.PENDING"))
        assertTrue(load.contains("scheduleVerification(instance,"))
        assertTrue(load.contains("requestGeneration != loadRequestGeneration"))
        assertTrue(load.contains("load.detector?.let(::closeRetiredLoad)"))
    }

    @Test fun homeDoesNotReplaceTheSavedChoiceWithUnconditionalGpu() {
        val preparation = body(main, "private fun prepareRuntimeServices()")
        assertFalse(preparation.contains("createWithStatus"))
        assertTrue(preparation.contains("nativeUiPage == NativeUiPage.GUIDANCE || isWalkSessionRuntimeActive()"))
    }

    @Test fun cameraIsCacheOnlyAndStartsAuxiliaryAfterPrimaryWarmup() {
        val prepare = body(camera, "private fun prepareCameraDetector()")
        assertTrue(prepare.contains("AndroidJointRuntimeLoader.restore("))
        assertTrue(prepare.contains("resumeUnknownAfterPrimaryLiveAttempt()"))
        assertTrue(prepare.contains("if (detector == null)"))
        assertFalse(prepare.contains("AndroidFixedFixtureRuntimeSelector"))
        val resume = body(camera, "private fun resumeUnknownAfterPrimaryLiveAttempt()")
        assertTrue(resume.indexOf("auxiliaryStartup.mayStart(generation, busy.get())") < resume.indexOf("unknownRuntime.resume()"))
        assertTrue(resume.contains("unknownRuntime.retry()"))
        assertTrue(body(camera, "override fun onResume()").contains("resumeUnknownAfterPrimaryLiveAttempt()"))
        assertTrue(camera.contains("auxiliaryStartup.complete(epoch)"))
    }

    @Test fun cameraSetupCannotPublishAfterPauseOrDestroy() {
        val start = body(camera, "private fun startCamera()")
        assertTrue(start.indexOf("cameraWorker.execute") < start.indexOf("Session(applicationContext)"))
        assertTrue(start.contains("generation == startGeneration"))
        assertTrue(start.contains("foreground && !isDestroyed && !isFinishing"))
        assertTrue(start.contains("releasePreparedCamera("))
        assertTrue(body(camera, "override fun onPause()").contains("generation++"))
        assertTrue(body(camera, "override fun onPause()").contains("unknownRuntime.pauseAndRelease()"))
        val destroy = body(camera, "override fun onDestroy()")
        assertTrue(destroy.contains("if (!startingCamera && (!closingCamera || cameraRelease.failure != null)) cameraWorker.shutdown()"))
        val release = body(camera, "private fun queueCameraRelease(")
        assertTrue(release.indexOf("cameraRelease.execute(attempt)") in 0 until
            release.indexOf("if (isDestroyed || isFinishing) cameraWorker.shutdown()"))
        assertTrue(start.indexOf("if (cameraRelease.failure != null)") in 0 until start.indexOf("cameraWorker.execute"))
    }

    @Test fun cameraEntryReleasesTheMainOwnerBeforeTheTestActivityAllocates() {
        val open = body(main, "private fun openLiveCameraScreen()")
        assertTrue(open.contains("if (!shouldShowNativeHome() || !isActivityForeground) return"))
        assertTrue(open.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(open.contains("cancelDeviceRuntimeCalibration(\"live_camera_requested\")"))
        assertTrue(open.indexOf("primaryDetectorLifecycleController.cancelPendingLoadAfterDrain") < open.indexOf("startActivity("))
        assertTrue(open.contains("startActivity("))
        assertTrue(open.indexOf("releaseForCompositeCheck(current)") < open.indexOf("startActivity("))
        assertTrue(open.contains("releaseBeforePrimaryCalibration()"))
        assertFalse(open.contains("loadDetectorForCurrentProcess()"))
        assertFalse(open.contains("detectorLoadInFlight"))
        assertFalse(main.contains("cameraTestLaunchPending"))
        val create = body(camera, "override fun onCreate(")
        assertFalse("Opening a camera must not run a new performance benchmark",
            create.contains("AndroidFixedFixtureRuntimeSelector.load("))
        val prepare = body(camera, "private fun prepareCameraDetector()")
        assertTrue(prepare.indexOf("worker.execute") < prepare.indexOf("AndroidJointRuntimeLoader.restore("))
        assertTrue(prepare.contains("AndroidJointRuntimeLoader.restore("))
        assertFalse(prepare.contains("choice.scope"))
        assertFalse(prepare.contains("choice.cameraMode"))
        assertTrue(body(camera, "private fun startCamera()").contains("prepareCameraDetector()"))
    }

    @Test fun recheckDuringLoadingCannotResetAttemptAndLeaveSuccessPending() {
        assertTrue(main.contains("override fun prepareDetectorRetry() { primaryDetectorLifecycleController.allowLoadRetryIfUnavailable() }"))
        val recheck = body(primary, "fun allowLoadRetryIfUnavailable()")
        assertTrue(recheck.contains("!detector.projection().available && !detector.projection().closing && !loadInFlight"))
        val load = body(primary, "fun loadForCurrentProcess(allowComparison: Boolean = false)")
        val complete = load.substringAfter("loadInFlight = false")
        assertTrue(complete.indexOf("loadAttempted = true") < complete.indexOf("preparation.loaded()"))
    }
    @Test fun existingDeviceCheckOwnsCompositeWorkAndSettingsHaveNoSeparateAction() {
        val support = body(main, "override fun startCompositeCameraCheck(")
        assertTrue(support.contains("startDeviceCompositeCameraCheck(binding, onFinished)"))
        assertTrue(main.contains("runtimeDeadlineMs"))
        assertFalse(main.contains("startCameraPerformanceCheck"))
        val settings = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt").readText()
        assertFalse(settings.contains("onCameraPerformanceCheck"))
        val apply = body(main, "private fun finishDeviceCompositeApplicationIfReady()")
        assertTrue(apply.contains("RunningDetectorVerificationState.VERIFIED"))
        assertTrue(apply.contains("DeviceCheckJointRuntimeChoice"))
    }

    @Test fun samplePerformanceCheckerAndWorkerHaveNoCameraOrArCoreDependency() {
        val directory = File("src/main/java/kr/co/hanium/dreamup/walksafe/inference/composite")
        val source = listOf("AndroidJointRuntimeCheck.kt", "FixtureRuntimeWorker.kt", "FixtureRuntimeService.kt",
            "FixtureRuntimeProtocol.kt", "RemoteFixtureRuntime.kt").joinToString("\n") { File(directory, it).readText() }
        listOf("AndroidRuntimeCalibrationSession", "com.google.ar.core", "android.media.Image",
            "android.hardware.camera2", "androidx.camera", "ACTION_IMAGE_CAPTURE").forEach { forbidden ->
            assertFalse("Sample-image performance check must not use $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("RuntimeCalibrationFixtures"))
        assertFalse(body(main, "private fun startDeviceCompositeCameraCheck(").contains("비춰"))
        val complete = body(main, "private fun completeDeviceCompositeAttempt(")
        assertTrue(complete.contains("metricDepth = null"))
        assertFalse(complete.contains("PostLoginMetricDepthState.SUPPORTED"))
    }

    @Test fun unconfirmedWorkerDeathFinishesFailurePresentationWhileRetainingItsAllocationFence() {
        val failure = body(main, "private fun publishDeviceCompositeReleaseFailure(")
        assertTrue(failure.contains("attempt.completionPublished = true"))
        assertTrue(failure.contains("attempt.finished(PostLoginCompositeCameraCheckResult(false"))
        assertTrue(failure.contains("releaseConfirmed = false"))
        assertFalse(failure.contains("deviceCompositeAttempt = null"))
    }

    @Test fun samplePerformanceWorkerIsPrivateAndSeparateFromMainAndVoice() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("<service[^>]*FixtureRuntimeService[\\s\\S]*?/>").find(manifest)?.value
        assertNotNull("Dedicated fixture Service must be declared", service)
        assertTrue(service!!.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:process=\":fixture_runtime\""))
        assertFalse(service.contains(":vosk_decoder"))
    }

}
