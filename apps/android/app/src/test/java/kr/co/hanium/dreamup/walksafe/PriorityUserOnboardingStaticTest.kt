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
    fun switchingActorLoadsItsProfileBeforeCheckingActivationRules() {
        val bindActor = functionBlock("private fun bindFirstRunVerifiedActorForTraining()")
        val reporter = functionBlock("private fun currentReporterUserId()")

        assertInOrder(
            bindActor,
            "reporterUserId = actorId",
            "restorePriorityUserOnboardingFromPrefs()",
        )
        assertTrue(bindActor.contains("firstRunOnboardingSnapshot.verifiedActorBinding?.value"))
        assertTrue(bindActor.contains("clearGatewaySession(logoutRemote = true)"))
        assertTrue(reporter.contains("priorityUserOnboardingPolicy.accountBlockReason() == null"))
        assertTrue(source.contains("priorityUserOnboardingActorId == session.actorId"))
        val gatewayLogin = source.substringAfter("val session = gatewaySessionClient.login")
            .substringBefore("} catch (error: GatewaySessionHttpException)")
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
        assertTrue(update.contains("if (environment.highContrastEnabled)"))
        assertTrue(update.contains("0xff000000.toInt()"))
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
