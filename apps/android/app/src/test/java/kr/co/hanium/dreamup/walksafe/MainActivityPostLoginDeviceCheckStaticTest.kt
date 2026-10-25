package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckCameraSampleVerdict
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckCameraSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPostLoginDeviceCheckStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val fieldLogSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt",
    ).readText()

    private val device = MainActivityRefactorSource.device
    private val probes = MainActivityRefactorSource.deviceProbes
    private val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText()
    private val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val factory = MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController")
    private val probeComposition = source.substringAfter("private val postLoginDeviceProbesHolder:")
        .substringBefore("private val postLoginDeviceProbes:")

    private fun main(name: String) = MainActivityRefactorSource.member(source, name)
    private fun owned(name: String) = MainActivityRefactorSource.function(source, "private fun $name(")
    private fun core(name: String) = MainActivityRefactorSource.member(device, name)
    private fun probe(name: String) = MainActivityRefactorSource.member(probes, name)
    private fun callback(name: String) = ReportStaticSourceInspector.functionBlock(probeComposition, "override fun $name")
    private fun effect(name: String) = ReportStaticSourceInspector.functionBlock(factory, "override fun $name")
    private fun native(className: String, name: String): String {
        check(probes.contains("private class $className("))
        return MainActivityRefactorSource.member(probes.substringAfter("private class $className("), name)
    }
    private fun ordered(text: String, vararg tokens: String) =
        assertTrue(tokens.joinToString(" -> "), ReportStaticSourceInspector.appearsInOrder(text, *tokens))

    private fun startupPresentationWire(): String {
        assertTrue(owned("refreshStartupCapabilityUi").contains("ui.render(decision, confirmed, priorityUserDecision, officialEnvironmentReady, phoneMountingReady)"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
            .contains("startupCapabilityFactsRenderer.renderStartupCapabilityFacts(decision, confirmed, priority, officialReady, mountingReady)"))
        val render = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/presentation/StartupCapabilityFactsRenderer.kt").readText()
        assertTrue(MainActivityRefactorSource.member(render, "renderStartupCapabilityFacts").contains("effects.render(startupUiFacts)"))
        return MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer")
    }

    @Test
    fun jitTransitionCreatesTheFreshRuntimeProbeBeforePublishingTheStageChange() {
        val completion = owned("completePostLoginDeviceCheckPermissionObservation")
        ordered(completion, "effects.recordPermissionObservation(binding)", "startRuntime(binding)", "effects.afterPermissionObservation(binding)")
        assertTrue(completion.contains("cancel(\"jit_transition_rejected\")"))
        val record = effect("recordPermissionObservation")
        val after = effect("afterPermissionObservation")
        assertTrue(record.contains("FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION"))
        assertTrue(record.contains("jitPermissionObservationBinding = binding"))
        ordered(after, "if (jitPermissionObservationBinding == binding)", "onFirstRunOnboardingStateChanged(")
        assertEquals(1, Regex("startRuntime\\(binding\\)").findAll(completion).count())
        assertTrue(completion.indexOf("startRuntime(binding)") > completion.indexOf("return"))
    }

    @Test
    fun loginDoesNotAutomaticallyStartAndOneTapBeginsTheDeviceCheck() {
        val login = MainActivityRefactorSource.function(source, "private fun loginEmailAccount(")
        assertFalse(login.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertFalse(MainActivityRefactorSource.member(source, "createEmailAccountController").contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onDeviceCheck() }"))
        assertTrue(source.contains("onDeviceCheck = { handlePostLoginDeviceCheckPrimaryAction() }"))
        val primary = owned("handlePostLoginDeviceCheckPrimaryAction")
        val start = owned("startPostLoginDeviceCheckFromPrimaryAction")
        val begin = owned("beginPostLoginDeviceCheckFromUserAction")
        assertTrue(primary.contains("beginFromPrimaryAction()"))
        ordered(start, "permissions.acknowledgeVoiceDisclosure()", "beginFromUserAction(binding)")
        assertTrue(effect("recordPermissionObservation").contains("firstRunOnboardingController.recordEmailPermissionObservation()"))
        assertTrue(factory.contains("requestPermissionsWithLease(permissions.toTypedArray(), PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK)"))
        assertTrue(begin.contains("permissions.request(binding, missing)"))
        assertTrue(begin.contains("PostLoginDeviceCheckPolicy.beginFromUserAction("))
        for (entry in listOf(primary, start, begin)) {
            assertFalse(entry.contains("AlertDialog"))
            assertFalse(entry.contains("showPostLoginDeviceCheckExplanation()"))
        }
    }

    @Test
    fun permissionAndDeviceStagesUseLocalTransitionsAndRespectLimitedFeatures() {
        val firstRunGate = main("firstRunOnboardingComplete")
        val deviceGate = owned("postLoginDeviceCheckPassesFeatureGate")
        val featureGate = owned("postLoginDeviceFeatureEnabled")
        assertTrue(source.contains("PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK"))
        assertTrue(effect("recordPermissionObservation").contains("firstRunOnboardingController.recordEmailPermissionObservation()"))
        assertTrue(owned("completePostLoginDeviceCheckPassEffects").contains("firstRun.completeEmailDeviceCheck("))
        assertTrue(onboardingOwner("completeEmailDeviceCheck").contains("FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(before, before.epoch, before.revision)"))
        assertTrue(onboardingOwner("recordEmailPermissionObservation").contains("FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(snapshot, snapshot.epoch, snapshot.revision)"))
        assertTrue(firstRunGate.contains("firstRunOnboardingSnapshot.mayEnterWalk"))
        assertTrue(firstRunGate.contains("GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(deviceGate.contains("current.passesFeatureGate"))
        assertFalse(deviceGate.contains("WalkSafeStartupCapabilityTier.FULL"))
        assertTrue(featureGate.contains("facts.passesFeatureGate()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPostLoginDeviceInteraction")
            .contains("override fun passesFeatureGate() = postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(featureGate.contains("feature !in currentPostLoginDisabledFeatures()"))
    }

    @Test
    fun passedDeviceCheckCannotBeReexposedAsANoOpCompletionButton() {
        val button = panel.substringAfter("startupMetricPreflightButton = Button(context).apply")
            .substringBefore("postLoginDeviceCheckSettingsButton = Button(context).apply")
        assertTrue(button.contains("visibility = View.GONE"))
        assertFalse(main("updateFirstRunOnboardingUi").contains("startupMetricPreflightButton.visibility"))
        val primary = owned("handlePostLoginDeviceCheckPrimaryAction")
        ordered(primary, "if (current.passesFeatureGate)", "if (!deferredChecksCanBeRetried) return", "beginFromPrimaryAction()")
        assertTrue(presenter.contains("val deviceCheckStage"))
        assertTrue(presenter.contains("val showDeviceCheckAction"))
        assertTrue(presenter.contains("!check.passesFeatureGate || check.deferredChecksRetriable"))
        assertTrue(presenter.contains("enabled = showDeviceCheckAction &&"))
        assertTrue(startupPresentationWire().contains("deviceCheckPresenter.renderStartupCapability(facts)"))
    }

    @Test
    fun cameraPipelineUsesBoundedDetectorAttemptsWithoutWalkOutputs() {
        val preflight = owned("startPostLoginCameraPipelinePreflight")
        assertTrue(native("AndroidPostLoginCameraSource", "requestBind").contains("ProcessCameraProvider.getInstance(context)"))
        assertTrue(preflight.contains("camera.requestBind("))
        assertTrue(preflight.contains("frameClaimed.compareAndSet(false, true)"))
        assertTrue(preflight.contains("attemptedFrames.incrementAndGet()"))
        assertTrue(preflight.contains("DeviceCheckCameraSamplingPolicy.evaluate(attempts, passes)"))
        assertTrue(preflight.contains("verdict == DeviceCheckCameraSampleVerdict.CONTINUE"))
        assertTrue(preflight.contains("terminalClaim.compareAndSet(false, true)"))
        assertEquals(10, DeviceCheckCameraSamplingPolicy.REQUIRED_FRAMES)
        assertEquals(80, DeviceCheckCameraSamplingPolicy.MINIMUM_PASSING_PERCENT)
        assertEquals(8, DeviceCheckCameraSamplingPolicy.REQUIRED_PASSES)
        assertEquals(DeviceCheckCameraSampleVerdict.CONTINUE, DeviceCheckCameraSamplingPolicy.evaluate(7, 7))
        assertEquals(DeviceCheckCameraSampleVerdict.PASSED, DeviceCheckCameraSamplingPolicy.evaluate(8, 8))
        assertEquals(DeviceCheckCameraSampleVerdict.FAILED, DeviceCheckCameraSamplingPolicy.evaluate(3, 0))
        assertEquals(DeviceCheckCameraSampleVerdict.FAILED, DeviceCheckCameraSamplingPolicy.evaluate(11, 8))
        (0..DeviceCheckCameraSamplingPolicy.REQUIRED_FRAMES).forEach { passed ->
            assertFalse(
                DeviceCheckCameraSamplingPolicy.evaluate(
                    DeviceCheckCameraSamplingPolicy.REQUIRED_FRAMES,
                    passed,
                ) == DeviceCheckCameraSampleVerdict.CONTINUE,
            )
        }
        assertTrue(preflight.contains("frameClaimed.set(false)"))
        assertTrue(preflight.contains("CAMERA_TIMEOUT_MS"))
        assertTrue(preflight.contains("detector().detect("))
        assertTrue(preflight.contains("DeviceCheckDetectorExecutionPolicy.passes(result)"))
        assertTrue(preflight.contains("finishCamera("))
        assertFalse(preflight.contains("phoneMountingSensorProbe"))
        assertFalse(preflight.contains("CameraLumaMeasurementPolicy"))
        assertFalse(preflight.contains("cameraFrameQualityObservation"))
        assertFalse(preflight.contains("angularShakeDegreesPerSecond"))
        assertFalse(preflight.contains("mountPitchDegrees"))
        assertFalse(preflight.contains("dispatchFeedback("))
        assertFalse(preflight.contains("processReportCandidate("))
        assertFalse(preflight.contains("recordRawCollectionDetectionMetadata("))
    }

    @Test
    fun functionalLocationProbeWaitsForAPassingSampleAndCleansUpItsCallback() {
        val start = owned("startPostLoginLocationFixProbe")
        val finish = probe("finishLocation")
        val cancel = owned("cancelPostLoginLocationFixProbe")
        val sdk = native("AndroidPostLoginLocationSource", "request")
        assertTrue(sdk.contains("object : LocationCallback()"))
        assertTrue(start.contains("val decisions = samples.map"))
        assertTrue(start.contains("DeviceCheckLocationFixPolicy.evaluate("))
        assertTrue(start.contains("observer.locationDiagnostic(it)"))
        assertTrue(start.contains("DeviceCheckLocationFixPolicy.selectBatch(decisions)"))
        assertTrue(start.contains("DeviceCheckLocationFixReason.MOCK_LOCATION"))
        assertTrue(sdk.contains("fused.requestLocationUpdates(request, callback, Looper.getMainLooper())"))
        assertTrue(sdk.contains("setWaitForAccurateLocation(false)"))
        assertTrue(sdk.contains("setMaxUpdateAgeMillis(DeviceCheckLocationFixPolicy.MAX_AGE_MS)"))
        assertTrue(probes.contains("LOCATION_TIMEOUT_MS = 30_000L"))
        assertFalse(sdk.contains("getCurrentLocation("))
        assertTrue(finish.contains("cancelLocation()"))
        assertTrue(cancel.contains("retired?.registration?.cancel()"))
        assertTrue(sdk.contains("fused.removeLocationUpdates(callback)"))
    }

    @Test
    fun cameraChecksOwnTheirUseCasesAndWaitForAnalyzerCleanupBeforeRetry() {
        val start = owned("startPostLoginCameraPipelinePreflight")
        val stop = owned("stopPostLoginCameraPipelinePreflight")
        val sdk = native("AndroidPostLoginCameraSource", "requestBind")
        val resources = probes.substringAfter("private class AndroidPostLoginCameraResources(").substringBefore("private class AndroidPostLoginCameraSource(")
        assertTrue(probeComposition.contains("officialEnvironmentCameraCleanupPending"))
        assertTrue(start.contains("env.otherCameraOwnerBusy"))
        assertTrue(sdk.contains("resources.provider = provider"))
        assertTrue(sdk.contains("resources.analysis = analysis"))
        assertFalse(sdk.contains("cameraFallbackProvider = provider"))
        assertTrue(start.contains("terminalClaim.compareAndSet(false, true)"))
        assertTrue(stop.contains("resources?.clearAnalyzer()"))
        assertTrue(stop.contains("cameraCleanupPending ="))
        assertTrue(stop.contains("detectorExecutor.execute"))
        assertTrue(resources.contains("provider?.unbind(ownedAnalysis)"))
        assertTrue(stop.contains("CAMERA_BARRIER_TIMEOUT_MS"))
        assertTrue(stop.contains("resources.awaitClosed(complete)"))
        assertTrue(stop.contains("markSharedReleaseBlocked()"))
        assertTrue(probeComposition.contains("markSharedReleaseBlocked = { cameraFallbackController.markReleaseBlocked() }"))
        assertTrue(stop.contains("PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT"))
        ordered(stop, "observer.continueCheck(binding)", "observer.changed()")
        assertTrue(callback("continueCheck").contains("maybeContinuePostLoginDeviceCheck(binding)"))
        assertTrue(callback("changed").contains("refreshStartupCapabilityUi()"))
    }

    @Test
    fun locationFailureIsDiagnosableWithoutLoggingCoordinates() {
        val diagnostic = callback("locationDiagnostic")
        val terminal = callback("locationTerminal")
        val failure = ReportStaticSourceInspector.functionBlock(presenter, "fun postLoginLocationFixFailureMessage")
        assertTrue(diagnostic.contains("GPS_PREFLIGHT_LOG_TAG"))
        for (token in listOf("decision.reason", "decision.accuracyMeters", "decision.ageMs")) assertTrue(diagnostic.contains(token))
        for (token in listOf("PREF_DEVICE_CHECK_LOCATION_RESULT", "PREF_DEVICE_CHECK_LOCATION_REASON",
            "PREF_DEVICE_CHECK_LOCATION_ACCURACY_BUCKET", "PREF_DEVICE_CHECK_LOCATION_AGE_BUCKET", "post_login_location_check_terminal")) assertTrue(terminal.contains(token))
        for (text in listOf(diagnostic, terminal)) { assertFalse(text.contains("latitude")); assertFalse(text.contains("longitude")) }
        for (token in listOf("\"accuracy_bucket\"", "\"age_bucket\"", "\"result\"")) assertTrue(fieldLogSource.contains(token))
        assertTrue(failure.contains("ACCURACY_OUTSIDE_FUNCTIONAL_RANGE"))
        assertTrue(failure.contains("MOCK_LOCATION"))
        assertTrue(main("captureDeviceCheckLocationFailureFacts").contains("PostLoginLocationFixTerminalFailure.REQUEST_FAILED"))
        assertTrue(failure.contains("facts.requestFailed"))
    }

    @Test
    fun statusIsAccessibleAndFailureOffersSettingsRecovery() {
        val evaluate = owned("evaluatePostLoginDeviceCheck")
        val progress = owned("announcePostLoginDeviceCheckProgress")
        val terminal = owned("announcePostLoginDeviceCheckTerminal")
        val timeout = core("armOverallTimeout")
        val sessionFailure = owned("failPostLoginDeviceCheckForSessionChange")
        val revalidate = owned("revalidateCompletedPostLoginDeviceCheckPrerequisites")
        assertTrue(panel.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(presenter.contains("필수 기능 중 일부를 사용할 수 없습니다. 위의 사용할 수 없는 기능 목록을 확인하세요."))
        assertFalse(presenter.contains("필수 카메라·GPS·마이크·진동·음성 기능을 사용할 수 없습니다."))
        assertTrue(panel.contains("postLoginDeviceCheckSettingsButton = Button(context).apply"))
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onDeviceSettings() }"))
        assertTrue(source.contains("onDeviceSettings = { openPostLoginDeviceCheckSettings() }"))
        val settings = owned("openPostLoginDeviceCheckSettings")
        assertTrue(settings.contains("settings.openWithApplicationFallback(target)"))
        assertTrue(MainActivityRefactorSource.factory(source, "createVoiceDataSettingsRecovery")
            .contains("settings = StartupSettingsNavigation(AndroidStartupSettingsNative(this))"))
        val nativeSettings = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/AndroidStartupSettingsNative.kt").readText()
        assertTrue(nativeSettings.contains("Settings.ACTION_LOCATION_SOURCE_SETTINGS"))
        assertTrue(nativeSettings.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(evaluate.contains("val detail = effects.failureMessage(next.failure)"))
        assertTrue(evaluate.contains("기기 점검에 실패했습니다. \$detail"))
        assertTrue(evaluate.contains("effects.announceTerminal("))
        assertTrue(factory.contains("override fun announceTerminal(message: String) = this@MainActivity.postLoginDeviceInteraction.announcePostLoginDeviceCheckTerminal(message)"))
        assertFalse(evaluate.contains("화면의 실패 항목을 확인하세요"))
        assertTrue(progress.contains("view.liveRegionPolite()"))
        assertTrue(terminal.contains("view.liveRegionNone()"))
        assertFalse(terminal.contains("view.liveRegionPolite()"))
        for (token in listOf("facts.screenReaderActive()", "PostLoginDeviceCheckState.FAIL", "view.shown()", "view.requestAccessibilityFocus()")) assertTrue(terminal.contains(token))
        val interaction = MainActivityRefactorSource.factory(source, "createPostLoginDeviceInteraction")
        assertTrue(interaction.contains("override fun screenReaderActive() = isScreenReaderActive()"))
        assertTrue(interaction.contains("postLoginDeviceCheckLiveStatusText.isShown"))
        assertTrue(ReportStaticSourceInspector.functionBlock(interaction, "override fun liveRegionPolite").contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(ReportStaticSourceInspector.functionBlock(interaction, "override fun liveRegionNone").contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertEquals(1, "ACTION_ACCESSIBILITY_FOCUS".toRegex().findAll(interaction).count())
        assertTrue(timeout.contains("effects.announceTerminal("))
        assertTrue(sessionFailure.contains("effects.announceTerminal("))
        assertFalse(revalidate.contains("PostLoginDeviceCheckPolicy.invalidatePassedGate("))
        assertFalse(revalidate.contains("effects.announceTerminal("))
    }

    @Test
    fun everyAsyncMutationAndResourceStartRequiresTheLiveGatewayBinding() {
        val start = owned("startPostLoginDeviceCheckRuntime")
        val orchestration = owned("maybeContinuePostLoginDeviceCheck")
        val evaluate = owned("evaluatePostLoginDeviceCheck")
        val camera = owned("startPostLoginCameraPipelinePreflight")
        assertTrue(start.contains("!isRunningBindingCurrent(binding)"))
        assertTrue(orchestration.contains("!isRunningBindingCurrent(binding)"))
        assertTrue(evaluate.contains("!isSnapshotBindingLive(binding)"))
        assertTrue(camera.contains("!isCurrent(binding)"))
        assertTrue(probeComposition.contains("isCurrent = ::isPostLoginDeviceCheckBindingCurrent"))
        assertTrue(owned("isPermissionRequestLeaseCurrent").contains("lease.postLoginBinding"))
        assertTrue(owned("isPermissionRequestLeaseCurrent").contains("authority::postLoginBindingLive"))
        assertTrue(owned("isRuntimeMetricPreflightAttemptCurrent").contains("device.bindingLive(binding)"))
        assertTrue(factory.contains("actorSource = ::capturePostLoginDeviceCheckActorFacts"))
        assertTrue(core("isSnapshotBindingLive").contains("PostLoginDeviceCheckPolicy.isCurrent(current, binding) && isSessionBindingLive(binding)"))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowContracts.kt").readText().contains("val postLoginBinding: PostLoginDeviceCheckBinding?"))
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowController")
            .contains("override fun postLoginBindingLive(binding: PostLoginDeviceCheckBinding) = postLoginDeviceCheckController.isSnapshotBindingLive(binding)"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession")
            .contains("override fun bindingLive(binding: PostLoginDeviceCheckBinding) = isPostLoginDeviceCheckSnapshotBindingLive(binding)"))
        assertFalse(source.contains("postLoginAttemptGeneration"))
    }

    @Test
    fun deviceCheckRequiresObservedWakePhraseAndExcludesHapticMeasurement() {
        val orchestration = owned("maybeContinuePostLoginDeviceCheck")
        val observation = owned("currentPostLoginDeviceCheckObservation")
        val evaluation = owned("evaluatePostLoginDeviceCheck")
        for (forbidden in listOf("maybeStartWake(binding)", "beginWakeFromUserAction()", "confirmHaptic(")) assertFalse(orchestration.contains(forbidden))
        assertTrue(observation.contains("wakePhraseRecognition = if"))
        assertTrue(observation.contains("!hardware.microphonePermission"))
        assertTrue(observation.contains("hardware.voiceModelPreparationFailed"))
        assertTrue(observation.contains("else probeSignals.wakePhrase"))
        assertFalse(observation.contains("hapticFeedback ="))
        assertFalse(observation.contains("startup.vibrationAvailable"))
        assertTrue(evaluation.contains("PostLoginDeviceCheckState.FULL"))
        assertTrue(evaluation.contains("PostLoginDeviceCheckState.LIMITED"))
        assertTrue(evaluation.contains("completePass()"))
        assertTrue(core("completePass").contains("effects.afterPassed(current)"))
        val pass = ReportStaticSourceInspector.maskNonCode(effect("afterPassed"))
        ordered(pass,
            "val lifecycleGeneration = feedbackLifecycleGeneration",
            "val isCurrent = {",
            "isFeedbackLifecycleCurrent(lifecycleGeneration) &&",
            "postLoginDeviceCheckController.isPassedSnapshotCurrent(snapshot)",
            "if (!isCurrent()) return PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED",
            "speechOutputController.restartFailedActuatorAfterDeviceCheck(isCurrent)",
            "if (!isCurrent()) return PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED",
            "return this@MainActivity.actorSessionLifecycleCoordinator.completePostLoginDeviceCheckPassEffects(snapshot)",
        )
        assertEquals(2, Regex(Regex.escape("if (!isCurrent()) return PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED")).findAll(pass).count())
        assertEquals(1, Regex("completePostLoginDeviceCheckPassEffects").findAll(pass).count())
        ordered(core("completePass"), "savedResults.save(current", "effects.afterPassed(current)",
            "PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED -> return")
        assertFalse(effect("afterRestoredResult").contains("restartFailedActuatorAfterDeviceCheck"))
        assertTrue(owned("completePostLoginDeviceCheckPassEffects").contains("firstRun.completeEmailDeviceCheck("))
        assertTrue(onboardingOwner("completeEmailDeviceCheck").contains("FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(before, before.epoch, before.revision)"))
        assertTrue(onboardingOwner("recordEmailPermissionObservation").contains("FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(snapshot, snapshot.epoch, snapshot.revision)"))
        assertFalse(owned("completePostLoginDeviceCheckPassEffects").contains("startupCapabilityConfirmButton"))
    }

    @Test
    fun inconclusiveFeatureMeasurementsBecomeLimitsInsteadOfBlockingTheApp() {
        val observation = owned("currentPostLoginDeviceCheckObservation")
        val blocking = observation.substringAfter("blockingFailure = when {")

        assertFalse(blocking.contains("!hasCameraPermission()"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.REQUIRED_PERMISSION"))
        assertTrue(blocking.contains("PostLoginDeviceCheckFailure.DEVICE_RESOURCE"))
        assertFalse(blocking.contains("PostLoginMetricDepthState.UNKNOWN"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.DEPTH_UNKNOWN"))
        assertFalse(blocking.contains("PostLoginMetricDepthState.TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.DEPTH_TIMEOUT"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.BIND_FAILED"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.NO_VALID_FRAME"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.DETECTOR_FAILED"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE"))

        val unsupported = observation.substringAfter("val unsupported = buildSet {")
            .substringBefore("return PostLoginDeviceCheckObservation(")
        assertFalse(unsupported.contains("cameraPermissionGranted &&"))
        assertFalse(unsupported.contains("cameraPipeline == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertFalse(unsupported.contains("!hasCameraPermission()"))
        assertTrue(unsupported.contains("startup.cameraAvailable == false"))
        assertTrue(unsupported.contains("detectorUnavailable"))
        assertTrue(unsupported.contains("PostLoginMetricDepthState.UNKNOWN"))
        assertTrue(unsupported.contains("PostLoginMetricDepthState.TIMED_OUT"))
    }

    @Test
    fun terminalCancellationOwnsTimeoutTtsCameraAndMetricPreflightCleanup() {
        val start = owned("startPostLoginDeviceCheckRuntime")
        val timeout = core("armOverallTimeout")
        val cancel = owned("cancelPostLoginDeviceCheckRuntime")
        ordered(start, "armOverallTimeout(binding)", "probes.prepareVoiceModelAndDetector()")
        assertTrue(timeout.contains("overallTimeout = scheduler.schedule("))
        assertTrue(timeout.contains("cancel(\"check_timeout\")"))
        assertTrue(cancel.contains("clearOverallTimeout()"))
        assertTrue(cancel.contains("probes.cancelFunctionalProbes()"))
        assertTrue(cancel.contains("probes.cancelMetricPreflight("))
        assertTrue(cancel.contains("probes.closeCapabilityAndResetResources()"))
        assertTrue(cancel.contains("probes.stopCamera()"))
        assertTrue(callback("cancelMetricPreflight").contains("this@MainActivity.runtimeMetricInvalidation.invalidate(reason)"))
        assertTrue(callback("closeCapabilityAndResetResources").contains("privacyStartupResources.releaseOnUiThread(startupCapabilityProbe)"))
        MainActivityRefactorSource.composition(source, "runtimeMetricInvalidation")
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession")
            .contains("override fun bindingLive(binding: PostLoginDeviceCheckBinding) = isPostLoginDeviceCheckSnapshotBindingLive(binding)"))
        assertTrue(owned("isRuntimeMetricPreflightAttemptCurrent").contains("val binding = attempt.binding ?: return false"))
        assertTrue(owned("isRuntimeMetricPreflightAttemptCurrent").contains("device.bindingLive(binding)"))
    }

    @Test
    fun backgroundFailureRemainsAccessibleDuringTheAutomaticCheck() {
        assertTrue(main("resumeWalkSafeRuntimeAfterPrivacyStartupInspection").contains("postLoginDeviceCheckController.announceDeferredBackgroundFailure()"))
        assertTrue(main("onPause").contains("postLoginDeviceCheckController.enteredBackground()"))
        val resumed = core("announceDeferredBackgroundFailure")
        val paused = core("enteredBackground")
        assertTrue(resumed.contains("backgroundFailurePending"))
        assertTrue(resumed.contains("effects.announceTerminal("))
        assertTrue(resumed.contains("PostLoginDeviceCheckFailure.BACKGROUNDED"))
        assertTrue(paused.contains("PostLoginDeviceCheckPolicy.onBackground("))
        assertTrue(paused.contains("backgroundFailurePending ="))
        assertTrue(paused.contains("PostLoginDeviceCheckFailure.BACKGROUNDED"))
    }

    @Test
    fun itemListIsNotLiveAndOnlyTheMeasuredWakePhrasePromptIsInteractive() {
        val capabilityText = panel.substringAfter("startupCapabilityText = TextView(context).apply")
            .substringBefore("postLoginDeviceCheckLiveStatusText = TextView(context).apply")
        val liveStatus = panel.substringAfter("postLoginDeviceCheckLiveStatusText = TextView(context).apply")
            .substringBefore("startupMetricPreflightButton = Button(context).apply")
        assertFalse(capabilityText.contains("contentDescription"))
        assertFalse(capabilityText.contains("accessibilityLiveRegion"))
        assertTrue(liveStatus.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        for (control in listOf("postLoginDeviceCheckHapticQuestionText", "postLoginDeviceCheckHapticConfirmButton", "postLoginDeviceCheckHapticRejectButton")) assertTrue(presenter.contains("$control.visibility = View.GONE"))
        assertTrue(presenter.contains("val interactiveCheckRunning = check.state == PostLoginDeviceCheckState.RUNNING"))
        assertTrue(presenter.contains("facts.wake.signal == PostLoginDeviceCheckSignal.PENDING"))
        assertTrue(presenter.contains("wakeInstructionVisible = interactiveCheckRunning"))
        assertTrue(presenter.contains("visible = needsWakePhrase"))
        assertTrue(presenter.contains("enabled = needsWakePhrase && facts.wake.modelReady && !facts.wake.probeActive"))
        assertTrue(startupPresentationWire().contains("deviceCheckPresenter.renderStartupCapability(facts)"))
        assertTrue(presenter.contains("facts.microphoneAvailable.toDeviceCheckSignal()"))
        assertTrue(presenter.contains("facts.gpsAvailable.toDeviceCheckSignal()"))
        assertFalse(main("postLoginDeviceCheckItemsMessage").contains("startup.vibrationAvailable"))
        assertFalse(main("postLoginDeviceCheckItemsMessage").contains("진동"))
        assertTrue(presenter.contains("facts.cameraAvailable.toDeviceCheckSignal()"))
    }

    private fun onboardingOwner(name: String): String {
        MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/onboarding/FirstRunOnboardingController.kt",
        ).readText(), name)
    }

}
