package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserOnboardingStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val panelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val styleSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/WalkSafeViewStyle.kt").readText()
    private val presenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText()
    private val profileSource = ownerSource("session/onboarding/PriorityUserProfileController.kt")
    private val onboardingSource = ownerSource("session/onboarding/FirstRunOnboardingController.kt")
    private val readinessSource = ownerSource("session/readiness/WalkReadinessCoordinator.kt")
    private val producerSource = ownerSource("session/readiness/WalkReadinessProducers.kt")
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
        val producer = MainActivityRefactorSource.member(
            producerSource.substringAfter("internal class PriorityWalkReadinessProducer("), "evaluate",
        )
        val composition = MainActivityRefactorSource.property(source, "walkReadinessCoordinator")

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
        assertTrue(capture.contains("producers.evaluate(requirement, effectiveDecision"))
        assertTrue(producer.contains("policy().evaluate(currentEnvironment(decision), walkIsActive = walkRuntimeActive())"))
        assertTrue(composition.contains("PriorityWalkReadinessProducer({ priorityUserOnboardingPolicy }, ::currentPriorityUserSupportEnvironment"))
        assertTrue(composition.contains("::isWalkSessionRuntimeActive, { developmentQuickStartEnabled }, ::currentReporterUserId"))
        assertTrue(source.contains("get() = BuildConfig.DEBUG && BuildConfig.DEVELOPMENT_QUICK_START"))
        assertTrue(blockReason.contains("if (!sources.onboardingComplete())"))
        assertTrue(producer.contains("currentActorId() != null"))
        assertTrue(producer.contains("account_profile_unbound"))
        assertTrue(blockReason.contains("if (!sources.quickStartEnabled() && !onboarding.mayStartWalk) return onboarding.noticeKo"))
        assertTrue(
            blockReason.indexOf("sources.priorityOnboarding(decision)") <
                blockReason.indexOf("sources.reporterUserId()"),
        )
    }

    @Test
    fun accountProfileIsLoadedBeforeRestoredCredentialPublishAndEligibilityIsRechecked() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")

        assertInOrder(
            create,
            "restorePermissionSessionStateFromPrefs()",
            "restorePriorityUserOnboardingFromPrefs()",
            "{ gatewaySessionController }, { try { it.detach() } finally { it.unregisterGatewayCapacityNetworkObserver() } },",
            ").attach(::dispatchGatewayProcessSessionChanged)",
            "enforcePriorityUserAccountEligibility()",
            "scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()",
        )
        val restore = functionBlock("private fun restoreGatewaySessionFromPrefs()")
        assertInOrder(
            restore,
            "val actorId = restored.firstRunSnapshot.verifiedActorBinding?.value",
            "actorId == null || actorId != restored.session.actorId",
            "profile.restorePriorityProfile()",
            "profile.priorityActorId() != actorId",
            "GatewaySessionProcessCoordinator.publishRestoredUnverified(",
        )
        assertTrue(source.contains("PREF_PRIORITY_USER_PROFILE_PREFIX"))
        assertTrue(profileSource.contains("priorityUserActorSha256(actorId)"))
        assertTrue(profileSource.contains("\"actor_id_sha256\""))
        assertTrue(source.contains("PRIORITY_USER_PROFILE_STORAGE_VERSION"))
        assertTrue(
            profileSource.contains(
                "PriorityUserPractice.entries.take(completedPractices.size)",
            ),
        )
        assertTrue(functionBlock("private fun restorePriorityUserOnboardingFromPrefs()").contains("storage.remove(profileKey)"))
        assertTrue(MainActivityRefactorSource.composition(source, "priorityUserProfileController")
            .contains("sensitivePrefs.edit().remove(key).commit()"))
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
        val observer = actorSessionChange(source)
        assertInOrder(observer,
            "permissions.reporterActorId = actorId",
            "restorePriorityUserOnboardingFromPrefs()",
            "if (verifiedActorSession != null)",
            "permissions.enforceAccountEligibility()",
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
            "profiles.beginMutation()",
            "policy.reviewSafetyEducation(",
        )
        assertInOrder(
            safePlace,
            "profiles.beginMutation()",
            "profiles.policy().confirmSafePracticePlace()",
        )
        assertInOrder(
            delivery,
            "profiles.beginMutation()",
            "policy.recordPracticeDelivery(token, signal)",
        )
        assertInOrder(
            reset,
            "beginPriorityUserProfileMutationOrFailClosed()",
            "profiles.policy.resetTraining()",
        )
        assertInOrder(
            persist,
            "storage.writeAndClearMarker(",
            "{ priorityUserProfileKey(actorId) }, { profile.toString() }, invalidKey",
            "if (committed)",
        )
        assertTrue(restore.contains("priorityUserProfileInvalidKey(actorId)"))
        assertTrue(restore.contains("if (profileInvalid)"))
        assertTrue(source.contains("PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX"))
        assertTrue(profileSource.contains("private val blockedActorHashes"))
        assertTrue(begin.contains("storage.markInvalid(invalidKey)"))
        val storage = MainActivityRefactorSource.composition(source, "priorityUserProfileController")
        assertInOrder(storage.substringAfter("override fun markInvalid(key: String) ="),
            ".putBoolean(key, true)", ".commit()")
        assertInOrder(storage.substringAfter("override fun writeAndClearMarker("),
            ".putString(key(), json())", ".remove(invalidKey)", ".commit()")
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
        val cleanupState = functionBlock("internal fun clearPriorityUserTrainingStateIfOwned(")
        val current = functionBlock("private fun isPriorityUserTrainingDeliveryCurrent(")

        assertTrue(update.contains("val deviceGateOpen = postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(update.contains("deviceGateOpen = deviceGateOpen"))
        assertTrue(update.contains("mayActivateAccount = decision.mayActivateAccount"))
        assertTrue(presenterBlock("fun updatePriorityUserOnboardingUi(").contains(
            "accountBound && deviceGateOpen && facts.mayActivateAccount",
        ))
        assertTrue(education.contains("if (!admission.requireDeviceCheck()) return"))
        assertTrue(safePlace.contains("if (!admission.requireDeviceCheck()) return"))
        assertTrue(practice.contains("if (!admission.requireDeviceCheck()) return"))
        assertTrue(delivery.contains("clearPriorityUserTrainingDeliveryIfOwned("))
        assertTrue(failure.contains("clearPriorityUserTrainingDeliveryIfOwned("))
        assertInOrder(cleanup, "clearPriorityUserTrainingStateIfOwned(generation, actorId, policy, token)", "runtime.cancelExistingFeedback()")
        assertTrue(cleanupState.contains("priorityUserEducationInFlight = false"))
        assertTrue(cleanupState.contains("priorityUserPracticeInFlight = null"))
        assertTrue(cleanupState.contains("policy.cancelPractice(token)"))
        assertTrue(cleanup.contains("runtime.cancelExistingFeedback()"))
        assertTrue(current.contains("profiles.deliveryCurrent(generation, priorityUserTrainingGeneration, actorId, policy)"))
        assertTrue(MainActivityRefactorSource.member(onboardingSource, "isTrainingDeliveryCurrent").contains("effects.deviceGatePassed()"))
        assertEducationPorts()
    }

    @Test
    fun educationRequiresPlaybackWhilePracticeKeepsItsAvailableChannels() {
        val update = presenterBlock("fun updatePriorityUserOnboardingUi(")
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
                "admission.featureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)",
            ),
        )
        assertTrue(
            practice.contains(
                "admission.featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK)",
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
        val actuator = MainActivityRefactorSource.function(source, "private fun ensureFeedbackActuator()")
        val actuatorFactory = MainActivityRefactorSource.member(source, "createSpeechOutputController")
        assertTrue(actuator.contains("actuatorFactory.create("))
        assertTrue(actuatorFactory.contains("speechAllowed = ::authorizeActuatorSpeechForHomeWakeInput"))
        assertTrue(functionBlock("private fun authorizeActuatorSpeechForHomeWakeInput()").contains("authorizeNativeFeedbackSpeech("))
        assertTrue(functionBlock("internal fun authorizeNativeFeedbackSpeech(").contains("navigationSpeechCapabilityAllowed()"))
        val speechAllowed = functionBlock("private fun navigationSpeechCapabilityAllowed()")
        val trainingOutput = MainActivityRefactorSource.member(source, "trainingOutputCapabilityAllowed")
        assertTrue(trainingOutput.contains("educationInteractionController.presentationState().educationInFlight || educationInteractionController.presentationState().practiceInFlight != null"))
        assertTrue(trainingOutput.contains("!isWalkSessionRuntimeActive() && postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(trainingOutput.contains("startupCapabilityDecision?.allowsTrainingOutput("))
        assertTrue(trainingOutput.contains("startupCapabilityProbe.snapshot().vibrationAvailable else null"))
        assertTrue(speechAllowed.contains("trainingOutputCapabilityAllowed(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)"))
        val hapticAllowed = actuatorFactory.substringAfter("hapticAllowed = {")
        assertTrue(actuator.contains("readyEffects.offlineSpeechUnavailable(generation, actor)"))
        assertTrue(actuatorFactory.contains("override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator)"))
        assertTrue(actuatorFactory.contains("handleOfflineKoreanSpeechUnavailable(lifecycleGeneration, actuator)"))
        val unavailableSpeech = functionBlock("private fun handleOfflineKoreanSpeechUnavailable(")

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
            "evaluation.probeDecision(",
        )
        assertInOrder(
            refresh,
            "resolveCurrentStartupCapabilityDecision()",
            "evidence().record(decision)",
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
            "effects.setReporterActor(actorId)",
            "if (!effects.authenticatedFor(actorId))",
            "effects.rememberActor(actorId)",
            "restorePriorityUserOnboardingFromPrefs()",
        )
        assertTrue(bindActor.contains("snapshot.verifiedActorBinding?.value"))
        assertTrue(bindActor.contains("gateway.clearSession()"))
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
            .contains("clearGatewaySession(logoutRemote = true)"))
        assertTrue(reporter.contains("priorityUserOnboardingPolicy.accountBlockReason() == null"))
        assertTrue(ownerSource("session/gateway/GatewaySessionController.kt").contains("profile.priorityActorId() == session.actorId"))
        val gatewayLogin = functionBlock("private fun onGatewaySessionButtonClicked()")
        assertInOrder(
            gatewayLogin,
            "profile.priorityActorId() == session.actorId",
            "commitLoggedInGatewaySession(",
        )
        val gatewayCommit = functionBlock(
            "private fun commitLoggedInGatewaySession(",
        )
        assertInOrder(
            gatewayCommit,
            "profile.priorityActorId() != session.actorId",
            "store.saveInitialIfAbsent(",
        )
    }

    @Test
    fun logoutDetachesMemoryButKeepsTheAccountScopedProfile() {
        val logout = functionBlock("private fun onAccountLogoutClicked()")

        assertTrue(logout.contains("onboarding.resetPriorityProfile(clearCompletedRecord = true)"))
        val binding = MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner")
        assertTrue(binding.contains("priorityUserProfileController.actorId = null"))
        assertTrue(binding.contains("priorityUserProfileController.policy = PriorityUserOnboardingPolicy()"))
        assertTrue(logout.contains("priority_user_account_logged_out"))
        assertTrue(logout.contains("ui.refreshStartupIfInitialized()"))
        assertTrue(binding.contains("refreshStartupCapabilityUi()"))
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
        assertFalse("Practice dispatch must not persist before tracked terminal callbacks",
            Regex("\\bprofiles\\s*\\.\\s*persistOrFailClosed\\s*\\(").containsMatchIn(practice))
        assertTrue(terminal.contains("policy.recordPracticeDelivery(token, signal)"))
        assertTrue(terminal.contains("profiles.persistOrFailClosed()"))
        assertEducationPorts()
    }

    @Test
    fun strictTrainingSpeechCannotInferSuccessFromDispatchOrAFollowingUtterance() {
        val strictSpeech = feedback.substringAfter("fun speakPriorityUserTraining(")
            .substringBefore("/** Queues a low-priority camera advisory")
        val listener = feedback.substringAfter("object : FeedbackSpeechRuntime.Listener")
            .substringBefore("if (listenerResult")
        val nativeSpeech = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackSpeechRuntime.kt").readText()
        val platformListener = nativeSpeech.substringAfter("internal class AndroidFeedbackUtteranceListener(")
            .substringBefore("private class AndroidProgressToneEffects")
        assertTrue(MainActivityRefactorSource.member(feedback, "installSpeechListener").contains("speechRuntime.setListener(listener)"))
        assertTrue(nativeSpeech.contains("textToSpeech.setOnUtteranceProgressListener(AndroidFeedbackUtteranceListener(listener))"))
        val inference = feedback.substringAfter("private fun armUtteranceTerminalWatchdog(")
            .substringBefore("private fun scheduleUtteranceWatchdogLocked")

        assertTrue(strictSpeech.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(listener.contains("postUtteranceCallback(engineGeneration, utteranceId)"))
        assertTrue(platformListener.contains(") : UtteranceProgressListener()"))
        assertTrue(platformListener.contains("override fun onStart(utteranceId: String?) = listener.onStart(utteranceId)"))
        assertTrue(platformListener.contains("override fun onDone(utteranceId: String?) = listener.onDone(utteranceId)"))
        assertFalse(platformListener.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(listener.contains("override fun onDone(utteranceId: String?)"))
        assertTrue(listener.contains("markUtteranceFinished(utteranceId, completed = true)"))
        assertTrue(listener.contains("onError"))
        assertTrue(listener.contains("onStop"))
        val ledger = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/UtteranceLedger.kt",
        ).readText()
        val detachPredecessors = ledger.substringAfter("fun detachPredecessors(id: String)")
            .substringBefore("fun detach(id: String)")
        assertTrue(feedback.contains("private val pendingUtterances = UtteranceLedger()"))
        assertTrue(inference.contains("val predecessors = pendingUtterances.detachPredecessors(utteranceId)"))
        assertTrue(inference.contains("val predecessor = record.id"))
        assertTrue(detachPredecessors.contains("records.keys.takeWhile { it != id }.mapNotNull(::detach)"))
        assertTrue(ledger.contains("fun detach(id: String): UtteranceRecord? = records.remove(id)"))
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
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val persistAccount = functionBlock("private fun persistReporterUserFromInput()")
        val reporter = functionBlock("private fun currentReporterUserId()")

        assertTrue(persistAccount.contains("manual_reporter_id_disallowed"))
        assertFalse(persistAccount.contains("reporterUserId ="))
        assertTrue(reporter.contains("priorityUserOnboardingPolicy.accountBlockReason() == null"))
        val ages = panelSource.substringAfter("priorityUserOnboardingStatusText = TextView(context).apply {")
            .substringBefore("priorityUserEducationButton =")
        assertTrue(ages.contains("PriorityUserAgeBand.UNDER_14"))
        assertTrue(ages.contains("PriorityUserAgeBand.AGE_14_TO_17"))
        assertTrue(ages.contains("PriorityUserAgeBand.ADULT_18_PLUS"))
        assertTrue(ages.contains("onClick = { actions.education.onPriorityAgeSelected(ageBand) }"))
        val composition = functionBlock("private fun buildContentView(): FrameLayout")
        assertTrue(composition.contains("onPriorityAgeSelected = {\n                        selectPriorityUserAgeBand(it)"))
        assertTrue(panelSource.contains("val priorityUserAgeButtons: Map<PriorityUserAgeBand, Button> get() = priorityAgeButtons"))
        assertTrue(MainActivityRefactorSource.member(registration, "publishEducationAndReadiness").contains("firstRun.publishEducationTargets()"))
        assertTrue(composition.contains("nativeRegistrationComposition.publishEducationAndReadiness()"))
        val selection = functionBlock("private fun selectPriorityUserAgeBand(")
        assertTrue(selection.contains("priorityUserOnboardingPolicy.selectAgeBand(ageBand)"))
        val traversal = presenterBlock("fun linkPriorityUserAccessibilityTraversal(")
        assertTrue(traversal.contains("PriorityUserAgeBand.UNDER_14"))
        assertTrue(traversal.contains("PriorityUserAgeBand.AGE_14_TO_17"))
        assertTrue(traversal.contains("PriorityUserAgeBand.ADULT_18_PLUS"))
        assertFalse(source.contains("recordGuardianVerification("))
    }

    @Test
    fun restoredTrainingNeverRestoresThePreviousWalkOrRoute() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val restore = functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")

        assertTrue(create.contains("restorePriorityUserOnboardingFromPrefs()"))
        assertTrue(
            create.contains(
                "walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)",
            ),
        )
        assertTrue(create.contains("PREF_WALK_SESSION_INTERRUPTED"))
        assertTrue(restore.contains("PriorityUserOnboardingSnapshot("))
        assertFalse(restore.contains("WalkSessionLifecycle"))
        assertFalse(restore.contains("RouteNavigator"))
    }

    @Test
    fun onboardingUsesFlexibleTextHighContrastAndExplicitFocusOrder() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val content = functionBlock("private fun buildContentView()")
        val factory = blockStartingAt(styleSource, "fun accessiblePriorityUserButton(")
        val traversal = presenterBlock("fun linkPriorityUserAccessibilityTraversal()")
        val update = presenterBlock("fun updatePriorityUserOnboardingUi(")
        val updateBinding = functionBlock("private fun updatePriorityUserOnboardingUi(")

        assertTrue(factory.contains("setSingleLine(false)"))
        assertTrue(factory.contains("ellipsize = null"))
        assertFalse(factory.contains("maxLines ="))
        assertTrue(factory.contains("minimumHeight = (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()"))
        assertTrue(factory.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
        assertTrue(panelSource.contains("ViewCompat.setAccessibilityHeading("))
        assertTrue(panelSource.contains("priorityUserEducationButton = style.accessiblePriorityUserButton("))
        assertTrue(content.contains("style = viewStyle"))
        assertInOrder(content.substringAfter("onboardingPanel.setAccountContent("),
            "nativeRegistrationComposition.publishEducationAndReadiness()",
            "linkPriorityUserAccessibilityTraversal()", "updatePriorityUserOnboardingUi()")
        assertTrue(content.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        val educationPublication = MainActivityRefactorSource.member(registration, "publishEducationAndReadiness")
        assertTrue(educationPublication.contains("firstRun.publishEducationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishEducationTargets").contains("publishedEducation = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "educationBinding").contains("= publishedEducation"))
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
        assertTrue(functionBlock("private fun linkPriorityUserAccessibilityTraversal()").contains(
            "firstRunOnboardingPresenter.linkPriorityUserAccessibilityTraversal()",
        ))
        assertTrue(updateBinding.contains("firstRunOnboardingPresenter.updatePriorityUserOnboardingUi("))
        assertTrue(updateBinding.contains("highContrastEnabled = environment.highContrastEnabled"))
        assertFalse(content.contains("setOnTouchListener"))
        assertFalse(content.contains("GestureDetector"))
        assertFalse(panelSource.contains("setOnTouchListener"))
        assertFalse(panelSource.contains("GestureDetector"))
    }

    @Test
    fun nativeEducationKeepsPostureListeningAndConsentInAccessibleOrder() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val content = functionBlock("private fun buildContentView()")
        val controls = blockStartingAt(panelSource, "priorityUserOnboardingControls = LinearLayout(context).apply")
        assertInOrder(controls, "addView(priorityUserOnboardingStatusText)", "addView(priorityUserEducationProgressText)", "addView(priorityUserEducationBodyText)", "addView(firstRunPhonePostureControls)", "addView(priorityUserEducationButton)", "addView(priorityUserPracticeNecessityButton)", "addView(priorityUserSafePlaceButton)", "PriorityUserPractice.entries.forEach { addView(practiceButtons.getValue(it)) }", "addView(priorityUserEducationAgreeButton)")
        assertFalse(controls.contains("addView(loginUserIdInput)"))
        assertTrue(panelSource.contains("onClick = actions.education.onSafetyEducation"))
        assertTrue(panelSource.contains("onClick = actions.education.onPracticeNecessity"))
        assertTrue(panelSource.contains("onClick = { actions.education.onPractice(practice) }"))
        assertTrue(content.contains("onSafetyEducation = ::reviewPriorityUserSafetyEducation"))
        assertTrue(content.contains("playNativeSafetyEducation(practiceNecessity = true)"))
        assertTrue(content.contains("performPriorityUserPractice(it)"))
        assertTrue(panelSource.contains("val priorityUserPracticeButtons: Map<PriorityUserPractice, Button> get() = practiceButtons"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "linkPriorityUserAccessibilityTraversal").contains("priorityUserPracticeButtons.getValue(practice)"))
        assertTrue(content.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        val educationPublication = MainActivityRefactorSource.member(registration, "publishEducationAndReadiness")
        assertTrue(educationPublication.contains("firstRun.publishEducationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishEducationTargets").contains("publishedEducation = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "educationBinding").contains("= publishedEducation"))
        assertTrue(panelSource.contains("val priorityUserEducationBodyText: TextView"))
        assertTrue(panelSource.contains("val priorityUserEducationProgressText: TextView"))
        assertTrue(content.contains("nativeRegistrationComposition.publishEducationAndReadiness()"))
        assertTrue(educationPublication.contains("publication.publish(NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_CONTROLS)"))
        val update = presenterBlock("fun updateNativeSafetyEducationUi(")
        assertTrue(update.contains("val heading = if (showPosture) \"사전 연습\" else \"안전 교육\""))
        assertTrue(update.contains("priorityUserEducationBodyText.accessibilityTraversalAfter = priorityUserEducationProgressText.id"))
        assertTrue(update.contains("firstRunPhonePostureButton.accessibilityTraversalAfter = priorityUserEducationBodyText.id"))
        assertTrue(update.contains("priorityUserPracticeNecessityButton.accessibilityTraversalAfter = priorityUserEducationButton.id"))
        assertTrue(update.contains("priorityUserEducationAgreeButton.accessibilityTraversalAfter ="))
        assertTrue(update.contains("if (showPosture) previousPracticeControl.id else priorityUserPracticeNecessityButton.id"))
        assertTrue(update.contains("if (showPosture) firstRunPhonePostureButton.id else priorityUserEducationBodyText.id"))
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
            "profiles.beginMutation",
            "policy.finishAppUsageEducationPlayback(usageToken, completed = true)",
            "profiles.persistOrFailClosed")
        assertTrue(update.contains("state.educationReviewed && state.practiceNecessityReviewed"))
        assertTrue(panelSource.contains("onClick = actions.education.onEducationAgree"))
        assertTrue(content.contains("onEducationAgree = ::acceptNativeSafetyEducation"))
        assertTrue(educationPublication.contains("publication.publish(NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON)"))
        assertEducationPorts()
    }

    @Test
    fun nativeEducationDisplaysSharedSpeechAndProgressWithoutTruncatingLargeText() {
        val update = functionBlock("private fun updateNativeSafetyEducationUi()")
        val playback = functionBlock("private fun playNativeSafetyEducation(")
        val body = blockStartingAt(panelSource, "priorityUserEducationBodyText = TextView(context).apply {")
        assertTrue(update.contains("PriorityUserEducationPresentation.screenText(state)"))
        assertTrue(update.contains("PriorityUserEducationPresentation.progressNotice("))
        assertTrue(update.contains("firstRunOnboardingPresenter.updateNativeSafetyEducationUi("))
        assertTrue(update.contains("body = body"))
        assertTrue(update.contains("progress = progress"))
        val rendered = presenterBlock("fun updateNativeSafetyEducationUi(")
        assertTrue(rendered.contains("val body = facts.body"))
        assertTrue(rendered.contains("val progress = facts.progress"))
        assertTrue(rendered.contains("priorityUserEducationBodyText.text.toString() != body"))
        assertTrue(rendered.contains("priorityUserEducationProgressText.text.toString() != progress"))
        assertFalse(presenterSource.contains("ensureFeedbackActuator()"))
        assertTrue(update.contains("if (prepareFeedback) ensureFeedbackActuator()"))
        assertTrue(playback.contains("PriorityUserEducationPresentation.speechText(playback)"))
        assertTrue(playback.contains("PriorityUserEducationPresentation.speechSegments(message)"))
        assertTrue(playback.contains("message = segments[dispatchedSegment]"))
        val completion = playback.substringAfter("onCompleted = {").substringBefore("onFailed = failed")
        assertInOrder(completion, "isPriorityUserTrainingDeliveryCurrent", "segmentIndex != dispatchedSegment",
            "segmentIndex += 1", "segmentIndex < segments.size", "playbackHandler.post { dispatchWhenReady() }",
            "profiles.beginMutation", "policy.finishAppUsageEducationPlayback(usageToken, completed = true)")
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
        val update = presenterBlock("fun updateNativeSafetyEducationUi(")
        val updateBinding = functionBlock("private fun updateNativeSafetyEducationUi()")
        val current = functionBlock("private fun isPriorityUserTrainingDeliveryCurrent(")
        assertTrue(practice.contains("confirmPriorityUserHazardResponse()"))
        assertFalse(delivery.contains("policy.confirmHazardResponse"))
        assertTrue(delivery.contains("if (policy.isHazardResponseReady(token)) effects.refresh(Presentation.PRIORITY)"))
        assertTrue(confirmation.contains("!policy.isHazardResponseReady(token)"))
        assertInOrder(confirmation, "isPriorityUserTrainingDeliveryCurrent", "profiles.beginMutation", "policy.confirmHazardResponse(token)", "profiles.persistOrFailClosed")
        assertTrue(current.contains("profiles.deliveryCurrent(generation, priorityUserTrainingGeneration, actorId, policy)"))
        assertTrue(MainActivityRefactorSource.member(onboardingSource, "isTrainingDeliveryCurrent").contains("effects.foreground() &&"))
        assertTrue(update.contains("PriorityUserEducationPresentation.hazardResponseActionKo"))
        assertTrue(update.contains("state.requiresPracticeRestart"))
        assertTrue(update.contains("priorityUserPracticeInFlight != null -> \"이번 연습 중지\""))
        assertTrue(updateBinding.contains("educationInteractionController.hazardResponseReady(priorityUserOnboardingPolicy)"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("ui/workflow/EducationInteractionController.kt"), "hazardResponseReady")
            .contains("priorityUserPracticeAttemptToken?.let(policy::isHazardResponseReady)"))
        assertTrue(updateBinding.contains("hazardResponseReady = hazardResponseReady"))
        val stop = functionBlock("private fun reviewPriorityUserSafetyEducation()")
            .substringBefore("if (priorityUserEducationInFlight)")
        assertInOrder(stop, "priorityUserPracticeInFlight != null", "cancelPendingPriorityUserTrainingFeedback()")
        assertFalse(stop.contains("resetTraining"))
        assertFalse(practice.contains("startLocationUpdates"))
        assertFalse(practice.contains("beginNativeWalkFromHome"))
        assertFalse(practice.contains("startCamera"))
        val ownedCleanup = functionBlock("private fun clearPriorityUserTrainingDeliveryIfOwned(")
        assertTrue(ownedCleanup.contains("clearPriorityUserTrainingStateIfOwned(generation, actorId, policy, token)"))
        listOf("private fun failPriorityUserTrainingDelivery(", "internal fun clearPriorityUserTrainingStateIfOwned(", "private fun cancelPendingPriorityUserTrainingFeedback()").forEach {
            assertTrue(functionBlock(it).contains("priorityUserPracticeAttemptToken = null"))
        }
    }

    @Test
    fun restoredEducationKeepsConsentAndReturnsOnlyMissingPracticeToTraining() {
        val restore = functionBlock("private fun restorePriorityUserOnboardingFromPrefs()")
        val persist = functionBlock("private fun persistPriorityUserOnboarding()")
        val bind = functionBlock("private fun bindFirstRunVerifiedActorForTraining()")
        assertTrue(restore.contains("decodeProfile(profile, actorSha256)"))
        val decode = MainActivityRefactorSource.member(profileSource, "decodeProfile")
        assertTrue(decode.contains("profile.optBoolean(\"hazard_response_confirmed\", false)"))
        assertTrue(persist.contains(".put(\"hazard_response_confirmed\", snapshot.hazardResponseConfirmed)"))
        assertTrue(bind.contains("hazardResponseConfirmed = compatible && restored.hazardResponseConfirmed"))
        assertTrue(bind.contains("educationReviewed = compatible && restored.educationReviewed"))
        assertTrue(bind.contains("appUsageAccepted = compatible && restored.appUsageAccepted"))
        assertTrue(bind.contains("FirstRunOnboardingPolicy.restartFp004Training(snapshot)"))
        assertFalse(bind.substringAfter("val restart =").contains("clearGatewaySession"))
        val accept = functionBlock("private fun acceptNativeSafetyEducation()")
        assertTrue(accept.contains("!state.interactivePracticeComplete"))
    }

    private fun presenterBlock(signature: String): String =
        ReportStaticSourceInspector.functionBlock(presenterSource, signature.substringBefore('('))

    private fun ownerSource(path: String): String =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()

    private fun assertEducationPublication(content: String) {
        assertTrue(content.contains("FirstRunOnboardingPresenter(this, viewStyle, onboardingPanel)"))
        assertTrue(content.contains("firstRunOnboardingPresenter.bindOnboardingPanel(onboardingPanel)"))
        assertTrue(content.contains("firstRunOnboardingPresenter.publishEducationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishEducationTargets")
            .contains("publishedEducation = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "educationBinding")
            .contains("= publishedEducation"))
    }

    private fun assertEducationPorts() {
        val binding = MainActivityRefactorSource.composition(source, "educationInteractionController")
        assertTrue(binding.contains("beginPriorityUserProfileMutationOrFailClosed()"))
        assertTrue(binding.contains("persistPriorityUserOnboardingOrFailClosed()"))
        assertTrue(binding.contains("requireDeviceCheckForPriorityUserTraining()"))
        assertTrue(binding.contains("firstRunOnboardingController.isTrainingDeliveryCurrent(generation, currentGeneration, actorId, policy)"))
        assertTrue(binding.contains("cancelPriorityUserTrainingFeedback()"))
        assertTrue(binding.contains("actuator.speakPriorityUserTraining(message, onCompleted, onFailed, onCancelled)"))
        assertTrue(binding.contains("actuator.playPriorityUserTrainingVibration(onWindowElapsed, onFailed)"))
        assertTrue(binding.contains("Presentation.PRIORITY -> this@MainActivity.updatePriorityUserOnboardingUi()"))
    }

    private fun functionBlock(signature: String): String {
        if (signature.contains("captureWalkSessionReadiness(")) {
            assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness")
                .contains("walkReadinessCoordinator.capture(decision, action, recordResourceDiagnostics, checkEntryPermissions)"))
            assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator").contains("WalkReadinessCoordinator("))
            return MainActivityRefactorSource.member(readinessSource, "capture")
        }
        return MainActivityRefactorSource.function(source, signature)
    }

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

    private fun assertInOrder(text: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = text.indexOf(snippet)
            assertTrue("missing or out of order: $snippet", current > previous)
            previous = current
        }
    }

    private fun actorSessionChange(mainSource: String): String {
        MainActivityRefactorSource.composition(mainSource, "actorSessionLifecycleCoordinator")
        val main = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
        consentAuthorityChanged: Boolean,
    ) {
        actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)
        refreshGuidanceDiagnosticAuthority()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main gateway callback must invoke the owner before diagnostic refresh", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val call = main.indexOf("actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)")
        org.junit.Assert.assertTrue(call >= 0)
        org.junit.Assert.assertTrue(main.indexOf("refreshGuidanceDiagnosticAuthority()") > call)
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt",
        ).readText(), "onGatewayProcessSessionChanged")
    }


    @Test
    fun actorSessionChangeRejectsNonExecutingOrReorderedCallers() {
        val mainSource = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        actorSessionChange(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        val call = "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"
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
            "refreshGuidanceDiagnosticAuthority()", "refreshGuidanceDiagnosticAuthority()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { actorSessionChange(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }

}
