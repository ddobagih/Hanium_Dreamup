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
        val frameInvalidation = pause.indexOf("runtimeMetricInvalidation.invalidate(\"app_paused\")")

        assertTrue(transition >= 0)
        assertTrue(frameInvalidation > transition)
        assertTrue(MainActivityRefactorSource.property(source, "runtimeMetricInvalidation").contains("RuntimeMetricInvalidationController("))
        assertTrue(pause.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(pause.contains("persistWalkSessionInterruptionMarker()"))
        assertTrue(pause.contains("cancelVoiceCommandRecognition()"))
        assertTrue(pause.contains("stopLocationUpdates()"))
        assertTrue(pause.contains("stopStepTracking()"))
    }

    @Test
    fun everyRuntimeEntryPointRequiresAnActiveWalkSession() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        assertTrue(
            MainActivityRefactorSource.function(source, "private fun currentStepTrackingCollectionAllowsWork()")
                .contains("isWalkSessionRuntimeActive()"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun currentNavigationCollectionAllowsWork()")
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun startDepthSession()")
                .contains("if (!admission.runtimeActive()) return"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun startCameraFallbackSession(")
                .contains("if (!admission.runtimeActive()) return"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun bindCameraFallbackSession()")
                .contains("currentRuntimeEpochOrNull() ?: return"),
        )
        assertTrue(
            MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
                .contains("isCameraFallbackLeaseCurrent("),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun currentRuntimeMetricOutputAllowsWork(")
                .contains("scope.runtimeActive"),
        )
        assertTrue(
            locationObservationPolicy(source)
                .contains("!scope.navigationAllowed"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun prepareReportCandidate(")
                .contains("currentRuntimeEpochOrNull()"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun requestRoute(")
                .contains("!facts.navigationAllowed"),
        )
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun currentNavigationCollectionAllowsWork()")
                .contains("postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"),
        )
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle").contains("override fun runtimeActive() = isWalkSessionRuntimeActive()"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("override val runtimeActive get() = isWalkSessionRuntimeActive()"))
        assertTrue(navigationInput("positions = PositioningAssemblyBindings(", "gpsBindings = GpsAssemblyBindings(").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        val positioningAssembly = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioningAssembly.contains("val scope = positions.scope()"))
        assertTrue(positioningAssembly.contains("PositioningSessionController(run, scope, sources"))
        val acquisitionAssembly = MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        assertTrue(acquisitionAssembly.contains("override val navigationAllowed: Boolean get() = activityFacts.navigationAllowed"))
        assertTrue(navigationInput("routes = AcquisitionAssemblyBindings(", "resources = NavigationAssemblyResources(").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
    }

    @Test
    fun recheckUsesTheApprovedPromptAndOnlyExactStartCanResume() {
        val prompt = MainActivityRefactorSource.function(source, "private fun requestWalkSessionResumeConfirmation()")
        val response = MainActivityRefactorSource.function(source, "private fun handleWalkSessionResumeRecognition(")

        assertTrue(
            prompt.contains("재점검 뒤 진행하던 보행을 이어갈까요? 시작 또는 취소라고 말해 주세요"),
        )
        assertTrue(prompt.contains("effects.startResumeRecognition()"))
        assertFalse(prompt.contains("postDelayed("))
        assertTrue(response.contains("WalkSessionResumeConfirmation.fromRecognizedText("))
        assertTrue(response.contains("WalkSessionEvent.ResumeConfirmationReceived("))
        assertTrue(response.contains(
            "if (response == WalkSessionResumeConfirmation.START && transition.changed && environment.runtimeActive())",
        ))
        val responseCode = ReportStaticSourceInspector.maskNonCode(response).filterNot(Char::isWhitespace)
        assertTrue(responseCode.contains(
            "valcurrentReadiness=if(response==WalkSessionResumeConfirmation.START){" +
                "recheckWalkSessionResumeBeforeConfirmation(confirmationToken)?:return}elseconfirmationToken.readiness",
        ))
        assertTrue(responseCode.contains(
            "walk.transition(WalkSessionEvent.ResumeConfirmationReceived(" +
                "confirmation=response,token=confirmationToken,currentReadiness=currentReadiness,",
        ))
        assertTrue(response.contains("if (!effects.activateRuntime())"))
        assertFalse(response.contains("speakInteraction(PriorityUserPractice.RESUME.instructionKo)"))
        assertTrue(response.contains("walkSessionResumeRetryRequiresUserAction"))
        val binding = MainActivityRefactorSource.composition(source, "walkSessionResumeController")
        assertTrue(binding.contains("override fun startResumeRecognition() = startVoiceCommandRecognition(VoiceRecognitionPurpose.WALK_SESSION_RESUME)"))
        assertTrue(binding.contains("override fun activateRuntime() = activateWalkSessionRuntime()"))
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
                .contains("resume.retryRequiresUserAction"),
        )
    }

    @Test
    fun failedAutomaticNoticeAndResumeResponseRequireAnExplicitRetryAction() {
        val failure = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText(), "failStartupCapabilityConfirmation")
        val advance = MainActivityRefactorSource.function(source, "private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
        val buttonAction =
            MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText(), "handleStartupCapabilityConfirmAction")

        val interactionFactoryStart = source.indexOf("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")
        val interactionFactoryEnd = source.indexOf("\nprivate fun handleStartupCapabilityConfirmAction()", interactionFactoryStart)
        assertTrue(Regex(Regex.escape("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")).findAll(source).count() == 1)
        assertTrue(Regex(Regex.escape("\nprivate fun handleStartupCapabilityConfirmAction()")).findAll(source).count() == 1)
        assertTrue(interactionFactoryStart >= 0 && interactionFactoryEnd > interactionFactoryStart)
        val interactionBinding = MainActivityRefactorSource.composition(source, "startupConfirmationInteraction")
        assertTrue(interactionBinding.contains("StartupConfirmationInteractionController("))
        assertTrue(interactionBinding.contains("lifecycle = { walkSessionLifecycle }"))
        assertTrue(interactionBinding.contains("override fun resumePermissionRecovery() = resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(interactionBinding.contains("override fun startFreshWalk(reason: String) = this@MainActivity.walkSessionCleanupController.startFreshWalk(reason)"))
        assertTrue(MainActivityRefactorSource.member(source, "handleStartupCapabilityConfirmAction").contains("startupConfirmationInteraction.handleStartupCapabilityConfirmAction()"))
        assertTrue(failure.contains("retryRequiresUserAction = true"))
        assertTrue(advance.contains("state.startupRetryRequiresUserAction()"))
        assertTrue(advance.contains("resume.retryRequiresUserAction"))
        assertTrue(buttonAction.contains("retryRequiresUserAction = false"))
        assertTrue(buttonAction.contains("effects.prepareResumeRetry()"))
        assertTrue(
            buttonAction.indexOf("effects.prepareResumeRetry()") <
                buttonAction.indexOf("effects.resumePermissionRecovery()"),
        )
        assertTrue(
            buttonAction.contains(
                "session.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED",
            ),
        )
        assertTrue(buttonAction.contains("effects.refresh()"))
        val presenter = ReportStaticSourceInspector.functionBlock(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(), "fun startupPresentation")
        assertTrue(presenter.contains("!confirmation.capabilityConfirmationPending && !confirmation.resumePromptPending"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer").contains("override fun capabilityConfirmationPending() = startupCapabilityConfirmationPending"))
        assertTrue(MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionResumeController.kt").readText(), "prepareConfirmationRetry").contains("walkSessionResumeRetryRequiresUserAction = false"))
        assertTrue(
            presenter.contains("mayConfirmSession"),
        )
    }

    @Test
    fun terminalWalkOffersFreshPreparationBeforeRecoveryAndReadinessGates() {
        val refresh = ReportStaticSourceInspector.functionBlock(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(), "fun startupPresentation")
        assertTrue(MainActivityRefactorSource.function(source, "private fun refreshStartupCapabilityUi()").contains("ui.render("))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer").contains("deviceCheckPresenter.renderStartupCapability(facts)"))
        val action = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText(), "handleStartupCapabilityConfirmAction")
        val freshLabel = refresh.indexOf("preparesNewWalk -> \"새 보행 준비\"")
        val environmentLabel = refresh.indexOf("!capability.officialEnvironmentReady")
        val freshAction = action.indexOf("if (preparesNewWalk)")
        val permissionRecovery =
            action.indexOf("effects.resumePermissionRecovery()")

        assertTrue(refresh.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(refresh.contains("WalkSessionState.ENDED"))
        assertTrue(refresh.contains("preparesNewWalk ||"))
        assertTrue(refresh.contains("confirmation.awaitingExplicitResume && !preparesNewWalk"))
        assertTrue(freshLabel >= 0)
        assertTrue(environmentLabel > freshLabel)
        val interactionFactoryStart = source.indexOf("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")
        val interactionFactoryEnd = source.indexOf("\nprivate fun handleStartupCapabilityConfirmAction()", interactionFactoryStart)
        assertTrue(Regex(Regex.escape("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")).findAll(source).count() == 1)
        assertTrue(Regex(Regex.escape("\nprivate fun handleStartupCapabilityConfirmAction()")).findAll(source).count() == 1)
        assertTrue(interactionFactoryStart >= 0 && interactionFactoryEnd > interactionFactoryStart)
        val interactionBinding = MainActivityRefactorSource.composition(source, "startupConfirmationInteraction")
        assertTrue(interactionBinding.contains("StartupConfirmationInteractionController("))
        assertTrue(interactionBinding.contains("lifecycle = { walkSessionLifecycle }"))
        assertTrue(interactionBinding.contains("override fun resumePermissionRecovery() = resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(interactionBinding.contains("override fun startFreshWalk(reason: String) = this@MainActivity.walkSessionCleanupController.startFreshWalk(reason)"))
        assertTrue(MainActivityRefactorSource.member(source, "handleStartupCapabilityConfirmAction").contains("startupConfirmationInteraction.handleStartupCapabilityConfirmAction()"))
        assertTrue(action.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(action.contains("WalkSessionState.ENDED"))
        assertTrue(freshAction >= 0)
        assertTrue(permissionRecovery > freshAction)
        assertTrue(action.substring(freshAction, permissionRecovery).contains("effects.startFreshWalk("))
        assertTrue(action.substring(freshAction, permissionRecovery).contains("return"))
    }

    @Test
    fun processRestartKeepsAFreshLifecycleWithoutAPreviousWalkPopup() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val persistence = functionBlock("private fun persistWalkSessionInterruptionMarker()")

        assertTrue(create.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(create.contains("previousProcessHadInterruptedWalk"))
        assertFalse(
            Regex(
                """putBoolean\(PREF_WALK_SESSION_INTERRUPTED, false\)\s*\.commit\(\)""",
            ).containsMatchIn(create),
        )
        assertFalse(source.contains("PreviousWalkInterruptionMarker"))
        assertFalse(source.contains("showPreviousWalkInterruptionNoticeIfNeeded"))
        assertFalse(source.contains("pendingFreshWalkActivationToken"))
        assertTrue(
            create.contains(
                "walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)",
            ),
        )
        assertTrue(create.contains("previous_walk_interrupted_fresh_walk_created"))
        assertFalse(create.contains("interruptedSessionRequiresConfirmation"))
        assertTrue(source.contains("const val PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(persistence.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(persistence.contains("WalkSessionState.ACTIVE"))
        assertTrue(persistence.contains("WalkSessionState.PAUSED"))
        assertTrue(persistence.contains("WalkSessionState.SAFE_STOP"))
        assertFalse(persistence.contains("previousWalkInterruptionMarker"))
        assertTrue(persistence.contains(".commit()"))
    }

    @Test
    fun runtimeFailuresEnterSafetyStopAndCancelAsynchronousOutputs() {
        val metricInvalidation = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricInvalidationController.kt").readText(), "invalidate")
        val speechFailure = MainActivityRefactorSource.function(source, "private fun handleRuntimeSpeechCapabilityFailure(")
        val safetyStop = MainActivityRefactorSource.function(source, "private fun enterWalkSessionSafetyStopAndCancelOutputs(")
        val foregroundRecheck = MainActivityRefactorSource.function(source, "private fun enterWalkSessionForegroundRecheckAndCancelOutputs(")
        val outputCancellationEntry = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputs(")
        assertTrue(outputCancellationEntry.contains("cancelWalkSessionOutputsInternal(reason, null)"))
        val outputCancellation = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputsInternal(")
        val safetyOutputCancellation = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputsForSafetyStop(")
        assertTrue(safetyOutputCancellation.contains("onLocationResult: ((Boolean) -> Unit)? = null"))
        assertTrue(safetyOutputCancellation.contains("): Boolean = cancelWalkSessionOutputsInternal(reason, checkpoints, onLocationResult)"))
        assertTrue(outputCancellation.contains("runLocationStopCleanupCheckpoint("))
        assertTrue(outputCancellation.contains("checkpoints, onLocationResult ?: {},"))
        assertTrue(outputCancellation.contains("navigation.locationCleanupSucceeded()"))
        assertTrue(outputCancellation.contains("if (!tailComplete) complete = false"))
        assertTrue(outputCancellation.contains("return complete"))
        val preflight = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText(), "canBeginRuntimeMetricPreflight")
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricSession").contains("RuntimeMetricSessionController("))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeMetricInvalidation").contains("RuntimeMetricInvalidationController("))

        assertTrue(metricInvalidation.contains("navigation.safetyStopAndPersist()"))
        val invalidationBinding = MainActivityRefactorSource.property(source, "runtimeMetricInvalidation")
        assertTrue(Regex("""RuntimeMetricInvalidationController\(\s*runtimeMetricStateLock\s*,\s*\{\s*runtimeMetricSession\s*}\s*,\s*\{\s*arSessionOwner\s*}\s*,""").containsMatchIn(invalidationBinding))
        assertTrue(invalidationBinding.contains("override fun safetyStopAndPersist()"))
        assertTrue(invalidationBinding.contains("transitionWalkSession(WalkSessionEvent.SafetyStopRequested)"))
        assertTrue(invalidationBinding.contains("persistWalkSessionInterruptionMarker()"))
        assertTrue(metricInvalidation.contains("reason.startsWith(\"runtime_\")"))
        assertTrue(metricInvalidation.contains("lifecycleOrNull()?.snapshot()?.state == WalkSessionState.ACTIVE"))
        assertTrue(metricInvalidation.contains("if (safetyStop) navigation.safetyStopAndPersist()"))
        assertTrue(speechFailure.contains("보행 시작/재개 불가"))
        assertTrue(speechFailure.contains("기기 점검을 다시 실행"))
        assertFalse(speechFailure.contains("다른 기능은 계속"))
        assertTrue(speechFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(safetyStop.contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"))
        val cleanup = MainActivityRefactorSource.function(source, "private fun performWalkSessionSafetyStopCleanup(")
        assertTrue(cleanup.contains("WalkSessionEvent.SafetyStopRequested"))
        assertTrue(cleanup.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(cleanup.contains("if (!cancelWalkSessionOutputsForSafetyStop(reason, checkpoints, onLocationResult)) complete = false"))
        assertTrue(foregroundRecheck.contains("WalkSessionEvent.RecheckRequested"))
        assertTrue(foregroundRecheck.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(foregroundRecheck.contains("stopDepthSession(closeSession = true)"))
        assertTrue(outputCancellation.contains("native.invalidateArRecheck()"))
        assertTrue(outputCancellation.contains("reports.cancelPrivacyUploads()"))
        assertTrue(outputCancellation.contains("navigation.cancelRequestsForPause()"))
        assertTrue(outputCancellation.contains("feedback.cancelTalkBack()"))
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "cancelPendingTalkBackInteraction").contains("pendingTalkBackInteraction = null"))
        assertTrue(outputCancellation.contains("feedback.closeActuator()"))
        assertTrue(outputCancellation.contains("admission.clearStartupState()"))
        assertTrue(preflight.contains("WalkSessionState.READY"))
        assertTrue(preflight.contains("WalkSessionState.PAUSED"))
        assertFalse(preflight.contains("WalkSessionState.SAFE_STOP"))
        val cleanupBinding = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
        assertTrue(cleanupBinding.contains("lastLocationCleanupFailure == null"))
        assertTrue(cleanupBinding.contains("invalidateArCoreAvailabilityRecheck()"))
        assertTrue(cleanupBinding.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(cleanupBinding.contains("cancelNavigationRequestsForPause()"))
        assertTrue(cleanupBinding.contains("speechOutputController.cancelPendingTalkBackInteraction()"))
        assertTrue(cleanupBinding.contains("speechOutputController.closeActuator()"))
        assertTrue(cleanupBinding.contains("confirmedStartupCapabilityDecision = null"))
        val fallbackRequest = MainActivityRefactorSource.function(source, "private fun requestCameraFallbackStart(")
        assertTrue(fallbackRequest.contains("automaticallyContinueMetricOnlyDowngrade"))
        assertTrue(fallbackRequest.contains("CameraFallbackStartReason.RUNTIME_METRIC_LOST"))
        assertTrue(fallbackRequest.contains("readiness.enterForegroundRecheckAndCancelOutputs("))
    }

    @Test
    fun rejectedResumeRecognizerAndStaleDetectorWorkFailClosed() {
        val resumePrompt = MainActivityRefactorSource.function(source, "private fun requestWalkSessionResumeConfirmation()")
        val voiceStart = MainActivityRefactorSource.function(source, "private fun startVoiceCommandRecognition(")
        val detectorFailure = MainActivityRefactorSource.function(source, "private fun handleDetectorRuntimeFailure(")
        val detectorScheduling = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/AndroidPrimaryArSubmissionBridge.kt").readText(), "schedule")
        assertTrue(MainActivityRefactorSource.member(source, "scheduleDetectionIfDue").contains("primaryArSubmissionBridge.schedule("))
        assertTrue(MainActivityRefactorSource.property(source, "primaryArSubmissionBridge").contains("AndroidPrimaryArSubmissionBridge("))
        val fullStart = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText(), "start")
        assertTrue(MainActivityRefactorSource.function(source, "private fun continueDepthSessionStart(").contains("transactions.start("))

        assertTrue(resumePrompt.contains("handleWalkSessionResumeRecognizerNotStarted()"))
        assertTrue(voiceStart.contains("): Boolean"))
        assertTrue(voiceStart.contains("return false"))
        assertTrue(detectorFailure.contains("generation: Int"))
        assertTrue(detectorFailure.contains("epoch: WalkRuntimeEpoch"))
        assertTrue(
            detectorFailure.contains(
                "currentness.frameIsCurrent(generation, epoch)",
            ) || detectorFailure.contains(
                "expectedGeneration,\n                                expectedEpoch",
            ),
        )
        assertTrue(
            detectorScheduling.contains(
                "authority.ownsFrame(frameGeneration, expectedWalkEpoch)",
            ),
        )
        assertTrue(MainActivityRefactorSource.property(source, "primaryArSubmissionBridge").contains("override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = isCurrentFrameGeneration(generation, epoch)"))
        assertTrue(fullStart.contains("if (!admission.detectorAvailable())"))
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
        // S02-14: FP-014-DETAIL-01 makes the microphone a walk-start permission.
        assertTrue(requiredPermissions.contains("Manifest.permission.RECORD_AUDIO"))
        assertFalse(requiredPermissions.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(effectiveMode.contains("toWalkSessionMode()"))
        assertFalse(effectiveMode.contains("!hasLocationPermission()"))
    }

    @Test
    fun activeResourceFailureAnnouncesTheSafetyStopReason() {
        val readiness = MainActivityRefactorSource.function(source, "private fun applyRuntimeReadinessIfActive(")

        assertTrue(readiness.contains("runtime.cancelOutputs(\"runtime_readiness_changed\")"))
        assertTrue(readiness.contains("runtime.status(\"필수 기능 변경 · 안전 중지\", reason)"))
        assertTrue(readiness.contains("runtime.speak("))
        assertTrue(readiness.contains("보행 기능을 안전 중지했습니다."))
        val binding = MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
        assertTrue(binding.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(binding.contains("updateStatus(title, detail)"))
        assertTrue(binding.contains("speakInteraction(message)"))
    }

    @Test
    fun permissionResultsAreOneShotAndPurposeGenerationBound() {
        val callback = permissionResultOwner()
        val request = MainActivityRefactorSource.function(source, "private fun requestPermissionsWithLease(")
        val current = MainActivityRefactorSource.function(source, "private fun isPermissionRequestLeaseCurrent(")
        val walkResult = MainActivityRefactorSource.function(source, "private fun handleWalkSessionPermissionResult()")

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
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowContracts.kt").readText().contains("val generation: Long"))
        assertTrue(walkResult.contains("readiness.missingRequired(action)"))
        assertTrue(walkResult.contains("runtime.refreshStartup()"))
        assertTrue(walkResult.contains("일부 기능 권한 없음"))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("missingRequiredWalkSessionPermissions(action)"))
        assertTrue(binding.contains("override fun refreshStartup() { refreshStartupCapabilityUi() }"))
        assertTrue(MainActivityRefactorSource.member(source, "onRequestPermissionsResult").contains("permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)"))
    }

    @Test
    fun voiceTerminalCallbacksConsumeTheirLeaseBeforeDispatch() {
        val voice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        val listener = MainActivityRefactorSource.member(voice, "buildVoiceCommandRecognitionListener")
        assertTrue(MainActivityRefactorSource.composition(source, "voiceInputController").contains("VoiceInputSessionController("))
        assertTrue(listener.contains("override fun onError(error: Int)"))
        assertTrue(listener.contains("override fun onResults(results: Bundle?)"))
        val listenerCode = ReportStaticSourceInspector.maskNonCode(listener).filterNot(Char::isWhitespace)
        assertTrue(listenerCode.contains(
            "valsource=recognitionSource?.takeIf{it.generation==generation}" +
                "?:RecognitionSource(generation,environment.identity(),environment.feedbackLifetimeGeneration())",
        ))
        assertTrue(listenerCode.contains(
            "funcurrentLease():Boolean=recognitionSourceIsCurrent(source)&&" +
                "isVoiceRecognitionLeaseCurrent(generation,purpose,expectedWalkEpoch," +
                "expectedResumeToken,expectedGatewayWalkOperationId)",
        ))
        val sourceGuard = ReportStaticSourceInspector.maskNonCode(
            MainActivityRefactorSource.member(voice, "recognitionSourceIsCurrent"),
        ).filterNot(Char::isWhitespace)
        assertTrue(sourceGuard.contains(
            "=voiceRecognitionGeneration==source.generation&&" +
                "environment.identity()==source.identity&&" +
                "environment.feedbackLifetimeGeneration()==source.lifecycleGeneration&&" +
                "environment.foreground()&&environment.microphoneGranted()",
        ))
        listOf(
            ReportStaticSourceInspector.functionBlock(listener, "override fun onError"),
            ReportStaticSourceInspector.functionBlock(listener, "override fun onResults"),
        ).forEach { callback ->
            val callbackCode = ReportStaticSourceInspector.maskNonCode(callback).filterNot(Char::isWhitespace)
            assertTrue(callbackCode.startsWith(
                "{if(!currentLease())returnvoiceRecognitionGeneration+=1" +
                    "voiceRecognitionActive=falsevoiceRecognitionPurpose=VoiceRecognitionPurpose.COMMAND",
            ))
            val deactivate = callbackCode.indexOf("voiceRecognitionActive=false")
            val dispatch = callbackCode.indexOf("commands.")
            assertTrue("Consume the current lease before any command dispatch", dispatch > deactivate)
        }
    }

    @Test
    fun systemResourceChangesReevaluateAndStopAnActiveWalk() {
        val create = MainActivityRefactorSource.function(source, "override fun onCreate(savedInstanceState: Bundle?)")
        val restoreDeviceCheck =
            MainActivityRefactorSource.function(source, "private fun bindPostLoginDeviceCheckSession(")
        val firstRunProbes =
            MainActivityRefactorSource.function(source, "private fun maybeStartFirstRunDeviceCheckProbes()")
        val ensureResourceMonitoring =
            MainActivityRefactorSource.function(source, "private fun ensureWalkSessionResourceMonitoring()")
        val resetResourceProbe =
            MainActivityRefactorSource.function(source, "private fun resetWalkSessionResourceProbe()")
        val activate = MainActivityRefactorSource.function(source, "private fun activateWalkSessionRuntime()")
        val initialActivation =
            MainActivityRefactorSource.function(source, "private fun completeGatewayWalkActivation(")
        val spokenResume =
            MainActivityRefactorSource.function(source, "private fun handleWalkSessionResumeRecognition(")
        val buttonResume =
            MainActivityRefactorSource.function(source, "private fun confirmWalkSessionResumeFromButton()")
        val readiness = MainActivityRefactorSource.function(source, "private fun applyRuntimeReadinessIfActive(")
        val destroy = MainActivityRefactorSource.function(source, "override fun onDestroy()")

        assertTrue(create.contains("maybeStartFirstRunDeviceCheckProbes()"))
        assertTrue(restoreDeviceCheck.contains("PostLoginDeviceCheckSnapshot(it.state, actorId, sessionGeneration,"))
        assertTrue(restoreDeviceCheck.contains("publish(restoredSnapshot)"))
        assertFalse(restoreDeviceCheck.contains("ensureWalkSessionResourceMonitoring()"))
        assertTrue(firstRunProbes.contains("ensureWalkSessionResourceMonitoring()"))
        assertTrue(ensureResourceMonitoring.contains("walkSessionResourceProbeStarted) return"))
        assertTrue(ensureResourceMonitoring.contains("walkSessionResourceProbe.start"))
        assertTrue(ensureResourceMonitoring.contains("observeWalkRuntimeResourceSafety(resources = resources)"))
        assertTrue(ensureResourceMonitoring.contains("refreshStartupCapabilityUi()"))
        assertTrue(ensureResourceMonitoring.contains(".getOrDefault(false)"))
        assertTrue(
            ensureResourceMonitoring.indexOf("walkSessionResourceProbeStarted = true") <
                ensureResourceMonitoring.indexOf("walkSessionResourceProbe.start"),
        )
        assertTrue(activate.contains("services.ensureResourceMonitoring()"))
        assertTrue(
            activate.indexOf("services.ensureResourceMonitoring()") <
                activate.indexOf("services.observeResourceSafety(runtimeEpoch)"),
        )
        assertTrue(
            activate.indexOf("services.observeResourceSafety(runtimeEpoch)") <
                activate.indexOf("services.activateOfficialEnvironment()"),
        )
        assertTrue(initialActivation.contains("runtime.activate()"))
        assertTrue(spokenResume.contains("effects.activateRuntime()"))
        assertTrue(buttonResume.contains("effects.activateRuntime()"))
        assertTrue(resetResourceProbe.contains("privacyStartupResources.releaseOnUiThread(walkSessionResourceProbe)"))
        assertTrue(resetResourceProbe.contains("AndroidWalkSessionResourceProbe(this)"))
        assertTrue(resetResourceProbe.contains("walkSessionResourceProbeStarted = false"))
        assertTrue(readiness.contains("WalkSessionEvent.RuntimeReadinessChanged(readiness)"))
        assertTrue(readiness.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(readiness.contains("runtime.cancelOutputs(\"runtime_readiness_changed\")"))
        assertTrue(readiness.contains("runtime.stopCameraFallback()"))
        assertTrue(readiness.contains("runtime.stopDepth()"))
        assertTrue(destroy.contains("privacyStartupResources.releaseOnUiThread(walkSessionResourceProbe)"))
        assertTrue(MainActivityRefactorSource.member(source, "createGatewayWalkSessionController").contains("override fun activate() = activateWalkSessionRuntime()"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionResumeController").contains("override fun activateRuntime() = activateWalkSessionRuntime()"))
        val binding = MainActivityRefactorSource.composition(source, "walkRuntimeActivationController")
        assertTrue(binding.contains("override fun ensureResourceMonitoring() = ensureWalkSessionResourceMonitoring()"))
        assertTrue(binding.contains("override fun observeResourceSafety(epoch: WalkRuntimeEpoch) = observeWalkRuntimeResourceSafety(epoch)"))
        assertTrue(binding.contains("override fun activateOfficialEnvironment() = activateOfficialEnvironmentRuntime()"))
        val readinessBinding = MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
        assertTrue(readinessBinding.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(readinessBinding.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(readinessBinding.contains("stopDepthSession(closeSession = true)"))
    }

    @Test
    fun remoteNavigationAndReportActionsRevalidateExactSessionLeases() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        val destination = MainActivityRefactorSource.member(MainActivityRefactorSource.destination, "search")
        assertTrue(MainActivityRefactorSource.composition(source, "destinationSearchController").contains("DestinationSearchController("))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("destinationSearchController.search(reset = true)"))
        val route = MainActivityRefactorSource.function(source, "private fun requestRoute(")
        val prepare = MainActivityRefactorSource.function(source, "private fun prepareReportCandidate(")
        val report = MainActivityRefactorSource.function(source, "private fun processReportCandidate(")
        val capture = MainActivityRefactorSource.function(source, "private fun buildReportQueueDrainTrigger(")
        val drainContext = MainActivityRefactorSource.function(source, "private fun reportQueueDrainContext(")

        assertTrue(destination.contains("isLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(destination.contains("backend.revalidate("))
        assertTrue(source.contains("gatewaySessionClient.revalidate(session, actorId, capacitySessionGeneration = capacitySessionGeneration)"))
        assertTrue(route.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(route.contains("backend.revalidate("))
        assertTrue(report.contains("authority.isRuntimeEpochCurrent(expectedWalkEpoch)"))
        assertTrue(prepare.contains("resources.current()"))
        assertTrue(report.contains("queue.enqueue("))
        assertFalse(report.contains("gatewaySessionClient.revalidate("))
        assertTrue(capture.contains("val gatewaySnapshot = sources.gatewaySnapshot()"))
        assertTrue(capture.contains("gatewaySession.isUsableFor(reporter)"))
        assertTrue(capture.contains("gatewaySessionGeneration = gatewaySnapshot.generation"))
        assertTrue(drainContext.contains("currentGateway.generation == trigger.gatewaySessionGeneration"))
        assertTrue(drainContext.contains("currentGateway.session === trigger.gatewaySession"))
        assertTrue(drainContext.contains("trigger.gatewaySession.isUsableFor(reporter)"))
        assertTrue(MainActivityRefactorSource.composition(source, "reportCandidateController").contains("resources = ReportCandidateController.Resources { walkSessionResourceProbe.snapshot() }"))
        assertTrue(MainActivityRefactorSource.composition(source, "reportQueueDrainController").contains("gatewaySnapshot = GatewaySessionProcessCoordinator::snapshot"))
        assertTrue(navigationInput("routes = AcquisitionAssemblyBindings(", "resources = NavigationAssemblyResources(").contains("gatewaySessionClient.revalidate(session, actorId, capacitySessionGeneration = capacitySessionGeneration)"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeAcquisitionController").contains("backend = routes.backend()"))
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

    private fun permissionResultOwner(mainSource: String = source): String {
        MainActivityRefactorSource.composition(mainSource, "permissionFlowController")
        val main = MainActivityRefactorSource.factory(mainSource, "onRequestPermissionsResult")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        invalidateRouteSnapshotResponseLease()
        invalidateSpeechOutputStorageBinding()
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main permission callback must retain invalidation, super and owner order", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val tokens = listOf("invalidateRouteSnapshotResponseLease()", "invalidateSpeechOutputStorageBinding()",
            "super.onRequestPermissionsResult(requestCode, permissions, grantResults)",
            "permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)")
        var previous = -1
        tokens.forEach { token ->
            val next = main.indexOf(token, previous + 1)
            assertTrue("Main permission ordering: $token", next > previous)
            previous = next
        }
        return MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt",
        ).readText(), "onRequestPermissionsResult")
    }


    private fun locationObservationPolicy(
        mainSource: String,
        ownerSource: String = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText(),
    ): String {
        MainActivityRefactorSource.composition(mainSource, "positioningSessionController")
        val main = MainActivityRefactorSource.factory(mainSource, "handleLocationUpdate")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun handleLocationUpdate(
        location: Location,
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main location callback must directly invoke its actual owner", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val owner = ownerSource
        val update = MainActivityRefactorSource.factory(owner, "handleLocationUpdate")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedUpdate = """
fun handleLocationUpdate(
        location: Location,
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        // Reject a retired callback before reading any SDK values.
        if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return
        val observation = location.toPositioningObservation(receivedAtElapsedRealtimeMs, sources.mock(location)) { atNanos ->
            runCatching { gnssQualityObserver?.snapshot(atNanos) }.getOrNull()
        }
        handleLocationObservation(
            observation, owner, locationGeneration, binding,
            updateOrientationReference = { updateRouteOrientationReference(location) },
            appendGnssTrace = { snapshot, matchedEvidenceCurrent ->
                effects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)
            },
        )
    }
        """.trimIndent()
        // Keep line boundaries: joining a // comment with the next line can disable its guard.
        org.junit.Assert.assertEquals("SDK callback must reject retired observations before mapping and invoking the observation policy",
            expectedUpdate.lines().map(String::trim), update.lines().map(String::trim))
        assertTrue(update.contains("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"))
        assertTrue(update.indexOf("if (!isLocationObservationCurrent") < update.indexOf("location.toPositioningObservation"))
        assertTrue(update.contains("handleLocationObservation("))
        return MainActivityRefactorSource.member(owner, "handleLocationObservation")
    }


    @Test
    fun permissionResultOwnerRejectsNonExecutingOrReorderedCallers() {
        val mainSource = source
        permissionResultOwner(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onRequestPermissionsResult")
        val call = "permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        variants["reordered effects"] = caller.replace(call, "").replace(
            "invalidateRouteSnapshotResponseLease()", "invalidateRouteSnapshotResponseLease()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { permissionResultOwner(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }


    @Test
    fun locationObservationPolicyRejectsNonExecutingMainCallers() {
        val mainSource = source
        locationObservationPolicy(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "handleLocationUpdate")
        val call = "positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { locationObservationPolicy(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }


    @Test
    fun locationObservationPolicyRejectsNonExecutingSdkObservationCalls() {
        val mainSource = source
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        locationObservationPolicy(mainSource, owner)
        val caller = MainActivityRefactorSource.factory(owner, "handleLocationUpdate")
        val start = caller.indexOf("        handleLocationObservation(")
        org.junit.Assert.assertTrue(start >= 0)
        val call = caller.substring(start, caller.lastIndexOf("\n    }")).trim()
        val commentBoundary = "// Reject a retired callback before reading any SDK values.\n"
        org.junit.Assert.assertTrue(caller.contains(commentBoundary))
        val variants = mapOf(
            "line comment swallows guard" to caller.replace(commentBoundary, commentBoundary.trimEnd() + " "),
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )
        val accepted = variants.filter { (_, changed) ->
            runCatching { locationObservationPolicy(mainSource, owner.replace(caller, changed)) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent SDK callers accepted: $accepted", accepted.isEmpty())
    }

}
