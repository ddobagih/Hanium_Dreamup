package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccessibilityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun screenReaderModeKeepsRiskHapticAndFallsBackAfterNavigationTtsFailure() {
        assertTrue(source.contains("private fun isScreenReaderActive()"))
        val screenReaderDetection = source.substringAfter("private fun isScreenReaderActive()")
            .substringBefore("private fun isFeedbackLifecycleCurrent")
        assertTrue(screenReaderDetection.contains("AccessibilityServiceInfo.FEEDBACK_SPOKEN"))
        assertTrue(screenReaderDetection.contains("getEnabledAccessibilityServiceList("))
        assertFalse(screenReaderDetection.contains("isTouchExplorationEnabled"))
        assertTrue(source.contains("actuator.vibrateRiskOnly(action)"))
        assertTrue(source.contains("actuator.prepareForExternalRiskAnnouncement()"))
        val navigation = source
            .substringAfter("private fun speakNavigation(message: String, onCompleted: (() -> Unit)? = null)")
            .substringBefore("private fun speakInteraction(message: String)")
        assertTrue(navigation.contains("val mainThreadCompletion = onCompleted?.let"))
        assertTrue(navigation.contains("if (isFeedbackLifecycleCurrent(generation)) completion()"))
        assertTrue(navigation.contains("onCompleted = mainThreadCompletion"))
        assertTrue(navigation.contains("return dispatchNavigationSpeech("))
        assertTrue(navigation.contains("ensureFeedbackActuator().speakNavigation(ttsMessage, onTtsCompleted, onTtsFailed)"))
        assertTrue(navigation.contains("dispatchNavigationTalkBackFallback(fallbackMessage, onDelivered, generation)"))
        val fallback = source
            .substringAfter("private fun dispatchNavigationTalkBackFallback(")
            .substringBefore("private fun speakInteraction(message: String)")
        assertTrue(fallback.contains("Looper.myLooper() != Looper.getMainLooper()"))
        assertTrue(fallback.contains("dispatchNavigationTalkBackFallback(message, onDelivered, generation)"))
        assertTrue(fallback.contains("if (!isFeedbackLifecycleCurrent(generation)) return false"))
        assertTrue(fallback.contains("if (!isScreenReaderActive()) return false"))
        assertTrue(fallback.contains("priority = TalkBackAnnouncementPriority.NAVIGATION"))
        assertTrue(fallback.contains("onDelivered = onDelivered"))
        assertTrue(source.contains("statusText.announceForAccessibility(message)\n            onDelivered?.invoke()"))
    }

    @Test
    fun liveRegionAndDebugCaptureAccessibilityContractIsStable() {
        val debugUploadPolicy = source
            .substringAfter("private fun updateDebugUploadButton()")
            .substringBefore("private fun updateFrameCaptureButton()")

        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(source.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(source.contains("statusText.announceForAccessibility(message)"))
        assertTrue(source.contains("riskAnnouncementHoldUntilMs"))
        assertTrue(source.contains("TALKBACK_INTERACTION_PRIORITY_WINDOW_MS"))
        assertTrue(source.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(source.contains("debugFrameCaptureButton.visibility = View.GONE"))
        assertTrue(debugUploadPolicy.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(debugUploadPolicy.contains("debugUploadButton.visibility = View.GONE"))
        assertTrue(debugUploadPolicy.contains("debugUploadButton.isEnabled = false"))
    }

    @Test
    fun permissionDenialPanelExposesItemsReasonsConfirmAndSettingsInOrder() {
        val panelStart = "permissionDenialPanel = LinearLayout(this).apply"
        val panelEnd = "actionButton = Button(this).apply"
        val labelStart = "private fun ObservedPermission.labelKo(): String = when (this) {"
        val reasonStart = "private fun ObservedPermission.denialReasonKo(): String = when (this) {"
        val mappingEnd = "private fun persistPermissionRecoveryGate()"
        val presentationStart = "private fun showPermissionDenialPanel("
        val confirmationStart = "private fun onPermissionDenialConfirmed()"
        val observedStateStart = "private fun applyObservedPermissionStateChange("
        val observedStateEnd = "private fun isRouteLocationPermissionReady()"
        val blockedStart = "PermissionRecoveryGateState.BLOCKED -> {"
        val awaitingStart = "PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {"
        val otherStatesStart = "PermissionRecoveryGateState.SETTINGS_PENDING,"
        listOf(
            panelStart,
            panelEnd,
            labelStart,
            reasonStart,
            mappingEnd,
            presentationStart,
            confirmationStart,
            observedStateStart,
            observedStateEnd,
            blockedStart,
            awaitingStart,
            otherStatesStart,
        ).forEach { marker ->
            assertTrue("Missing exact source marker: $marker", source.contains(marker))
        }

        val panel = source
            .substringAfter(panelStart)
            .substringBefore(panelEnd)
        val labels = source
            .substringAfter(labelStart)
            .substringBefore(reasonStart)
        val reasons = source
            .substringAfter(reasonStart)
            .substringBefore(mappingEnd)
        val presentation = source
            .substringAfter(presentationStart)
            .substringBefore(confirmationStart)
        val confirmation = source
            .substringAfter(confirmationStart)
            .substringBefore(observedStateStart)
        val blockedConfirmation = confirmation
            .substringAfter(blockedStart)
            .substringBefore(awaitingStart)
        val awaitingConfirmation = confirmation
            .substringAfter(awaitingStart)
            .substringBefore(otherStatesStart)
        val observedState = source
            .substringAfter(observedStateStart)
            .substringBefore(observedStateEnd)

        val summary = panel.indexOf("permissionDenialSummaryText = TextView")
        val confirmAction = panel.indexOf("permissionDenialConfirmButton = Button")
        val settingsAction = panel.indexOf("permissionDenialSettingsButton = Button")
        assertTrue(summary >= 0)
        assertTrue(confirmAction > summary)
        assertTrue(settingsAction > confirmAction)
        assertTrue(panel.contains("ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertTrue(panel.contains("accessibilityTraversalAfter = permissionDenialSummaryText.id"))
        assertTrue(panel.contains("accessibilityTraversalAfter = permissionDenialConfirmButton.id"))
        assertFalse(panel.contains("확인하고 앱 끝내기"))
        assertTrue(panel.contains("setOnClickListener { onPermissionDenialConfirmed() }"))
        assertTrue(panel.contains("setOnClickListener { openAppSettings() }"))

        listOf(
            "ObservedPermission.CAMERA -> \"카메라\"" to
                "ObservedPermission.CAMERA -> \"장애물 인식과 신고 전송을 사용할 수 없습니다.\"",
            "ObservedPermission.PRECISE_LOCATION -> \"정확한 위치\"" to
                "ObservedPermission.PRECISE_LOCATION -> \"거리 측정, 길안내와 신고 전송을 사용할 수 없습니다.\"",
            "ObservedPermission.MICROPHONE -> \"마이크\"" to
                "ObservedPermission.MICROPHONE -> \"음성 명령과 음성 재개 확인을 사용할 수 없습니다.\"",
            "ObservedPermission.ACTIVITY_RECOGNITION -> \"보행 센서\"" to
                "ObservedPermission.ACTIVITY_RECOGNITION -> \"걸음 수 추적을 사용할 수 없습니다.\"",
        ).forEach { (label, reasonText) ->
            assertTrue(labels.contains(label))
            assertTrue(reasons.contains(reasonText))
        }

        val item = presentation.indexOf("permission.labelKo()")
        val reason = presentation.indexOf("permission.denialReasonKo()")
        val actions = presentation.indexOf("permissionDenialConfirmButton.text")
        assertTrue(item >= 0)
        assertTrue(reason > item)
        assertTrue(actions > reason)
        assertTrue(presentation.contains("permissionDenialSummaryText.contentDescription = message"))
        assertFalse(presentation.contains("원인: \$reason"))
        assertFalse(presentation.contains("permission_result_walk_session"))
        assertFalse(presentation.contains("permission_recovery_blocked"))
        assertTrue(presentation.contains("permissionDenialSummaryText.requestFocus()"))
        assertTrue(presentation.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertFalse(presentation.contains("announceForAccessibility("))
        assertFalse(presentation.contains("speakInteraction("))
        assertFalse(observedState.contains("speakInteraction("))

        val exitLabel = "확인하고 앱 끝내기"
        assertTrue(
            presentation.contains(
                "PermissionRecoveryGateState.BLOCKED -> \"$exitLabel\"",
            ),
        )
        assertTrue(presentation.indexOf(exitLabel) == presentation.lastIndexOf(exitLabel))
        assertTrue(blockedConfirmation.contains("finishAndRemoveTask()"))
        assertTrue(
            confirmation.indexOf("finishAndRemoveTask()") ==
                confirmation.lastIndexOf("finishAndRemoveTask()"),
        )
        assertFalse(awaitingConfirmation.contains("finishAndRemoveTask()"))
        assertFalse(awaitingConfirmation.contains("maybeAdvance"))
        val hidePanel = awaitingConfirmation.indexOf(
            "permissionDenialPanel.visibility = View.GONE",
        )
        val renderResumeControl =
            awaitingConfirmation.indexOf("renderAwaitingExplicitResumeControl()", hidePanel)
        val postFocus = awaitingConfirmation.indexOf(
            "startupCapabilityConfirmButton.post {",
            renderResumeControl,
        )
        val requestFocus = awaitingConfirmation.indexOf(
            "startupCapabilityConfirmButton.requestFocus()",
            postFocus,
        )
        val performFocus = awaitingConfirmation.indexOf(
            "startupCapabilityConfirmButton.performAccessibilityAction(",
            requestFocus,
        )
        val accessibilityFocus = awaitingConfirmation.indexOf(
            "ACTION_ACCESSIBILITY_FOCUS",
            performFocus,
        )
        assertTrue(hidePanel >= 0)
        assertTrue(
            hidePanel < renderResumeControl &&
                renderResumeControl < postFocus &&
                postFocus < requestFocus &&
                requestFocus < performFocus &&
                performFocus < accessibilityFocus,
        )
        assertFalse(awaitingConfirmation.contains("announceForAccessibility("))
    }

    @Test
    fun permissionDenialPanelRemainsVisibleOutsideHiddenRuntimeControls() {
        val runtimeControls = source
            .substringAfter("runtimeControls = LinearLayout(this).apply")
            .substringBefore("val overlay = LinearLayout(this).apply")
        val overlay = source
            .substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("linkFirstRunAccessibilityTraversal()")
        val panelPosition = overlay.indexOf("addView(permissionDenialPanel)")
        val runtimePosition = overlay.indexOf("addView(runtimeControls)")

        assertFalse(runtimeControls.contains("addView(permissionDenialPanel)"))
        assertTrue(panelPosition >= 0)
        assertTrue(runtimePosition > panelPosition)
        assertTrue(source.contains("runtimeControls.visibility = View.GONE"))
    }

    @Test
    fun feedbackActuatorIsReleasedOnPause() {
        assertTrue(source.contains("override fun onPause()"))
        val pause = source.substringAfter("internal fun pauseWalkSafeRuntime()")
            .substringBefore("private fun invalidateFrameStateForPause()")
        assertTrue(pause.contains("if (::surfaceView.isInitialized) surfaceView.onPause()"))
        assertTrue(pause.contains("feedbackActuator?.close()"))
        assertTrue(pause.contains("feedbackActuator = null"))
        assertTrue(
            pause.indexOf("surfaceView.onPause()") <
                pause.indexOf("invalidateRuntimeMetricEvidence(\"app_paused\")"),
        )
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")
        assertTrue(destroy.contains("cancelPendingFeedbackTerminalResolution()"))
        assertTrue(destroy.contains("latestFeedbackDeliveryState = FeedbackDeliveryState()"))
    }

    @Test
    fun manifestMakesSpeechServicesVisibleOnAndroid11AndLater() {
        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"))
        assertTrue(manifest.contains("android.speech.RecognitionService"))
    }

    @Test
    fun riskFeedbackMovesToMainThreadAndCancelsActiveSpeechRecognition() {
        val feedback = source.substringAfter(
            "private fun emitFeedbackAction(action: FeedbackAction, policyEvaluatedAtMs: Long)",
        )
            .substringBefore("private fun speakNavigation(message: String, onCompleted: (() -> Unit)? = null)")

        assertTrue(feedback.contains("Looper.myLooper() != Looper.getMainLooper()"))
        assertTrue(feedback.contains("if (!isFeedbackLifecycleCurrent(generation)) {"))
        assertTrue(feedback.contains("emitFeedbackAction(action, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("if (isRisk && voiceRecognitionActive) cancelVoiceCommandRecognition()"))
        assertTrue(feedback.contains("shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk)"))
        assertTrue(source.contains("!feedbackPolicy.canSpeakNavigation(SystemClock.elapsedRealtime())"))
        assertTrue(source.contains("voice=blocked_by_active_feedback"))
        assertTrue(source.contains("feedbackActuator?.prepareForSpeechRecognition() == false"))
        assertTrue(source.contains("voice=blocked_by_risk_speech"))
        assertTrue(feedback.indexOf("prepareForExternalRiskAnnouncement()") < feedback.indexOf("announceForTalkBack("))
        assertTrue(feedback.contains("confirmFeedbackDelivery(action, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("SystemClock.elapsedRealtime()"))
        assertTrue(feedback.contains("feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("feedbackPolicy.claimFeedbackDelivery(action.trackId, policyEvaluatedAtMs)"))
        assertTrue(feedback.contains("val vibrationAccepted = if (isRisk) actuator.vibrateRiskOnly(action) else false"))
        assertTrue(feedback.contains("if (screenReaderActive)"))
        assertTrue(feedback.contains("} else if (isRisk) {"))
        assertTrue(
            feedback.indexOf("if (screenReaderActive)") <
                feedback.indexOf("val talkBackAccepted = announceForTalkBack("),
        )
        assertTrue(feedback.contains("onSpeechCompleted = {"))
        assertTrue(feedback.contains("onSpeechFailed = {"))
        assertTrue(feedback.contains("scheduleFeedbackTerminalResolution("))
        assertTrue(feedback.contains("dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED"))
        val progressBeep = source.substringAfter("private fun maybePlayProgressBeep(")
            .substringBefore("private fun updateDebugUploadButton()")
        assertTrue(progressBeep.contains("if (!isActivityForeground) return"))
        assertTrue(progressBeep.contains("shouldSuppressFeedbackDuringVoiceRecognition"))
    }

    @Test
    fun queuedFeedbackIsRevalidatedAtTheSingleOutputBoundary() {
        val intake = source.substringAfter("private fun emitTactileFrameFeedback(")
            .substringBefore("private fun emitFeedbackAction(")
        val boundary = source.substringAfter(
            "private fun emitFeedbackAction(action: FeedbackAction, policyEvaluatedAtMs: Long)",
        ).substringBefore("private fun confirmFeedbackDelivery(")

        assertTrue(intake.contains("latestFeedbackDeliveryState = FeedbackDeliveryState("))
        assertTrue(intake.contains("activeFeedbackDeliveryKeys = dispatch.activeFeedbackDeliveryKeys"))
        assertTrue(boundary.contains("isFeedbackActionStillDeliverable(action)"))
        assertTrue(source.contains("action.deliveryKey in current.activeFeedbackDeliveryKeys"))
        assertTrue(source.contains("nowMs <= action.validUntilMs"))
        assertTrue(source.contains("currentFeedbackDeviceGateAllowsAlerts()"))
        assertTrue(source.contains("feedbackPolicy.cancelPendingFeedbackDeliveries()"))
        assertTrue(source.contains("pendingFeedbackTerminalResolution?.runnable !== resolution"))
        assertTrue(source.contains("cancelPendingFeedbackTerminalResolution()"))
    }

    @Test
    fun talkBackRiskHoldSuppressesRepeatsButAllowsSeverityEscalation() {
        val accessibility = source.substringAfter("private fun announceForTalkBack(")
            .substringBefore("private fun scheduleTalkBackInteraction")

        assertTrue(accessibility.contains("riskRank: Int? = null"))
        assertTrue(accessibility.contains("riskTrackId: String? = null"))
        assertTrue(accessibility.contains("rank > lastRiskAnnouncementRank"))
        assertTrue(accessibility.contains("activeRiskHeld && !preemptsActiveRisk"))
        assertTrue(accessibility.contains("utteranceTerminalTimeoutMs(message.length)"))
        assertTrue(accessibility.contains("if (nowMs < riskAnnouncementHoldUntilMs)"))
        assertTrue(source.contains("reconcileTalkBackRiskTracks(activeRiskTrackIds)"))
        assertTrue(source.contains("lastRiskAnnouncementTrackId in activeTrackIds"))
        assertTrue(source.contains("if (activeTrackIds.isNotEmpty()) return"))
        assertTrue(source.contains("pendingInteraction.run()"))
        assertTrue(source.contains("if (!isFeedbackLifecycleCurrent(feedbackGeneration)) {"))
    }

    @Test
    fun asynchronousFeedbackCannotCrossForegroundGeneration() {
        val lifecycle = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onDestroy()")
        val gatewayLogin = source.substringAfter("private fun onGatewaySessionButtonClicked()")
            .substringBefore("private fun clearGatewaySession(")
        val navigation = source
            .substringAfter("private fun speakNavigation(message: String, onCompleted: (() -> Unit)? = null)")
            .substringBefore("private fun speakInteraction(message: String)")
        val interaction = source.substringAfter("private fun speakInteraction(message: String)")
            .substringBefore("private fun isScreenReaderActive()")

        assertTrue(source.contains("private var feedbackLifecycleGeneration = 0"))
        assertTrue(lifecycle.contains("feedbackLifecycleGeneration += 1\n        isActivityForeground = true"))
        assertTrue(lifecycle.contains("isActivityForeground = false\n        feedbackLifecycleGeneration += 1"))
        assertTrue(gatewayLogin.contains("val feedbackGeneration = feedbackLifecycleGeneration"))
        assertTrue(gatewayLogin.contains("if (isFeedbackLifecycleCurrent(feedbackGeneration))"))
        assertTrue(navigation.contains("if (!isFeedbackLifecycleCurrent(generation)) return false"))
        assertTrue(navigation.contains("shouldSuppressFeedbackDuringVoiceRecognition"))
        assertTrue(navigation.contains("if (isFeedbackLifecycleCurrent(generation)) completion()"))
        assertTrue(interaction.contains("if (!isFeedbackLifecycleCurrent(generation)) return false"))
        assertTrue(interaction.contains("shouldSuppressFeedbackDuringVoiceRecognition"))
        val locationCallback = source.substringAfter(
            "override fun onLocationResult(result: LocationResult)",
        ).substringBefore("private fun freshTrustedLocationOrNull(")
        assertTrue(
            locationCallback.contains(
                "isLocationCallbackCurrent(walkEpoch, generation, callback)",
            ),
        )
        assertTrue(locationCallback.contains("locationCallback === callback"))
        assertTrue(locationCallback.contains("generation == locationCallbackGeneration"))
        assertTrue(locationCallback.contains("isRuntimeEpochCurrent(walkEpoch)"))
        assertTrue(locationCallback.contains("currentLocationCollectionAllowsWork()"))
    }

    @Test
    fun safetyCooldownsUseMonotonicTimeInsteadOfWallClock() {
        val drawFrame = source.substringAfter("override fun onDrawFrame(gl: GL10?)")
            .substringBefore("private fun buildContentView()")
        val routeGuidance = source.substringAfter("private fun updateRouteGuidance(location: TrustedLocation)")
            .substringBefore("private fun updateNavigationStatus(text: String)")
        val accessibility = source.substringAfter(
            "private fun announceForTalkBack(",
        )
            .substringBefore("private fun ensureFeedbackActuator()")

        assertTrue(drawFrame.contains("val elapsedRealtimeMs = SystemClock.elapsedRealtime()"))
        assertTrue(drawFrame.contains("nowMs = elapsedRealtimeMs"))
        assertTrue(routeGuidance.contains("val nowMs = SystemClock.elapsedRealtime()"))
        assertTrue(accessibility.contains("val nowMs = SystemClock.elapsedRealtime()"))
    }

    @Test
    fun explicitVoiceResponsesUseInteractionSpeechWhileAutomaticGuidanceUsesNavigationSpeech() {
        val voice = source.substringAfter("private fun handleVoiceCommandPhrases(")
            .substringBefore("private fun startLocationUpdatesIfAllowed")
        val route = source.substringAfter("private fun updateRouteGuidance(location: TrustedLocation)")
            .substringBefore("private fun updateNavigationStatus(text: String)")

        assertTrue(source.contains("private fun speakInteraction(message: String)"))
        assertTrue(voice.contains("speakInteraction(message)"))
        assertTrue(voice.contains("speakNavigation(message)"))
        assertTrue(voice.contains("routeNavigator.acknowledgeCurrentInstruction"))
        assertTrue(route.contains("speakNavigation(instruction)"))
        assertTrue(route.contains("routeNavigator.acknowledgeInstruction(update, SystemClock.elapsedRealtime())"))
    }

    @Test
    fun accountAndGatewayPromptsRemainAvailableOutsideAnActiveWalk() {
        val gatewayButton = source.substringAfter("private fun onGatewaySessionButtonClicked()")
            .substringBefore("private fun clearGatewaySession(")
        val reporterGate = source.substringAfter("private fun requireReporterUserId(")
            .substringBefore("private fun gatewaySessionOrNull(")
        val gatewayGate = source.substringAfter("private fun gatewaySessionOrNull(")
            .substringBefore("private fun ensureNavigationPermissions(")

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
        val cameraFallbackStart = source.substringAfter("private fun bindCameraFallbackSession()")
            .substringBefore("private fun stopCameraFallbackSession(")
        val cameraFallbackStop = source.substringAfter("private fun stopCameraFallbackSession(")
            .substringBefore("private fun analyzeCameraFallbackFrame(")
        assertTrue(cameraFallbackStart.contains("cameraFallbackRunning = true\n                    syncActiveSessionScreenPolicy()"))
        assertTrue(cameraFallbackStart.contains("cameraFallbackRunning = false"))
        assertTrue(
            cameraFallbackStart.contains(
                "continueAfterCameraFallbackFailure(",
            ),
        )
        assertFalse(cameraFallbackStart.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(cameraFallbackStop.contains("cameraFallbackRunning = false\n        syncActiveSessionScreenPolicy()"))
        assertTrue(source.contains("isActivityForeground = false\n        feedbackLifecycleGeneration += 1"))
        assertTrue(source.contains("feedbackPolicy.cancelPendingFeedbackDeliveries()\n        feedbackActuator?.close()"))
        assertTrue(source.contains("window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(source.contains("window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
    }

    @Test
    fun portraitOrientationAvoidsLosingInMemoryRouteAndGatewayStateOnRotation() {
        assertTrue(manifest.contains("android:screenOrientation=\"portrait\""))
    }

    @Test
    fun longControlPanelIsScrollableWithoutReplacingCameraPreview() {
        assertTrue(source.contains("controlsScroll = ScrollView(this).apply"))
        assertTrue(source.contains("addView(surfaceView, FrameLayout.LayoutParams"))
        assertTrue(source.contains("controlsScroll,\n                FrameLayout.LayoutParams"))
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
        assertTrue(source.contains("resources.displayMetrics.density"))
        assertFalse(source.contains("setPadding(32, 48, 32, 32)"))
    }
    @Test
    fun settingsSectionStaysHiddenUntilFirstRunOnboardingCompletes() {
        // 온보딩 중에는 설정·동의·계정 섹션을 접근성 트리에서 제거한다. 단계와 무관한
        // 컨트롤이 낭독 순서를 채우고, 안전 고지를 읽기 전에 동의 초안이 기록되는 것을 막는다.
        // 계정 삭제 복구 로그인 화면은 온보딩 완료 전에도 필요하므로 예외로 둔다.
        val update = functionBlock("private fun updateFirstRunOnboardingUi")

        assertTrue(update.contains("updatePrivacySectionVisibility()"))

        val visibility = functionBlock("private fun updatePrivacySectionVisibility")
        assertTrue(visibility.contains("privacySettingsControls.visibility"))
        assertTrue(visibility.contains("accountDeletionControls.visibility"))
        assertTrue(visibility.contains("gatewaySessionControls.visibility"))
        assertTrue(visibility.contains("firstRunOnboardingComplete()"))
        assertTrue(visibility.contains("accountDeletionRecoveryLoginRequired()"))
        assertTrue(visibility.contains("BuildConfig.DEBUG"))
        assertTrue(visibility.contains("View.GONE"))
        assertTrue(visibility.contains("privacySectionExpanded"))
        assertTrue(visibility.contains("deletionRecoverySurface"))
        assertTrue(visibility.contains("gatewaySessionControlsExpanded"))
        assertTrue(source.contains("label = \"설정과 개인정보 펼치기\""))
        assertTrue(source.contains("label = \"개발자용 Gateway 설정 펼치기\""))
    }

    @Test
    fun secondaryAndGatewayControlsUseExplicitCollapsedDisclosure() {
        val privacy = functionBlock("private fun updatePrivacySectionVisibility()")
        assertTrue(privacy.contains("privacyControls.visibility"))
        assertTrue(privacy.contains("privacySectionExpanded"))
        assertTrue(privacy.contains("gatewaySessionControls.visibility"))
        assertTrue(privacy.contains("gatewaySessionControlsExpanded"))
        assertTrue(privacy.contains("accountDeletionRecoveryLoginRequired()"))
        assertTrue(privacy.contains("contentDescription"))

        val overlay = sourceBlock(
            "val overlay = LinearLayout(this).apply",
            "walkSafetyOverlay = LinearLayout(this).apply",
        )
        val disclosure = overlay.indexOf("addView(privacySectionToggleButton)")
        val controls = overlay.indexOf("addView(privacyControls)")
        assertTrue(disclosure >= 0)
        assertTrue(controls >= 0)
        assertTrue(disclosure < controls)
    }

    @Test
    fun allInteractiveControlsReceiveReadableTextAndFortyEightDpTargets() {
        val defaults = functionBlock("private fun applyAccessibleControlDefaults(")
        assertTrue(defaults.contains("accessibilityTargetSizePx()"))
        assertTrue(defaults.contains("minimumHeight = maxOf"))
        assertTrue(defaults.contains("minimumWidth = maxOf"))
        assertTrue(source.contains("const val MIN_INTERACTIVE_TEXT_SP = 16f"))
        assertTrue(defaults.contains("TypedValue.applyDimension("))
        assertTrue(defaults.contains("if (root.textSize < minimumTextSizePx)"))
        assertTrue(defaults.contains("root is ViewGroup"))
        assertTrue(source.contains("applyAccessibleControlDefaults(overlay)"))
    }
    @Test
    fun controlsCarryTheMeasuredDesignTokensInsteadOfPlatformDefaults() {
        // 측정된 대비값을 코드 상수로 고정한다. 흰 글자/회색 면 6.97:1, 테두리 4.08:1.
        assertTrue(source.contains("WS_COLOR_BUTTON_FILL"))
        assertTrue(source.contains("WS_COLOR_BUTTON_TEXT"))
        assertTrue(source.contains("WS_COLOR_LINE"))
        assertTrue(source.contains("WS_CORNER_RADIUS_DP"))
        assertTrue(source.contains("WS_CONTROL_GAP_DP"))

        val factory = source.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("productPurposeText =")
        assertTrue(factory.contains("backgroundTintList = ColorStateList("))
        assertTrue(factory.contains("android.R.attr.state_enabled"))
        assertTrue(factory.contains("android.R.attr.state_pressed"))
        assertTrue(factory.contains("android.R.attr.state_focused"))
        assertTrue(factory.contains("bottomMargin"))
        // 기존 접근성 계약은 그대로 유지한다.
        assertTrue(factory.contains("setSingleLine(false)"))
        assertTrue(factory.contains("minimumHeight = (48f * resources.displayMetrics.density).roundToInt()"))
    }
    @Test
    fun onboardingShowsStepProgressAsDecorationAndStatusTextAsTitle() {
        // 진행 표시줄은 시각 정보만 제공한다. 같은 내용을 heading 문장이 이미 낭독하므로
        // 접근성 트리에서 제외해 중복 낭독을 막는다.
        assertTrue(source.contains("firstRunProgressBar"))
        assertTrue(source.contains("firstRunProgressSegments"))

        val bar = sourceBlock(
            "firstRunProgressBar = LinearLayout(this)",
            "firstRunOnboardingStatusText = TextView(this)",
        )
        assertTrue(bar.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO"))

        // 큰 글꼴에서도 현재 행동이 첫 화면에 남는 제목 크기를 쓴다.
        val title = sourceBlock(
            "firstRunOnboardingStatusText = TextView(this).apply",
            "ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText",
        )
        assertTrue(title.contains("Typeface.NORMAL"))
        assertTrue(title.contains("textSize = 20f"))
    }
    @Test
    fun acknowledgedSafetyNoticeCollapsesToACompactDisclosure() {
        // 최초 안전 확인 뒤에는 카드 전문을 숨기고 한 개의 명시적인
        // 펼침 동작만 남겨 1.5배 글꼴에서도 현재 단계와 로그인을 먼저 볼 수 있게 한다.
        assertTrue(source.contains("WS_COLOR_NOTICE_TEXT"))
        assertTrue(source.contains("WS_COLOR_NOTICE_FILL"))

        val notice = sourceBlock(
            "productPurposeText = TextView(this).apply",
            "firstRunProgressSegments.clear()",
        )
        assertTrue(notice.contains("GradientDrawable()"))
        assertTrue(notice.contains("WS_COLOR_NOTICE_TEXT"))
        assertFalse(notice.contains("setTextColor(0xffffffff.toInt())"))

        val update = functionBlock("private fun refreshFirstRunNoticeUi")
        assertTrue(update.contains("productPurposeText.visibility"))
        assertTrue(update.contains("if (expanded) View.VISIBLE else View.GONE"))
        assertTrue(
            update.contains(
                "안전 보장·보조수단 대체 아님 · 자세히 보기",
            ),
        )
        assertFalse(update.contains("신고는 자동 아님"))
    }
    @Test
    fun safetyNoticeCollapsesOnlyAfterTheUserAcknowledgesIt() {
        // 1단계에서는 전문이 펼쳐진 채 확인 버튼이 그 아래에 온다. 순서 자체가 게이트이므로
        // 스크롤 위치 같은 시각 전용 조건을 걸지 않는다. 확인 뒤에는 접히고, 접힌 줄은
        // 정책 상수 WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO 를 그대로 발췌해 새 문구를 만들지 않는다.
        assertTrue(source.contains("firstRunNoticeToggleButton"))
        assertTrue(source.contains("WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO"))

        val update = functionBlock("private fun refreshFirstRunNoticeUi")
        assertTrue(update.contains("FirstRunOnboardingStage.PURPOSE_AND_SAFETY"))
        assertTrue(update.contains("WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO"))
        assertTrue(update.contains("contentDescription"))
        assertFalse(update.contains("scrollY"))
        assertFalse(update.contains("canScrollVertically"))
    }

    @Test
    fun dynamicDestinationResultsKeepReadableTextAndTouchTargets() {
        val update = functionBlock("private fun updateDestinationSearchUi")
        assertTrue(update.contains("textSize = MIN_INTERACTIVE_TEXT_SP"))
        assertTrue(update.contains("minimumHeight = accessibilityTargetSizePx()"))
        assertTrue(update.contains("minimumWidth = accessibilityTargetSizePx()"))
        assertFalse(update.contains("textSize = 12f"))
        assertFalse(update.contains("textSize = 11f"))
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "missing: $marker" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "missing function body: $marker" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated: $marker")
    }

    private fun sourceBlock(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return source.substring(start, end)
    }
    @Test
    fun stageHeadingRendersEyebrowAndTitleWithoutSplittingTheAnnouncement() {
        // "첫 실행 N단계." 는 작고 흐리게, 나머지는 크고 굵게. 한 TextView 안에서
        // Spannable 로 처리하므로 text 와 contentDescription 은 전체 문장 그대로 남고
        // 접근성 heading 계약도 유지된다.
        val update = functionBlock("private fun applyStageHeadingStyle")
        assertTrue(update.contains("SpannableString"))
        assertTrue(update.contains("RelativeSizeSpan"))
        assertTrue(update.contains("StyleSpan"))
        assertTrue(update.contains("ForegroundColorSpan"))

        val caller = functionBlock("private fun updateFirstRunOnboardingUi")
        assertTrue(caller.contains("firstRunOnboardingStatusText.text = message"))
        assertTrue(caller.contains("firstRunOnboardingStatusText.contentDescription = message"))
        assertTrue(caller.contains("applyStageHeadingStyle("))
    }

    @Test
    fun stageHeadingsUseShortActionLanguageForLargeFonts() {
        val update = functionBlock("private fun updateFirstRunOnboardingUi")
        assertTrue(update.contains("로그인하거나 새 계정을 만드세요."))
        assertTrue(update.contains("인증번호를 입력해 계정을 만드세요."))
        assertTrue(update.contains("기기 기능을 점검하세요."))
        assertFalse(
            update.contains("이메일과 생년월일로 가입하거나 기존 계정으로 로그인하세요."),
        )
    }

    @Test
    fun completeHeadingKeepsFeaturesLockedUntilRuntimeSafetyGatesPass() {
        val update = functionBlock("private fun updateFirstRunOnboardingUi")
        val complete = update.substring(
            update.indexOf("FirstRunOnboardingStage.COMPLETE ->").also {
                require(it >= 0) { "missing COMPLETE heading branch" }
            },
            update.indexOf("FirstRunOnboardingStage.BLOCKED_UNDER_14 ->").also {
                require(it >= 0) { "missing heading branch after COMPLETE" }
            },
        )
        assertTrue(complete.contains("firstRunOnboardingComplete()"))
        assertTrue(complete.contains("기능은 잠겨 있습니다"))
        assertTrue(complete.contains("권한·안전 상태"))
    }
    @Test
    fun primaryActionIsVisuallySeparatedFromSecondaryChoices() {
        // 각 단계의 주 행동만 56dp 와 강조 테두리를 갖는다. 연령 3개처럼 대등한 선택은
        // 어느 하나를 권장처럼 보이게 하면 안 되므로 모두 보조로 둔다.
        assertTrue(source.contains("WS_TOUCH_PRIMARY_DP"))
        assertTrue(source.contains("WS_COLOR_EMPHASIS"))

        val factory = source.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("firstRunNoticeToggleButton =")
        assertTrue(factory.contains("emphasis: Boolean"))
        // 기존 접근성 계약 유지
        assertTrue(factory.contains("minimumHeight = (48f * resources.displayMetrics.density).roundToInt()"))
        assertTrue(factory.contains("setSingleLine(false)"))

        assertTrue(source.contains("label = \"목적과 안전 한계 확인\",\n            emphasis = true,"))
        assertTrue(
            source.contains("label = \"네 가지 선택을 서버에 저장하고 확인\",\n            emphasis = true,"),
        )
    }
    @Test
    fun buttonLabelsStayShortOnScreenWhileTalkBackKeepsTheContext() {
        // 화면에는 "만 18세 이상", TalkBack 에는 "가입 연령: 만 18세 이상".
        // 눈으로 보는 라벨은 짧게, 낭독 맥락은 유지한다.
        val factory = source.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("firstRunNoticeToggleButton =")
        assertTrue(factory.contains("spokenLabel"))

        val ageBlock = source.substringAfter("firstRunAgeButtons.clear()")
            .substringBefore("firstRunIntegratedConsentDisclosureText =")
        assertTrue(ageBlock.contains("spokenLabel = \"가입 연령: "))
        assertFalse(ageBlock.contains("label = \"가입 연령: "))
    }

    @Test
    fun spacingSeparatesGroupsInsteadOfBeingUniform() {
        // 24dp 일괄이 아니라, 섹션 경계는 넓게 같은 그룹의 버튼 사이는 좁게 둔다.
        assertTrue(source.contains("WS_GROUP_GAP_DP"))
        assertTrue(source.contains("WS_TITLE_GAP_DP"))
        val factory = source.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("firstRunNoticeToggleButton =")
        assertTrue(factory.contains("WS_GROUP_GAP_DP"))
        assertFalse(factory.contains("bottomMargin = (WS_CONTROL_GAP_DP"))
    }
    @Test
    fun consentItemsRenderInsideCards() {
        // 항목마다 카드로 묶어 시각적으로 분리한다. 버튼 표시 텍스트는 기존 계약대로
        // "$label: $syncState" 를 유지하므로 상태는 카드 헤더로 옮기지 않는다.
        assertTrue(source.contains("firstRunConsentCards"))

        val build = source.substringAfter("firstRunIntegratedConsentButtons.clear()")
            .substringBefore("firstRunIntegratedConsentSaveButton =")
        assertTrue(build.contains("GradientDrawable()"))
        assertTrue(build.contains("WS_COLOR_LINE"))
        assertTrue(build.contains("firstRunConsentCards[item]"))
    }
    @Test
    fun onboardingCardsCarryTheirOwnHeadingAndCount() {
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

        assertTrue(source.contains("firstRunConsentCountText"))
    }

    @Test
    fun consentTraversalIncludesToggleDisclosureCountClauseAndAction() {
        assertTrue(source.contains("firstRunConsentClauseTexts"))
        val traversal = functionBlock("private fun linkFirstRunAccessibilityTraversal")
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

        val clause = source.substringAfter("firstRunConsentClauseTexts[item] = TextView")
            .substringBefore("addView(firstRunConsentClauseTexts.getValue(item))")
        assertTrue(clause.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
    }

    @Test
    fun expandedNoticesReceiveAccessibilityFocus() {
        val build = source.substringAfter("firstRunNoticeToggleButton =")
            .substringBefore("firstRunOnboardingControls =")
        assertTrue(build.contains("productPurposeText.requestFocus()"))
        assertTrue(build.contains("firstRunIntegratedConsentDisclosureText.requestFocus()"))
        assertTrue(build.contains("ACTION_ACCESSIBILITY_FOCUS"))
    }

    @Test
    fun onboardingBodyAndButtonsMeetLowVisionTextBaseline() {
        val build = source.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("firstRunOnboardingControls =")
        assertFalse(build.contains("textSize = 16f"))
        assertFalse(build.contains("textSize = 17f"))
        assertTrue(build.contains("textSize = 18f"))
    }

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
        }
    }
}
