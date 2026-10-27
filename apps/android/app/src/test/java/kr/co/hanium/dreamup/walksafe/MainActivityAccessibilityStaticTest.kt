package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccessibilityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val styleSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/WalkSafeViewStyle.kt").readText()
    private val onboardingSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val destinationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
    private val firstRunPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()
    private val nativePresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt",
    ).readText()
    private val fieldControllerSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/PositionFieldRecorderController.kt",
    ).readText()
    private val runtimeComposerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
    private val contentComposerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeContentViewComposer.kt").readText()
    private val assemblySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeContentAssembly.kt").readText()
    private val pageControllerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
    private val firstRunCoordinatorSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/presentation/FirstRunPresentationCoordinator.kt").readText()
    private val riskDeliverySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/RiskFeedbackDeliveryController.kt").readText()
    private val searchPresenterSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun appOwnedFeedbackUsesTtsCompletionWithScreenReaderEnabledOrDisabled() {
        val detection = functionBlock("private fun isScreenReaderActive()")
        assertTrue(detection.contains("AccessibilityServiceInfo.FEEDBACK_SPOKEN"))
        assertTrue(detection.contains("getEnabledAccessibilityServiceList("))
        assertFalse(detection.contains("isTouchExplorationEnabled"))
        val risk = functionBlock("private fun emitFeedbackAction(")
        assertTrue(risk.contains("val dispatch = actuator.emit("))
        assertTrue(risk.contains("vibrationAccepted = dispatch.vibrationAccepted"))
        assertTrue(risk.contains("onSpeechCompleted = {"))
        assertTrue(risk.contains("onSpeechFailed = {"))
        assertFalse(risk.contains("announceForTalkBack("))
        assertFalse(risk.contains("isScreenReaderActive()"))
        val navigation = functionBlock("private fun dispatchNavigationSpeech(")
        assertTrue(navigation.contains("dispatchCurrentNavigationSpeech("))
        assertTrue(functionBlock("private fun createSpeechOutputController()").contains("isWalkSessionRuntimeActive() && navigationEnvironmentOutputsAllowed()"))
        assertTrue(navigation.contains("isStillValid = ::outputCurrent"))
        assertTrue(
            navigation.contains("isStillValidAtStart = { outputCurrent() && isInstructionStillCurrent() }"),
        )
        val navigationCurrent = navigation.substringAfter("fun outputCurrent(): Boolean =")
            .substringBefore("fun failOnce()")
        assertTrue(navigationCurrent.contains("!cancelled.get() && !terminal.get()"))
        assertTrue(navigationCurrent.contains("isFeedbackLifecycleCurrent(generation)"))
        assertTrue(navigationCurrent.contains("owner.epoch != null"))
        assertTrue(navigationCurrent.contains("reader.navigationOwnerIsCurrent(owner)"))
        assertFalse(navigationCurrent.contains("isInstructionStillCurrent()"))
        val navigationCompletion = navigation.substringAfter("onCompleted = {")
            .substringBefore("isStillValid = ::outputCurrent")
        assertTrue(navigationCompletion.contains("if (outputCurrent() && terminal.compareAndSet(false, true))"))
        assertTrue(navigationCompletion.contains("onCompleted?.invoke()"))
        assertFalse(navigationCompletion.contains("isInstructionStillCurrent()"))
        assertTrue(navigation.contains("ensureFeedbackActuator().speakNavigation("))
        assertTrue(navigation.contains("onCancelled = {"))
        assertTrue(navigation.contains("cancelled.set(true)"))
        assertTrue(navigation.contains("announceForTalkBack("))
        assertTrue(navigation.contains("if (dispatched) failOnce()"))
        assertTrue(navigation.contains("isStillValid = { outputCurrent() && isInstructionStillCurrent() }"))
        assertTrue(navigation.contains("return deliver()"))
        assertFalse(source.contains("private fun dispatchNavigationTalkBackFallback("))
        val interaction = functionBlock("private fun speakInteraction(")
        val commandResponse = functionBlock("private fun speakCommandResponse(")
        assertTrue(interaction.contains("speakCommandResponse(message)"))
        assertFalse(interaction.contains("announceForTalkBack("))
        assertTrue(commandResponse.contains("commandSpeechResponseCallbacks.registerLatest("))
        assertTrue(commandResponse.contains("val completed: () -> Unit = {"))
        assertTrue(commandResponse.contains("val failed: () -> Unit = {"))
        assertTrue(commandResponse.contains("val dispatch = dispatchCommandSpeechResponse("))
        val sharedDispatch = MainActivityRefactorSource.function(source, "internal fun dispatchCommandSpeechResponse(")
        assertTrue(
            sharedDispatch.contains(
                "actuator.speakGuardedHomeCommandInteraction(message, isCurrent,\n            onFailed = failed, onCompleted = completed, onCancelled = cancelled,\n            deferToCurrentSpeech = deferToCurrentSpeech)",
            ),
        )
        val dispatchCaller = code(MainActivityRefactorSource.factory(source, "dispatchCommandSpeechResponse"))
        assertEquals(
            "speechOutputController.dispatchCommandSpeechResponse(" +
                "actuator,message,homeOrStatusResponse,stateSource,isCurrent,completed,failed,cancelled,)",
            dispatchCaller.substringAfter("):NavigationSpeechDispatchResult=", ""),
        )
        val ordinaryDispatch = ReportStaticSourceInspector.blockAfter(
            code(sharedDispatch).substringAfter("}elseif(homeOrStatusResponse){"), "}else",
        )
        assertEquals(
            "{actuator.speakGuardedInteraction(message,isCurrent," +
                "onCompleted=completed,onFailed=failed,onCancelled=cancelled,deferToCurrentSpeech=deferToCurrentSpeech)}",
            ordinaryDispatch,
        )
        val actuatorSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt").readText()
        val guardedInteraction = code(MainActivityRefactorSource.factory(actuatorSource, "speakGuardedInteraction"))
        assertEquals(
            "speak(message=message,priority=SpeechPriority.INTERACTION,onCompleted=onCompleted,onFailed=onFailed," +
                "advisoryStartDeadlineMs=Long.MAX_VALUE,advisoryStartValidator=isStillValidAtStart," +
                "requiresExplicitTerminalCallback=true,protectsFromFollowingSpeech=false," +
                "onCancelled=onCancelled?:onFailed,onCloseCancelled=onCancelled," +
                "messageFamily=SpeechMessageFamily.INTERACTION,deferToCurrentSpeech=deferToCurrentSpeech,)",
            guardedInteraction.substringAfter("):NavigationSpeechDispatchResult=", ""),
        )
        assertTrue(sharedDispatch.contains("actuator.speakGuardedHomeCommandInteraction(message, startCurrent"))
        assertTrue(sharedDispatch.contains("actuator.speakGuardedInteraction(message, startCurrent"))
        // Screen-reader presence alone must not block app TTS. An actually owned fallback
        // output can still hold it through the shared speech-output policy.
        assertFalse(commandResponse.contains("if (isScreenReaderActive()"))
        assertTrue(commandResponse.contains("externalFallbackSpeechOutputActive(scheduler.elapsedRealtimeMs(), reader.screenReaderActive())"))
        assertFalse(commandResponse.contains("announceForTalkBack("))
    }

    @Test
    fun liveRegionAndDebugCaptureAccessibilityContractIsStable() {
        val debugUploadPolicy = source
            .substringAfter("private fun updateDebugUploadButton()")
            .substringBefore("private fun updateFrameCaptureButton()")

        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(functionBlock("fun createRuntimeStatus(", runtimeComposerSource).contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(functionBlock("private fun buildContentView()").contains("statusText = runtimeComposer.createRuntimeStatus()"))
        assertTrue(functionBlock("private fun buildContentView()").contains("NativeRuntimeViewComposer(this, viewStyle)"))
        assertTrue(functionBlock("override fun accessibilityTargetOrNull(", File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText()).contains("target.text = message"))
        assertTrue(MainActivityRefactorSource.speech.contains("riskAnnouncementHoldUntilMs"))
        assertTrue(MainActivityRefactorSource.speech.contains("TALKBACK_INTERACTION_PRIORITY_WINDOW_MS"))
        assertTrue(source.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(source.contains("debugFrameCaptureButton.visibility = View.GONE"))
        assertTrue(debugUploadPolicy.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(debugUploadPolicy.contains("debugUploadButton.visibility = View.GONE"))
        assertTrue(debugUploadPolicy.contains("debugUploadButton.isEnabled = false"))
    }

    @Test
    fun permissionDenialPanelExposesItemsReasonsConfirmAndSettingsInOrder() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val permissionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText()
        val textSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionExplanationText.kt").readText()
        val androidUiSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText()
        val panel = sourceBlock("permissionDenialSummaryText = TextView(context).apply", "walkReadinessSummaryText = TextView(context).apply", onboardingSource)
        val presentation = functionBlock("fun showPermissionDenialPanel(", permissionSource)
        val reasons = functionBlock("fun messageKo(", textSource)
        val render = functionBlock("fun renderPermissionDenial(", firstRunPresenterSource)
        val confirmation = functionBlock("fun onPermissionDenialConfirmed(", permissionSource)
        val blocked = sourceBlock("PermissionRecoveryGateState.BLOCKED -> {", "PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {", confirmation)
        val awaiting = sourceBlock("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {", "PermissionRecoveryGateState.SETTINGS_PENDING,", confirmation)
        val observed = functionBlock("fun applyObservedPermissionStateChange(", permissionSource)

        assertInOrder(panel, "permissionDenialSummaryText = TextView", "permissionDenialConfirmButton = Button", "permissionDenialSettingsButton = Button")
        assertTrue(panel.contains("ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertTrue(panel.contains("accessibilityTraversalAfter = permissionDenialSummaryText.id"))
        assertTrue(panel.contains("accessibilityTraversalAfter = permissionDenialConfirmButton.id"))
        assertFalse(panel.contains("확인하고 앱 끝내기"))
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onPermissionDenialConfirmed() }"))
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onAppSettings() }"))
        val content = functionBlock("private fun buildContentView()")
        assertTrue(content.contains("onPermissionDenialConfirmed = { onPermissionDenialConfirmed() }"))
        assertTrue(content.contains("onAppSettings = { openAppSettings() }"))
        assertTrue(content.contains("nativeRegistrationComposition.publishPermissionTargets()"))
        val permissionPublication = MainActivityRefactorSource.member(registration, "publishPermissionTargets")
        assertTrue(permissionPublication.contains("firstRun.publishPermissionTargets()"))
        assertTrue(permissionPublication.contains("publication.publish(NativeUiPublication.Target.PERMISSION_DENIAL_PANEL)"))
        assertTrue(functionBlock("fun publishPermissionTargets(", firstRunPresenterSource).contains("publishedPermission = onboarding"))
        assertTrue(functionBlock("fun permissionBinding(", firstRunPresenterSource).contains("publishedPermission"))
        assertTrue(MainActivityRefactorSource.member(source, "showPermissionDenialPanel").contains("permissionFlowController.showPermissionDenialPanel(missingPermissions, reason, requestExplanation)"))
        assertTrue(MainActivityRefactorSource.member(source, "onPermissionDenialConfirmed").contains("permissionFlowController.onPermissionDenialConfirmed()"))
        val controller = MainActivityRefactorSource.property(source, "permissionFlowController")
        assertTrue(controller.contains("ui = permissionFlowUi"))
        assertTrue(controller.contains("override fun lifecycleOrNull() = if (::walkSessionLifecycle.isInitialized) walkSessionLifecycle else null"))
        assertTrue(controller.contains("platform = AndroidPermissionPlatform(this,"))
        val ui = MainActivityRefactorSource.property(source, "permissionFlowUi")
        assertTrue(ui.contains("AndroidPermissionUi(this, { firstRunOnboardingPresenter }, { nativeScreenPresenter },"))
        assertTrue(ui.contains("{ nativeUiPublication }, { viewStyle }, ::openAppSettings, ::finishAndRemoveTask"))
        assertTrue(functionBlock("override fun panelReady(", androidUiSource).contains("NativeUiPublication.Target.PERMISSION_DENIAL_PANEL"))
        assertTrue(functionBlock("override fun renderDenial(", androidUiSource).contains("onboarding().renderPermissionDenial(message)"))
        assertInOrder(presentation, "if (!ui.panelReady()) return", "PermissionExplanationPolicy.withoutResult(", "PermissionExplanationText.messageKo(it,", "ui.renderDenial(message)")
        listOf("CAMERA", "PRECISE_LOCATION", "MICROPHONE", "ACTIVITY_RECOGNITION").forEach { permission ->
            assertTrue(Regex("ObservedPermission\\.$permission ->\\s*PermissionExplanationKind\\.$permission").containsMatchIn(presentation))
        }
        assertTrue(presentation.contains("coarseLocationCurrentlyGranted = platform.coarseLocationGranted()"))
        assertTrue(presentation.contains("authority.lifecycleOrNull()!!.snapshot().state"))
        // The original active messageKo path, rather than the uncalled denialReasonKo helper.
        listOf(
            "PermissionExplanationKind.CAMERA -> add(\"카메라 권한이 필요합니다.\")",
            "PermissionExplanationKind.MICROPHONE -> add(\"마이크 권한이 필요합니다.\")",
            "PermissionExplanationKind.ACTIVITY_RECOGNITION -> add(\"보행 센서 권한이 필요합니다.\")",
            "대략적 위치는 허용됐지만 정확한 위치는 허용되지 않아 정확한 위치가 필요한 기능이 제한됩니다.",
            "정확한 위치와 대략적 위치 모두 허용되지 않아 위치 기능이 제한됩니다.",
        ).forEach { cause -> assertTrue(reasons.contains(cause)) }
        listOf("카메라", "정확한 위치", "대략적 위치", "마이크", "보행 센서").forEach { label -> assertTrue(reasons.contains("-> \"$label\"")) }
        assertTrue(reasons.contains("unavailablePermissions.forEach { permission ->"))
        assertTrue(reasons.contains("reRequestabilityByPermission[permission]"))
        assertTrue(reasons.contains("PermissionReRequestability.NOT_EVALUATED, null ->"))
        assertTrue(reasons.contains("권한은 요청 결과가 확인되지 않아 거부 원인이나 재요청 가능 여부를 단정할 수 없습니다. 앱 설정에서 상태를 확인해 주세요."))
        assertTrue(reasons.contains("PermissionReRequestability.RATIONALE_RECOMMENDED ->"))
        assertTrue(reasons.contains("권한은 Android가 앱 내 재요청 전 이유 안내를 권장하는 상태입니다. 앱 설정에서도 허용할 수 있습니다."))
        assertTrue(reasons.contains("PermissionReRequestability.NO_RATIONALE_SIGNAL ->"))
        assertTrue(reasons.contains("권한은 앱 설정에서 허용할 수 있습니다. 앱 내 요청 화면이 다시 표시될지는 Android가 결정합니다."))
        assertTrue(reasons.contains("walkState == WalkSessionState.SAFE_STOP"))
        assertTrue(reasons.contains("보행 안내가 중지되었습니다. 권한과 필수 기능을 다시 점검한 뒤 보행 시작을 직접 확인해 주세요."))
        assertTrue(reasons.contains("해당 기능만 제한되며 나머지 기능은 계속 사용할 수 있습니다."))
        assertFalse(render.contains("원인: \$reason"))
        assertFalse(render.contains("permission_result_walk_session"))
        assertFalse(render.contains("permission_recovery_blocked"))
        assertInOrder(render, "permissionDenialPanel.visibility = View.VISIBLE", "permissionDenialSummaryText.text = message", "permissionDenialSummaryText.contentDescription = message", "permissionDenialConfirmButton.text = \"확인\"", "permissionDenialSummaryText.post {", "permissionDenialSummaryText.requestFocus()", "permissionDenialSummaryText.performAccessibilityAction(", "ACTION_ACCESSIBILITY_FOCUS")
        assertTrue(render.contains("if (permissionDenialPanel.visibility == View.VISIBLE)"))
        assertFalse(render.contains("확인하고 앱 끝내기"))
        assertFalse(render.contains("announceForAccessibility("))
        assertFalse(presentation.contains("speakInteraction("))
        assertFalse(observed.contains("speakInteraction("))
        assertTrue(blocked.contains("permissionRecoveryGate.recheckBinding != null"))
        assertTrue(blocked.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue(blocked.contains("PermissionRecoveryGate()"))
        assertInOrder(blocked, "persistPermissionRecoveryGate()", "ui.hidePanel()", "runtime.refreshStartup()")
        assertTrue(functionBlock("override fun hidePanel(", androidUiSource).contains("onboarding().permissionBinding().permissionDenialPanel.visibility = View.GONE"))
        assertFalse(confirmation.contains("finishAndRemoveTask()"))
        assertFalse(awaiting.contains("finishAndRemoveTask()"))
        assertFalse(awaiting.contains("maybeAdvance"))
        assertInOrder(awaiting, "ui.hidePanel()", "renderAwaitingExplicitResumeControl()", "ui.postResumeFocus {")
        assertTrue(awaiting.contains("permissionRecoveryGate.state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        assertTrue(functionBlock("fun renderAwaitingExplicitResumeControl(", permissionSource).contains("ui.renderResumeControl()"))
        val resumeFocus = functionBlock("override fun postResumeFocus(", androidUiSource)
        assertInOrder(resumeFocus, "startupCapabilityConfirmButton.post {", "if (stillAwaiting()", "startupCapabilityConfirmButton.requestFocus()", "startupCapabilityConfirmButton.performAccessibilityAction(", "ACTION_ACCESSIBILITY_FOCUS")
        assertTrue(resumeFocus.contains("startupCapabilityConfirmButton.visibility == View.VISIBLE"))
        assertTrue(resumeFocus.contains("startupCapabilityConfirmButton.isEnabled"))
        assertFalse(resumeFocus.contains("announceForAccessibility("))
    }

    @Test
    fun permissionDenialPanelRemainsVisibleOutsideHiddenRuntimeControls() {
        assertTrue(functionBlock("private fun buildContentView()").contains("nativeScreenPresenter.publishSearchTargets(destinationPanel)"))
        assertTrue(functionBlock("fun searchBinding(", nativePresenterSource).contains("publishedSearch"))
        val runtimeControls = destinationSource
            .substringAfter("val runtimeControls = LinearLayout(context).apply")
        val overlay = source
            .substringAfter("val overlay = contentComposer.createOverlayContainer(")
            .substringBefore("linkFirstRunAccessibilityTraversal()")
        val panelPosition = overlay.indexOf("addView(firstRunOnboardingPresenter.permissionBinding().permissionDenialPanel)")
        val runtimePosition = overlay.indexOf("addView(nativeScreenPresenter.searchBinding().runtimeControls)")

        assertFalse(runtimeControls.contains("addView(firstRunOnboardingPresenter.permissionBinding().permissionDenialPanel)"))
        assertTrue(panelPosition >= 0)
        assertTrue(runtimePosition > panelPosition)
        assertTrue(functionBlock("fun renderRuntime(", firstRunPresenterSource).contains("runtimeViews.runtimeControls?.visibility = if (facts.runtimeVisible) View.VISIBLE else View.GONE"))
    }

    @Test
    fun feedbackActuatorIsReleasedOnPause() {
        assertTrue(source.contains("override fun onPause()"))
        val pause = source.substringAfter("internal fun pauseWalkSafeRuntime()")
            .substringBefore("private fun invalidateFrameStateForPause()")
        assertTrue(pause.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(pause.contains("surfaceView.onPause()"))
        assertTrue(pause.contains("speechOutputController.closeActuator()"))
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "closeActuator").contains("feedbackActuator = null"))
        assertTrue(
            pause.indexOf("surfaceView.onPause()") <
                pause.indexOf("runtimeMetricInvalidation.invalidate(\"app_paused\")"),
        )
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")
        assertTrue(destroy.contains("cancelPendingFeedbackTerminalResolution()"))
        assertTrue(destroy.contains("riskFeedbackComponents.delivery.resetDeliveryState()"))
        assertTrue(functionBlock("fun resetDeliveryState(", riskDeliverySource).contains("latestFeedbackDeliveryState = FeedbackDeliveryState()"))
        assertTrue(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents").contains("RiskFeedbackComponents("))
    }

    @Test
    fun manifestMakesSpeechServicesVisibleOnAndroid11AndLater() {
        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"))
        assertTrue(manifest.contains("android.speech.RecognitionService"))
    }

    @Test
    fun riskFeedbackKeepsFreshnessTerminalEvidenceAndRecognitionPreemption() {
        val riskWiring = MainActivityRefactorSource.member(source, "createRiskFeedbackComponents")
        assertTrue(riskWiring.contains("override fun lifecycleCurrent(generation: Int) = isFeedbackLifecycleCurrent(generation)"))
        assertTrue(riskWiring.contains("scheduler = voiceCallbacks"))
        val callbacks = MainActivityRefactorSource.property(source, "voiceCallbacks")
        assertTrue(callbacks.contains("AndroidVoiceCallbacks("))
        assertFalse(callbacks.contains("clock ="))
        assertFalse(callbacks.contains("mainThread ="))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt").readText().contains("private val mainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }"))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt").readText().contains("private val clock: () -> Long = SystemClock::elapsedRealtime"))
        val feedback = functionBlock("private fun emitFeedbackAction(")
        assertTrue(feedback.contains("!scheduler.isMainThread()"))
        assertTrue(feedback.contains("if (!admission.lifecycleCurrent(generation)) {"))
        assertTrue(feedback.contains("emitFeedbackAction(action, policyEvaluatedAtMs, startIsCurrent)"))
        assertTrue(feedback.contains("if (isRisk && output.recognitionActive()) output.cancelVoiceCommandRecognition()"))
        assertTrue(feedback.contains("shouldSuppressFeedbackDuringVoiceRecognition(output.guidanceVoiceInputActive(), isRisk)"))
        assertTrue(feedback.contains("isFeedbackActionStillDeliverable(action)"))
        assertTrue(feedback.contains("feedbackPolicy.claimFeedbackDelivery(action, policyEvaluatedAtMs, scheduler.nowMs())"))
        assertFalse(feedback.contains("feedbackPolicy.claimFeedbackDelivery(action.trackId, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)"))
        val riskCompletion = feedback.substringAfter("onSpeechCompleted = {")
            .substringBefore("onSpeechFailed = {")
        assertTrue(riskCompletion.contains("completionCurrent()"))
        assertTrue(
            riskCompletion.contains("confirmFeedbackDelivery(action, policyEvaluatedAtMs, outputCompleted = true)"),
        )
        assertFalse(riskCompletion.contains("outputCurrent()"))
        val riskFailure = feedback.substringAfter("onSpeechFailed = {")
            .substringBefore("onSpeechCancelled = cancelDelivery")
        assertTrue(riskFailure.contains("completionCurrent()"))
        assertFalse(riskFailure.contains("outputCurrent()"))
        assertTrue(feedback.contains("isStillValidAtStart = outputCurrent"))
        assertTrue(feedback.contains("onSpeechCancelled = cancelDelivery"))
        assertTrue(feedback.contains("onSpeechCompleted = {"))
        assertTrue(feedback.contains("onSpeechFailed = {"))
        assertTrue(feedback.contains("scheduleFeedbackTerminalResolution("))
        assertTrue(feedback.contains("dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED"))
        assertFalse(feedback.contains("talkBackAccepted"))
        val current = functionBlock("private fun isFeedbackActionStillDeliverable(")
        assertTrue(current.contains("nowMs <= action.validUntilMs"))
        assertTrue(current.contains("action.deliveryKey in current.activeFeedbackDeliveryKeys"))
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "currentActuatorOrNull").contains("feedbackActuator"))
        assertTrue(MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText(), "prepareForRecognition").contains("speech().currentActuatorOrNull()?.prepareForSpeechRecognition()"))
        assertTrue(MainActivityRefactorSource.member(source, "createVoiceInputController").contains("VoiceInputSpeechPeerAdapter"))
        assertTrue(MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText(), "startVoiceCommandRecognition").contains("output.prepareForRecognition() == false"))
        val progressBeep = functionBlock("private fun maybePlayProgressBeep(")
        assertTrue(progressBeep.contains("if (!isActivityForeground) return"))
        assertTrue(progressBeep.contains("if (isGuidanceVoiceInputActive()) return"))
        assertTrue(MainActivityRefactorSource.member(source, "isGuidanceVoiceInputActive").contains("guidanceVoiceInputActive { voiceInputController }"))
        assertTrue(sourceBlock("internal fun guidanceVoiceInputActive(", "/** Recognition reaches", File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()).contains("input().state.recognitionActive"))
    }

    @Test
    fun queuedFeedbackIsRevalidatedAtTheSingleOutputBoundary() {
        val intake = delegatedOwner("emitTactileFrameFeedback", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "emitTactileFrameFeedback", "= this@MainActivity.riskFeedbackComponents.delivery.emitTactileFrameFeedback(dispatch, producerScope, startIsCurrent, producerIsCurrent)")
        val boundary = functionBlock("private fun emitFeedbackAction(")

        assertTrue(intake.contains("latestFeedbackDeliveryState = FeedbackDeliveryState("))
        assertTrue(intake.contains("activeFeedbackDeliveryKeys = dispatch.activeFeedbackDeliveryKeys"))
        assertTrue(boundary.contains("isFeedbackActionStillDeliverable(action)"))
        assertTrue(functionBlock("private fun isFeedbackActionStillDeliverable(").contains("action.deliveryKey in current.activeFeedbackDeliveryKeys"))
        assertTrue(functionBlock("private fun isFeedbackActionStillDeliverable(").contains("nowMs <= action.validUntilMs"))
        assertTrue(source.contains("currentFeedbackDeviceGateAllowsAlerts()"))
        assertTrue(functionBlock("fun cancelPendingFeedbackDeliveries(", riskDeliverySource).contains("feedbackPolicy.cancelPendingFeedbackDeliveries()"))
        assertTrue(functionBlock("private fun scheduleFeedbackTerminalResolution(").contains("pendingFeedbackTerminalResolution?.runnable !== resolution"))
        assertTrue(source.contains("cancelPendingFeedbackTerminalResolution()"))
    }

    @Test
    fun talkBackRiskHoldSuppressesRepeatsButAllowsSeverityEscalation() {
        val accessibility = MainActivityRefactorSource.function(source, "private fun announceForTalkBack(")

        assertTrue(accessibility.contains("riskRank: Int? = null"))
        assertTrue(accessibility.contains("riskTrackId: String? = null"))
        assertTrue(accessibility.contains("rank > lastRiskAnnouncementRank"))
        assertTrue(accessibility.contains("activeRiskHeld && !preemptsActiveRisk"))
        val accepted = functionBlock("internal fun recordAcceptedTalkBackAnnouncement(")
        assertTrue(accessibility.substringAfter("SpeechAnnouncementPriority.RISK -> {")
            .substringBefore("SpeechAnnouncementPriority.INTERACTION -> {")
            .contains("recordAcceptedTalkBackAnnouncement(message, priority, nowMs, rank, riskTrackId)"))
        assertTrue(accepted.substringAfter("SpeechAnnouncementPriority.RISK -> {")
            .substringBefore("SpeechAnnouncementPriority.INTERACTION -> {")
            .contains("riskAnnouncementHoldUntilMs = nowMs + utteranceTerminalTimeoutMs(message.length, SpeechMessageFamily.RISK)"))
        assertTrue(accessibility.contains("if (nowMs < riskAnnouncementHoldUntilMs)"))
        assertTrue(delegatedOwner("emitTactileFrameFeedback", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "emitTactileFrameFeedback", "= this@MainActivity.riskFeedbackComponents.delivery.emitTactileFrameFeedback(dispatch, producerScope, startIsCurrent, producerIsCurrent)").contains("output.reconcileTalkBackRiskTracks(activeRiskTrackIds)"))
        assertTrue(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents").contains("RiskVoicePeerAdapter({ speechOutputController }, { voiceInputController })"))
        assertTrue(functionBlock("internal fun reconcileTalkBackRiskTracks(").contains("lastRiskAnnouncementTrackId in activeTrackIds"))
        assertTrue(functionBlock("internal fun reconcileTalkBackRiskTracks(").contains("if (activeTrackIds.isNotEmpty()) return"))
        assertTrue(functionBlock("internal fun reconcileTalkBackRiskTracks(").contains("pendingInteraction.run()"))
        assertTrue(delegatedOwner("emitTactileFrameFeedback", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "emitTactileFrameFeedback", "= this@MainActivity.riskFeedbackComponents.delivery.emitTactileFrameFeedback(dispatch, producerScope, startIsCurrent, producerIsCurrent)").contains("if (!admission.lifecycleCurrent(feedbackGeneration)) {"))
    }

    @Test
    fun informationStartExpiryDoesNotRevokeOwnedCompletion() {
        assertTrue(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents").contains("override fun runtimeEpochOrNull() = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents").contains("override fun lifecycleCurrent(generation: Int) = isFeedbackLifecycleCurrent(generation)"))
        val feedback = functionBlock("private fun emitFeedbackAction(")
        assertTrue(feedback.contains("val completionCurrent"))
        val completionFence = feedback.substringAfter("val completionCurrent")
            .substringBefore("val outputCurrent")
        assertTrue(completionFence.contains("!cancelled"))
        assertTrue(completionFence.contains("expectedWalkEpoch != null"))
        assertTrue(completionFence.contains("admission.lifecycleCurrent(generation)"))
        assertTrue(completionFence.contains("admission.runtimeEpochOrNull() == expectedWalkEpoch"))
        assertFalse(completionFence.contains("isFeedbackActionStillDeliverable"))
        assertFalse(completionFence.contains("validUntilMs"))
        val startFence = feedback.substringAfter("val outputCurrent").substringBefore("val cancelDelivery")
        assertTrue(startFence.contains("completionCurrent()"))
        assertTrue(startFence.contains("isFeedbackActionStillDeliverable(action)"))
        val information = feedback.substringAfter("val speech = actuator.speakNavigation(")
        assertTrue(information.contains("isStillValidAtStart = outputCurrent"))
        assertTrue(information.contains("onCancelled = cancelDelivery"))
        val completion = information.substringAfter("onCompleted = {").substringBefore("onFailed =")
        assertTrue(completion.contains("completionCurrent()"))
        assertTrue(completion.contains("outputCompleted = true"))
        assertFalse(completion.contains("outputCurrent()"))
    }

    @Test
    fun asynchronousFeedbackCannotCrossForegroundGeneration() {
        val lifecycle = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onDestroy()")
        val gatewayLogin = functionBlock("private fun onGatewaySessionButtonClicked()")
        val navigation = functionBlock("private fun dispatchNavigationSpeech(")
        val interaction = functionBlock("private fun speakInteraction(")
        val commandResponse = functionBlock("private fun speakCommandResponse(")

        assertTrue(source.contains("private var feedbackLifecycleGeneration = 0"))
        assertTrue(lifecycle.contains("feedbackLifecycleGeneration += 1\n        isActivityForeground = true"))
        assertTrue(lifecycle.contains("isActivityForeground = false\n        feedbackLifecycleGeneration += 1"))
        assertTrue(gatewayLogin.contains("val feedbackGeneration = runtime.feedbackGeneration()"))
        assertTrue(MainActivityRefactorSource.member(source, "createGatewaySessionController").contains("override fun feedbackGeneration() = feedbackLifecycleGeneration"))
        assertTrue(MainActivityRefactorSource.member(source, "createGatewaySessionController").contains("isFeedbackLifecycleCurrent(generation)"))
        assertTrue(gatewayLogin.contains("if (runtime.feedbackGenerationIsCurrent(feedbackGeneration))"))
        assertTrue(navigation.contains("isFeedbackLifecycleCurrent(generation)"))
        assertTrue(navigation.contains("if (!outputCurrent())"))
        assertTrue(functionBlock("private fun createSpeechOutputController()").contains("walkSessionLifecycle.currentRuntimeEpochOrNull() == owner.epoch"))
        assertTrue(functionBlock("private fun createSpeechOutputController()").contains("!isGuidanceVoiceInputActive()"))
        assertTrue(functionBlock("private fun createSpeechOutputController()").contains("isWalkSessionRuntimeActive() && navigationEnvironmentOutputsAllowed()"))
        assertTrue(interaction.contains("speakCommandResponse(message)"))
        assertTrue(commandResponse.contains("val lifecycleGeneration = reader.lifetime().generation"))
        assertTrue(
            commandResponse.contains(
                "if (!isFeedbackLifecycleCurrent(lifecycleGeneration)",
            ),
        )
        assertTrue(commandResponse.contains("shouldSuppressFeedbackDuringVoiceRecognition"))
        assertTrue(commandResponse.contains("isFeedbackLifecycleCurrent(lifecycleGeneration)"))
        assertTrue(commandResponse.contains("captureCommandSpeechResponseOwner(stateSource, responseGeneration, lifecycleGeneration)"))
        assertTrue(commandResponse.contains("responseOwner.isCurrent(this)"))
        val owner = MainActivityRefactorSource.speech.substringAfter("internal class CommandSpeechResponseOwner").substringBefore("fun captureCommandSpeechResponseOwner(")
        assertTrue(owner.contains("current.recognitionGeneration == captured.recognitionGeneration"))
        assertTrue(owner.contains("current.actorId == captured.actorId"))
        assertTrue(owner.contains("current.walkEpoch == captured.walkEpoch"))
        val positioningSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        val locationCallback = functionBlock("fun startLocationUpdatesIfAllowed(", positioningSource)
        val callbackCurrent = functionBlock("fun isLocationCallbackCurrent(", positioningSource)
        val fusedSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationUpdateSource.kt").readText()
        assertTrue(fusedSource.contains("if (!callbackIsCurrent(this)) return"))
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        val navigationGraph = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationSessionComposition.kt").readText()
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(MainActivityRefactorSource.property(source, "navigationSessions").contains("= createNavigationSessions()"))
        assertTrue(MainActivityRefactorSource.property(source, "positioningSessionController").contains("by navigationSessions.positioning"))
        assertTrue(navigationFactory.contains("sources = { createNavigationAndroidSources() }"))
        assertTrue(navigationFactory.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        val positioning = MainActivityRefactorSource.property(navigationGraph, "positioning")
        assertInOrder(positioning, "val sources = positions.sources()", "PositioningSessionController(run, scope, sources, mounting,")
        val sourcesFactory = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(sourcesFactory.contains("fusedAvailableNow = { ::fusedLocationClient.isInitialized }, fusedClient = { fusedLocationClient }"))
        val fusedFactory = MainActivityRefactorSource.member(androidSources, "fusedSource")
        assertTrue(fusedFactory.contains("FusedLocationUpdateSource(fusedClient(), { android.os.SystemClock.elapsedRealtime() }, current, onEvent)"))
        val onLocation = ReportStaticSourceInspector.functionBlock(fusedSource, "override fun onLocationResult")
        assertInOrder(onLocation, "val receivedAt = receivedAtMs()", "if (!callbackIsCurrent(this)) return", "result.lastLocation?.let", "FusedLocationUpdateEvent.LocationReceived(location, receivedAt)")
        assertTrue(locationCallback.contains("isPositioningCollectionCurrent(binding)"))
        assertTrue(
            locationCallback.contains(
                "isLocationCallbackCurrent(owner, generation, callback)",
            ),
        )
        assertTrue(callbackCurrent.contains("locationCallback === callback"))
        assertTrue(callbackCurrent.contains("generation == locationCallbackGeneration"))
        assertTrue(callbackCurrent.contains("locationCollectionOwner == owner"))
        assertTrue(callbackCurrent.contains("scope.currentOwner() == owner"))
        assertTrue(navigationFactory.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
    }

    @Test
    fun safetyCooldownsUseMonotonicTimeInsteadOfWallClock() {
        val frameSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val drawFrame = functionBlock("private fun synthesize(", frameSource)
        val frameDraw = functionBlock("fun draw(", frameSource)
        val routeGuidance = MainActivityRefactorSource.member(source, "updateRouteGuidance")
        val accessibility = functionBlock("private fun announceForTalkBack(")
        assertTrue(frameDraw.contains("synthesize(qualified)"))
        assertTrue(drawFrame.contains("val decision = clock.elapsedRealtimeMs()"))
        assertTrue(drawFrame.contains("val tactileAt = clock.elapsedRealtimeMs()"))
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        assertTrue(MainActivityRefactorSource.property(source, "framePipelineController").contains("override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()"))
        assertTrue(routeGuidance.contains("routeGuidanceController.updateRouteGuidance(location, rawLocation)"))
        val routeOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            MainActivityRefactorSource.member(routeOwner, "updateRouteGuidance"),
            "val queryAtMs = elapsedRealtimeMs()", "val compass = compassInput.heading(queryAtMs)",
            "val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())", "routeNavigator.update(", "nowMs = nowMs"))
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(navigationFactory.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(MainActivityRefactorSource.property(source, "navigationSessions").contains("= createNavigationSessions()"))
        assertTrue(MainActivityRefactorSource.property(source, "routeGuidanceController").contains("by navigationSessions.guidance"))
        val navigationGraph = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationSessionComposition.kt").readText()
        val guidance = MainActivityRefactorSource.property(navigationGraph, "guidance")
        assertTrue(guidance.contains("RouteGuidanceController(run,"))
        assertTrue(guidance.contains("elapsedRealtimeMs = elapsedRealtimeMs"))
        assertTrue(accessibility.contains("val nowMs = scheduler.elapsedRealtimeMs()"))
        val callbacks = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt").readText()
        assertTrue(callbacks.contains("private val clock: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(functionBlock("override fun elapsedRealtimeMs(", callbacks).contains("clock()"))
        assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController").contains("scheduler = voiceCallbacks"))
    }

    @Test
    fun explicitVoiceResponsesUseInteractionSpeechWhileAutomaticGuidanceUsesNavigationSpeech() {
        val voice = functionBlock("private fun handleVoiceCommandPhrases(")
        val route = delegatedOwner("updateRouteGuidance", "routeGuidanceController", "navigation/session/RouteGuidanceController.kt", "updateRouteGuidance", "{ routeGuidanceController.updateRouteGuidance(location, rawLocation) recordDirectionGraceDiagnostic() }")

        assertTrue(source.contains("private fun speakInteraction(message: String)"))
        assertTrue(voice.contains("speakInteraction(message)"))
        // S08-8: the manual repeat also passes the compass cause of that facing lookup.
        val dispatch = functionBlock("private fun executeVoiceAction(")
        assertTrue(dispatch.contains("AndroidVoiceAction.SpeakNextNavigationInstruction -> speakNextNavigationInstruction()"))
        val repeat = functionBlock("private fun speakNextNavigationInstruction()")
        assertInOrder(repeat, "val compass = route.currentRouteCompassHeadingResult(nowMs)",
            "routeRun().navigator.currentGuidance(nowMs, compass?.observation, compass?.reason)",
            "route.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)")
        assertTrue(MainActivityRefactorSource.member(source, "createVoiceInteractionControllers").contains("route = VoiceRoutePeerAdapter("))
        val routeDispatch = functionBlock("private fun tryDispatchRouteInstruction(")
        assertTrue(route.contains("tryDispatchRouteInstruction("))
        assertTrue(routeDispatch.contains("dispatchRouteInstructionSpeech("))
        assertTrue(routeDispatch.contains("routeNavigator.reserveInstruction(update)"))
        assertTrue(routeDispatch.contains("routeNavigator.releaseInstruction(update, elapsedRealtimeMs())"))
        assertTrue(routeDispatch.contains("routeNavigator.acknowledgeInstruction(update, elapsedRealtimeMs())"))
    }

    @Test
    fun accountAndGatewayPromptsRemainAvailableOutsideAnActiveWalk() {
        val gatewayButton = functionBlock("private fun onGatewaySessionButtonClicked()")
        val reporterGate = functionBlock("private fun requireReporterUserId(")
        val gatewayGate = functionBlock("private fun gatewaySessionOrNull(")

        listOf(gatewayButton, reporterGate, gatewayGate).forEach { interaction ->
            assertTrue(interaction.contains("speakInteraction("))
            assertFalse(interaction.contains("speakNavigation("))
        }
    }

    @Test
    fun activeForegroundRouteRiskOrFieldSessionKeepsScreenOnAndBackgroundClearsIt() {
        assertTrue(source.contains("syncActiveSessionScreenPolicy()"))
        assertTrue(source.contains("isWalkSessionRuntimeActive() &&"))
        assertTrue(
            source.contains(
                "session != null || cameraFallbackRunning || isRouteActive || isFieldSessionActive()",
            ),
        )
        val screenPolicy = functionBlock("private fun syncActiveSessionScreenPolicy()")
        assertTrue(screenPolicy.contains("isFieldSessionActive() ||\n                positionFieldController.explicitChestConfirmed"))
        assertTrue(fieldControllerSource.contains("val explicitChestConfirmed: Boolean get() = recording != null"))
        val fieldController = fieldControllerSource.substringAfter("internal class PositionFieldRecorderController(")
        val fieldStart = ReportStaticSourceInspector.functionBlock(fieldController, "fun start")
        val fieldStop = ReportStaticSourceInspector.functionBlock(fieldController, "fun stop")
        assertTrue(fieldStart.contains("recording = Recording(lease, walkEpoch"))
        assertInOrder(fieldStop, "recorder?.stop(active.lease)", "clearRecording()", "onRecordingStopped()")
        assertTrue(source.contains("onRecordingStopped = ::syncActiveSessionScreenPolicy"))
        val cameraSource = MainActivityRefactorSource.camera
        val bind = MainActivityRefactorSource.member(cameraSource, "bind")
        val stop = MainActivityRefactorSource.member(cameraSource, "stop")
        val events = functionBlock("private fun handleCameraFallbackSourceEvent(")
        assertInOrder(bind, "running = true", "onEvent(CameraFallbackEvent.Bound(")
        assertInOrder(stop, "running = false", "onEvent(CameraFallbackEvent.Stopped(")
        val eventCaller = code(MainActivityRefactorSource.factory(source, "handleCameraFallbackSourceEvent"))
        assertEquals(
            "privatefunhandleCameraFallbackSourceEvent(event:CameraFallbackEvent)=" +
                "runtimeCameraLifecycle.handleCameraFallbackSourceEvent(event)",
            eventCaller,
        )
        val bound = code(ReportStaticSourceInspector.blockAfter(events, "is CameraFallbackEvent.Bound ->"))
        val stopped = code(ReportStaticSourceInspector.blockAfter(events, "is CameraFallbackEvent.Stopped ->"))
        assertTrue(bound.startsWith("{sourceChanged()renderer.syncScreenPolicy()presentation.previewVisible(true)frames.resetPacing()"))
        assertTrue(stopped.startsWith("{renderer.syncScreenPolicy()if(presentation.previewInitialized())presentation.previewVisible(false)frames.resetPacing()"))
        assertTrue(code(MainActivityRefactorSource.property(source, "runtimeCameraLifecycle"))
            .contains("sourceChanged=::startUnknownRuntimeForWalk)"))
        assertTrue(events.contains("continueAfterCameraFallbackFailure("))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeCameraLifecycle").contains("override fun syncScreenPolicy() = syncActiveSessionScreenPolicy()"))
        assertFalse(events.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(source.contains("isActivityForeground = false\n        feedbackLifecycleGeneration += 1"))
        assertInOrder(functionBlock("internal fun pauseWalkSafeRuntime()"), "riskFeedbackComponents.delivery.cancelPendingFeedbackDeliveries()", "speechOutputController.closeActuator()")
        assertTrue(source.contains("window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(source.contains("window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
    }

    @Test
    fun portraitOrientationAvoidsLosingInMemoryRouteAndGatewayStateOnRotation() {
        assertTrue(manifest.contains("android:screenOrientation=\"portrait\""))
    }

    @Test
    fun longControlPanelIsScrollableWithoutReplacingCameraPreview() {
        val build = functionBlock("private fun buildContentView()")
        val scroll = functionBlock("fun createControlsScroll(", runtimeComposerSource)
        val assembly = sourceBlock("fun <T> compose(", "\n    }\n", assemblySource)
        val compose = MainActivityRefactorSource.member(contentComposerSource, "composeRootFrame")
        assertTrue(scroll.contains("ScrollView(context).apply"))
        assertTrue(build.contains("controlsScroll = runtimeComposer.createControlsScroll(overlay"))
        assertTrue(build.contains("NativeContentAssembly.Layers<View>(surfaceView, cameraFallbackPreviewView, debugBboxOverlay,"))
        assertTrue(build.contains("controlsScroll, walkSafetyScroll, cameraScreenControls)"))
        assertInOrder(assembly, "target.addFullscreen(content, layers.surface, false)", "target.addFullscreen(content, layers.controls, true)")
        assertTrue(compose.contains("(parent as FrameLayout).addView(child, params)"))
        assertTrue(compose.contains("ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT"))
    }

    @Test
    fun activeFieldSessionStartsLocationAndStepServicesWhenPermissionsAllow() {
        assertTrue(source.contains("navigationPermissionsRequestedForReport || fieldSessionActive"))
        assertTrue(source.contains("navigationPermissionsRequestedForRoute || fieldSessionActive"))
        assertTrue(source.contains("ensureNavigationPermissions(requireActivityRecognition = true)"))
    }
    @Test
    fun onboardingOverlayBottomPaddingScalesWithScreenDensity() {
        // 하드코딩된 32px 는 density 420 기기에서 12dp 에 불과하고, 밀도가 다른
        // 기기에서는 여백이 제각각이 된다. 마지막 컨트롤 아래 24dp 를 보장한다.
        assertTrue(source.contains("OVERLAY_BOTTOM_PADDING_DP"))
        assertTrue(functionBlock("fun readOverlayInsets(", contentComposerSource).contains("context.resources.displayMetrics.density"))
        assertTrue(functionBlock("private fun buildContentView()").contains("contentComposer.readOverlayInsets(OVERLAY_BOTTOM_PADDING_DP, OVERLAY_HORIZONTAL_PADDING_DP, OVERLAY_TOP_PADDING_DP)"))
        assertFalse(source.contains("setPadding(32, 48, 32, 32)"))
    }
    @Test
    fun settingsPageKeepsOnboardingGatesAndDeletionRecoveryAccess() {
        val update = functionBlock("private fun updateFirstRunOnboardingUi")
        assertTrue(update.contains("effects.updatePrivacyVisibility()"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunPresentationCoordinator").contains("override fun updatePrivacyVisibility() = updatePrivacySectionVisibility()"))
        assertTrue(functionBlock("private fun updatePrivacySectionVisibility").contains("renderMainUi()"))
        val renderer = functionBlock("private fun renderMainUi()")
        assertTrue(renderer.contains("val homeAvailable = queries.shouldShowNativeHome()"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeWorkflowController").contains("override fun shouldShowNativeHome(): Boolean = this@MainActivity.shouldShowNativeHome()"))
        assertTrue(renderer.contains("queries.deletionRecoveryRequired()"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeWorkflowController").contains("this@MainActivity.accountDeletionRecoveryLoginRequired() || GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly"))
        assertTrue(renderer.contains("if (!homeAvailable && !forceSettings)"))
        val settings = functionBlock("fun finishFrame(", nativePresenterSource)
        assertTrue(settings.contains("facts.settingsVisible && facts.homeAvailable && !facts.deletionRecovery"))
        assertTrue(renderer.contains("pages.renderFrame(NativeScreenPresenter.FrameFacts("))
        assertTrue(functionBlock("fun renderFrame(", pageControllerSource).contains("screen().finishFrame(frame)"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeWorkflowController").contains("nativePagePresentationController.renderFrame(frame)"))
        assertTrue(MainActivityRefactorSource.property(source, "nativePagePresentationController").contains("screen = { nativeScreenPresenter }"))
        assertTrue(settings.contains("show(settings.gatewaySessionControls, facts.deletionRecovery)"))
        assertTrue(settings.contains("show(runtime.privacySectionToggleButton, false)"))
        assertTrue(settings.contains("show(runtime.gatewaySessionControlsToggleButton, false)"))
    }

    @Test
    fun settingsUsesTheExplicitPageWithoutRestoringRemovedDisclosureControls() {
        val renderer = functionBlock("private fun renderMainUi()")
        val settings = functionBlock("fun finishFrame(", nativePresenterSource)
        assertTrue(renderer.contains("pages.renderFrame(NativeScreenPresenter.FrameFacts("))
        assertTrue(functionBlock("fun renderFrame(", pageControllerSource).contains("screen().finishFrame(frame)"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeWorkflowController").contains("nativePagePresentationController.renderFrame(frame)"))
        assertTrue(MainActivityRefactorSource.property(source, "nativePagePresentationController").contains("screen = { nativeScreenPresenter }"))
        assertTrue(renderer.contains("nativeUiPage == NativeUiPage.SETTINGS"))
        assertTrue(settings.contains("show(settings.privacyControls, facts.settingsVisible)"))
        assertTrue(settings.contains("show(settings.nativeSettingsHomeButton, facts.settingsVisible && facts.homeAvailable && !facts.forceSettings)"))
        assertTrue(settings.contains("show(runtime.privacySectionToggleButton, false)"))
        assertTrue(functionBlock("private fun show(", nativePresenterSource).contains("view.visibility = if (visible) View.VISIBLE else View.GONE"))
        val overlay = sourceBlock("val overlay = contentComposer.createOverlayContainer(", "return contentComposer.composeRootFrame(")
        assertTrue(overlay.contains("addView(settingsPanel.privacyControls)"))
        assertFalse(overlay.contains("addView(privacySectionToggleButton)"))
    }

    @Test
    fun allInteractiveControlsReceiveReadableTextAndCurrentMinimumTargets() {
        val defaults = functionBlock("fun applyAccessibleControlDefaults(", styleSource)
        assertTrue(defaults.contains("accessibilityTargetSizePx()"))
        assertTrue(defaults.contains("minimumHeight = maxOf"))
        assertTrue(defaults.contains("minimumWidth = maxOf"))
        assertTrue(styleSource.contains("const val MIN_INTERACTIVE_TEXT_SP = 18f"))
        assertTrue(defaults.contains("TypedValue.applyDimension("))
        assertTrue(defaults.contains("if (root.textSize < minimumTextSizePx)"))
        assertTrue(defaults.contains("root is ViewGroup"))
        assertTrue(source.contains("applyAccessibleControlDefaults(overlay)"))
        assertTrue(source.contains("= viewStyle.applyAccessibleControlDefaults(root)"))
    }

    @Test
    fun controlsCarryTheMeasuredDesignTokensInsteadOfPlatformDefaults() {
        // 측정된 대비값을 코드 상수로 고정한다. 흰 글자/회색 면 6.97:1, 테두리 4.08:1.
        assertTrue(styleSource.contains("WS_COLOR_BUTTON_FILL"))
        assertTrue(styleSource.contains("WS_COLOR_BUTTON_TEXT"))
        assertTrue(styleSource.contains("WS_COLOR_LINE"))
        assertTrue(styleSource.contains("WS_CORNER_RADIUS_DP"))
        assertTrue(styleSource.contains("WS_CONTROL_GAP_DP"))

        assertTrue(source.contains("= viewStyle.accessiblePriorityUserButton(label, emphasis, spokenLabel, onClick)"))
        val factory = functionBlock("fun accessiblePriorityUserButton(", styleSource)
        assertTrue(factory.contains("backgroundTintList = ColorStateList("))
        assertTrue(factory.contains("android.R.attr.state_enabled"))
        assertTrue(factory.contains("android.R.attr.state_pressed"))
        assertTrue(factory.contains("android.R.attr.state_focused"))
        assertTrue(factory.contains("bottomMargin"))
        // 기존 접근성 계약은 그대로 유지한다.
        assertTrue(factory.contains("setSingleLine(false)"))
        assertTrue(factory.contains("minimumHeight = (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()"))
    }
    @Test
    fun onboardingShowsStepProgressAsDecorationAndStatusTextAsTitle() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        // 진행 표시줄은 시각 정보만 제공한다. 같은 내용을 heading 문장이 이미 낭독하므로
        // 접근성 트리에서 제외해 중복 낭독을 막는다.
        assertTrue(onboardingSource.contains("firstRunProgressBar = LinearLayout(context)"))
        assertTrue(bindRegistration.contains("publication.publish(NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR)"))
        assertTrue(functionBlock("fun renderProgress(", firstRunPresenterSource).contains("firstRunProgressSegments.forEachIndexed"))

        val bar = sourceBlock(
            "firstRunProgressBar = LinearLayout(context)",
            "firstRunOnboardingStatusText = TextView(context)",
            onboardingSource,
        )
        assertTrue(bar.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO"))

        // 큰 글꼴에서도 현재 행동이 첫 화면에 남는 제목 크기를 쓴다.
        val title = sourceBlock(
            "firstRunOnboardingStatusText = TextView(context).apply",
            "ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText",
            onboardingSource,
        )
        assertTrue(title.contains("Typeface.NORMAL"))
        assertTrue(title.contains("textSize = 20f"))
    }
    @Test
    fun safetyNoticeUsesTheCurrentFullBodyOnlyOnItsOnboardingStage() {
        val update = functionBlock("fun refreshFirstRunNoticeUi(", firstRunPresenterSource)
        val eligibility = functionBlock("private fun refreshFirstRunNoticeUi")
        assertTrue(eligibility.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
        assertTrue(update.contains("firstRunNoticeToggleButton.visibility = View.GONE"))
        assertTrue(update.contains("productPurposeText.visibility = if (purposeStageVisible) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("모든 장애물과 위험을 감지하지 못하며"))
        assertTrue(update.contains("흰지팡이·안내견 등 기존 보조수단을 대신하지 않습니다."))
        assertTrue(update.contains("안내가 없더라도 안전하다고 판단하지 마세요."))
        assertTrue(update.contains("productPurposeText.contentDescription = productPurposeText.text"))
        assertTrue(update.contains("applyWsSafetyNoticeBody(productPurposeText)"))
        assertTrue(eligibility.contains("firstRunOnboardingPresenter.refreshFirstRunNoticeUi(visible)"))
    }

    @Test
    fun safetyNoticeAcknowledgmentRemainsExplicitAndIndependentOfScrolling() {
        val update = functionBlock("fun refreshFirstRunNoticeUi(", firstRunPresenterSource)
        val eligibility = functionBlock("private fun refreshFirstRunNoticeUi")
        assertTrue(eligibility.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
        assertFalse(update.contains("scrollY"))
        assertFalse(update.contains("canScrollVertically"))
        val action = sourceBlock("firstRunPurposeButton = style.accessiblePriorityUserButton(", "FirstRunAgeBand.ADULT_18_PLUS,", onboardingSource)
        assertTrue(action.contains("onClick = actions.registration.onPurposeAcknowledged"))
        assertTrue(source.contains("onPurposeAcknowledged = ::acknowledgeFirstRunPurposeAndSafety"))
        assertTrue(action.contains("emphasis = true"))
        val acknowledgment = functionBlock("private fun acknowledgeFirstRunPurposeAndSafety()")
        assertTrue(acknowledgment.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
    }

    @Test
    fun dynamicDestinationResultsKeepReadableNamesAddressesAndFullWidthTargets() {
        val update = functionBlock("override fun renderSearchResults(", destinationSource)
        val mainSearch = functionBlock("private fun updateDestinationSearchUi")
        val searchPresentation = functionBlock("fun renderSearch(", searchPresenterSource)
        assertTrue(mainSearch.contains("destinationSearchPresenter.renderSearch("))
        assertTrue(mainSearch.contains("onSelected = ::openNativeDestinationConfirmation"))
        assertTrue(searchPresentation.contains("panel.renderSearchResults("))
        assertTrue(functionBlock("private fun buildContentView()").contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        assertTrue(update.contains("applyWsButtonStyle(this, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(update.contains("textSize = 22f"))
        assertTrue(update.contains("AbsoluteSizeSpan(18, true)"))
        assertTrue(update.contains("minimumHeight = (200 * density).toInt()"))
        assertTrue(update.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        assertTrue(update.contains("setSingleLine(false)"))
        assertTrue(update.contains("setOnClickListener { onSelected(candidate.result) }"))
        assertFalse(update.contains("textSize = 12f"))
        assertFalse(update.contains("textSize = 11f"))
    }

    private fun functionBlock(marker: String, text: String = source): String {
        if (text === source) return MainActivityRefactorSource.function(text, marker)
        val name = Regex("fun\\s+(\\w+)").find(marker)?.groupValues?.get(1)
            ?: error("missing function signature: $marker")
        return try {
            MainActivityRefactorSource.member(text, name)
        } catch (_: IllegalStateException) {
            ReportStaticSourceInspector.functionBlock(text, marker)
        }
    }

    private fun sourceBlock(startMarker: String, endMarker: String, text: String = source): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return text.substring(start, end)
    }
    @Test
    fun stageHeadingRendersEyebrowAndTitleWithoutSplittingTheAnnouncement() {
        // "첫 실행 N단계." 는 작고 흐리게, 나머지는 크고 굵게. 한 TextView 안에서
        // Spannable 로 처리하므로 text 와 contentDescription 은 전체 문장 그대로 남고
        // 접근성 heading 계약도 유지된다.
        val update = functionBlock("private fun applyStageHeadingStyle", firstRunPresenterSource)
        assertTrue(update.contains("SpannableString"))
        assertTrue(update.contains("RelativeSizeSpan"))
        assertTrue(update.contains("StyleSpan"))
        assertTrue(update.contains("ForegroundColorSpan"))

        val caller = functionBlock("fun renderStage(", firstRunPresenterSource)
        val binding = functionBlock("private fun updateFirstRunOnboardingUi")
        assertTrue(caller.contains("firstRunOnboardingStatusText.text = message"))
        assertTrue(caller.contains("firstRunOnboardingStatusText.contentDescription = message"))
        assertTrue(caller.contains("applyStageHeadingStyle("))
        assertTrue(binding.contains("effects.renderStage {"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunPresentationCoordinator").contains("firstRunOnboardingPresenter.renderStage(facts())"))
        assertTrue(binding.contains("stageNumber = stageNumber"))
        assertTrue(binding.contains("stageCount = stageCount"))
    }

    @Test
    fun stageHeadingsUseShortActionLanguageForLargeFonts() {
        val update = functionBlock("fun renderStage(", firstRunPresenterSource)
        assertTrue(update.contains("로그인하거나 새 계정을 만드세요."))
        assertTrue(update.contains("인증번호를 입력해 계정을 만드세요."))
        assertTrue(update.contains("기기 기능을 점검하세요."))
        assertFalse(
            update.contains("이메일과 생년월일로 가입하거나 기존 계정으로 로그인하세요."),
        )
    }

    @Test
    fun completeHeadingKeepsFeaturesLockedUntilRuntimeSafetyGatesPass() {
        val update = functionBlock("fun renderStage(", firstRunPresenterSource)
        val eligibility = functionBlock("private fun updateFirstRunOnboardingUi")
        val complete = update.substring(
            update.indexOf("FirstRunOnboardingStage.COMPLETE ->").also {
                require(it >= 0) { "missing COMPLETE heading branch" }
            },
            update.indexOf("FirstRunOnboardingStage.BLOCKED_UNDER_14 ->").also {
                require(it >= 0) { "missing heading branch after COMPLETE" }
            },
        )
        assertTrue(complete.contains("facts.firstRunComplete"))
        assertTrue(eligibility.contains("firstRunComplete = snapshot.stage == FirstRunOnboardingStage.COMPLETE && facts.complete()"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunPresentationCoordinator").contains("override fun complete() = firstRunOnboardingComplete()"))
        assertTrue(complete.contains("기능은 잠겨 있습니다"))
        assertTrue(complete.contains("권한·안전 상태"))
    }
    @Test
    fun primaryActionsRetainTheirSharedAccessibleSizeAndExplicitHandlers() {
        val factory = functionBlock("fun accessiblePriorityUserButton(", styleSource)
        assertTrue(factory.contains("emphasis: Boolean"))
        assertTrue(factory.contains("minimumHeight = (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()"))
        assertTrue(factory.contains("setSingleLine(false)"))
        assertTrue(factory.contains("wsEmphasisButtons += this"))
        val purpose = sourceBlock("firstRunPurposeButton = style.accessiblePriorityUserButton(", "FirstRunAgeBand.ADULT_18_PLUS,", onboardingSource)
        assertTrue(purpose.contains("emphasis = true"))
        assertTrue(purpose.contains("onClick = actions.registration.onPurposeAcknowledged"))
        assertTrue(source.contains("onPurposeAcknowledged = ::acknowledgeFirstRunPurposeAndSafety"))
        val education = sourceBlock("priorityUserEducationAgreeButton = style.accessiblePriorityUserButton(", "firstRunPhonePostureText =", onboardingSource)
        assertTrue(education.contains("emphasis = true"))
        assertTrue(education.contains("onClick = actions.education.onEducationAgree"))
        assertTrue(source.contains("onEducationAgree = ::acceptNativeSafetyEducation"))
    }

    @Test
    fun buttonLabelsStayShortOnScreenWhileTalkBackKeepsTheContext() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        // 화면에는 "만 18세 이상", TalkBack 에는 "가입 연령: 만 18세 이상".
        // 눈으로 보는 라벨은 짧게, 낭독 맥락은 유지한다.
        val factory = functionBlock("fun accessiblePriorityUserButton(", styleSource)
        assertTrue(factory.contains("spokenLabel"))

        val ageBlock = sourceBlock("FirstRunAgeBand.ADULT_18_PLUS,", "firstRunIntegratedConsentDisclosureText =", onboardingSource)
        assertTrue(onboardingSource.contains("val firstRunAgeButtons: Map<FirstRunAgeBand, Button> get() = ageButtons"))
        assertTrue(functionBlock("fun renderConsentStage(", firstRunPresenterSource).contains("firstRunAgeButtons.forEach"))
        assertTrue(ageBlock.contains("spokenLabel = \"가입 연령: "))
        assertFalse(ageBlock.contains("label = \"가입 연령: "))
    }

    @Test
    fun spacingSeparatesGroupsInsteadOfBeingUniform() {
        // 24dp 일괄이 아니라, 섹션 경계는 넓게 같은 그룹의 버튼 사이는 좁게 둔다.
        assertTrue(styleSource.contains("WS_GROUP_GAP_DP"))
        assertTrue(styleSource.contains("WS_TITLE_GAP_DP"))
        val factory = functionBlock("fun accessiblePriorityUserButton(", styleSource)
        assertTrue(factory.contains("WS_GROUP_GAP_DP"))
        assertFalse(factory.contains("bottomMargin = (WS_CONTROL_GAP_DP"))
    }
    @Test
    fun consentItemsRenderInsideCards() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        // 항목마다 카드로 묶어 시각적으로 분리한다. 버튼 표시 텍스트는 기존 계약대로
        // "$label: $syncState" 를 유지하므로 상태는 카드 헤더로 옮기지 않는다.
        assertTrue(functionBlock("fun renderConsentStage(", firstRunPresenterSource).contains("firstRunConsentCards.values"))

        val build = sourceBlock("consentCards[item] = LinearLayout(context).apply", "firstRunIntegratedConsentSaveButton =", onboardingSource)
        assertTrue(build.contains("GradientDrawable()"))
        assertTrue(build.contains("WS_COLOR_LINE"))
        assertTrue(build.contains("consentCards[item]"))
        assertTrue(onboardingSource.contains("val firstRunConsentCards: Map<IntegratedConsentItem, LinearLayout> get() = consentCards"))
        assertTrue(functionBlock("fun publishRegistrationTargets(", firstRunPresenterSource).contains("publishedRegistration = onboarding"))
    }
    @Test
    fun onboardingCardsCarryTheirOwnHeadingAndCount() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        // 고지 카드 머리글은 Spannable 로 넣는다. addView(productPurposeText) 가 오버레이에
        // 직접 있어야 하는 계약 때문에 별도 컨테이너로 감쌀 수 없다.
        assertTrue(source.contains("SAFETY_NOTICE_HEADING"))
        val notice = functionBlock("private fun applyNoticeHeadingStyle")
        assertTrue(notice.contains("SpannableString"))
        assertTrue(notice.contains("RelativeSizeSpan"))

        // 항목 설명은 승인된 전문에서 그대로 잘라 쓴다. 새 문구를 만들지 않는다.
        assertTrue(source.contains("consentClauseOrNull"))
        val clause = functionBlock("private fun consentClauseOrNull")
        assertTrue(clause.contains("INTEGRATED_CONSENT_DISCLOSURE_KO"))

        assertTrue(onboardingSource.contains("firstRunConsentCountText = TextView(context)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))
        assertTrue(bindRegistration.contains("publication.publish(NativeUiPublication.Target.FIRST_RUN_CONSENT_COUNT_TEXT)"))
    }

    @Test
    fun consentTraversalIncludesToggleDisclosureCountClauseAndAction() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        assertTrue(functionBlock("fun linkFirstRunAccessibilityTraversal(", firstRunPresenterSource).contains("firstRunConsentClauseTexts.getValue(item)"))
        val traversal = functionBlock("fun linkFirstRunAccessibilityTraversal(", firstRunPresenterSource)
        assertTrue(functionBlock("private fun linkFirstRunAccessibilityTraversal").contains("firstRunOnboardingPresenter.linkFirstRunAccessibilityTraversal(SIGNUP_DOCUMENT_VERSIONS.keys)"))
        assertInOrder(
            traversal,
            "add(firstRunOnboardingStatusText)",
            "add(firstRunDisclosureToggleButton)",
            "add(firstRunIntegratedConsentDisclosureText)",
            "add(firstRunConsentCountText)",
            "add(firstRunConsentClauseTexts.getValue(item))",
            "add(firstRunIntegratedConsentButtons.getValue(item))",
            "add(firstRunIntegratedConsentSaveButton)",
        )

        assertTrue(onboardingSource.contains("val firstRunConsentClauseTexts: Map<IntegratedConsentItem, TextView> get() = consentClauseTexts"))
        assertTrue(functionBlock("fun publishRegistrationTargets(", firstRunPresenterSource).contains("publishedRegistration = onboarding"))
        val clause = onboardingSource.substringAfter("consentClauseTexts[item] = TextView")
            .substringBefore("addView(consentClauseTexts.getValue(item))")
        assertTrue(clause.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
    }

    @Test
    fun expandedNoticesReceiveAccessibilityFocus() {
        val notice = functionBlock("fun toggleNotice(", firstRunPresenterSource)
        val disclosure = functionBlock("fun toggleDisclosure(", firstRunPresenterSource)
        val build = sourceBlock("onNoticeToggle = {", "onConsentSave =", source)
        assertTrue(notice.contains("productPurposeText.requestFocus()"))
        assertTrue(disclosure.contains("firstRunIntegratedConsentDisclosureText.requestFocus()"))
        assertTrue(notice.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(disclosure.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(build.contains("firstRunOnboardingPresenter.toggleNotice("))
        assertTrue(build.contains("firstRunOnboardingPresenter.toggleDisclosure(::updateFirstRunOnboardingUi)"))
    }

    @Test
    fun onboardingBodyAndButtonsMeetLowVisionTextBaseline() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val composition = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(composition.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val factory = functionBlock("fun accessiblePriorityUserButton(", styleSource)
        assertFalse(factory.contains("textSize = 16f"))
        assertFalse(factory.contains("textSize = 17f"))
        assertTrue(factory.contains("textSize = 18f"))
        val build = sourceBlock("productPurposeText = TextView(context).apply", "firstRunOnboardingControls =", onboardingSource)
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(functionBlock("fun registrationBinding(", firstRunPresenterSource).contains("publishedRegistration"))
        assertTrue(functionBlock("fun publishRegistrationTargets(", firstRunPresenterSource).contains("publishedRegistration = onboarding"))
        assertFalse(build.contains("textSize = 16f"))
        assertFalse(build.contains("textSize = 17f"))
        assertTrue(build.contains("textSize = 18f"))
    }

    private fun code(value: String): String = ReportStaticSourceInspector.maskNonCode(value).filterNot(Char::isWhitespace)

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
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
