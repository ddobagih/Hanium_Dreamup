package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWalkSessionLifecycleStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun foregroundReturnCannotDirectlyRestartRuntimeResources() {
        val onResume = functionBlock("override fun onResume()")
        val readyResume = functionBlock(
            "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()",
        )

        assertTrue(
            onResume.contains("resumeWalkSafeRuntimeAfterPrivacyStartupInspection()"),
        )
        assertTrue(readyResume.contains("handleWalkSessionForegroundReturn()"))
        assertFalse(readyResume.contains("startNavigationServicesIfNeeded()"))
        assertFalse(readyResume.contains("bindCameraFallbackSession()"))
        assertFalse(readyResume.contains("currentSession.resume()"))
        assertFalse(readyResume.contains("earthOrientationTracker.start()"))
        assertFalse(readyResume.contains("surfaceView.onResume()"))
    }

    @Test
    fun pauseTransitionsTheSessionBeforeCancellingRuntimeWork() {
        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val transition = pause.indexOf("WalkSessionEvent.EnteredBackground")
        val frameInvalidation = pause.indexOf("invalidateRuntimeMetricEvidence(\"app_paused\")")

        assertTrue(transition >= 0)
        assertTrue(frameInvalidation > transition)
        assertTrue(pause.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(pause.contains("persistWalkSessionInterruptionMarker()"))
        assertTrue(pause.contains("cancelVoiceCommandRecognition()"))
        assertTrue(pause.contains("stopLocationUpdates()"))
        assertTrue(pause.contains("stopStepTracking()"))
    }

    @Test
    fun everyRuntimeEntryPointRequiresAnActiveWalkSession() {
        assertTrue(
            functionBlock("private fun currentStepTrackingCollectionAllowsWork()")
                .contains("isWalkSessionRuntimeActive()"),
        )
        assertTrue(
            functionBlock("private fun currentNavigationCollectionAllowsWork()")
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun startDepthSession()")
                .contains("if (!isWalkSessionRuntimeActive()) return"),
        )
        assertTrue(
            functionBlock("private fun startCameraFallbackSession(")
                .contains("if (!isWalkSessionRuntimeActive()) return"),
        )
        assertTrue(
            functionBlock("private fun bindCameraFallbackSession()")
                .contains("currentRuntimeEpochOrNull() ?: return"),
        )
        assertTrue(
            functionBlock("private fun analyzeCameraFallbackFrame(")
                .contains("isCameraFallbackLeaseCurrent("),
        )
        assertTrue(
            functionBlock("private fun currentRuntimeMetricOutputAllowsWork(")
                .contains("isWalkSessionRuntimeActive()"),
        )
        assertTrue(
            functionBlock("private fun handleLocationUpdate(")
                .contains("!currentNavigationCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun prepareReportCandidate(")
                .contains("currentRuntimeEpochOrNull()"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("!currentNavigationCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun currentNavigationCollectionAllowsWork()")
                .contains("postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"),
        )
    }

    @Test
    fun recheckUsesTheApprovedPromptAndOnlyExactStartCanResume() {
        val prompt = functionBlock("private fun requestWalkSessionResumeConfirmation()")
        val response = functionBlock("private fun handleWalkSessionResumeRecognition(")

        assertTrue(
            prompt.contains("보행 안내를 다시 시작할까요? 시작 또는 취소라고 말해 주세요"),
        )
        assertTrue(prompt.contains("VoiceRecognitionPurpose.WALK_SESSION_RESUME"))
        assertFalse(prompt.contains("postDelayed("))
        assertTrue(response.contains("WalkSessionResumeConfirmation.fromRecognizedText("))
        assertTrue(response.contains("WalkSessionEvent.ResumeConfirmationReceived("))
        assertTrue(response.contains("if (isWalkSessionRuntimeActive())"))
        assertTrue(response.contains("walkSessionResumeRetryRequiresUserAction"))
        assertTrue(
            functionBlock("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
                .contains("walkSessionResumeRetryRequiresUserAction"),
        )
    }

    @Test
    fun failedAutomaticNoticeAndResumeResponseRequireAnExplicitRetryAction() {
        val failure = functionBlock("private fun failStartupCapabilityConfirmation(")
        val advance = functionBlock("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
        val buttonAction =
            functionBlock("private fun handleStartupCapabilityConfirmAction()")

        assertTrue(failure.contains("startupCapabilityRetryRequiresUserAction = true"))
        assertTrue(advance.contains("startupCapabilityRetryRequiresUserAction"))
        assertTrue(advance.contains("walkSessionResumeRetryRequiresUserAction"))
        assertTrue(buttonAction.contains("startupCapabilityRetryRequiresUserAction = false"))
        assertTrue(buttonAction.contains("walkSessionResumeRetryRequiresUserAction = false"))
        assertTrue(
            buttonAction.indexOf("walkSessionResumeRetryRequiresUserAction = false") <
                buttonAction.indexOf("resumePermissionRecoveryFromExplicitUserAction()"),
        )
        assertTrue(
            buttonAction.contains(
                "session.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED",
            ),
        )
        assertTrue(buttonAction.contains("refreshStartupCapabilityUi()"))
        assertTrue(
            Regex(
                """!startupCapabilityConfirmationPending\s*&&\s*!walkSessionResumePromptPending""",
            ).containsMatchIn(source),
        )
        assertTrue(
            functionBlock("private fun refreshStartupCapabilityUi()")
                .contains("mayConfirmSession"),
        )
    }

    @Test
    fun terminalWalkOffersFreshPreparationBeforeRecoveryAndReadinessGates() {
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val action = functionBlock("private fun handleStartupCapabilityConfirmAction()")
        val freshLabel = refresh.indexOf("preparesNewWalk -> \"새 보행 준비\"")
        val environmentLabel = refresh.indexOf("!officialEnvironmentReady")
        val freshAction = action.indexOf("if (preparesNewWalk)")
        val permissionRecovery =
            action.indexOf("resumePermissionRecoveryFromExplicitUserAction()")

        assertTrue(refresh.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(refresh.contains("WalkSessionState.ENDED"))
        assertTrue(refresh.contains("preparesNewWalk ||"))
        assertTrue(refresh.contains("awaitingExplicitResume && !preparesNewWalk"))
        assertTrue(freshLabel >= 0)
        assertTrue(environmentLabel > freshLabel)
        assertTrue(action.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(action.contains("WalkSessionState.ENDED"))
        assertTrue(freshAction >= 0)
        assertTrue(permissionRecovery > freshAction)
        assertTrue(action.substring(freshAction, permissionRecovery).contains("startFreshWalk("))
        assertTrue(action.substring(freshAction, permissionRecovery).contains("return"))
    }

    @Test
    fun processRestartMarkerPreventsFreshAutomaticRestart() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val persistence = functionBlock("private fun persistWalkSessionInterruptionMarker()")

        assertTrue(create.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(create.contains("previousProcessHadInterruptedWalk"))
        assertTrue(
            Regex(
                """putBoolean\(PREF_WALK_SESSION_INTERRUPTED, false\)\s*\.commit\(\)""",
            ).containsMatchIn(create),
        )
        assertTrue(create.contains("walkSessionLifecycle = WalkSessionLifecycle()"))
        assertTrue(create.contains("previous_walk_interrupted_fresh_walk_created"))
        assertFalse(create.contains("interruptedSessionRequiresConfirmation"))
        assertTrue(source.contains("const val PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(persistence.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(persistence.contains("WalkSessionState.ACTIVE"))
        assertTrue(persistence.contains("WalkSessionState.PAUSED"))
        assertTrue(persistence.contains(".commit()"))
    }

    @Test
    fun runtimeFailuresEnterSafetyStopAndCancelAsynchronousOutputs() {
        val metricInvalidation = functionBlock("private fun invalidateRuntimeMetricEvidence(")
        val speechFailure = functionBlock("private fun handleRuntimeSpeechCapabilityFailure(")
        val safetyStop = functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
        val foregroundRecheck = functionBlock(
            "private fun enterWalkSessionForegroundRecheckAndCancelOutputs(",
        )
        val outputCancellation = functionBlock("private fun cancelWalkSessionOutputs(")
        val preflight = functionBlock("private fun canBeginRuntimeMetricPreflight()")

        assertTrue(metricInvalidation.contains("WalkSessionEvent.SafetyStopRequested"))
        assertTrue(metricInvalidation.contains("runtimeFailureRequiresSafetyStop"))
        assertTrue(speechFailure.contains("기능 제한"))
        assertTrue(speechFailure.contains("다른 기능은 계속 사용할 수 있습니다."))
        assertFalse(speechFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(safetyStop.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(foregroundRecheck.contains("WalkSessionEvent.RecheckRequested"))
        assertTrue(foregroundRecheck.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(foregroundRecheck.contains("stopDepthSession(closeSession = true)"))
        assertTrue(outputCancellation.contains("invalidateArCoreAvailabilityRecheck()"))
        assertTrue(outputCancellation.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(outputCancellation.contains("cancelNavigationRequestsForPause()"))
        assertTrue(outputCancellation.contains("pendingTalkBackInteraction"))
        assertTrue(outputCancellation.contains("feedbackActuator?.close()"))
        assertTrue(outputCancellation.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(preflight.contains("WalkSessionState.READY"))
        assertTrue(preflight.contains("WalkSessionState.PAUSED"))
        assertFalse(preflight.contains("WalkSessionState.SAFE_STOP"))
        val fallbackRequest = functionBlock("private fun requestCameraFallbackStart(")
        assertTrue(fallbackRequest.contains("automaticallyContinueMetricOnlyDowngrade"))
        assertTrue(fallbackRequest.contains("CameraFallbackStartReason.RUNTIME_METRIC_LOST"))
        assertTrue(fallbackRequest.contains("enterWalkSessionForegroundRecheckAndCancelOutputs("))
    }

    @Test
    fun rejectedResumeRecognizerAndStaleDetectorWorkFailClosed() {
        val resumePrompt = functionBlock("private fun requestWalkSessionResumeConfirmation()")
        val voiceStart = functionBlock("private fun startVoiceCommandRecognition(")
        val detectorFailure = functionBlock("private fun handleDetectorRuntimeFailure(")
        val detectorScheduling = functionBlock("private fun scheduleDetectionIfDue(")
        val fullStart = functionBlock("private fun continueDepthSessionStart(")

        assertTrue(resumePrompt.contains("handleWalkSessionResumeRecognizerNotStarted()"))
        assertTrue(voiceStart.contains("): Boolean"))
        assertTrue(voiceStart.contains("return false"))
        assertTrue(detectorFailure.contains("expectedDetectorGeneration"))
        assertTrue(detectorFailure.contains("expectedWalkEpoch"))
        assertTrue(
            detectorFailure.contains(
                "isCurrentFrameGeneration(expectedDetectorGeneration, expectedWalkEpoch)",
            ) || detectorFailure.contains(
                "expectedDetectorGeneration,\n                                expectedWalkEpoch",
            ),
        )
        assertTrue(
            detectorScheduling.contains(
                "isCurrentFrameGeneration(generation, expectedWalkEpoch)",
            ),
        )
        assertTrue(fullStart.contains("if (!detectorAvailable)"))
        assertTrue(fullStart.contains("runtime_detector_start_unavailable"))
    }

    @Test
    fun startWalkRequestsOptionalPermissionsButRequiresOnlyActiveCoreFeatures() {
        val permissions = functionBlock("private fun missingWalkSessionPermissions(")
        val requiredPermissions =
            functionBlock("private fun missingRequiredWalkSessionPermissions(")
        val effectiveMode =
            functionBlock("private fun WalkSafeStartupCapabilityDecision.effectiveWalkSessionMode()")

        assertTrue(permissions.contains("Manifest.permission.CAMERA"))
        assertTrue(permissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(permissions.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertFalse(
            permissions.contains(
                "if (decision.tier == WalkSafeStartupCapabilityTier.FULL)",
            ),
        )
        assertTrue(permissions.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(requiredPermissions.contains("Manifest.permission.CAMERA"))
        assertTrue(requiredPermissions.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertFalse(requiredPermissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertFalse(requiredPermissions.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(effectiveMode.contains("toWalkSessionMode()"))
        assertFalse(effectiveMode.contains("!hasLocationPermission()"))
    }

    @Test
    fun activeResourceFailureAnnouncesTheSafetyStopReason() {
        val readiness = functionBlock("private fun applyRuntimeReadinessIfActive(")

        assertTrue(readiness.contains("cancelWalkSessionOutputs(\"runtime_readiness_changed\")"))
        assertTrue(readiness.contains("updateStatus(\"필수 기능 변경 · 안전 중지\", reason)"))
        assertTrue(readiness.contains("speakInteraction("))
        assertTrue(readiness.contains("보행 기능을 안전 중지했습니다."))
    }

    @Test
    fun permissionResultsAreOneShotAndPurposeGenerationBound() {
        val callback = functionBlock("override fun onRequestPermissionsResult(")
        val request = functionBlock("private fun requestPermissionsWithLease(")
        val current = functionBlock("private fun isPermissionRequestLeaseCurrent(")
        val walkResult = functionBlock("private fun handleWalkSessionPermissionResult()")

        assertTrue(callback.contains("permissionRequestLeases.remove(requestCode) ?: return"))
        assertTrue(callback.contains("if (!isPermissionRequestLeaseCurrent(lease)) return"))
        assertTrue(request.contains("permissionRequestGenerationByPurpose[purpose]"))
        assertTrue(request.contains("generation = generation"))
        assertTrue(
            current.contains(
                "permissionRequestGenerationByPurpose[lease.purpose]",
            ),
        )
        assertTrue(current.contains("lease.generation"))
        assertTrue(source.contains("val generation: Long"))
        assertTrue(walkResult.contains("missingRequiredWalkSessionPermissions(action)"))
        assertTrue(walkResult.contains("refreshStartupCapabilityUi()"))
        assertTrue(walkResult.contains("일부 기능 권한 없음"))
    }

    @Test
    fun voiceTerminalCallbacksConsumeTheirLeaseBeforeDispatch() {
        listOf(
            functionBlock("override fun onError(error: Int)"),
            functionBlock("override fun onResults(results: Bundle?)"),
        ).forEach { callback ->
            val guard = callback.indexOf("isVoiceRecognitionLeaseCurrent(")
            val consume = callback.indexOf("voiceRecognitionGeneration += 1")
            val deactivate = callback.indexOf("voiceRecognitionActive = false")

            assertTrue(guard >= 0)
            assertTrue(consume > guard)
            assertTrue(deactivate > consume)
        }
    }

    @Test
    fun systemResourceChangesReevaluateAndStopAnActiveWalk() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val restoreDeviceCheck =
            functionBlock("private fun bindPostLoginDeviceCheckSession(")
        val firstRunProbes =
            functionBlock("private fun maybeStartFirstRunDeviceCheckProbes()")
        val ensureResourceMonitoring =
            functionBlock("private fun ensureWalkSessionResourceMonitoring()")
        val resetResourceProbe =
            functionBlock("private fun resetWalkSessionResourceProbe()")
        val activate = functionBlock("private fun activateWalkSessionRuntime()")
        val initialActivation =
            functionBlock("private fun completeGatewayWalkActivation(")
        val spokenResume =
            functionBlock("private fun handleWalkSessionResumeRecognition(")
        val buttonResume =
            functionBlock("private fun confirmWalkSessionResumeFromButton()")
        val readiness = functionBlock("private fun applyRuntimeReadinessIfActive(")
        val destroy = functionBlock("override fun onDestroy()")

        assertTrue(create.contains("maybeStartFirstRunDeviceCheckProbes()"))
        assertTrue(restoreDeviceCheck.contains("state = result.state"))
        assertTrue(restoreDeviceCheck.contains("postLoginDeviceCheckSnapshot = restoredSnapshot"))
        assertFalse(restoreDeviceCheck.contains("ensureWalkSessionResourceMonitoring()"))
        assertTrue(firstRunProbes.contains("ensureWalkSessionResourceMonitoring()"))
        assertTrue(ensureResourceMonitoring.contains("walkSessionResourceProbeStarted) return"))
        assertTrue(ensureResourceMonitoring.contains("walkSessionResourceProbe.start"))
        assertTrue(ensureResourceMonitoring.contains("observeWalkRuntimeResourceSafety()"))
        assertTrue(ensureResourceMonitoring.contains("refreshStartupCapabilityUi()"))
        assertTrue(ensureResourceMonitoring.contains(".getOrDefault(false)"))
        assertTrue(
            ensureResourceMonitoring.indexOf("walkSessionResourceProbeStarted = true") <
                ensureResourceMonitoring.indexOf("walkSessionResourceProbe.start"),
        )
        assertTrue(activate.contains("ensureWalkSessionResourceMonitoring()"))
        assertTrue(
            activate.indexOf("ensureWalkSessionResourceMonitoring()") <
                activate.indexOf("observeWalkRuntimeResourceSafety(runtimeEpoch)"),
        )
        assertTrue(
            activate.indexOf("observeWalkRuntimeResourceSafety(runtimeEpoch)") <
                activate.indexOf("activateOfficialEnvironmentRuntime()"),
        )
        assertTrue(initialActivation.contains("activateWalkSessionRuntime()"))
        assertTrue(spokenResume.contains("activateWalkSessionRuntime()"))
        assertTrue(buttonResume.contains("activateWalkSessionRuntime()"))
        assertTrue(resetResourceProbe.contains("walkSessionResourceProbe.close()"))
        assertTrue(resetResourceProbe.contains("AndroidWalkSessionResourceProbe(this)"))
        assertTrue(resetResourceProbe.contains("walkSessionResourceProbeStarted = false"))
        assertTrue(readiness.contains("WalkSessionEvent.RuntimeReadinessChanged(readiness)"))
        assertTrue(readiness.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(readiness.contains("cancelWalkSessionOutputs(\"runtime_readiness_changed\")"))
        assertTrue(readiness.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(readiness.contains("stopDepthSession(closeSession = true)"))
        assertTrue(destroy.contains("walkSessionResourceProbe.close()"))
    }

    @Test
    fun remoteNavigationAndReportActionsRevalidateExactSessionLeases() {
        val destination = functionBlock("private fun performDestinationSearch(")
        val route = functionBlock("private fun requestRoute(")
        val prepare = functionBlock("private fun prepareReportCandidate(")
        val report = functionBlock("private fun processReportCandidate(")
        val capture = functionBlock("private fun buildReportQueueDrainTrigger(")
        val drainContext = functionBlock("private fun reportQueueDrainContext(")

        assertTrue(destination.contains("isDestinationSearchLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(destination.contains("gatewaySessionClient.revalidate("))
        assertTrue(route.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(route.contains("gatewaySessionClient.revalidate("))
        assertTrue(report.contains("walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)"))
        assertTrue(prepare.contains("walkSessionResourceProbe.snapshot()"))
        assertTrue(report.contains("reportQueueStore.enqueue("))
        assertFalse(report.contains("gatewaySessionClient.revalidate("))
        assertTrue(capture.contains("val gatewaySnapshot = GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(capture.contains("gatewaySession.isUsableFor(reporter)"))
        assertTrue(capture.contains("gatewaySessionGeneration = gatewaySnapshot.generation"))
        assertTrue(drainContext.contains("currentGateway.generation == trigger.gatewaySessionGeneration"))
        assertTrue(drainContext.contains("currentGateway.session === trigger.gatewaySession"))
        assertTrue(drainContext.contains("trigger.gatewaySession.isUsableFor(reporter)"))
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing source marker: $marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("missing function body: $marker", bodyStart >= 0)
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
        throw AssertionError("unterminated function body: $marker")
    }
}
