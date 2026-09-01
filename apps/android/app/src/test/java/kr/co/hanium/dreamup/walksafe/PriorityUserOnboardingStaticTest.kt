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
        assertTrue(capture.contains("currentReporterUserId() != null"))
        assertTrue(capture.contains("account_profile_unbound"))
        assertTrue(blockReason.contains("if (!onboarding.mayStartWalk) return onboarding.noticeKo"))
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
            "scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()",
            "enforcePriorityUserAccountEligibility()",
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
    fun limitedVoiceOrHapticUsesTheAvailableTrainingChannelsAndScreenConfirmation() {
        val update = functionBlock("private fun updatePriorityUserOnboardingUi(")
        val practiceButtons = update.substringAfter(
            "priorityUserPracticeButtons.forEach",
        ).substringBefore("priorityUserResetButton")
        val education = functionBlock("private fun reviewPriorityUserSafetyEducation()")
        val practice = functionBlock("private fun performPriorityUserPractice(")
        val hapticDelivery = practice.substringAfter("if (hapticFeedbackEnabled) {")
            .substringBefore("if (voiceGuidanceEnabled) {")
        val hapticFallback = hapticDelivery.substringAfter("} else {")
        val speechDelivery = practice.substringAfter("if (voiceGuidanceEnabled) {")
        val speechFallback = speechDelivery.substringAfter("} else {")

        assertFalse(practiceButtons.contains("environment.offlineKoreanVoiceAvailable"))
        assertFalse(practiceButtons.contains("environment.vibrationAvailable"))
        assertTrue(
            education.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)",
            ),
        )
        assertTrue(education.contains("if (!voiceGuidanceEnabled)"))
        assertTrue(education.contains("화면의 안전 안내 확인으로 교육을 진행합니다."))
        assertTrue(
            education.substringAfter("if (!voiceGuidanceEnabled) {")
                .substringBefore("val dispatch")
                .contains("completed()"),
        )
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
        assertTrue(
            hapticFallback.contains(
                "PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED",
            ),
        )
        assertFalse(hapticFallback.contains("return"))
        assertTrue(speechDelivery.contains("speakPriorityUserTraining("))
        assertTrue(
            speechFallback.contains(
                "PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED",
            ),
        )
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

        assertTrue(practice.contains("policy.beginPractice(practice)"))
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
        assertTrue(inference.contains("completed = !explicitTerminalRequired"))
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
        assertTrue(factory.contains("minimumHeight = (48f * resources.displayMetrics.density).roundToInt()"))
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
    fun visibleAndAccessibilityOrderStartsWithAccountSelectionThenOneActionSteps() {
        val content = functionBlock("private fun buildContentView()")
        val controls = content.substringAfter("priorityUserOnboardingControls =")
            .substringBefore("linkPriorityUserAccessibilityTraversal()")
        val traversal = functionBlock("private fun linkPriorityUserAccessibilityTraversal()")

        assertInOrder(
            controls,
            "addView(priorityUserOnboardingStatusText)",
            "addView(loginUserIdInput)",
            "addView(loginSaveButton)",
            "addView(accountLogoutButton)",
            "addView(priorityUserEducationButton)",
            "addView(priorityUserSafePlaceButton)",
            "addView(priorityUserResetButton)",
        )
        assertInOrder(
            traversal,
            "add(priorityUserOnboardingStatusText)",
            "add(loginUserIdInput)",
            "add(loginSaveButton)",
            "add(accountLogoutButton)",
            "add(priorityUserEducationButton)",
            "add(priorityUserSafePlaceButton)",
            "add(priorityUserResetButton)",
        )
        assertTrue(content.contains("1. 안전 제한 안내 듣기"))
        assertTrue(content.contains("2. 안전한 연습 장소 확인"))
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
