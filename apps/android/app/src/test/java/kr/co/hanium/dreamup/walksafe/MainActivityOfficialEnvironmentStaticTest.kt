package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOfficialEnvironmentStaticTest {
    private val presenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText()

    private val nativePresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()

    @Test
    fun firstRunEnvironmentVisibilityKeepsGateEvaluationInMainAndViewWritesInPresenter() {
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        val presentation = ReportStaticSourceInspector.functionBlock(presenterSource, "fun renderPreparation")
        assertTrue(update.contains("val showWalkPreparation = snapshot.isComplete"))
        assertTrue(update.contains("showWalkPreparation = showWalkPreparation"))
        assertTrue(update.contains("developmentGuidanceStartBypassEnabled = facts.guidanceBypassEnabled"))
        assertTrue(update.contains("environmentStatusReady = facts.published(NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_STATUS_TEXT)"))
        assertTrue(update.contains("effects.renderPreparation {"))
        assertTrue(presentation.contains("if (facts.environmentStatusReady)"))
        assertTrue(presentation.contains("officialEnvironmentStatusText.visibility ="))
        assertTrue(presentation.contains("officialEnvironmentConfirmButton.visibility ="))
        assertTrue(presentation.contains("facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled"))
        assertFalse(presenterSource.contains("currentOfficialEnvironmentAssessment("))
        assertFalse(presenterSource.contains("officialEnvironmentUserConfirmation ="))
    }

    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val panelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val destinationSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()

    @Test
    fun educationAcknowledgmentNeverCreatesCurrentEnvironmentPassEvidence() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val assessment = functionBlock("private fun currentOfficialEnvironmentAssessment(")
        val notice = delegatedOwner("officialEnvironmentStatusMessage", "officialEnvironmentPresentation", "startup/capability/OfficialEnvironmentPresentation.kt", "officialEnvironmentStatusMessage", "= officialEnvironmentPresentation.officialEnvironmentStatusMessage(assessment)")

        assertTrue(confirmation.contains("requireFirstRunOnboardingComplete("))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentController").contains("priorityUserOnboardingActorId == reporterUserId"))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentController").contains("priorityUserOnboardingPolicy.snapshot().nativeEducationComplete"))
        assertFalse(confirmation.contains("OfficialEnvironmentUserConfirmation("))
        assertFalse(confirmation.contains("EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("preflight.startCamera(lifecycle.epoch)"))
        assertTrue(confirmation.contains("requestGpsForCheck(lifecycle.epoch)"))
        val gpsSelection = functionBlock("internal fun requestOfficialEnvironmentGpsForCheck(")
        assertTrue(gpsSelection.contains("requestGeneral: (WalkRuntimeEpoch) -> Unit = effects::requestContinuousGpsForNativeCheck"))
        assertTrue(gpsSelection.contains("requestSeparate: (WalkRuntimeEpoch, Boolean) -> Unit = preflight::requestGps"))
        assertTrue(gpsSelection.contains("if (facts.nativeGpsCheckCurrent(epoch)) requestGeneral(epoch)"))
        assertTrue(gpsSelection.contains("requestSeparate(epoch, clearExistingEvidence)"))
        val generalCheck = functionBlock("private fun requestContinuousGpsForNativeCheck(")
        assertTrue(generalCheck.contains("startLocationUpdatesIfAllowed()"))
        assertFalse(generalCheck.contains("requestLocationUpdates("))
        assertTrue(assessment.contains("OfficialEnvironmentPolicy.assess(epoch, nowMs,"))
        assertTrue(assessment.contains("gpsEvidence, cameraEvidence, userConfirmation"))
        assertTrue(assessment.contains("cameraEvidence, userConfirmation"))
        assertTrue(assessment.contains("userConfirmation,"))
        assertTrue(MainActivityRefactorSource.property(source, "officialEnvironmentAssessmentFacts").contains("priorityUserOnboardingActorId == reporterUserId"))
        assertTrue(MainActivityRefactorSource.property(source, "officialEnvironmentAssessmentFacts").contains("priorityUserOnboardingPolicy.snapshot().nativeEducationComplete"))
        assertTrue(notice.contains("assessment.conditionallyAllowed"))
        assertTrue(notice.contains("현재 조건은 미확인"))
        assertFalse(confirmation.contains("stepLengthPrefs"))
        assertFalse(source.contains("PREF_OFFICIAL_ENVIRONMENT"))
    }

    @Test
    fun gpsPreflightContinuouslyRefreshesAndIsGenerationEpochBound() {
        val request = functionBlock("private fun requestOfficialEnvironmentGpsPreflight(")
        val lease = functionBlock("private fun isOfficialEnvironmentGpsPreflightCurrent(")
        val observe = functionBlock("private fun observeOfficialEnvironmentGpsLocation(")
        val stop = functionBlock("private fun stopOfficialEnvironmentGpsPreflight(")
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        val cameraStop = delegatedOwner("stopOfficialEnvironmentCameraPreflight", "officialEnvironmentPreflightController", "device/environment/OfficialEnvironmentPreflightController.kt", "stopCamera", "{ officialEnvironmentPreflightController.stopCamera(releaseMountingSensor, onReleased) }")
        val sdk = nativeMember("createRegistration")
        val sourceRequest = nativeMember("createRequest")
        assertTrue(sourceRequest.contains("maximumAgeMs = minOf(DeviceCheckLocationFixPolicy.MAX_AGE_MS, profile?.maximumMeasuredEvidenceAgeMs ?: 0L)"))
        assertTrue(MainActivityRefactorSource.property(source, "officialEnvironmentGpsSource").contains("OFFICIAL_ENVIRONMENT_GPS_REQUEST_MAX_DURATION_MS"))
        assertTrue(owner("device/environment/OfficialEnvironmentPreflightController.kt", "equals").contains("generation == other.generation && sessionIdentity === other.sessionIdentity"))
        val diagnostic = MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController")

        assertTrue(sdk.contains("object : LocationCallback()"))
        assertTrue(sdk.contains("fused.requestLocationUpdates("))
        assertTrue(sdk.contains("callback,"))
        assertTrue(sdk.contains("Looper.getMainLooper()"))
        assertTrue(sdk.contains("Priority.PRIORITY_HIGH_ACCURACY"))
        assertTrue(sdk.contains("LocationRequest.Builder("))
        assertTrue(sdk.contains("setMaxUpdateAgeMillis(options.maximumAgeMs)"))
        assertTrue(request.contains("facts.profile() == requestedProfile"))
        assertTrue(request.contains("gateway == requestedOwner && gateway.usable"))
        assertTrue(sdk.contains("setWaitForAccurateLocation(true)"))
        assertTrue(
            sdk.contains(
                "setDurationMillis(options.maximumDurationMs)",
            ),
        )
        assertFalse(sdk.contains("getCurrentLocation("))
        val mockCompatibility = functionBlock("private fun isMockLocationCompat(")
        assertTrue(mockCompatibility.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(mockCompatibility.contains("location.isMock"))
        assertTrue(mockCompatibility.contains("location.isFromMockProvider"))
        assertTrue(request.contains("isGpsCurrent("))
        assertTrue(lease.contains("gpsRequest === request"))
        assertTrue(lease.contains("generation == gpsGeneration"))
        assertTrue(lease.contains("cameraActive"))
        assertTrue(lease.contains("lifecycle.epoch == epoch"))
        assertTrue(observe.contains("assessGpsPreflightSample("))
        val sample = functionBlock("internal fun assessOfficialEnvironmentGpsPreflightSample(")
        assertTrue(sample.contains("nowMs - observedAtMs > gpsPreflightMaximumAgeMs(profile)"))
        assertTrue(sample.contains("OfficialEnvironmentGpsPreflightPolicy.assess(epoch, nowMs, sample, profile)"))
        assertTrue(nativeSources().contains("OfficialEnvironmentGpsSample(observedAtElapsedRealtimeNs / 1_000_000L,"))
        assertTrue(owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "acceptGpsPreflightSample").contains("OfficialEnvironmentGpsPreflightPolicy.selectEvidence("))
        assertTrue(diagnostic.contains("maximumGpsHorizontalAccuracyMeters"))
        assertTrue(diagnostic.contains("GPS_PREFLIGHT_LOG_TAG"))
        assertFalse(diagnostic.contains("\"latitude="))
        assertFalse(diagnostic.contains("\"longitude="))
        assertTrue(sdk.contains("removeLocationUpdates(callback)"))
        assertTrue(invalidate.contains("preflight.invalidateGeneration()"))
        assertTrue(invalidate.contains("preflight.stopCamera()"))
        assertTrue(cameraStop.contains("stopGps()"))
        assertFalse(request.contains("activateWalkSessionRuntime()"))
        assertFalse(request.contains("confirmedStartupCapabilityDecision ="))
    }

    @Test
    fun officialEnvironmentMapsIntoWalkReadinessFailClosed() {
        val readiness = functionBlock("private fun officialEnvironmentReadiness(")
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")

        assertTrue(readiness.contains("OfficialEnvironmentSupport.SUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.READY"))
        assertTrue(
            owner("device/environment/OfficialEnvironmentRuntimeController.kt", "runtimeReadiness").contains(
                "OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY",
            ),
        )
        assertTrue(readiness.contains("OfficialEnvironmentSupport.LIMITED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.PENDING"))
        assertTrue(readiness.contains("OfficialEnvironmentSupport.UNSUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.UNAVAILABLE"))
        assertTrue(
            capture.contains(
                "producers.evaluate(requirement, effectiveDecision, epoch, officialAssessment, mountingAssessment,\n                deviceResources, allowModelPreparationPending)",
            ),
        )
        assertTrue(capture.contains("official.assessment(epoch)"))
        assertTrue(capture.contains(".filterKeys { it in assessment.enabledMeasuredFactors }"))
        assertTrue(capture.contains("observedAtElapsedRealtimeMs = observedAt"))
        assertTrue(capture.contains("supportingEvidence = supportingEvidence"))
        assertTrue(capture.contains("capability.reusableOrRefresh(decision, captureStartedAt)"))
        assertTrue(blockReason.contains("sources.officialEnvironmentBlockReason()?.let { return it }"))
        assertTrue(
            blockReason.indexOf("officialEnvironmentBlockReason()") <
                blockReason.indexOf("sources.missingRequiredPermissions(action)"),
        )
    }

    @Test
    fun sessionIdentityBoundariesInvalidateEnvironmentEvidence() {
        val manualReporterInput = functionBlock("private fun persistReporterUserFromInput()")
        val verifiedActorBinding =
            functionBlock("private fun bindFirstRunVerifiedActorForTraining()")

        assertTrue(
            manualReporterInput.contains(
                "updateNavigationStatus(\"login=blocked manual_reporter_id_disallowed\")",
            ),
        )
        assertFalse(manualReporterInput.contains("reporterUserId ="))
        assertTrue(
            functionBlock("internal fun pauseWalkSafeRuntime()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_paused\")"),
        )
        assertTrue(
            functionBlock("override fun onDestroy()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_destroyed\")"),
        )
        assertTrue(
            verifiedActorBinding
                .contains("effects.invalidateEnvironment(\"first_run_actor_bound\")"),
        )
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains(
                    "runtime.invalidateOfficialEnvironment(\"priority_user_account_logged_out\")",
                ),
        )
        assertTrue(
            functionBlock("private fun resetPriorityUserTraining()")
                .contains("effects.invalidateEnvironment(\"priority_user_training_reset\")"),
        )
        assertTrue(
            delegatedOwner("startFreshWalk", "walkSessionCleanupController", "session/runtime/WalkSessionCleanupController.kt", "startFreshWalk", "= this@MainActivity.walkSessionCleanupController.startFreshWalk(reason)")
                .contains("admission.invalidateOfficialEnvironment(\"new_walk:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun performWalkSessionSafetyStopCleanup(")
                .contains("admission.invalidateOfficialEnvironment(\"safety_stop:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun applyRuntimeReadinessIfActive(")
                .contains("runtime.invalidateEnvironment(\"runtime_readiness_changed\")"),
        )
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        assertTrue(invalidate.contains("evidence.invalidate()"))
        assertTrue(owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "invalidate").contains("userConfirmation = null"))
        assertTrue(owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "clearGpsEvidence").contains("gpsEvidence = null"))
        assertTrue(owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "clearCameraEvidence").contains("cameraEvidence = null"))
        assertTrue(invalidate.contains("runtime.clearGuard()"))
    }

    @Test
    fun runtimeRetrySuppressesOutputsWithoutStoppingMeasurement() {
        val decision = functionBlock("private fun applyOfficialEnvironmentRuntimeDecision(")
        val measuredFeatures = decision
            .substringAfter("OfficialEnvironmentRuntimeAction.CONTINUE ->")
            .substringBefore("OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY ->")
        val retry = decision
            .substringAfter("OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY ->")
            .substringBefore("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")
        val terminal = decision.substringAfter("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")
        val runtimeBinding = MainActivityRefactorSource.composition(source, "officialEnvironmentRuntimeController")
        val gpsRestriction = ReportStaticSourceInspector.functionBlock(runtimeBinding, "override fun restrictGpsNavigationAndCancelFeedback")
        val outputCancellation = ReportStaticSourceInspector.functionBlock(runtimeBinding, "override fun cancelVoiceAndFeedbackActuator")
        assertTrue(ReportStaticSourceInspector.functionBlock(runtimeBinding, "override fun invalidateReportOutput").contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(ReportStaticSourceInspector.functionBlock(runtimeBinding, "override fun resumeGpsNavigation").contains("startNavigationServicesIfNeeded()"))
        assertTrue(outputCancellation.contains("cancelVoiceCommandRecognition()"))

        assertTrue(measuredFeatures.contains("outputsAllowed = true"))
        assertTrue(measuredFeatures.contains("decision.unavailableFactors"))
        assertTrue(measuredFeatures.contains("effects.invalidateReportOutput("))
        assertTrue(measuredFeatures.contains("OfficialEnvironmentFactor.GPS_QUALITY in newlyUnavailable"))
        assertTrue(measuredFeatures.contains("decision.navigationOutputsAllowed"))
        assertTrue(measuredFeatures.contains("effects.resumeGpsNavigation()"))
        assertTrue(measuredFeatures.contains("scheduleWatchdog(decision.epoch)"))
        assertFalse(measuredFeatures.contains("outputsAllowed = false"))
        assertFalse(measuredFeatures.contains("effects.enterSafetyStop("))
        assertFalse(gpsRestriction.contains("cancelActiveRouteRequest()"))
        assertFalse(gpsRestriction.contains("cancelDestinationSearch()"))
        assertFalse(gpsRestriction.contains("stopLocationUpdates("))
        assertFalse(gpsRestriction.contains("stopCameraFallbackSession("))
        assertFalse(gpsRestriction.contains("stopDepthSession("))
        assertTrue(retry.contains("outputsAllowed = false"))
        assertTrue(retry.contains("effects.invalidateReportOutput("))
        assertTrue(retry.contains("scheduleWatchdog"))
        assertInOrder(
            retry,
            "outputsAllowed = false",
            "effects.cancelVoiceAndFeedbackActuator()",
            "effects.speakRecheckExplanation(",
        )
        assertTrue(retry.contains("effects.speakRecheckExplanation(message, stillSuppressed,"))
        assertTrue(retry.contains("if (stillSuppressed())"))
        val runtimeWiring = MainActivityRefactorSource.property(source, "officialEnvironmentRuntimeController")
        assertTrue(runtimeWiring.contains("routeGuidanceController.offerEnvironmentRecheckNotice(message, current, delivered)"))
        assertTrue(runtimeWiring.contains("additionalCurrent = current, onCompleted = delivered"))
        assertFalse(retry.contains("outputsAllowed = true"))
        assertFalse(retry.contains("speakInteraction("))
        assertFalse(outputCancellation.contains("cancelWalkSessionOutputs("))
        assertFalse(outputCancellation.contains("stopLocationUpdates("))
        assertFalse(outputCancellation.contains("stopCameraFallbackSession("))
        assertFalse(outputCancellation.contains("stopDepthSession("))
        assertTrue(
            terminal.contains(
                "effects.enterSafetyStop(",
            ),
        )
        assertInOrder(
            terminal,
            "outputsAllowed = false",
            "effects.enterSafetyStop(",
            "speakStatusExplanation(",
        )
        // Expression-bodied wrappers end before the next declaration, not its closing brace.
        val explanation = MainActivityRefactorSource.function(source, "private fun speakStatusExplanation(")
            .substringBefore("\n    private fun ")
        val ordinaryInteraction = MainActivityRefactorSource.function(source, "private fun speakInteraction(")
            .substringBefore("\n    private fun ")
        val response = MainActivityRefactorSource.function(source, "private fun speakCommandResponse(")
        assertTrue(explanation.contains("speakCommandResponse(message, statusExplanation = true)"))
        assertFalse(explanation.contains("OutputsAllowed = true"))
        assertFalse(ordinaryInteraction.contains("statusExplanation = true"))
        assertTrue(response.contains("statusExplanation: Boolean = false"))
        assertTrue(response.contains("dispatchCommandSpeechResponse(actuator, nativeParts[index], homeResponse || statusExplanation"))
        val dispatch = MainActivityRefactorSource.function(source, "internal fun dispatchCommandSpeechResponse(")
        assertTrue(dispatch.contains("actuator.speakGuardedHomeCommandInteraction(message, isCurrent"))
        val dispatchCode = ReportStaticSourceInspector.maskNonCode(dispatch).filterNot(Char::isWhitespace)
        val ordinaryDispatch = ReportStaticSourceInspector.blockAfter(
            dispatchCode.substringAfter("}elseif(homeOrStatusResponse){"), "}else",
        )
        assertEquals(
            "{actuator.speakGuardedInteraction(message,isCurrent," +
                "onCompleted=completed,onFailed=failed,onCancelled=cancelled,deferToCurrentSpeech=deferToCurrentSpeech)}",
            ordinaryDispatch,
        )
        assertFalse(functionBlock("private fun speakNextNavigationInstruction(").contains("speakStatusExplanation("))
        assertFalse(functionBlock("private fun emitFeedbackAction(").contains("speakStatusExplanation("))
        val advisorySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val stillDeliverable = MainActivityRefactorSource.member(advisorySource, "stillDeliverable")
        assertTrue(stillDeliverable.contains("nowMs <= action.validUntilMs"))
        assertTrue(stillDeliverable.contains("authority.newOutputAllowed(nowMs)"))
        assertTrue(stillDeliverable.contains("authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)"))
        val advisoryWire = MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery")
        assertTrue(advisoryWire.contains("CameraNonMetricAdvisoryDeliveryController("))
        assertTrue(advisoryWire.contains("isCameraFallbackAdvisoryOwnerCurrent("))
        assertTrue(advisoryWire.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(advisoryWire.contains("currentRawCameraFrameAllowsNewOutput(nowMs)"))
        assertTrue(
            functionBlock("private fun isCameraFallbackAdvisoryOwnerCurrent(").contains("cameraEnvironmentOutputsAllowed()"),
        )
        assertTrue(
            functionBlock("private fun processReportCandidate(")
                .contains(
                    "if (!authority.walkSafetyOutputsAllowed())",
                ),
        )
        val preflightFrame = functionBlock("private fun handleRuntimeMetricPreflightFrame(")
        assertFalse(preflightFrame.contains("effects.cameraFrameUnavailable("))
        val frameFailure = functionBlock("private fun handleRuntimeMetricFrameFailure(")
        assertTrue(frameFailure.contains("native.generation != expectedArSessionGeneration"))
        assertTrue(frameFailure.contains("scope.lifecycle.snapshot().epoch != expectedWalkEpoch"))
        val watchdog =
            functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")
        assertTrue(watchdog.contains("it.observedAtElapsedRealtimeMs"))
        assertTrue(watchdog.contains("it.maximumEvidenceAgeMs"))
        assertTrue(
            watchdog.contains(
                "minOf(profile.maximumMeasuredEvidenceAgeMs, checkNotNull(it.maximumEvidenceAgeMs))",
            ),
        )
    }

    @Test
    fun runtimeCameraHandoffRevokesCameraEvidenceWithoutStoppingNavigation() {
        val handoff = functionBlock("private fun beginRuntimeCameraHandoff(")

        assertTrue(handoff.contains("synchronized(observationLock)"))
        assertTrue(handoff.contains("evidence.clearCameraEvidence()"))
        assertTrue(handoff.contains("latestPhoneMountingCameraAssessment = null"))
        assertTrue(handoff.contains("resetRuntimeRawCameraAssessment()"))
        assertTrue(functionBlock("internal fun resetRuntimeRawCameraAssessment()").contains("cameraQualityGate.reset()"))
        assertTrue(handoff.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(handoff.contains("effects.reassessOfficialEnvironment()"))
        assertTrue(handoff.contains("generation != runtimeCameraHandoffGeneration"))
        assertTrue(handoff.contains("!lifecycle().isRuntimeEpochCurrent(epoch)"))
        assertInOrder(
            handoff.substringAfter("if (!timedOut) return@postStatus"),
            "effects.observeUnavailableCamera(epoch, clock())",
            "effects.cameraHandoffTimedOut()",
        )
        assertInOrder(MainActivityRefactorSource.composition(source, "phoneMountingSession"),
            "override fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long)",
            "observeOfficialEnvironmentCameraFrame(epoch = epoch, observedAtElapsedRealtimeMs = observedAtMs, frameAvailable = false)",
        )
        assertFalse(handoff.contains("officialEnvironmentOutputsAllowed = false"))
        assertFalse(handoff.contains("evidence.clearGpsEvidence"))
        assertFalse(handoff.contains("cancelActiveRouteRequest()"))
        assertFalse(handoff.contains("cancelDestinationSearch()"))
        assertFalse(handoff.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
    }

    @Test
    fun centralSafetyStopClosesBothCameraPipelines() {
        val delegate = functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
        assertTrue(delegate.contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"))
        val safetyStop = functionBlock("private fun performWalkSessionSafetyStopCleanup(")

        assertInOrder(
            safetyStop,
            "admission.invalidateOfficialEnvironment(\"safety_stop:\$reason\")",
            "cancelWalkSessionOutputs(reason)",
            "native.stopCameraFallbackSession(updateUi = false)",
            "native.stopDepthSession(closeSession = true)",
        )
    }

    @Test
    fun environmentStatusExposesCauseAndNextActionAccessibly() {
        val statusView = blockStartingAt(panelSource, "officialEnvironmentStatusText = TextView(context).apply")
        val overlay = source.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")
        val readiness = blockStartingAt(panelSource, "walkReadinessControls = LinearLayout(context).apply")
        val content = functionBlock("private fun buildContentView()")
        val update = functionBlock("private fun updateOfficialEnvironmentUi()")
        val message = delegatedOwner("officialEnvironmentStatusMessage", "officialEnvironmentPresentation", "startup/capability/OfficialEnvironmentPresentation.kt", "officialEnvironmentStatusMessage", "= officialEnvironmentPresentation.officialEnvironmentStatusMessage(assessment)")
        val detail = functionBlock("private fun officialEnvironmentMeasurementDetail(")
        val guidance = functionBlock("private fun updatePrewalkGuidance(")

        assertTrue(statusView.contains("text = \"공식 사용환경을 확인하는 중입니다.\""))
        assertTrue(statusView.contains("contentDescription = text"))
        assertTrue(statusView.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(statusView.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(panelSource.contains("ViewCompat.setAccessibilityHeading(officialEnvironmentStatusText, true)"))
        assertTrue(panelSource.contains("label = props.officialEnvironmentConfirmLabel"))
        assertTrue(panelSource.contains("onClick = actions.readiness.onEnvironmentConfirm"))
        assertTrue(content.contains("officialEnvironmentConfirmLabel = OFFICIAL_ENVIRONMENT_CONFIRM_ACTION_KO"))
        assertTrue(content.contains("onEnvironmentConfirm = ::confirmOfficialEnvironmentConditions"))
        assertTrue(content.contains("onboardingPanel = OnboardingReadinessPanel("))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentPresentation").contains("firstRunOnboardingPresenter.readinessBinding().officialEnvironmentConfirmButton"))
        assertTrue(source.contains("addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)"))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentPresentation").contains("contentDescription = message"))
        assertTrue(update.contains("view.currentMessage() != message"))
        assertTrue(update.contains("!reader.preflightActive"))
        assertTrue(update.contains("위치·카메라 자동 점검 중"))
        assertTrue(update.contains("reader.profile != null"))
        assertTrue(update.contains("승인된 환경 프로필 없음"))
        assertTrue(message.contains("원인:"))
        assertTrue(message.contains("다음 행동:"))
        assertTrue(detail.contains("GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"))
        assertTrue(detail.contains("현재 정확도"))
        assertTrue(detail.contains("CAMERA_STABILIZING"))
        assertTrue(MainActivityRefactorSource.member(source, "prewalkGuidanceTarget").contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertFalse(guidance.contains("requestFocus()"))
        assertFalse(guidance.contains("announceForAccessibility("))
        assertTrue(guidance.contains("환경과 장착 점검 통과. 보행 시작 확인"))
        assertInOrder(
            readiness,
            "addView(priorityUserOnboardingControls)",
            "addView(startupCapabilityText)",
            "addView(nativeDeviceCheckPanel)",
            "addView(postLoginDeviceCheckLiveStatusText)",
            "addView(startupMetricPreflightButton)",
            "addView(startupCapabilityConfirmButton)",
        )
        assertTrue(readiness.contains("addView(officialEnvironmentStatusText)"))
        assertTrue(readiness.contains("addView(officialEnvironmentConfirmButton)"))
        val presentation = functionBlock("private fun applyNativePreviewPresentation(")
        assertTrue(presentation.contains("NativePreviewPresentationReader.read("))
        val previewPolicy = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePreviewPresentationPolicy.kt").readText()
        assertTrue(previewPolicy.contains("val guidanceVisible = input.homeAvailable && input.page == NativeUiPage.GUIDANCE"))
        assertTrue(previewPolicy.contains("!guidanceVisible && input.homeAvailable && preparing"))
        assertTrue(previewPolicy.contains("guidanceVisible = guidanceVisible"))
        assertTrue(previewPolicy.contains("showPhysicalPreparation = physicalPreparation(preparing, deviceScreen)"))
        assertTrue(presentation.contains("nativeScreenPresenter.renderPreviewAccountAndPreparation(preview,"))
        val preparation = ReportStaticSourceInspector.functionBlock(
            nativePresenterSource, "fun renderPreviewAccountAndPreparation",
        )
        assertTrue(preparation.contains("show(walkReadinessControls, !facts.guidanceVisible"))
        assertTrue(preparation.contains("show(officialEnvironmentStatusText, facts.showPhysicalPreparation)"))
        assertTrue(preparation.contains("show(officialEnvironmentConfirmButton, facts.showPhysicalPreparation)"))
        val guidanceMessage = functionBlock("private fun nativeGuidanceStatusMessage(")
        assertTrue(owner("ui/NativeGuidanceTextPresentation.kt", "guidanceStatus").contains("presentation.routeMessageKo"))
        assertTrue(owner("ui/NativeGuidanceTextPresentation.kt", "guidanceStatus").contains("presentation.cameraMessageKo"))
        assertTrue(guidanceMessage.contains("destinationPreparationMessage = ::nativeDestinationPreparationMessage"))
        assertTrue(guidanceMessage.contains("officialEnvironmentFailureSpeech(currentOfficialEnvironmentAssessment(),"))
        assertTrue(guidanceMessage.contains("setOf(OfficialEnvironmentFactor.GPS_QUALITY)"))
        val guidanceAssessment = functionBlock("private fun currentNativeGuidancePresentation()")
        assertTrue(guidanceAssessment.contains("currentOfficialEnvironmentAssessment()"))
        assertFalse(guidanceAssessment.contains("officialEnvironmentOutputsAllowed = true"))
        val guidanceStatusMarker = "nativeGuidanceStatusText = TextView(context).apply {"
        assertTrue(destinationSource.contains(guidanceStatusMarker))
        val guidanceStatusView = blockStartingAt(destinationSource, guidanceStatusMarker)
        assertTrue(source.contains("nativeScreenPresenter.guidanceBinding().nativeGuidanceStatusText"))
        assertTrue(guidanceStatusView.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(guidanceStatusView.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(overlay.contains("addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)"))
    }

    @Test
    fun prewalkCameraAndMountingSensorsProduceAllRequiredMeasurements() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val start = functionBlock("private fun startOfficialEnvironmentCameraPreflight(")
        val current = functionBlock("private fun isOfficialEnvironmentCameraPreflightCurrent(")
        val measurement = nativeMember("build")
        val cameraSdk = nativeMember("requestBind")
        val cameraSource = MainActivityRefactorSource.member(nativeSources().substringAfter("internal class AndroidOfficialEnvironmentCameraSource("), "start")
        val cameraBinding = MainActivityRefactorSource.property(source, "officialEnvironmentCameraSource")
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        val activation = functionBlock("private fun activateWalkSessionRuntime()")
        val startAfterRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")

        assertTrue(confirmation.contains("preflight.startCamera(lifecycle.epoch)"))
        assertTrue(cameraBinding.contains("phoneMountingSensorProbe.start()"))
        assertTrue(cameraSdk.contains("ProcessCameraProvider.getInstance(context())"))
        assertTrue(cameraSdk.contains("ImageAnalysis.Builder()"))
        assertTrue(cameraSdk.contains("slot.bindPreflightCamera(provider, analysis)"))
        val prepared = MainActivityRefactorSource.member(MainActivityRefactorSource.camera, "bindPreflight")
        assertInOrder(prepared, "val resources = CameraXFallbackResources(provider, null, analysis, null, awaitClosed)",
            "onPrepared(resources)", "provider.bindToLifecycle(")
        assertTrue(nativeSources().contains("val plane = image.planes.firstOrNull()"))
        assertTrue(cameraSource.contains("OfficialEnvironmentCameraObservationFactory.build(epoch, now,"))
        assertTrue(cameraSource.contains("observe(observation, generation)"))
        assertTrue(cameraSource.contains("val now = nowMs()"))
        assertFalse(cameraSource.contains("imageInfo.timestamp"))
        assertTrue(current.contains("generation != cameraGeneration"))
        assertTrue(current.contains("lifecycle.epoch == epoch"))
        assertTrue(measurement.contains("CameraLumaMeasurementPolicy.measure("))
        assertTrue(
            cameraBinding.contains(
                "phoneMountingSensorProbe.latestNow()",
            ),
        )
        assertTrue(measurement.contains("normalizedBrightness = luma?.normalizedBrightness"))
        assertTrue(measurement.contains("occludedFraction = null, nearBlackFraction = luma?.nearBlackFraction"))
        assertTrue(
            measurement.contains(
                "angularShakeDegreesPerSecond = mounting?.angularShakeDegreesPerSecond",
            ),
        )
        assertTrue(
            measurement.contains(
                "mountPitchDegrees = mounting?.cameraPitchFromHorizontalDegrees",
            ),
        )
        assertTrue(invalidate.contains("preflight.stopCamera()"))
        assertTrue(invalidate.contains("effects.stopMountingSensorIfInitialized()"))
        assertTrue(
            activation.indexOf(
                "camera.stopEnvironmentPreflight(",
            ) <
                activation.indexOf("camera.startRuntimeAfterRelease(runtimeEpoch)"),
        )
        assertTrue(startAfterRelease.contains("ensurePermissionsThenStart()"))
    }

    @Test
    fun debugPrewalkCameraLogContainsRawMeasurementsThresholdsAndDecision() {
        val dispatch = owner("device/environment/OfficialEnvironmentCameraObserver.kt", "observe")
        assertTrue(dispatch.contains("if (expectedCameraPreflightGeneration != null) effects.preflightDiagnostic("))
        val observation = ReportStaticSourceInspector.functionBlock(MainActivityRefactorSource.composition(source, "officialEnvironmentCameraObserver"), "override fun preflightDiagnostic")

        assertTrue(
            observation.contains("if (BuildConfig.DEBUG)"),
        )
        assertTrue(observation.contains("CAMERA_PREFLIGHT_LOG_TAG"))
        assertTrue(observation.contains("brightness=${'$'}{observation.normalizedBrightness}"))
        assertTrue(observation.contains("occludedFraction=${'$'}{observation.occludedFraction}"))
        assertTrue(
            observation.contains(
                "shakeDegreesPerSecond=${'$'}{observation.angularShakeDegreesPerSecond}",
            ),
        )
        assertTrue(observation.contains("mountPitchDegrees=${'$'}{observation.mountPitchDegrees}"))
        assertTrue(observation.contains("status=${'$'}{assessment.status}"))
        assertTrue(observation.contains("reason=${'$'}{assessment.reason}"))
    }

    @Test
    fun prewalkRetryFencesOldFramesAndReleasesOnlyOwnedCameraResources() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val mounting = functionBlock("private fun confirmPhoneMounting(")
        val observer = owner("device/environment/OfficialEnvironmentCameraObserver.kt", "observe")
        assertTrue(MainActivityRefactorSource.member(source, "observeOfficialEnvironmentCameraFrame").contains("officialEnvironmentCameraObserver.observe("))
        val stop = delegatedOwner("stopOfficialEnvironmentCameraPreflight", "officialEnvironmentPreflightController", "device/environment/OfficialEnvironmentPreflightController.kt", "stopCamera", "{ officialEnvironmentPreflightController.stopCamera(releaseMountingSensor, onReleased) }")

        assertTrue(confirmation.contains("preflight.cameraCleanupPending"))
        assertTrue(confirmation.contains("onReleased = ::confirmConditions"))
        assertTrue(observer.contains("expectedCameraPreflightGeneration"))
        assertTrue(observer.contains("preflight.isCameraCurrent("))
        assertTrue(observer.contains("previousAt"))
        assertTrue(observer.contains("val commit = synchronized(evidence.observationLock)"))
        assertTrue(observer.contains("candidateAt < previousAt"))
        assertTrue(observer.contains("preflight.observeCameraStability(assessment)"))
        val stability = functionBlock("internal fun observePreflightCameraStability(")
        assertTrue(stability.contains("stability.observe(assessment.status,"))
        assertTrue(stability.contains("assessment.reason.isTransientPreflightQualityFailure()"))
        assertTrue(stability.contains("decision == CameraPreflightStabilityDecision.ACCEPT && assessment.status == EnvironmentEvidenceStatus.PASS"))
        assertTrue(stability.contains("cameraHasPassed = true"))
        assertTrue(observer.contains("CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS"))
        assertTrue(observer.contains("CAMERA_STABILIZING"))
        assertTrue(observer.contains("evidence.commitCameraEvidence(admitted)"))
        assertTrue(
            observer.indexOf("val commit = synchronized(evidence.observationLock)") <
                observer.indexOf("evidence.commitCameraEvidence(admitted)"),
        )
        assertTrue(stop.contains("synchronized(evidence.observationLock)"))
        assertTrue(stop.contains("cameraActive = false"))
        assertTrue(stop.contains("cameraGeneration += 1L"))
        assertTrue(stop.contains("resources?.unbindOwned()"))
        assertTrue(nativeMember("awaitAnalyzerBarrier").contains("detectorExecutor.execute"))
        assertTrue(stop.contains("afterCameraRelease"))
        assertFalse(stop.contains("unbindAll()"))
        assertTrue(MainActivityRefactorSource.camera.contains("provider.unbind(analysis)"))
        assertFalse(MainActivityRefactorSource.camera.contains("unbindAll()"))
        assertTrue(stop.contains("effects.stopMountingSensorIfInitialized()"))
        assertTrue(mounting.contains("onReleased = { confirmPhoneMounting(method) }"))
        assertInOrder(
            mounting.substringAfter("onReleased = { confirmPhoneMounting(method) }"),
            "return",
            "resetPrewalkCameraEvidenceForNewAttempt()",
            "val (confirmedAtMs, mountObservation) = session.confirmExplicit(",
        )
    }

    @Test
    fun activeLocationPermissionRevocationStopsOnlyLocationFeatures() {
        val permissionChange =
            functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(permissionChange.contains("runtime.stopLocation()"))
        val locationRevocation = permissionChange.substringAfter(
            "if (!platform.preciseLocationGranted() || !readiness.locationServiceEnabled())",
        ).substringBefore("if (!platform.notificationGranted())")
        assertFalse(locationRevocation.contains("runtime.stopSteps()"))
        assertTrue(permissionChange.contains("runtime.cancelRouteRequest()"))
        assertTrue(permissionChange.contains("runtime.resetRoute()"))
        assertTrue(permissionChange.contains("해당 기능만 중지하고 나머지 기능은 계속 사용합니다."))
        assertFalse(locationRevocation.contains("runtime.safetyStop("))
        // The shared camera + location dependency barrier is separate from location-only loss.
        val sharedBarrier = permissionChange.substringAfter("PermissionDependencyPolicy.evaluate(")
            .substringBefore("// S03-6:")
        assertTrue(sharedBarrier.contains(").requiresWholeWalkSafetyStop"))
        assertTrue(sharedBarrier.contains("runtime.safetyStop(\"core_guidance_permissions_unavailable\")"))
        assertTrue(sharedBarrier.contains("enterPermissionRecoveryBarrier("))
        assertFalse(permissionChange.contains("WalkSessionEvent.Resume"))
    }

    private fun functionBlock(signature: String): String {
        val preflightNames = mapOf("isOfficialEnvironmentGpsPreflightCurrent" to "isGpsCurrent",
            "isOfficialEnvironmentCameraPreflightCurrent" to "isCameraCurrent", "observeOfficialEnvironmentGpsLocation" to "observeGpsSample")
        val name = Regex("fun\\s+(\\w+)").find(signature)?.groupValues?.get(1)
        preflightNames[name]?.let { target ->
            MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController")
            return owner("device/environment/OfficialEnvironmentPreflightController.kt", target)
        }
        if (signature.contains("currentOfficialEnvironmentAssessment(")) {
            assertTrue(MainActivityRefactorSource.member(source, "currentOfficialEnvironmentAssessment").contains("officialEnvironmentEvidenceStore.currentAssessment(epoch, nowElapsedRealtimeMs, officialEnvironmentAssessmentFacts"))
            MainActivityRefactorSource.composition(source, "officialEnvironmentEvidenceStore")
            return owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "currentAssessment")
        }
        if (signature.contains("captureWalkSessionReadiness(")) {
            assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator").contains("WalkReadinessCoordinator("))
            assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness").contains("walkReadinessCoordinator.capture("))
            return owner("session/readiness/WalkReadinessCoordinator.kt", "capture")
        }
        return MainActivityRefactorSource.function(source, signature)
    }
    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )
    private fun nativeSources() = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/AndroidOfficialEnvironmentPreflightSources.kt").readText()
    private fun nativeMember(name: String) = ReportStaticSourceInspector.functionBlock(nativeSources(), if (name == "build") "fun $name" else "override fun $name")

    private fun blockStartingAt(text: String, marker: String): String {
        val start = text.indexOf(marker)
        check(start >= 0) { "missing block: $marker" }
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> if (sawOpeningBrace) {
                    braceDepth -= 1
                    if (braceDepth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("missing or out of order: $fragment", index > previous)
            previous = index
        }
    }

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
