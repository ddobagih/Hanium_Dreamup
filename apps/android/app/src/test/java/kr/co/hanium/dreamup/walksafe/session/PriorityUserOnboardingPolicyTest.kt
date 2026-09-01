package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserOnboardingPolicyTest {
    private val supportedEnvironment = PriorityUserSupportEnvironment(
        screenReaderActive = true,
        largeTextEnabled = false,
        highContrastEnabled = false,
        offlineKoreanVoiceAvailable = true,
        vibrationAvailable = true,
    )

    @Test
    fun readingSafetyNoticeAloneNeverCompletesTraining() {
        val policy = adultPolicy()

        policy.reviewSafetyEducation(voicePlaybackCompleted = true)

        assertTrue(policy.snapshot().educationReviewed)
        assertFalse(policy.snapshot().trainingComplete)
        assertEquals(
            PriorityUserBlockReason.SAFE_PRACTICE_PLACE_NOT_CONFIRMED,
            policy.evaluate(supportedEnvironment).walkBlockReason,
        )
    }

    @Test
    fun educationIsNotRecordedBeforeTrackedSpeechPlaybackCompletes() {
        val policy = adultPolicy()

        policy.reviewSafetyEducation(voicePlaybackCompleted = false)

        assertFalse(policy.snapshot().educationReviewed)
    }

    @Test
    fun practiceDispatchOrEitherSingleTerminalSignalNeverCompletesPractice() {
        val policy = readyForPractice()
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!

        assertTrue(policy.snapshot().completedPractices.isEmpty())
        policy.recordPracticeDelivery(
            token,
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
        )
        policy.recordPracticeDelivery(
            token,
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
        )

        assertTrue(policy.snapshot().completedPractices.isEmpty())
    }

    @Test
    fun matchingAttemptCompletesOnlyAfterBothTerminalSignalsInEitherOrder() {
        val speechFirst = readyForPractice()
        val firstToken = speechFirst.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        speechFirst.recordPracticeDelivery(
            firstToken,
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
        )
        speechFirst.recordPracticeDelivery(
            firstToken,
            PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED,
        )

        val vibrationFirst = readyForPractice()
        val secondToken = vibrationFirst.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        vibrationFirst.recordPracticeDelivery(
            secondToken,
            PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED,
        )
        vibrationFirst.recordPracticeDelivery(
            secondToken,
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
        )

        assertEquals(
            setOf(PriorityUserPractice.HAZARD_ALERT),
            speechFirst.snapshot().completedPractices,
        )
        assertEquals(
            setOf(PriorityUserPractice.HAZARD_ALERT),
            vibrationFirst.snapshot().completedPractices,
        )
    }

    @Test
    fun staleCancelledAndWrongAttemptSignalsCannotCompletePractice() {
        val policy = readyForPractice()
        val stale = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        policy.cancelPractice(stale)
        val current = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!

        PriorityUserPracticeDeliverySignal.entries.forEach { signal ->
            policy.recordPracticeDelivery(stale, signal)
        }
        policy.recordPracticeDelivery(
            current,
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
        )

        assertTrue(policy.snapshot().completedPractices.isEmpty())
        policy.recordPracticeDelivery(
            current,
            PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED,
        )
        assertEquals(
            setOf(PriorityUserPractice.HAZARD_ALERT),
            policy.snapshot().completedPractices,
        )
    }

    @Test
    fun onlyTheNextPracticeCanBegin() {
        val policy = readyForPractice()

        assertNull(policy.beginPractice(PriorityUserPractice.PAUSE))
        completePractice(policy, PriorityUserPractice.HAZARD_ALERT)
        assertNull(policy.beginPractice(PriorityUserPractice.RESUME))
        assertTrue(policy.beginPractice(PriorityUserPractice.PAUSE) != null)
    }

    @Test
    fun practiceSandboxTransitionsActivePausedActiveSafeStop() {
        val policy = readyForPractice()
        assertEquals(WalkSessionState.ACTIVE, policy.practiceLifecycleSnapshot().state)
        assertEquals(
            PriorityUserPractice.HAZARD_ALERT,
            policy.practiceLifecycleNextRequiredPractice(),
        )

        val hazard = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        assertEquals(WalkSessionState.ACTIVE, policy.practiceLifecycleSnapshot().state)
        assertEquals(
            PriorityUserPractice.PAUSE,
            policy.practiceLifecycleNextRequiredPractice(),
        )
        completeAttempt(policy, hazard)

        val pause = policy.beginPractice(PriorityUserPractice.PAUSE)!!
        assertEquals(WalkSessionState.PAUSED, policy.practiceLifecycleSnapshot().state)
        assertEquals(
            WalkSessionRecoveryStage.RECHECK_REQUIRED,
            policy.practiceLifecycleSnapshot().recoveryStage,
        )
        completeAttempt(policy, pause)

        completePractice(policy, PriorityUserPractice.RESUME)
        assertEquals(WalkSessionState.ACTIVE, policy.practiceLifecycleSnapshot().state)

        completePractice(policy, PriorityUserPractice.SAFE_STOP)
        assertEquals(WalkSessionState.SAFE_STOP, policy.practiceLifecycleSnapshot().state)
        assertEquals(WalkSessionMode.UNAVAILABLE, policy.practiceLifecycleSnapshot().mode)
        assertTrue(policy.snapshot().trainingComplete)
    }

    @Test
    fun cancelledStagedActionRollsPracticeSandboxBackToStoredPrefix() {
        val policy = readyForPractice()
        completePractice(policy, PriorityUserPractice.HAZARD_ALERT)
        val pause = policy.beginPractice(PriorityUserPractice.PAUSE)!!

        assertEquals(WalkSessionState.PAUSED, policy.practiceLifecycleSnapshot().state)
        policy.cancelPractice(pause)

        assertEquals(WalkSessionState.ACTIVE, policy.practiceLifecycleSnapshot().state)
        assertEquals(
            PriorityUserPractice.PAUSE,
            policy.practiceLifecycleNextRequiredPractice(),
        )
        assertEquals(
            setOf(PriorityUserPractice.HAZARD_ALERT),
            policy.snapshot().completedPractices,
        )
    }

    @Test
    fun practiceCannotBeginOutsideAConfirmedSafePlace() {
        val policy = adultPolicy()
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)

        assertNull(policy.beginPractice(PriorityUserPractice.HAZARD_ALERT))
        assertTrue(policy.snapshot().completedPractices.isEmpty())
    }

    @Test
    fun ageAndGuardianRulesBlockAccountActivationConservatively() {
        val unknown = PriorityUserOnboardingPolicy()
        val under14 = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.UNDER_14)
        }
        val minor = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.AGE_14_TO_17)
        }
        val verifiedMinor = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.AGE_14_TO_17)
            recordGuardianVerification(verified = true)
        }
        val adult = adultPolicy()

        assertEquals(
            PriorityUserBlockReason.AGE_SELECTION_REQUIRED,
            unknown.evaluate(supportedEnvironment).accountBlockReason,
        )
        assertEquals(
            PriorityUserBlockReason.MINIMUM_AGE_NOT_MET,
            under14.evaluate(supportedEnvironment).accountBlockReason,
        )
        assertEquals(
            PriorityUserBlockReason.GUARDIAN_VERIFICATION_REQUIRED,
            minor.evaluate(supportedEnvironment).accountBlockReason,
        )
        assertTrue(verifiedMinor.evaluate(supportedEnvironment).mayActivateAccount)
        assertTrue(adult.evaluate(supportedEnvironment).mayActivateAccount)
    }

    @Test
    fun missingVoiceOrVibrationDoesNotBlockCompletedTrainingOrStopAnActiveWalk() {
        val policy = completedPolicy()
        val voiceMissing = supportedEnvironment.copy(
            offlineKoreanVoiceAvailable = false,
        )
        val vibrationMissing = supportedEnvironment.copy(
            vibrationAvailable = false,
        )
        val bothMissing = supportedEnvironment.copy(
            offlineKoreanVoiceAvailable = false,
            vibrationAvailable = false,
        )

        assertTrue(policy.evaluate(voiceMissing).mayStartWalk)
        assertTrue(policy.evaluate(vibrationMissing).mayStartWalk)
        assertTrue(policy.evaluate(bothMissing).mayStartWalk)
        assertFalse(
            policy.evaluate(voiceMissing, walkIsActive = true).requiresSafetyStop,
        )
        assertFalse(
            policy.evaluate(vibrationMissing, walkIsActive = true).requiresSafetyStop,
        )
        assertFalse(
            policy.evaluate(bothMissing, walkIsActive = true).requiresSafetyStop,
        )
    }

    @Test
    fun accessibilityModesAreObservedWithoutRankingBlindAndLowVisionUsers() {
        val policy = completedPolicy()
        val screenReader = supportedEnvironment.copy(
            screenReaderActive = true,
            largeTextEnabled = false,
            highContrastEnabled = false,
        )
        val lowVision = supportedEnvironment.copy(
            screenReaderActive = false,
            largeTextEnabled = true,
            highContrastEnabled = true,
        )

        assertTrue(policy.evaluate(screenReader).mayStartWalk)
        assertTrue(policy.evaluate(lowVision).mayStartWalk)
        assertEquals(
            PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,
            policy.evaluate(screenReader).supportPriority,
        )
        assertEquals(
            PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,
            policy.evaluate(lowVision).supportPriority,
        )
    }

    @Test
    fun resetAndAgeChangeInvalidatePendingAttempt() {
        val reset = readyForPractice()
        val resetToken = reset.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        reset.resetTraining()
        completeAttempt(reset, resetToken)

        val ageChanged = readyForPractice()
        val ageToken = ageChanged.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        ageChanged.selectAgeBand(PriorityUserAgeBand.UNDER_14)
        completeAttempt(ageChanged, ageToken)

        assertTrue(reset.snapshot().completedPractices.isEmpty())
        assertTrue(ageChanged.snapshot().completedPractices.isEmpty())
        assertFalse(reset.snapshot().educationReviewed)
        assertEquals(PriorityUserAgeBand.UNDER_14, ageChanged.snapshot().ageBand)
    }

    @Test
    fun restoredCompletionReplaysOnlyThePracticeSandboxNotARealWalk() {
        val completed = completedPolicy().snapshot()
        val restored = PriorityUserOnboardingPolicy(completed)

        assertTrue(restored.snapshot().trainingComplete)
        assertTrue(restored.evaluate(supportedEnvironment).mayStartWalk)
        assertFalse(restored.evaluate(supportedEnvironment).requiresSafetyStop)
        assertEquals(WalkSessionState.SAFE_STOP, restored.practiceLifecycleSnapshot().state)
    }

    @Test
    fun restoredPracticesMustBeAnOrderedPrefix() {
        assertThrows(IllegalArgumentException::class.java) {
            PriorityUserOnboardingSnapshot(
                ageBand = PriorityUserAgeBand.ADULT_18_PLUS,
                educationReviewed = true,
                safePracticePlaceConfirmed = true,
                completedPractices = setOf(PriorityUserPractice.PAUSE),
            )
        }
    }

    private fun adultPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
    }

    private fun readyForPractice() = adultPolicy().apply {
        reviewSafetyEducation(voicePlaybackCompleted = true)
        confirmSafePracticePlace()
    }

    private fun completedPolicy() = readyForPractice().apply {
        PriorityUserPractice.entries.forEach { practice ->
            completePractice(this, practice)
        }
    }

    private fun completePractice(
        policy: PriorityUserOnboardingPolicy,
        practice: PriorityUserPractice,
    ) {
        completeAttempt(policy, policy.beginPractice(practice)!!)
    }

    private fun completeAttempt(
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken,
    ) {
        PriorityUserPracticeDeliverySignal.entries.forEach { signal ->
            policy.recordPracticeDelivery(token, signal)
        }
    }
}
