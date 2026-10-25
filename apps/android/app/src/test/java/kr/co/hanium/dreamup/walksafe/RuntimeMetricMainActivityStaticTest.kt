package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMetricMainActivityStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun oneTapDeviceCheckStartsPreflightWithoutAnIntermediateDialog() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        assertTrue(
            "startup must expose a dedicated runtime metric preflight button",
            source.contains("startupMetricPreflightButton"),
        )
        assertTrue(
            "the post-login check must start from its single primary action",
            source.contains("onDeviceCheck = { handlePostLoginDeviceCheckPrimaryAction() }"),
        )
        val primaryAction = functionBlock("private fun handlePostLoginDeviceCheckPrimaryAction()")
        assertTrue(primaryAction.contains("beginFromPrimaryAction()"))
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onDeviceCheck() }"))
        assertFalse(primaryAction.contains("showPostLoginDeviceCheckExplanation()"))
        val start = functionBlock("private fun startPostLoginDeviceCheckFromPrimaryAction()")
        assertTrue(start.contains("beginFromUserAction(binding)"))
        assertFalse(start.contains("AlertDialog"))

        assertTrue(source.contains("OnboardingReadinessPanel("))
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bind = MainActivityRefactorSource.member(registration, "bindRegistration")
        assertTrue(bind.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bind.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativeRegistrationComposition").contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication)"))
        assertTrue(source.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        val readinessStart = panel.indexOf("walkReadinessControls = LinearLayout(context).apply")
        assertTrue(readinessStart >= 0)
        val readiness = panel.substring(readinessStart).substringBefore("\n        }")
        val preflight = readiness.indexOf("addView(startupMetricPreflightButton)")
        val confirmation = readiness.indexOf("addView(startupCapabilityConfirmButton)")
        assertTrue("preflight button is missing from the startup overlay", preflight >= 0)
        assertTrue("confirmation button is missing from the startup overlay", confirmation >= 0)
        assertTrue(
            "runtime metric evidence must be collected before confirmation is offered",
            preflight < confirmation,
        )
    }

    @Test
    fun preflightCameraPermissionHasAnIsolatedCallbackThatCannotStartConfirmedRuntime() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        assertTrue(
            "preflight camera permission must use its own one-shot request purpose",
            source.contains("PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA"),
        )
        val callback = MainActivityRefactorSource.member(source, "onRequestPermissionsResult")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(callback,
            "invalidateRouteSnapshotResponseLease()", "invalidateSpeechOutputStorageBinding()",
            "super.onRequestPermissionsResult(requestCode, permissions, grantResults)",
            "permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)"))
        val permissionDispatch = delegatedOwner("onRequestPermissionsResult", "permissionFlowController", "session/permission/PermissionFlowController.kt", "onRequestPermissionsResult", "{ invalidateRouteSnapshotResponseLease() invalidateSpeechOutputStorageBinding() super.onRequestPermissionsResult(requestCode, permissions, grantResults) permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults) }")
        assertTrue(
            permissionDispatch.contains(
                "PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->",
            ),
        )
        assertTrue(
            permissionDispatch.contains("resultHandlers.metricCamera()") &&
                MainActivityRefactorSource.composition(source, "permissionFlowController")
                    .contains("override fun metricCamera() { handleMetricPreflightCameraPermissionResult() }"),
        )
        assertFalse(
            "preflight permission handling must not depend on a reusable fixed request code",
            source.contains("METRIC_PREFLIGHT_CAMERA_PERMISSION_REQUEST"),
        )

        val handler = functionBlock("private fun handleMetricPreflightCameraPermissionResult()")
        assertFalse(
            "a preflight permission callback must never enter the confirmed runtime path",
            handler.contains("startConfirmedRuntimeAfterCameraPermission"),
        )
        assertFalse(
            "a preflight permission callback must not confirm the startup decision",
            handler.contains("confirmStartupCapabilityDecision"),
        )
    }

    @Test
    fun preflightSessionStartCannotActivateWalkingOrCollectionWorkloads() {
        assertTrue(source.contains("ArSessionPurpose.PREFLIGHT"))
        assertTrue(source.contains("ArSessionPurpose.RUNTIME"))
        assertTrue(source.contains("arSessionOwner.observe().purpose"))
        assertTrue(source.contains("RuntimeMetricPreflightSession("))

        val start = MainActivityRefactorSource.member(source, "startRuntimeMetricPreflightSession")
        val forbiddenCalls = listOf(
            "loadDetectorAfterCameraGate(",
            "startNavigationServicesIfNeeded(",
            "startCameraFallbackSession(",
            "requestCameraFallbackStart(",
            "prepareReportCandidate(",
            "publishCurrentFrameReportState(",
            "appendTelemetry(",
            "metadataLogUploader.enqueue(",
        )
        forbiddenCalls.forEach { call ->
            assertFalse("preflight session start must not call $call", start.contains(call))
        }
    }

    @Test
    fun drawFrameHandlesPreflightAndReturnsBeforeDetectorReportOrFeedbackWork() {
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        val render = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRenderController.kt").readText()
        val pipeline = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val renderBinding = MainActivityRefactorSource.property(source, "frameRenderController")
        val pipelineBinding = MainActivityRefactorSource.property(source, "framePipelineController")
        assertTrue(renderBinding.contains("purpose == ArSessionPurpose.PREFLIGHT"))
        assertTrue(renderBinding.contains("handleRuntimeMetricPreflightFrame("))
        assertTrue(pipelineBinding.contains("FramePipelineController(frameStateLock, frameRenderController,"))
        assertTrue(MainActivityRefactorSource.member(pipeline, "draw").contains("render.draw { source ->"))
        assertTrue(MainActivityRefactorSource.member(pipeline, "qualify").contains("scheduling.scheduleNamed("))
        assertTrue(pipelineBinding.contains("scheduleDetectionIfDue(input, lease, frame,"))
        val draw = MainActivityRefactorSource.member(render, "draw")
        val branch = draw.lastIndexOf("source.isPreflight(lease.purpose)")
        val handler = draw.indexOf("observer.processPreflight(scope)")
        val detector = draw.indexOf("processRuntimeCapture(scope)")

        assertTrue("onDrawFrame must branch on the preflight session purpose", branch >= 0)
        assertTrue("onDrawFrame must submit preflight frame evidence", handler >= 0)
        assertTrue("the detector scheduling call must remain present", detector >= 0)
        assertTrue(branch < handler)
        assertTrue("preflight handling must happen before detector scheduling", handler < detector)
        assertTrue(
            "the preflight branch must return instead of falling through into runtime processing",
            draw.substring(handler, detector).contains("return"),
        )
    }

    @Test
    fun terminalPreflightClosesItsSessionThenUpdatesEvidenceWithoutStartingWalk() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val finish = functionBlock("private fun finishRuntimeMetricPreflight(")
        val close = firstIndexOf(
            finish,
            "native.finishDetachedPreflight(currentSession)",
        )
        val metric = finish.indexOf("metricDistanceCapabilityOverride")
        val profile = finish.indexOf("profile", ignoreCase = true)

        assertTrue("terminal preflight must close its ARCore session", close >= 0)
        assertTrue("terminal preflight must publish the metric result", metric >= 0)
        assertTrue("terminal preflight must publish designated-device profile evidence", profile >= 0)
        assertTrue("metric evidence must be applied only after session closure", close < metric)
        assertTrue("profile evidence must be applied only after session closure", close < profile)
        assertTrue(finish.contains("effects.refreshStartupUi()"))
        val binding = MainActivityRefactorSource.composition(source, "runtimeMetricSession")
        assertTrue(binding.contains("override fun finishDetachedPreflight(currentSession: Session?)"))
        val closeBody = binding.substringAfter("override fun finishDetachedPreflight(currentSession: Session?)")
            .substringBefore("object : RuntimeMetricDeviceChecks")
        assertTrue(closeBody.contains("currentSession.close()"))
        assertTrue(closeBody.indexOf("currentSession.close()") < closeBody.indexOf("syncActiveSessionScreenPolicy()"))
        assertTrue(binding.contains("override fun refreshStartupUi() { refreshStartupCapabilityUi() }"))
        assertFalse(
            "device-check measurement must not confirm walk startup",
            finish.contains("confirmedStartupCapabilityDecision ="),
        )
        assertFalse(finish.contains("completeStartupCapabilityConfirmation("))
        assertFalse(finish.contains("confirmStartupCapabilityDecision()"))
    }

    @Test
    fun deviceCheckReportsUnknownWithoutInstallingOrStartingAFrameSession() {
        val continuation = MainActivityRefactorSource.function(source, "private fun continueRuntimeMetricPreflightStart(")
        val installedGate = continuation.indexOf(
            "availability != ArCoreApk.Availability.SUPPORTED_INSTALLED",
        )
        val sessionCreation = continuation.indexOf("probe.automaticDepthSupported()")
        val probeSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/AndroidRuntimeMetricPreflight.kt").readText()
        val probe = MainActivityRefactorSource.member(probeSource, "automaticDepthSupported", occurrence = 0)
        val creation = probe.indexOf("platform.createSession()")
        val query = probe.indexOf("automaticDepthSupported(supportSession)")
        val close = probe.indexOf("platform.closeSession(supportSession)")
        assertTrue(creation >= 0 && query > creation && close > query)
        assertTrue(probe.substring(query, close).contains("finally"))
        val binding = MainActivityRefactorSource.composition(source, "runtimeMetricPreflight")
        assertTrue(binding.contains("RuntimeMetricSupportProbe(runtimeCameraPlatform, AndroidRuntimeMetricFrameEvidence::automaticDepthSupported)"))
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        assertTrue(MainActivityRefactorSource.member(platform, "createSession").contains("Session(activity())"))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeCameraPlatform").contains("AndroidRuntimeArPlatform({ this },"))

        assertTrue(installedGate >= 0)
        assertTrue(sessionCreation >= 0)
        assertTrue(installedGate < sessionCreation)
        assertTrue(continuation.contains("RuntimeMetricDepthSupport.UNKNOWN"))
        assertTrue(continuation.contains("finishDeviceMetricDepthSupportCheck("))
        assertFalse(continuation.contains("requestInstall("))
        assertFalse(continuation.contains("startRuntimeMetricPreflightSession("))
    }

    @Test
    fun pauseAndDestroyInvalidatePreflightGenerationAndLateCallbacks() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val destroy = functionBlock("override fun onDestroy()")
        val invalidationTransaction = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricInvalidationController.kt").readText(), "invalidate")
        assertTrue(invalidationTransaction.contains("metric().invalidateEvidence()"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricInvalidation").contains("{ runtimeMetricSession }"))
        val metric = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val invalidation = MainActivityRefactorSource.member(metric, "invalidateEvidence")

        assertTrue(pause.contains("""this@MainActivity.runtimeMetricInvalidation.invalidate("app_paused")"""))
        assertTrue(destroy.contains("""this@MainActivity.runtimeMetricInvalidation.invalidate("app_destroyed")"""))
        assertTrue("invalidation replaces the complete attempt", invalidation.contains("metricPreflightAttempt = metricPreflightAttempt.retired()"))
        val retired = metric.substringAfter("fun retired(").substringBefore("private var metricDistanceCapabilityOverride")
        assertTrue("replacement advances generation", retired.contains("MetricPreflightAttempt<L>(generation + 1L, lifecycleGeneration)"))
        assertTrue("ordinary retirement drops evaluator", retired.contains("retainEvaluator: Boolean = false"))
        assertTrue(metric.contains("private val owner: PreflightOwner<L>? = null"))
        assertTrue(metric.contains("evaluator: RuntimeMetricPreflightSession? = null"))

    }

    @Test
    fun runtimeMetricEvidenceFailsClosedBeforeDetectorReportFeedbackAndQueuedDelivery() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        assertTrue(source.contains("runtimeMetricOutputAllowed"))
        val pipelineSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val pipelineBinding = MainActivityRefactorSource.property(source, "framePipelineController")
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        assertTrue(pipelineBinding.contains("FramePipelineController(frameStateLock, frameRenderController,"))
        val draw = MainActivityRefactorSource.member(pipelineSource, "qualify")
        val failClosedGate = draw.indexOf(
            "if (!runtime.visualOutputAllowsWork(lease, native.timestamp, tracking))",
        )
        val detector = draw.indexOf("scheduling.scheduleNamed(")
        val report = draw.indexOf("synthesis.selectOverlayDetections(")
        val feedback = draw.indexOf("return Qualified(")
        val synthesize = MainActivityRefactorSource.member(pipelineSource, "synthesize")
        assertTrue(synthesize.contains("if (runtime.metricOutputAllowsWork(arGeneration)) tactile.prepareFrozen("))
        assertTrue(pipelineBinding.contains("override fun metricOutputAllowsWork(arGeneration: Long) = currentRuntimeMetricOutputAllowsWork(arGeneration)"))
        val reportClaim = functionBlock("internal fun publishCurrentFrameReportState(")
        assertTrue(reportClaim.contains("frames.runtimeMetricAllowsWork(expectedRuntimeMetricGeneration)"))

        assertTrue("onDrawFrame needs an explicit fail-closed runtime metric gate", failClosedGate >= 0)
        assertTrue(detector >= 0)
        assertTrue(report >= 0)
        assertTrue(feedback >= 0)
        assertTrue(
            "camera quality observation must remain reachable while outputs are suppressed",
            detector < failClosedGate,
        )
        assertTrue(failClosedGate < report)
        assertTrue(failClosedGate < feedback)
        assertTrue(
            "the runtime metric gate must return before any output workload",
            draw.substring(failClosedGate, report).contains("return null"),
        )
        val scheduledSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val scheduled = MainActivityRefactorSource.member(scheduledSource, "run")
        val scheduledBinding = MainActivityRefactorSource.member(source, "createPrimaryArDetectorWorker")
        assertTrue(MainActivityRefactorSource.member(source, "scheduleDetectionIfDue").contains("primaryArSubmissionBridge.schedule("))
        val submission = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/AndroidPrimaryArSubmissionBridge.kt").readText()
        val submit = MainActivityRefactorSource.member(submission, "schedule")
        assertTrue(submit.contains("val targetWorker = worker()"))
        assertTrue(submit.contains("targetWorker.submit(queuedWork)"))
        assertTrue(MainActivityRefactorSource.property(source, "primaryArSubmissionBridge").contains("{ primaryArDetectorWorker }"))
        assertTrue(scheduledBinding.contains("observeOfficialEnvironmentCameraFrame(observation)"))
        assertTrue(scheduledBinding.contains("currentRuntimeVisualDepthProcessingAllowsWork("))
        val cameraObservation = scheduled.indexOf("quality.observe(work.input.qualityObservation)")
        val scheduledVisualGate = scheduled.indexOf("if (!FrameRecognitionAdmission.allowsVisualWork(")
        val detectorInvocation = scheduled.indexOf("invocationWorker.invoke(")
        assertTrue(cameraObservation >= 0)
        assertTrue(scheduledVisualGate >= 0)
        assertTrue(detectorInvocation >= 0)
        assertTrue(cameraObservation < scheduledVisualGate)
        assertTrue(scheduledVisualGate < detectorInvocation)
        assertTrue(scheduled.substring(scheduledVisualGate, detectorInvocation).contains("authority.visualLeaseAllowsWork(work.lease)"))
        val recognitionGate = functionBlock("internal fun runtimeRecognitionAllowsVisualWork(")
        assertTrue(recognitionGate.contains("FrameRecognitionAdmission.allowsVisualWork(cameraTimestampNs, frameId, snapshot, visualLeaseAllowsWork)"))
        val recognitionPolicy = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameRecognitionAdmission.kt").readText()
        assertTrue(MainActivityRefactorSource.member(recognitionPolicy, "allowsVisualWork").contains("matchesDepth(cameraTimestampNs, frameId, snapshot) && visualLeaseAllowsWork()"))
        val publicationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText()
        val publication = MainActivityRefactorSource.member(publicationSource, "publish")
        val publicationGate = publication.indexOf("if (!metricOutputAllowed(scope.arGeneration))")
        val publicationReport = publication.indexOf("reports.publish(")
        val publicationFeedback = publication.indexOf("tactile.dispatchFeedback(")
        assertTrue(pipelineBinding.contains("FramePublicationController(frameStateLock, runtime::ownsFrame, runtime::metricOutputAllowsWork,"))
        assertTrue(publicationGate >= 0 && publicationReport > publicationGate && publicationFeedback > publicationReport)
        assertTrue(publication.substring(publicationGate, publicationReport).contains("return"))
        assertTrue(publication.substring(publicationReport, publicationFeedback).contains("if (!metricOutputAllowed(scope.arGeneration)) return"))
        val visualGate = functionBlock("private fun currentRuntimeVisualDepthProcessingAllowsWork(")
        assertTrue(visualGate.contains("currentRuntimeMetricOutputAllowsWork(lease.generation)"))
        assertTrue(visualGate.contains("allowsRuntimeDepthGapProcessing("))

        val metricGate = functionBlock("private fun currentRuntimeMetricOutputAllowsWork(")
        assertTrue(metricGate.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(metricGate.contains("runtimeMetricOutputAllowed"))
        assertTrue(metricGate.contains(
            "observeRuntimeAr().allowsMetricLease(runtimePurpose, expectedGeneration)",
        ))

        val queuedFeedbackGate = functionBlock("private fun currentFeedbackDeviceGateAllowsAlerts()")
        assertTrue(
            "queued TTS/vibration must re-check current runtime metric evidence",
            queuedFeedbackGate.contains("runtimeMetricOutputAllowed"),
        )
        assertTrue(queuedFeedbackGate.contains("ArSessionPurpose.RUNTIME"))
        val ports = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionPorts.kt").readText()
        val lease = MainActivityRefactorSource.member(ports, "allowsMetricLease")
        assertTrue(lease.contains("expectedGeneration == null || generation == expectedGeneration"))
    }

    @Test
    fun preflightRejectsActiveFieldLoggingAndDoesNotMutateOverlaySelection() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val eligibility = functionBlock("private fun canBeginRuntimeMetricPreflight()")
        assertTrue(eligibility.contains("if (device.fieldSessionActive) return false"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession")
            .contains("override val fieldSessionActive get() = ::fieldSessionLog.isInitialized && isFieldSessionActive()"))

        val render = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRenderController.kt").readText()
        val pipeline = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val draw = MainActivityRefactorSource.member(render, "draw")
        val qualify = MainActivityRefactorSource.member(pipeline, "qualify")
        assertTrue(qualify.contains("selectOverlayDetections("))
        assertTrue(MainActivityRefactorSource.member(pipeline, "draw").contains("qualify(source) ?: return@draw"))
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        assertTrue(MainActivityRefactorSource.property(source, "framePipelineController").contains("FramePipelineController(frameStateLock, frameRenderController,"))
        val preflight = draw.lastIndexOf("source.isPreflight(lease.purpose)")
        val overlay = draw.indexOf("processRuntimeCapture(scope)")
        assertTrue(preflight >= 0)
        assertTrue(overlay >= 0)
        assertTrue("preflight must return before overlay state selection", preflight < overlay)
        assertTrue(draw.substring(preflight, overlay).contains("return"))
    }

    @Test
    fun sessionLeaseAndMetricStateLockPreventOldFramesFromCrossingGenerations() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val renderSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRenderController.kt").readText()
        val renderBinding = MainActivityRefactorSource.property(source, "frameRenderController")
        assertTrue(renderBinding.contains("override fun acquireLease():"))
        val acquire = renderBinding.substringAfter("override fun acquireLease():").substringBefore("override fun bindTextureIfReady()")
        assertTrue(acquire.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(acquire.contains("arSessionOwner.acquireLeaseOrNull()"))
        assertTrue(acquire.contains("ArSessionLease("))
        assertTrue(renderBinding.contains("override fun leaseIsCurrent(lease: ArSessionLease) = isArSessionLeaseCurrent(lease)"))
        val draw = MainActivityRefactorSource.member(renderSource, "draw")
        val lease = draw.indexOf("source.acquireLease()")
        val update = draw.indexOf("lease.session.update()")
        val validationAfterUpdate = draw.indexOf("source.leaseIsCurrent(lease.originalLease)", update)
        assertTrue(lease >= 0)
        assertTrue(update > lease)
        assertTrue(validationAfterUpdate > update)

        val frameAdapter = MainActivityRefactorSource.member(source, "updateRuntimeMetricOutputGate")
        assertTrue(frameAdapter.contains("runtimeMetricPreflight.updateOutputGate(frame, snapshot, observedAtElapsedRealtimeMs, expectedArSessionGeneration, expectedWalkEpoch, acquisitionFailure, recognitionCameraTimestampNs)"))
        val preflight = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        val adapter = MainActivityRefactorSource.member(preflight, "updateOutputGate")
        assertTrue(adapter.contains("metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,"))
        assertTrue(adapter.contains("frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs)"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricPreflight").contains("runtimeMetricStateLock, { runtimeMetricSession }, { arSessionOwner }, runtimeCameraPlatform,"))
        assertTrue(MainActivityRefactorSource.member(source, "updateRuntimeMetricFrameEvidence").contains("runtimeMetricSession.updateRuntimeMetricFrameEvidence("))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("RuntimeMetricSessionController("))
        val metricOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val gate = MainActivityRefactorSource.member(metricOwner, "updateRuntimeMetricFrameEvidence")
        assertTrue(gate.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(gate.contains("assessRuntimeDepthFrame("))
        assertTrue(gate.contains("previousFrameTimestampNanos = runtimeMetricLastFrameTimestampNanos"))
    }

    @Test
    fun metricInvalidationCancelsEveryPendingOutputAndNetworkPath() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val invalidation = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricInvalidationController.kt").readText(), "invalidate")
        val binding = MainActivityRefactorSource.composition(source, "runtimeMetricInvalidation")
        listOf(
            "voice.cancelRecognition()" to "cancelVoiceCommandRecognition()",
            "reports.cancelPrivacyCalls()" to "reportPrivacyConsentSession.cancelActiveCalls()",
            "navigation.cancelAndClearRequests()" to "cancelNavigationRequestsForPause()",
            "navigation.cancelAndClearRequests()" to "clearDestinationSearchState(",
            "navigation.resetRoute()" to "resetRouteState()",
            "reports.clearExplicitFrame(" to "this@MainActivity.reportCandidateController.clearFrameState(reason)",
        ).forEach { (portCall, originalCall) ->
            assertTrue("missing invalidation call: $portCall", invalidation.contains(portCall))
            assertTrue("missing actual invalidation adapter: $originalCall", binding.contains(originalCall))
        }
        assertTrue(invalidation.contains("feedback().cancelPendingFeedbackDeliveries()"))
        assertTrue(binding.contains("{ this@MainActivity.riskFeedbackComponents.delivery }"))
        assertTrue(invalidation.contains("speech().closeActuator()"))
        assertTrue(binding.contains("{ speechOutputController }"))
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "closeActuator").contains("feedbackActuator?.close()"))

        val explicitReport = functionBlock("private fun ensureExplicitReportCapturePreconditions()")
        val metricGate = explicitReport.indexOf("frames.runtimeMetricAllowsWork()")
        assertTrue(metricGate >= 0)
        assertTrue(MainActivityRefactorSource.composition(source, "reportCandidateController")
            .contains("override fun runtimeMetricAllowsWork(expectedGeneration: Long?) = currentRuntimeMetricOutputAllowsWork(expectedGeneration)"))
        val submit = functionBlock("private fun submitFrozenExplicitReportAfterConfirmation(")
        assertTrue(submit.contains("confirmed.useExactBytes"))
        assertFalse(submit.contains("prepareExplicitReportCandidateIfCurrent("))
    }

    @Test
    fun preflightResultPersistsAggregateProvenanceWithoutRawPayloads() {
        val store = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightResultStore.kt").readText()
        val persistence = MainActivityRefactorSource.member(store, "persist")
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricPreflightStore").contains("RuntimeMetricPreflightResultStore({ stepLengthPrefs }, RUNTIME_METRIC_PREFLIGHT_POLICY_VERSION)"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("this@MainActivity.runtimeMetricPreflightStore.persist(result, profileMatch)"))
        listOf(
            "runtime_metric_preflight_status",
            "runtime_metric_preflight_reason",
            "runtime_metric_preflight_distinct_frames",
            "runtime_metric_preflight_passing_frames",
            "runtime_metric_preflight_observation_span_ms",
            "runtime_metric_preflight_profile_registry_sha256",
            "WalkSafeApprovedDeviceProfiles.registryContentSha256",
        ).forEach { token -> assertTrue("missing provenance: $token", persistence.contains(token)) }
        assertFalse(persistence.contains("rawDepth"))
        assertFalse(persistence.contains("fullDepth"))
        assertFalse(persistence.contains("ByteArray"))

        val sampleCount = functionBlock("private fun DepthFrameSnapshot.runtimeMetricValidSampleCount(")
        assertTrue(sampleCount.contains("validMetricSampleCount("))
        assertTrue(sampleCount.contains("requireFreshRaw = requireFreshRaw"))
        val preflight = functionBlock("private fun handleRuntimeMetricPreflightFrame(")
        assertTrue(preflight.contains("runtimeMetricValidSampleCount(requireFreshRaw = true)"))
    }

    @Test
    fun rendererCannotObserveAnArSessionUntilItHasResumed() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val preflight = functionBlock("private fun startRuntimeMetricPreflightSession(")
        val preflightPause = preflight.indexOf("renderer.pauseForClose()")
        val preflightResume = preflight.indexOf("platform.resumeBeforePublish(preflightSession)")
        val preflightPublish = preflight.indexOf("native().publish(preflightSession, provider, preflightPurpose)")
        assertTrue(preflightPause >= 0)
        assertTrue(preflightResume > preflightPause)
        assertTrue(preflightPublish > preflightResume)
        assertTrue(preflight.contains("renderer.resumeAfterClose(resumeRenderer)"))
        assertTrue(preflight.contains("renderer.publishSupported()"))
        val preflightBinding = MainActivityRefactorSource.composition(source, "runtimeMetricPreflight")
        assertTrue(preflightBinding.contains("override fun pauseForClose() = pauseRendererForSessionClose()"))
        assertTrue(preflightBinding.contains("override fun resumeAfterClose(resume: Boolean) = resumeRendererAfterSessionClose(resume)"))
        assertTrue(preflightBinding.contains("syncActiveSessionScreenPolicy()"))
        assertTrue(preflightBinding.contains("ArSessionPurpose.PREFLIGHT, ArSessionPurpose.RUNTIME"))

        val runtimeSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val lifecycleSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeCameraLifecycleCoordinator.kt").readText()
        val runtimeBinding = MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue(runtimeBinding.contains("RuntimeCameraLifecycleCoordinator(runtimeMetricStateLock, frameStateLock, arSessionOwner,"))
        assertTrue(runtimeBinding.contains("runtimeCameraPlatform"))
        assertTrue(runtimeBinding.contains("override fun pauseForClose() = pauseRendererForSessionClose()"))
        assertTrue(runtimeBinding.contains("override fun resumeAfterClose(shouldResume: Boolean) = resumeRendererAfterSessionClose(shouldResume)"))
        assertTrue(MainActivityRefactorSource.member(lifecycleSource, "continueDepthSessionStart").contains("transactions.start("))
        assertTrue(MainActivityRefactorSource.property(lifecycleSource, "transactions").contains("RuntimeArLifecycleTransactions(metricStateLock, arOwner, runtimePurpose,"))
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        assertTrue(MainActivityRefactorSource.property(source, "runtimeCameraPlatform").contains("AndroidRuntimeArPlatform("))
        assertTrue(MainActivityRefactorSource.member(platform, "resumeBeforePublish").contains("session.resume()"))
        val runtime = MainActivityRefactorSource.member(runtimeSource, "start")
        val runtimePause = runtime.indexOf("renderer.pauseForClose()")
        val runtimeResume = runtime.indexOf("platform.resumeBeforePublish(newSession)")
        val runtimePublish = runtime.indexOf("arOwner.publish(newSession, provider, runtimePurpose)")
        assertTrue(runtimePause >= 0)
        assertTrue(runtimeResume > runtimePause)
        assertTrue(runtimePublish > runtimeResume)
        assertTrue(runtime.contains("renderer.resumeAfterClose(resumeRenderer)"))
    }

    @Test
    fun aggregateActivationNeverAllowsAFailingTerminalFrameAndTracksEveryTimestamp() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val frameAdapter = MainActivityRefactorSource.member(source, "updateRuntimeMetricOutputGate")
        assertTrue(frameAdapter.contains("runtimeMetricPreflight.updateOutputGate(frame, snapshot, observedAtElapsedRealtimeMs, expectedArSessionGeneration, expectedWalkEpoch, acquisitionFailure, recognitionCameraTimestampNs)"))
        val preflight = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        val adapter = MainActivityRefactorSource.member(preflight, "updateOutputGate")
        assertTrue(adapter.contains("metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,"))
        assertTrue(adapter.contains("frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs)"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricPreflight").contains("runtimeMetricStateLock, { runtimeMetricSession }, { arSessionOwner }, runtimeCameraPlatform,"))
        assertTrue(MainActivityRefactorSource.member(source, "updateRuntimeMetricFrameEvidence").contains("runtimeMetricSession.updateRuntimeMetricFrameEvidence("))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("RuntimeMetricSessionController("))
        val metricOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val gate = MainActivityRefactorSource.member(metricOwner, "updateRuntimeMetricFrameEvidence")
        val aggregateAvailable = gate.indexOf("result.status == RuntimeMetricPreflightStatus.AVAILABLE")
        val currentFrameCheck = gate.indexOf("runtimeMetricOutputAllowed = frameEvidence.tracking", aggregateAvailable)
        val activationEnd = gate.indexOf("runtimeMetricActivationSession = null", aggregateAvailable)
        assertTrue(aggregateAvailable >= 0)
        assertTrue(currentFrameCheck > aggregateAvailable)
        assertTrue(activationEnd > currentFrameCheck)

        val steadyState = gate.substringAfter("runtimeMetricActivationSession == null")
        val orderCheck = steadyState.indexOf("if (assessment.isNewFrame)")
        val timestampUpdate = steadyState.indexOf("runtimeMetricLastFrameTimestampNanos = frameEvidence.frameTimestampNanos")
        val validFrameBranch = steadyState.indexOf("if (assessment.refreshesLastValidTime)")
        assertTrue(orderCheck >= 0)
        assertTrue(timestampUpdate > orderCheck)
        assertTrue("all increasing observations must advance the monotonic timestamp", timestampUpdate < validFrameBranch)
        assertTrue(
            steadyState.contains(
                "shouldStartNavigation = assessment.outputAllowed && runtimeMetricInitialNavigationStartPending",
            ),
        )
        assertTrue(steadyState.contains("assessRuntimeDepthFrame("))
        assertTrue(steadyState.contains("shouldHandleLoss = assessment.shouldHandleLoss"))
        assertTrue(steadyState.contains("startInitialNavigationServicesIfCurrent("))
        val navigationStart = functionBlock("private fun startInitialNavigationServicesIfCurrent(")
        val actualStart = navigationStart.indexOf("effects.startNavigationServices()")
        val pendingConsumed = navigationStart.indexOf("runtimeMetricInitialNavigationStartPending = false")
        assertTrue(actualStart >= 0)
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("override fun startNavigationServices() { startNavigationServicesIfNeeded() }"))
        assertTrue("pending must be consumed only after the main-thread start call", pendingConsumed > actualStart)
    }

    @Test
    fun invalidationAndReportClaimsAreSerializedBeforeAnyStaleOutput() {
        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val invalidation = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricInvalidationController.kt").readText(), "invalidate")
        val reportCancellation = invalidation.indexOf(
            "reports.cancelPrivacyCalls()",
        )
        val frameInvalidation = invalidation.indexOf("navigation.invalidateFrame()")
        val rendererPause = invalidation.indexOf("renderer.pauseForSessionClose()")
        assertTrue(reportCancellation >= 0)
        assertTrue(frameInvalidation > reportCancellation)
        assertTrue(rendererPause > frameInvalidation)
        val binding = MainActivityRefactorSource.composition(source, "runtimeMetricInvalidation")
        assertTrue(binding.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(binding.contains("override fun invalidateFrame() = invalidateFrameStateForPause()"))
        assertTrue(binding.contains("override fun pauseForSessionClose() = pauseRendererForSessionClose()"))

        val automatic = functionBlock("internal fun publishCurrentFrameReportState(")
        assertTrue(automatic.contains("synchronized(frameStateLock)"))
        assertTrue(automatic.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(automatic.contains("frames.runtimeMetricAllowsWork(expectedRuntimeMetricGeneration)"))

        val explicit = functionBlock("private fun prepareExplicitReportCandidateIfCurrent(")
        assertTrue(explicit.contains("synchronized(frameStateLock)"))
        assertTrue(explicit.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(explicit.contains("frames.runtimeMetricAllowsWork()"))
        val reportBinding = MainActivityRefactorSource.composition(source, "reportCandidateController")
        assertTrue(reportBinding.contains("frameStateLock = frameStateLock"))
        assertTrue(reportBinding.contains("runtimeMetricStateLock = runtimeMetricStateLock"))
        assertTrue(reportBinding.contains("override fun runtimeMetricAllowsWork(expectedGeneration: Long?) = currentRuntimeMetricOutputAllowsWork(expectedGeneration)"))
        val request = functionBlock("private fun requestExplicitReport(")
        assertTrue(request.contains("explicitReportConfirmationPolicy.consumeIfConfirmed("))
        assertTrue(request.contains("submitFrozenExplicitReportAfterConfirmation(confirmed)"))
        assertTrue(
            request
                .contains("prepareExplicitReportCandidateIfCurrent("),
        )
    }

    @Test
    fun startupCanRecoverAnActiveFieldLogAndCollectionUsesFeatureSpecificGates() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

        val runtimePanel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        val onboardingPanel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
        assertTrue(source.contains("DestinationGuidancePanel("))
        assertTrue(source.contains("OnboardingReadinessPanel("))
        val runtimeControls = runtimePanel
        val readiness = onboardingPanel
        assertFalse(runtimeControls.contains("addView(fieldSessionLogButton)"))
        assertFalse(readiness.contains("addView(fieldSessionLogButton)"))
        assertFalse(source.contains("addView(fieldSessionLogButton)"))
        val fieldButton = functionBlock("private fun updateFieldSessionLogButton()")
        assertTrue(fieldButton.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(fieldButton.contains("fieldSessionLogButton.visibility = View.GONE"))
        assertTrue(fieldButton.contains("val sessionId = fieldSessionLog.activeSessionId()"))
        assertTrue(Regex(Regex.escape("fieldSessionLog.activeSessionId()")).findAll(fieldButton).count() == 1)
        assertFalse(fieldButton.contains("fieldSessionLog.isActive()"))
        assertTrue(fieldButton.contains("val active = sessionId != null"))
        assertTrue(fieldButton.contains("active || isStartupCapabilityConfirmed()"))
        assertTrue(
            Regex(
                """if \(this@MainActivity\.riskFeedbackComponents\.delivery\.rejectionFieldSessionId\(\) != sessionId\) \{\s*""" +
                    """this@MainActivity\.riskFeedbackComponents\.delivery\.setRejectionFieldSession\(sessionId\)\s*resetFeedbackOutputRejectionDiagnostics\(\)""",
            ).containsMatchIn(fieldButton),
        )
        assertTrue(
            functionBlock("private fun toggleFieldSessionLog()")
                .contains("refreshStartupCapabilityUi()"),
        )

        val collectionGate = functionBlock("private fun currentNavigationCollectionAllowsWork()")
        assertTrue(
            collectionGate.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)",
            ),
        )
        assertFalse(collectionGate.contains("WalkSafeStartupCapabilityTier.FULL"))
        assertFalse(collectionGate.contains("currentRuntimeMetricOutputAllowsWork()"))
        assertTrue(
            functionBlock("private fun startLocationUpdatesIfAllowed(")
                .contains("scope.currentOwner()"),
        )
        assertTrue(
            functionBlock("private fun startStepTrackingIfAllowed()")
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )

        assertTrue(navigationInput("positions = PositioningAssemblyBindings(", "gpsBindings = GpsAssemblyBindings(").contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        val positioningAssembly = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioningAssembly.contains("val scope = positions.scope()"))
        assertTrue(positioningAssembly.contains("PositioningSessionController(run, scope, sources"))
        val confirmation = functionBlock("private fun completeStartupCapabilityConfirmation(")
        assertTrue(MainActivityRefactorSource.composition(source, "startupConfirmationInteraction").contains("StartupConfirmationInteractionController("))
        assertTrue(source.contains("this@MainActivity.startupConfirmationInteraction.completeStartupCapabilityConfirmation(decision)"))
        assertFalse(confirmation.contains("earthOrientationTracker.start()"))
        val activation = functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")
        assertTrue(activation.contains("effects.startOrientationIfInitialized()"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkRuntimeCameraAdmissionController").contains("earthOrientationTracker.start()"))
    }

    private fun sourceSection(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("missing source marker: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing source marker: $endMarker", end > start)
        return source.substring(start, end)
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing function: $marker", start >= 0)
        val nextPrivate = source.indexOf("\n    private fun ", start + marker.length)
        val nextInternal = source.indexOf("\n    internal fun ", start + marker.length)
        val nextOverride = source.indexOf("\n    override fun ", start + marker.length)
        val end = listOf(nextPrivate, nextInternal, nextOverride)
            .filter { it > start }
            .minOrNull()
            ?: source.length
        return source.substring(start, end)
    }

    private fun functionContaining(token: String): String {
        val tokenIndex = source.indexOf(token)
        assertTrue("missing source token: $token", tokenIndex >= 0)
        val privateStart = source.lastIndexOf("\n    private fun ", tokenIndex)
        val internalStart = source.lastIndexOf("\n    internal fun ", tokenIndex)
        val overrideStart = source.lastIndexOf("\n    override fun ", tokenIndex)
        val start = maxOf(privateStart, internalStart, overrideStart)
        assertTrue("$token must be created inside a function", start >= 0)
        return functionBlock(source.substring(start + 5).substringBefore('(') + "(")
    }

    private fun firstIndexOf(source: String, vararg candidates: String): Int =
        candidates.map(source::indexOf).filter { it >= 0 }.minOrNull() ?: -1

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
