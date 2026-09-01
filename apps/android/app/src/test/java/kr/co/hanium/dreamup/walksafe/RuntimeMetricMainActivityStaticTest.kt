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
        assertTrue(
            "startup must expose a dedicated runtime metric preflight button",
            source.contains("startupMetricPreflightButton"),
        )
        assertTrue(
            "the post-login check must start from its single primary action",
            source.contains("setOnClickListener { handlePostLoginDeviceCheckPrimaryAction() }"),
        )
        val primaryAction = functionBlock("private fun handlePostLoginDeviceCheckPrimaryAction()")
        assertTrue(primaryAction.contains("startPostLoginDeviceCheckFromPrimaryAction()"))
        assertFalse(primaryAction.contains("showPostLoginDeviceCheckExplanation()"))
        val start = functionBlock("private fun startPostLoginDeviceCheckFromPrimaryAction()")
        assertTrue(start.contains("beginPostLoginDeviceCheckFromUserAction(sessionBinding)"))
        assertFalse(start.contains("AlertDialog"))

        val readiness = sourceSection(
            "walkReadinessControls = LinearLayout(this).apply",
            "walkLastResultText = TextView(this).apply",
        )
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
        assertTrue(
            "preflight camera permission must use its own one-shot request purpose",
            source.contains("PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA"),
        )
        val permissionDispatch = functionBlock("override fun onRequestPermissionsResult(")
        assertTrue(
            permissionDispatch.contains(
                "PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->",
            ),
        )
        assertTrue(
            permissionDispatch.contains("handleMetricPreflightCameraPermissionResult()"),
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
        assertTrue(source.contains("arSessionPurpose"))
        assertTrue(source.contains("RuntimeMetricPreflightSession("))

        val start = functionContaining("RuntimeMetricPreflightSession(")
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
        val draw = functionBlock("override fun onDrawFrame(gl: GL10?)")
        val branch = draw.indexOf("ArSessionPurpose.PREFLIGHT")
        val handler = draw.indexOf("handleRuntimeMetricPreflightFrame(")
        val detector = draw.indexOf("scheduleDetectionIfDue(")

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
        val finish = functionBlock("private fun finishRuntimeMetricPreflight(")
        val close = firstIndexOf(
            finish,
            "stopDepthSession(closeSession = true)",
            "currentSession.close()",
            "session?.close()",
        )
        val metric = finish.indexOf("metricDistanceCapabilityOverride")
        val profile = finish.indexOf("profile", ignoreCase = true)

        assertTrue("terminal preflight must close its ARCore session", close >= 0)
        assertTrue("terminal preflight must publish the metric result", metric >= 0)
        assertTrue("terminal preflight must publish designated-device profile evidence", profile >= 0)
        assertTrue("metric evidence must be applied only after session closure", close < metric)
        assertTrue("profile evidence must be applied only after session closure", close < profile)
        assertTrue(finish.contains("refreshStartupCapabilityUi()"))
        assertFalse(
            "device-check measurement must not confirm walk startup",
            finish.contains("confirmedStartupCapabilityDecision ="),
        )
        assertFalse(finish.contains("completeStartupCapabilityConfirmation("))
        assertFalse(finish.contains("confirmStartupCapabilityDecision()"))
    }

    @Test
    fun missingOrOutdatedArCoreLimitsMetricDistanceWithoutAnInstallPrompt() {
        val continuation = functionBlock("private fun continueRuntimeMetricPreflightStart(")
        val unsupportedBranch = continuation.substringAfter(
            "availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED",
        ).substringBefore("var candidateSession")

        assertTrue(
            unsupportedBranch.contains(
                "availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD",
            ),
        )
        assertTrue(unsupportedBranch.contains("finishRuntimeMetricPreflightWithoutSession("))
        assertTrue(unsupportedBranch.contains("RuntimeMetricDepthSupport.UNSUPPORTED"))
        assertTrue(unsupportedBranch.contains("return"))
        assertFalse(unsupportedBranch.contains("requestInstall("))
        assertFalse(unsupportedBranch.contains("updateStatus("))
    }

    @Test
    fun pauseAndDestroyInvalidatePreflightGenerationAndLateCallbacks() {
        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val destroy = functionBlock("override fun onDestroy()")
        val invalidation = functionBlock("private fun invalidateRuntimeMetricEvidence(")

        assertTrue(pause.contains("invalidateRuntimeMetricEvidence("))
        assertTrue(destroy.contains("invalidateRuntimeMetricEvidence("))
        assertTrue(
            "invalidation must advance a preflight generation",
            invalidation.contains("runtimeMetricPreflightGeneration += 1") ||
                invalidation.contains("runtimeMetricPreflightGeneration++"),
        )
        assertTrue(
            "invalidation must discard the active preflight session",
            invalidation.contains("runtimeMetricPreflightSession = null"),
        )
    }

    @Test
    fun runtimeMetricEvidenceFailsClosedBeforeDetectorReportFeedbackAndQueuedDelivery() {
        assertTrue(source.contains("runtimeMetricOutputAllowed"))
        val draw = functionBlock("override fun onDrawFrame(gl: GL10?)")
        val failClosedGate = draw.indexOf("if (!runtimeMetricOutputAllowed)")
        val detector = draw.indexOf("scheduleDetectionIfDue(")
        val report = draw.indexOf("publishCurrentFrameReportState(")
        val feedback = draw.indexOf("dispatchFeedback(")

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
            draw.substring(failClosedGate, report).contains("return"),
        )
        val scheduled = functionBlock("private fun scheduleDetectionIfDue(")
        assertTrue(
            scheduled.indexOf("observeOfficialEnvironmentCameraFrame(qualityObservation)") <
                scheduled.indexOf("if (!currentRuntimeMetricOutputAllowsWork("),
        )
        assertTrue(
            scheduled.indexOf("if (!currentRuntimeMetricOutputAllowsWork(") <
                scheduled.indexOf("frameDetector.detect("),
        )

        val queuedFeedbackGate = functionBlock("private fun currentFeedbackDeviceGateAllowsAlerts()")
        assertTrue(
            "queued TTS/vibration must re-check current runtime metric evidence",
            queuedFeedbackGate.contains("runtimeMetricOutputAllowed"),
        )
        assertTrue(queuedFeedbackGate.contains("ArSessionPurpose.RUNTIME"))
    }

    @Test
    fun preflightRejectsActiveFieldLoggingAndDoesNotMutateOverlaySelection() {
        val eligibility = functionBlock("private fun canBeginRuntimeMetricPreflight()")
        assertTrue(eligibility.contains("isFieldSessionActive()"))

        val draw = functionBlock("override fun onDrawFrame(gl: GL10?)")
        val preflight = draw.indexOf("ArSessionPurpose.PREFLIGHT")
        val overlay = draw.indexOf("selectOverlayDetections(")
        assertTrue(preflight >= 0)
        assertTrue(overlay >= 0)
        assertTrue("preflight must return before overlay state selection", preflight < overlay)
        assertTrue(draw.substring(preflight, overlay).contains("return"))
    }

    @Test
    fun sessionLeaseAndMetricStateLockPreventOldFramesFromCrossingGenerations() {
        val draw = functionBlock("override fun onDrawFrame(gl: GL10?)")
        val lease = draw.indexOf("ArSessionLease(")
        val update = draw.indexOf("arLease.session.update()")
        val validationAfterUpdate = draw.indexOf("isArSessionLeaseCurrent(arLease)", update)
        assertTrue(lease >= 0)
        assertTrue(update > lease)
        assertTrue(validationAfterUpdate > update)

        val gate = functionBlock("private fun updateRuntimeMetricOutputGate(")
        assertTrue(gate.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(gate.contains("frame.timestamp > runtimeMetricLastFrameTimestampNanos"))
    }

    @Test
    fun metricInvalidationCancelsEveryPendingOutputAndNetworkPath() {
        val invalidation = functionBlock("private fun invalidateRuntimeMetricEvidence(")
        listOf(
            "cancelVoiceCommandRecognition()",
            "reportPrivacyConsentSession.cancelActiveCalls()",
            "cancelNavigationRequestsForPause()",
            "clearDestinationSearchState(",
            "resetRouteState()",
            "clearExplicitReportFrameState(",
            "feedbackPolicy.cancelPendingFeedbackDeliveries()",
            "feedbackActuator?.close()",
        ).forEach { call -> assertTrue("missing invalidation call: $call", invalidation.contains(call)) }

        val explicitReport = functionBlock("private fun ensureExplicitReportCapturePreconditions()")
        val metricGate = explicitReport.indexOf("currentRuntimeMetricOutputAllowsWork()")
        assertTrue(metricGate >= 0)
        val submit = functionBlock("private fun submitFrozenExplicitReportAfterConfirmation(")
        assertTrue(submit.contains("confirmed.useExactBytes"))
        assertFalse(submit.contains("prepareExplicitReportCandidateIfCurrent("))
    }

    @Test
    fun preflightResultPersistsAggregateProvenanceWithoutRawPayloads() {
        val persistence = functionBlock("private fun persistRuntimeMetricPreflightResult(")
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

        val sampleCount = functionBlock("private fun DepthFrameSnapshot.runtimeMetricValidSampleCount()")
        assertTrue(sampleCount.contains("maxOf(rawCount, fullCount)"))
    }

    @Test
    fun rendererCannotObserveAnArSessionUntilItHasResumed() {
        val preflight = functionBlock("private fun startRuntimeMetricPreflightSession(")
        val preflightPause = preflight.indexOf("pauseRendererForSessionClose()")
        val preflightResume = preflight.indexOf("resumeArSessionBeforePublish(preflightSession)")
        val preflightPublish = preflight.indexOf("session = preflightSession")
        assertTrue(preflightPause >= 0)
        assertTrue(preflightResume > preflightPause)
        assertTrue(preflightPublish > preflightResume)
        assertTrue(preflight.contains("resumeRendererAfterSessionClose(resumeRenderer)"))
        assertTrue(preflight.contains("syncActiveSessionScreenPolicy()"))

        val runtime = functionBlock("private fun continueDepthSessionStart(")
        val runtimePause = runtime.indexOf("pauseRendererForSessionClose()")
        val runtimeResume = runtime.indexOf("resumeArSessionBeforePublish(newSession)")
        val runtimePublish = runtime.indexOf("session = newSession")
        assertTrue(runtimePause >= 0)
        assertTrue(runtimeResume > runtimePause)
        assertTrue(runtimePublish > runtimeResume)
        assertTrue(runtime.contains("resumeRendererAfterSessionClose(resumeRenderer)"))
    }

    @Test
    fun aggregateActivationNeverAllowsAFailingTerminalFrameAndTracksEveryTimestamp() {
        val gate = functionBlock("private fun updateRuntimeMetricOutputGate(")
        val aggregateAvailable = gate.indexOf("result.status == RuntimeMetricPreflightStatus.AVAILABLE")
        val currentFrameCheck = gate.indexOf("runtimeMetricOutputAllowed = frameEvidence.tracking", aggregateAvailable)
        val activationEnd = gate.indexOf("runtimeMetricActivationSession = null", aggregateAvailable)
        assertTrue(aggregateAvailable >= 0)
        assertTrue(currentFrameCheck > aggregateAvailable)
        assertTrue(activationEnd > currentFrameCheck)

        val steadyState = gate.substringAfter("runtimeMetricActivationSession == null")
        val orderCheck = steadyState.indexOf("if (frameOrderValid)")
        val timestampUpdate = steadyState.indexOf("runtimeMetricLastFrameTimestampNanos = frame.timestamp")
        val validFrameBranch = steadyState.indexOf("if (currentFrameValid)")
        assertTrue(orderCheck >= 0)
        assertTrue(timestampUpdate > orderCheck)
        assertTrue("all increasing observations must advance the monotonic timestamp", timestampUpdate < validFrameBranch)
        assertTrue(
            steadyState.contains(
                "shouldStartNavigation = currentFrameValid && runtimeMetricInitialNavigationStartPending",
            ),
        )
        assertFalse(steadyState.contains("currentFrameValid && !runtimeMetricOutputAllowed"))
        assertTrue(steadyState.contains("startInitialNavigationServicesIfCurrent("))
        val navigationStart = functionBlock("private fun startInitialNavigationServicesIfCurrent(")
        val actualStart = navigationStart.indexOf("startNavigationServicesIfNeeded()")
        val pendingConsumed = navigationStart.indexOf("runtimeMetricInitialNavigationStartPending = false")
        assertTrue(actualStart >= 0)
        assertTrue("pending must be consumed only after the main-thread start call", pendingConsumed > actualStart)
    }

    @Test
    fun invalidationAndReportClaimsAreSerializedBeforeAnyStaleOutput() {
        val invalidation = functionBlock("private fun invalidateRuntimeMetricEvidence(")
        val reportCancellation = invalidation.indexOf(
            "reportPrivacyConsentSession.cancelActiveCalls()",
        )
        val frameInvalidation = invalidation.indexOf("invalidateFrameStateForPause()")
        val rendererPause = invalidation.indexOf("pauseRendererForSessionClose()")
        assertTrue(reportCancellation >= 0)
        assertTrue(frameInvalidation > reportCancellation)
        assertTrue(rendererPause > frameInvalidation)

        val automatic = functionBlock("internal fun publishCurrentFrameReportState(")
        assertTrue(automatic.contains("synchronized(frameStateLock)"))
        assertTrue(automatic.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(automatic.contains("currentRuntimeMetricOutputAllowsWork(expectedRuntimeMetricGeneration)"))

        val explicit = functionBlock("private fun prepareExplicitReportCandidateIfCurrent(")
        assertTrue(explicit.contains("synchronized(frameStateLock)"))
        assertTrue(explicit.contains("synchronized(runtimeMetricStateLock)"))
        assertTrue(explicit.contains("currentRuntimeMetricOutputAllowsWork()"))
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
        val runtimeControls = sourceSection(
            "runtimeControls = LinearLayout(this).apply",
            "val overlay = LinearLayout(this).apply",
        )
        val readiness = sourceSection(
            "walkReadinessControls = LinearLayout(this).apply",
            "walkLastResultText = TextView(this).apply",
        )
        assertFalse(runtimeControls.contains("addView(fieldSessionLogButton)"))
        assertTrue(readiness.contains("if (BuildConfig.DEBUG)"))
        assertTrue(readiness.contains("addView(fieldSessionLogButton)"))
        val fieldButton = functionBlock("private fun updateFieldSessionLogButton()")
        assertTrue(fieldButton.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(fieldButton.contains("fieldSessionLogButton.visibility = View.GONE"))
        assertTrue(fieldButton.contains("fieldSessionLog.isActive() || isStartupCapabilityConfirmed()"))
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
                .contains("currentLocationCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun startStepTrackingIfAllowed()")
                .contains("currentNavigationCollectionAllowsWork()"),
        )

        val confirmation = functionBlock("private fun completeStartupCapabilityConfirmation(")
        assertFalse(confirmation.contains("earthOrientationTracker.start()"))
        assertTrue(
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")
                .contains("earthOrientationTracker.start()"),
        )
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
}
