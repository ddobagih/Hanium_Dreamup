package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserOnboardingStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val feedback =
        File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
        ).readText()
    private val readiness =
        File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
        ).readText()

    @Test
    fun onboardingIsRequiredAtBothStartAndRuntimeReadinessBoundaries() {
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")
        val capture = functionBlock("private fun captureWalkSessionReadiness(")

        assertTrue(
            readiness.contains(
                "add(WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING)",
            ),
        )
        assertTrue(
            capture.contains(
                "WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING",
            ),
        )
        assertTrue(capture.contains("priorityUserOnboardingPolicy.evaluate("))
        assertTrue(source.contains("get() = BuildConfig.DEBUG && BuildConfig.DEVELOPMENT_QUICK_START"))
        assertTrue(blockReason.contains("if (!firstRunOnboardingComplete())"))
        assertTrue(capture.contains("currentReporterUserId() != null"))
        assertTrue(capture.contains("account_profile_unbound"))
        assertTrue(blockReason.contains("if (!developmentQuickStartEnabled && !onboarding.mayStartWalk) return onboarding.noticeKo"))
        assertTrue(
            blockReason.indexOf("priorityUserOnboardingPolicy.evaluate(") <
                blockReason.indexOf("currentReporterUserId()"),
        )
    }

    @Test
    fun accountProfileIsLoadedBeforeRestoredCredentialPublishAndEligibilityIsRechecked() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")

        assertInOrder(
            create,
            "restorePermissionSessionStateFromPrefs()",
            "restorePriorityUserOnboardingFromPrefs()",
            "GatewaySessionProcessCoordinator.attach(",
            "enforcePriorityUserAccountEligibility()",
            "scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()",
        )
        val restore = functionBlock("private fun restoreGatewaySessionFromPrefs()")
        assertInOrder(
            restore,
            "val actorId = restored.firstRunSnapshot.reporterActorBinding?.value",
            "actorId == null || actorId != restored.session.actorId",
            "restorePriorityUserOnboardingFromPrefs()",
            "priorityUserOnboardingActorId != actorId",
            "GatewaySessionProcessCoordinator.publishRestoredUnverified(",
        )
        assertTrue(source.contains("PREF_PRIORITY_USER_PROFILE_PREFIX"))
        assertTrue(source.contains("priorityUserActorSha256(actorId)"))
        assertTrue(source.contains("\"actor_id_sha256\""))
        assertTrue(source.contains("PRIORITY_USER_PROFILE_STORAGE_VERSION"))
        assertTrue(
            source.contains(
                "PriorityUserPractice.entries.take(completedPractices.size)",
            ),
        )
        assertTrue(source.contains("sensitivePrefs.edit().remove(profileKey).commit()"))
    }

    @Test
    fun startupEligibilityCannotRevokeAnUnboundOrRestoringSession() {
        val eligibility = functionBlock("private fun enforcePriorityUserAccountEligibility()")
        assertInOrder(eligibility,
            "val session = process.session ?: return",
            "reporterUserId != session.actorId",
            "process.inFlightOperationId != null",
            "process.storageBlocked",
            "process.deletionRecoveryOnly",
            "session.sessionScope != GatewaySessionScope.GENERAL",
            "session.verificationState != GatewaySessionVerificationState.VERIFIED",
            "priorityUserOnboardingPolicy.accountBlockReason()",
            "clearGatewaySession(",
            "expectedSession = session",
            "expectedProcessGeneration = process.generation",
        )
        val observer = functionBlock("private fun onGatewayProcessSessionChanged(")
        assertInOrder(observer,
            "reporterUserId = actorId",
            "restorePriorityUserOnboardingFromPrefs()",
            "if (verifiedActorSession != null)",
            "enforcePriorityUserAccountEligibility()",
        )
    }

    @Test
    fun profileMutationIsDurablyInvalidatedBeforePolicyStateChanges() {
        val age = functionBlock("private fun selectPriorityUserAgeBand(")
        val education = functionBlock("private fun reviewPriorityUserSafetyEducation()")
        val safePlace = functionBlock("private fun confirmPriorityUserSafePracticePlace()")
        val delivery = functionBlock("private fun recordPriorityUserPracticeDelivery(")
        val reset = functionBlock("private fun resetPriorityUserTraining()")
        val persist = functionBlock("private fun persistPriorityUserOnboarding()")
        val begin = functionBlock(
            "private fun beginPriorityUserProfileMutationOrFailClosed()",
        )
        val restore = functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")

        assertInOrder(
            age,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "priorityUserOnboardingPolicy.selectAgeBand(ageBand)",
        )
        assertInOrder(
            education,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "policy.reviewSafetyEducation(",
        )
        assertInOrder(
            safePlace,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "priorityUserOnboardingPolicy.confirmSafePracticePlace()",
        )
        assertInOrder(
            delivery,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "policy.recordPracticeDelivery(token, signal)",
        )
        assertInOrder(
            reset,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "priorityUserOnboardingPolicy.resetTraining()",
        )
        assertInOrder(
            persist,
            ".putString(priorityUserProfileKey(actorId), profile.toString())",
            ".remove(invalidKey)",
            ".commit()",
        )
        assertTrue(restore.contains("priorityUserProfileInvalidKey(actorId)"))
        assertTrue(restore.contains("if (profileInvalid)"))
        assertTrue(source.contains("PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX"))
        assertTrue(source.contains("priorityUserStorageBlockedActorHashes"))
        assertInOrder(begin, ".putBoolean(invalidKey, true)", ".commit()")
        assertFalse(begin.contains("alreadyInvalid"))
        assertFalse(reset.contains(".remove(priorityUserProfileKey(actorId))"))
    }

    @Test
    fun fp004TrainingCannotStartOrRemainInFlightBehindAClosedDeviceGate() {
        val update = functionBlock("private fun updatePriorityUserOnboardingUi(")
        val education = functionBlock("private fun reviewPriorityUserSafetyEducation()")
        val safePlace = functionBlock("private fun confirmPriorityUserSafePracticePlace()")
        val practice = functionBlock("private fun performPriorityUserPractice(")
        val delivery = functionBlock("private fun recordPriorityUserPracticeDelivery(")
        val failure = functionBlock("private fun failPriorityUserTrainingDelivery(")
        val cleanup = functionBlock("private fun clearPriorityUserTrainingDeliveryIfOwned(")
        val current = functionBlock("private fun isPriorityUserTrainingDeliveryCurrent(")

        assertTrue(update.contains("val deviceGateOpen = postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(update.contains("accountBound && deviceGateOpen && decision.mayActivateAccount"))
        assertTrue(education.contains("if (!requireDeviceCheckForPriorityUserTraining()) return"))
        assertTrue(safePlace.contains("if (!requireDeviceCheckForPriorityUserTraining()) return"))
        assertTrue(practice.contains("if (!requireDeviceCheckForPriorityUserTraining()) return"))
        assertTrue(delivery.contains("clearPriorityUserTrainingDeliveryIfOwned("))
        assertTrue(failure.contains("clearPriorityUserTrainingDeliveryIfOwned("))
        assertTrue(cleanup.contains("priorityUserEducationInFlight = false"))
        assertTrue(cleanup.contains("priorityUserPracticeInFlight = null"))
        assertTrue(cleanup.contains("policy.cancelPractice(token)"))
        assertTrue(cleanup.contains("cancelPriorityUserTrainingFeedback()"))
        assertTrue(current.contains("postLoginDeviceCheckPassesFeatureGate()"))
    }

    @Test
    fun educationRequiresPlaybackWhilePracticeKeepsItsAvailableChannels() {
        val update = functionBlock("private fun updatePriorityUserOnboardingUi(")
        val practiceButtons = update.substringAfter(
            "priorityUserPracticeButtons.forEach",
        ).substringBefore("priorityUserResetButton")
        val education = functionBlock("private fun reviewPriorityUserSafetyEducation()")
        val practice = functionBlock("private fun performPriorityUserPractice(")
        val hapticDelivery = practice.substringAfter("if (hapticFeedbackEnabled) {")
            .substringBefore("if (voiceGuidanceEnabled) {")
        val speechDelivery = practice.substringAfter("if (voiceGuidanceEnabled) {")

        assertFalse(practiceButtons.contains("environment.offlineKoreanVoiceAvailable"))
        assertFalse(practiceButtons.contains("environment.vibrationAvailable"))
        assertFalse(education.contains("if (!voiceGuidanceEnabled)"))
        assertFalse(education.contains("completed()"))
        assertTrue(education.contains("onCompleted = completed"))
        assertTrue(education.contains("onFailed = failed"))
        assertTrue(education.contains("speakPriorityUserTraining("))
        val native = functionBlock("private fun playNativeSafetyEducation(")
        assertTrue(native.contains("onCompleted = {"))
        assertTrue(native.contains("isPriorityUserTrainingDeliveryCurrent(generation, actorId, policy)"))
        assertTrue(native.contains("voicePlaybackCompleted = true"))
        assertTrue(
            practice.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)",
            ),
        )
        assertTrue(
            practice.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK)",
            ),
        )
        assertTrue(hapticDelivery.contains("playPriorityUserTrainingVibration("))
        assertFalse(hapticDelivery.contains("} else {"))
        assertTrue(speechDelivery.contains("speakPriorityUserTraining("))
        assertFalse(speechDelivery.contains("} else {"))
        assertTrue(practice.contains("if (voiceGuidanceEnabled) add(PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED)"))
        assertTrue(practice.contains("if (hapticFeedbackEnabled) add(PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED)"))
        assertTrue(practice.contains("requiredDeliverySignals.isEmpty()"))
        assertTrue(practice.contains("policy.beginPractice(practice, requiredDeliverySignals)"))
    }

    @Test
    fun centralActuatorMapsFeatureRestrictionsToOnlyTheirOwnOutputChannels() {
        val restrictions = functionBlock("private fun applyPostLoginDeviceFeatureRestrictions(")
        val resolver = functionBlock("private fun resolveCurrentStartupCapabilityDecision(")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val actuator = functionBlock("private fun ensureFeedbackActuator()")
        val speechAllowed = actuator.substringAfter("speechAllowed = {")
            .substringBefore("hapticAllowed = {")
        val hapticAllowed = actuator.substringAfter("hapticAllowed = {")
        val unavailableSpeech = actuator.substringAfter(
            "onOfflineKoreanSpeechUnavailable = {",
        ).substringBefore("speechAllowed = {")

        assertTrue(
            restrictions.contains(
                "PostLoginDeviceCheckFeature.VOICE_GUIDANCE ->\n" +
                    "                        add(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)",
            ),
        )
        assertTrue(
            restrictions.contains(
                "PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK ->\n" +
                    "                        add(WalkSafeStartupRequirement.VIBRATION)",
            ),
        )
        assertInOrder(
            resolver,
            "applyPostLoginDeviceFeatureRestrictions(",
            "startupCapabilityProbe.decision(",
        )
        assertInOrder(
            refresh,
            "resolveCurrentStartupCapabilityDecision()",
            "startupCapabilityDecision = decision",
        )
        assertTrue(speechAllowed.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
        assertFalse(speechAllowed.contains("WalkSafeStartupRequirement.VIBRATION"))
        assertTrue(hapticAllowed.contains("WalkSafeStartupRequirement.VIBRATION"))
        assertFalse(hapticAllowed.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
        assertTrue(unavailableSpeech.contains("PostLoginDeviceCheckFeature.VOICE_GUIDANCE"))
        assertTrue(
            unavailableSpeech.indexOf("PostLoginDeviceCheckFeature.VOICE_GUIDANCE") <
                unavailableSpeech.indexOf("handleRuntimeSpeechCapabilityFailure("),
        )
    }

    @Test
    fun switchingActorLoadsItsProfileBeforeCheckingActivationRules() {
        val bindActor = functionBlock("private fun bindFirstRunVerifiedActorForTraining()")
        val reporter = functionBlock("private fun currentReporterUserId()")

        assertInOrder(
            bindActor,
            "reporterUserId = actorId",
            "if (!permissionSessionPolicy.isAuthenticatedFor(actorId))",
            "permissionSessionPolicy.rememberActor(actorId)",
            "restorePriorityUserOnboardingFromPrefs()",
        )
        assertTrue(bindActor.contains("firstRunOnboardingSnapshot.verifiedActorBinding?.value"))
        assertTrue(bindActor.contains("clearGatewaySession(logoutRemote = true)"))
        assertTrue(reporter.contains("priorityUserOnboardingPolicy.accountBlockReason() == null"))
        assertTrue(source.contains("priorityUserOnboardingActorId == session.actorId"))
        val gatewayLogin = functionBlock("private fun onGatewaySessionButtonClicked()")
        assertInOrder(
            gatewayLogin,
            "priorityUserOnboardingActorId == session.actorId",
            "commitLoggedInGatewaySession(",
        )
        val gatewayCommit = functionBlock(
            "private fun commitLoggedInGatewaySession(",
        )
        assertInOrder(
            gatewayCommit,
            "priorityUserOnboardingActorId != session.actorId",
            "gatewaySessionStore.saveInitialIfAbsent(",
        )
    }

    @Test
    fun logoutDetachesMemoryButKeepsTheAccountScopedProfile() {
        val logout = functionBlock("private fun onAccountLogoutClicked()")

        assertTrue(logout.contains("priorityUserOnboardingActorId = null"))
        assertTrue(logout.contains("priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()"))
        assertTrue(logout.contains("priority_user_account_logged_out"))
        assertTrue(logout.contains("refreshStartupCapabilityUi()"))
        assertFalse(logout.contains("priorityUserProfileKey("))
    }

    @Test
    fun unownedLegacyTrainingIsPurgedInsteadOfClaimedByAnAccount() {
        val purge = functionBlock("private fun purgeUnownedLegacyPriorityUserOnboardingPrefs()")

        assertTrue(purge.contains("LEGACY_PREF_PRIORITY_USER_POLICY_VERSION"))
        assertTrue(purge.contains("LEGACY_PREF_PRIORITY_USER_GUARDIAN_VERIFIED"))
        assertTrue(purge.contains("LEGACY_PREF_PRIORITY_USER_COMPLETED_PRACTICES"))
        assertTrue(purge.contains(".commit()"))
        assertFalse(
            functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")
                .contains("LEGACY_PREF_"),
        )
    }

    @Test
    fun practiceCompletionUsesOnlyTrackedTerminalCallbacks() {
        val practice = functionBlock("private fun performPriorityUserPractice(")
        val terminal = functionBlock("private fun recordPriorityUserPracticeDelivery(")

        assertTrue(practice.contains("policy.beginPractice(practice, requiredDeliverySignals)"))
        assertTrue(practice.contains("playPriorityUserTrainingVibration("))
        assertTrue(practice.contains("speakPriorityUserTraining("))
        assertTrue(
            practice.contains(
                "PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED",
            ),
        )
        assertTrue(
            practice.contains(
                "PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED",
            ),
        )
        assertFalse(practice.contains("speakInteraction(practice.instructionKo)"))
        assertFalse(practice.contains("persistPriorityUserOnboardingOrFailClosed()"))
        assertTrue(terminal.contains("policy.recordPracticeDelivery(token, signal)"))
        assertTrue(terminal.contains("persistPriorityUserOnboardingOrFailClosed()"))
    }

    @Test
    fun strictTrainingSpeechCannotInferSuccessFromDispatchOrAFollowingUtterance() {
        val strictSpeech = feedback.substringAfter("fun speakPriorityUserTraining(")
            .substringBefore("/** Queues a low-priority camera advisory")
        val listener = feedback.substringAfter("object : UtteranceProgressListener()")
            .substringBefore("ready.set(true)")
        val inference = feedback.substringAfter("private fun armUtteranceTerminalWatchdog(")
            .substringBefore("private fun scheduleUtteranceWatchdogLocked")

        assertTrue(strictSpeech.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(listener.contains("override fun onDone(utteranceId: String?)"))
        assertTrue(listener.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(listener.contains("onError"))
        assertTrue(listener.contains("onStop"))
        assertTrue(inference.contains("explicitTerminalRequiredUtterances.remove(predecessor)"))
        assertTrue(inference.contains("completed = false"))
        assertFalse(inference.contains("completed = true"))
        assertFalse(inference.contains("completed = !explicitTerminalRequired"))
    }

    @Test
    fun trainingVibrationWaitsForTheWholeNonRepeatingPatternWindow() {
        val vibration = feedback.substringAfter("fun playPriorityUserTrainingVibration(")
            .substringBefore("/** Gives a spoken accessibility service")

        assertTrue(vibration.contains("longArrayOf(0L, 140L, 90L, 140L)"))
        assertTrue(vibration.contains("mainHandler.postDelayed("))
        assertTrue(vibration.contains("PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS"))
        assertTrue(feedback.contains("const val PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS = 370L"))
        assertTrue(feedback.contains("cancelPriorityUserTrainingVibration(notifyFailure = true)"))
        assertTrue(
            functionBlock("internal fun pauseWalkSafeRuntime()")
                .contains("cancelPendingPriorityUserTrainingFeedback()"),
        )
    }

    @Test
    fun ageBoundaryBlocksLocalAccountActivationWithoutFakeGuardianApproval() {
        val persistAccount = functionBlock("private fun persistReporterUserFromInput()")
        val reporter = functionBlock("private fun currentReporterUserId()")

        assertTrue(persistAccount.contains("manual_reporter_id_disallowed"))
        assertFalse(persistAccount.contains("reporterUserId ="))
        assertTrue(reporter.contains("priorityUserOnboardingPolicy.accountBlockReason() == null"))
        assertTrue(source.contains("PriorityUserAgeBand.UNDER_14"))
        assertTrue(source.contains("PriorityUserAgeBand.AGE_14_TO_17"))
        assertTrue(source.contains("PriorityUserAgeBand.ADULT_18_PLUS"))
        assertFalse(source.contains("recordGuardianVerification("))
    }

    @Test
    fun restoredTrainingNeverRestoresThePreviousWalkOrRoute() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val restore = functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")

        assertTrue(create.contains("restorePriorityUserOnboardingFromPrefs()"))
        assertTrue(create.contains("walkSessionLifecycle = WalkSessionLifecycle()"))
        assertTrue(create.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(restore.contains("PriorityUserOnboardingSnapshot("))
        assertFalse(restore.contains("WalkSessionLifecycle"))
        assertFalse(restore.contains("RouteNavigator"))
    }

    @Test
    fun onboardingUsesFlexibleTextHighContrastAndExplicitFocusOrder() {
        val content = functionBlock("private fun buildContentView()")
        val factory = content.substringAfter("fun accessiblePriorityUserButton(")
            .substringBefore("productPurposeText =")
        val traversal = functionBlock("private fun linkPriorityUserAccessibilityTraversal()")
        val update = functionBlock("private fun updatePriorityUserOnboardingUi(")

        assertTrue(factory.contains("setSingleLine(false)"))
        assertTrue(factory.contains("ellipsize = null"))
        assertFalse(factory.contains("maxLines ="))
        assertTrue(factory.contains("minimumHeight = (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()"))
        assertTrue(factory.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
        assertTrue(content.contains("ViewCompat.setAccessibilityHeading("))
        assertTrue(traversal.contains("accessibilityTraversalAfter = previous.id"))
        assertTrue(traversal.contains("nextFocusForwardId = current.id"))
        assertTrue(traversal.contains("nextFocusDownId = current.id"))
        assertTrue(traversal.contains("nextFocusUpId = previous.id"))
        assertTrue(update.contains("if (environment.highContrastEnabled) 2f else 1f"))
        assertTrue(
            update.contains(
                "if (environment.highContrastEnabled) WS_COLOR_BUTTON_BORDER else WS_COLOR_LINE",
            ),
        )
        assertTrue(update.contains("priorityUserOnboardingStatusText.contentDescription = message"))
        assertTrue(update.contains("button.contentDescription = button.text"))
        assertFalse(content.contains("setOnTouchListener"))
        assertFalse(content.contains("GestureDetector"))
    }

    @Test
    fun nativeEducationKeepsPostureListeningAndConsentInAccessibleOrder() {
        val content = functionBlock("private fun buildContentView()")
        val controls = content.substringAfter("priorityUserOnboardingControls =").substringBefore("linkPriorityUserAccessibilityTraversal()")
        assertInOrder(controls, "addView(priorityUserOnboardingStatusText)", "addView(priorityUserEducationProgressText)", "addView(priorityUserEducationBodyText)", "addView(priorityUserEducationButton)", "addView(firstRunPhonePostureControls)", "addView(priorityUserPracticeNecessityButton)", "addView(priorityUserSafePlaceButton)", "PriorityUserPractice.entries.forEach { addView(priorityUserPracticeButtons.getValue(it)) }", "addView(priorityUserEducationAgreeButton)")
        assertFalse(controls.contains("addView(loginUserIdInput)"))
        val update = functionBlock("private fun updateNativeSafetyEducationUi()")
        assertTrue(update.contains("val heading = if (showPosture) \"사전 연습\" else \"안전 교육\""))
        assertTrue(update.contains("priorityUserEducationBodyText.accessibilityTraversalAfter = priorityUserEducationProgressText.id"))
        assertTrue(update.contains("priorityUserEducationBodyText.visibility = if (showEducation) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("firstRunPhonePostureButton.accessibilityTraversalAfter = priorityUserEducationButton.id"))
        assertTrue(update.contains("priorityUserPracticeNecessityButton.accessibilityTraversalAfter = priorityUserEducationButton.id"))
        assertTrue(update.contains("priorityUserEducationAgreeButton.accessibilityTraversalAfter ="))
        assertTrue(update.contains("if (showPosture) previousPracticeControl.id else priorityUserPracticeNecessityButton.id"))
        assertTrue(update.contains("if (showPosture) priorityUserEducationProgressText.id else priorityUserEducationBodyText.id"))
        assertTrue(update.contains("state.usageConditionsAcknowledged && state.appUsageReviewed && state.interactivePracticeComplete"))
        val safetyStep = source.substringAfter("private fun shouldShowFirstRunEducation()")
            .substringBefore("private fun ")
        val usageStep = source.substringAfter("private fun shouldShowFirstRunPhonePosture()")
            .substringBefore("private fun ")
        assertTrue(safetyStep.contains("!priorityUserOnboardingPolicy.snapshot().safetyEducationConsentComplete"))
        assertTrue(usageStep.contains("priorityUserOnboardingPolicy.snapshot().safetyEducationConsentComplete"))
        val playback = functionBlock("private fun playNativeSafetyEducation(")
        assertTrue(update.contains("val educationPlaying = priorityUserEducationInFlight && !priorityUserEducationPlaybackIsPracticeNecessity"))
        assertTrue(update.contains("val necessityPlaying = priorityUserEducationInFlight && priorityUserEducationPlaybackIsPracticeNecessity"))
        assertTrue(
            update.contains(
                "educationPlaying -> if (showPosture) \"사전 연습 듣기 중지\" else \"1. 안전 제한 안내 듣기 중지\"",
            ),
        )
        assertTrue(update.contains("necessityPlaying -> \"2. 연습 필요성 듣기 중지\""))
        assertTrue(playback.contains("if (practiceNecessity == priorityUserEducationPlaybackIsPracticeNecessity)"))
        assertTrue(playback.contains("cancelPendingPriorityUserTrainingFeedback()"))
        assertTrue(playback.contains("이미 완료한 청취 기록은 유지됩니다."))
        val completion = playback.substringAfter("onCompleted = {").substringBefore("onFailed = failed")
        assertInOrder(completion, "isPriorityUserTrainingDeliveryCurrent",
            "beginPriorityUserProfileMutationOrFailClosed",
            "policy.finishAppUsageEducationPlayback(usageToken, completed = true)",
            "persistPriorityUserOnboardingOrFailClosed")
        assertTrue(update.contains("state.educationReviewed && state.practiceNecessityReviewed"))
        assertTrue(content.contains("onClick = ::acceptNativeSafetyEducation"))
    }

    @Test
    fun nativeEducationDisplaysSharedSpeechAndProgressWithoutTruncatingLargeText() {
        val update = functionBlock("private fun updateNativeSafetyEducationUi()")
        val playback = functionBlock("private fun playNativeSafetyEducation(")
        val body = source.substringAfter("priorityUserEducationBodyText = TextView(this).apply {")
            .substringBefore("priorityUserAgeButtons.clear()")
        assertTrue(update.contains("PriorityUserEducationPresentation.screenText(state)"))
        assertTrue(update.contains("PriorityUserEducationPresentation.progressNotice("))
        assertTrue(playback.contains("PriorityUserEducationPresentation.speechText(playback)"))
        assertTrue(playback.contains("AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS"))
        assertFalse(playback.contains("+ 8_000L"))
        assertTrue(body.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
        assertTrue(body.contains("textSize = 18f"))
        assertTrue(body.contains("setLineSpacing(0f, 1.4f)"))
        assertFalse(body.contains("maxLines"))
        assertFalse(body.contains("ellipsize"))
    }

    @Test
    fun hazardPracticeNeedsASeparateCurrentUserResponseAfterRealDelivery() {
        val practice = functionBlock("private fun performPriorityUserPractice(")
        val delivery = functionBlock("private fun recordPriorityUserPracticeDelivery(")
        val confirmation = functionBlock("private fun confirmPriorityUserHazardResponse()")
        val update = functionBlock("private fun updateNativeSafetyEducationUi()")
        val current = functionBlock("private fun isPriorityUserTrainingDeliveryCurrent(")
        assertTrue(practice.contains("confirmPriorityUserHazardResponse()"))
        assertFalse(delivery.contains("policy.confirmHazardResponse"))
        assertTrue(delivery.contains("if (policy.isHazardResponseReady(token)) updatePriorityUserOnboardingUi()"))
        assertTrue(confirmation.contains("!policy.isHazardResponseReady(token)"))
        assertInOrder(confirmation, "isPriorityUserTrainingDeliveryCurrent", "beginPriorityUserProfileMutationOrFailClosed", "policy.confirmHazardResponse(token)", "persistPriorityUserOnboardingOrFailClosed")
        assertTrue(current.contains("isActivityForeground &&"))
        assertTrue(update.contains("PriorityUserEducationPresentation.hazardResponseActionKo"))
        assertTrue(update.contains("state.requiresPracticeRestart"))
        assertTrue(update.contains("priorityUserPracticeInFlight != null -> \"이번 연습 중지\""))
        val stop = functionBlock("private fun reviewPriorityUserSafetyEducation()")
            .substringBefore("if (priorityUserEducationInFlight)")
        assertInOrder(stop, "priorityUserPracticeInFlight != null", "cancelPendingPriorityUserTrainingFeedback()")
        assertFalse(stop.contains("resetTraining"))
        assertFalse(practice.contains("startLocationUpdates"))
        assertFalse(practice.contains("beginNativeWalkFromHome"))
        assertFalse(practice.contains("startCamera"))
        listOf("private fun failPriorityUserTrainingDelivery(", "private fun clearPriorityUserTrainingDeliveryIfOwned(", "private fun cancelPendingPriorityUserTrainingFeedback()").forEach {
            assertTrue(functionBlock(it).contains("priorityUserPracticeAttemptToken = null"))
        }
    }

    @Test
    fun restoredEducationKeepsConsentAndReturnsOnlyMissingPracticeToTraining() {
        val restore = functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")
        val persist = functionBlock("private fun persistPriorityUserOnboarding()")
        val bind = functionBlock("private fun bindFirstRunVerifiedActorForTraining()")
        assertTrue(restore.contains("profile.optBoolean(\"hazard_response_confirmed\", false)"))
        assertTrue(persist.contains(".put(\"hazard_response_confirmed\", snapshot.hazardResponseConfirmed)"))
        assertTrue(bind.contains("hazardResponseConfirmed = compatible && restored.hazardResponseConfirmed"))
        assertTrue(bind.contains("educationReviewed = compatible && restored.educationReviewed"))
        assertTrue(bind.contains("appUsageAccepted = compatible && restored.appUsageAccepted"))
        assertTrue(bind.contains("FirstRunOnboardingPolicy.restartFp004Training(firstRunOnboardingSnapshot)"))
        assertFalse(bind.substringAfter("val restart =").contains("clearGatewaySession"))
        val accept = functionBlock("private fun acceptNativeSafetyEducation()")
        assertTrue(accept.contains("!state.interactivePracticeComplete"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }

    private fun assertInOrder(text: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = text.indexOf(snippet)
            assertTrue("missing or out of order: $snippet", current > previous)
            previous = current
        }
    }
}
