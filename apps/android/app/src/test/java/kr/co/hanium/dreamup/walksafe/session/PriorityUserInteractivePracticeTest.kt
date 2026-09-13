package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Button-response and sandbox state contracts only; this does not prove physical output or safety. */
class PriorityUserInteractivePracticeTest {
    @Test
    fun hazardNeedsAResponseAfterTheActualRequiredOutputsAndCannotAdvanceAutomatically() {
        val policy = readyPolicy()
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!

        policy.confirmHazardResponse(token)
        assertFalse(policy.isHazardResponseReady(token))
        policy.recordPracticeDelivery(token, PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED)
        policy.confirmHazardResponse(token)
        assertFalse(policy.isHazardResponseReady(token))
        assertTrue(policy.snapshot().completedPractices.isEmpty())

        policy.recordPracticeDelivery(token, PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED)
        assertTrue(policy.isHazardResponseReady(token))
        assertTrue(policy.snapshot().completedPractices.isEmpty())
        assertNull(policy.beginPractice(PriorityUserPractice.PAUSE))

        policy.confirmHazardResponse(token)
        val confirmed = policy.snapshot()
        assertTrue(confirmed.hazardResponseConfirmed)
        assertEquals(setOf(PriorityUserPractice.HAZARD_ALERT), confirmed.completedPractices)
        assertFalse(policy.isHazardResponseReady(token))
        policy.confirmHazardResponse(token)
        assertEquals(confirmed, policy.snapshot())
    }

    @Test
    fun cancellingWhileAwaitingTheHazardResponseRejectsItsLateResponseAndAllowsRetry() {
        val policy = readyPolicy()
        val old = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        deliver(policy, old)
        assertTrue(policy.isHazardResponseReady(old))
        policy.cancelPractice(old)
        val current = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!

        deliver(policy, old)
        policy.confirmHazardResponse(old)
        assertFalse(policy.isHazardResponseReady(current))
        assertTrue(policy.snapshot().completedPractices.isEmpty())

        deliver(policy, current)
        policy.confirmHazardResponse(current)
        assertTrue(policy.snapshot().hazardResponseConfirmed)
    }

    @Test
    fun processRestorationAndActorPolicyReplacementCannotUseAnOldResponseToken() {
        val original = readyPolicy()
        val old = original.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        deliver(original, old)
        val restored = PriorityUserOnboardingPolicy(original.snapshot())
        val otherActor = readyPolicy()

        for (policy in listOf(restored, otherActor)) {
            val current = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
            policy.confirmHazardResponse(old)
            deliver(policy, old)
            assertTrue(policy.snapshot().completedPractices.isEmpty())
            assertFalse(policy.isHazardResponseReady(current))
            deliver(policy, current)
            policy.confirmHazardResponse(current)
            assertTrue(policy.snapshot().hazardResponseConfirmed)
        }
    }

    @Test
    fun revokedGuardianVerificationCancelsThePendingAttemptButKeepsTheCompletedPrefix() {
        val policy = readyPolicy().let {
            PriorityUserOnboardingPolicy(it.snapshot().copy(
                ageBand = PriorityUserAgeBand.AGE_14_TO_17,
                guardianVerified = true,
            ))
        }
        complete(policy, PriorityUserPractice.HAZARD_ALERT)
        val pause = policy.beginPractice(PriorityUserPractice.PAUSE)!!
        policy.recordGuardianVerification(false)
        policy.recordGuardianVerification(true)
        deliver(policy, pause)

        assertEquals(setOf(PriorityUserPractice.HAZARD_ALERT), policy.snapshot().completedPractices)
        assertEquals(WalkSessionState.ACTIVE, policy.practiceLifecycleSnapshot().state)
        complete(policy, PriorityUserPractice.PAUSE)
        assertEquals(WalkSessionState.PAUSED, policy.practiceLifecycleSnapshot().state)
    }

    @Test
    fun realSpeechOnlyOrVibrationOnlyCallbacksCanFulfilTheirOwnConfiguredChannel() {
        for (required in PriorityUserPracticeDeliverySignal.entries) {
            val policy = readyPolicy()
            val mutableRequirements = mutableSetOf(required)
            val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT, mutableRequirements)!!
            mutableRequirements.clear()
            val other = PriorityUserPracticeDeliverySignal.entries.first { it != required }
            policy.recordPracticeDelivery(token, other)
            policy.confirmHazardResponse(token)
            assertFalse(policy.isHazardResponseReady(token))
            policy.recordPracticeDelivery(token, required)
            assertTrue(policy.isHazardResponseReady(token))
            policy.confirmHazardResponse(token)
            assertTrue(policy.snapshot().hazardResponseConfirmed)
        }
    }

    @Test
    fun noOutputAndDuplicateSignalsCannotSubstituteForRequiredDelivery() {
        val policy = readyPolicy()
        assertNull(policy.beginPractice(PriorityUserPractice.HAZARD_ALERT, emptySet()))
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        repeat(3) {
            policy.recordPracticeDelivery(token, PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED)
        }
        policy.confirmHazardResponse(token)
        assertFalse(policy.isHazardResponseReady(token))
        assertFalse(policy.snapshot().hazardResponseConfirmed)
    }

    @Test
    fun eachCancelledControlRollsBackAndNeedsItsOwnFreshButtonAttempt() {
        val policy = readyPolicy()
        complete(policy, PriorityUserPractice.HAZARD_ALERT)
        for (practice in listOf(PriorityUserPractice.PAUSE, PriorityUserPractice.RESUME, PriorityUserPractice.SAFE_STOP)) {
            val before = policy.practiceLifecycleSnapshot()
            val completedPrefix = policy.snapshot().completedPractices
            val cancelled = policy.beginPractice(practice)!!
            policy.cancelPractice(cancelled)
            deliver(policy, cancelled)

            assertEquals(before.state, policy.practiceLifecycleSnapshot().state)
            assertEquals(before.recoveryStage, policy.practiceLifecycleSnapshot().recoveryStage)
            assertEquals(completedPrefix, policy.snapshot().completedPractices)
            assertEquals(practice, policy.snapshot().nextRequiredPractice)
            complete(policy, practice)
        }
        assertTrue(policy.snapshot().interactivePracticeComplete)
        assertEquals(WalkSessionState.SAFE_STOP, policy.practiceLifecycleSnapshot().state)
    }

    @Test
    fun oldEducationAndOldOutputOnlyPracticeCannotClaimNewInteractiveCompletion() {
        val old = readyPolicy().snapshot().copy(
            appUsageAccepted = true,
            completedPractices = PriorityUserPractice.entries.toSet(),
        )
        val policy = PriorityUserOnboardingPolicy(old)
        assertTrue(policy.snapshot().trainingComplete)
        assertFalse(policy.snapshot().nativeEducationComplete)
        assertTrue(policy.snapshot().requiresPracticeRestart)
        assertNull(policy.beginPractice(PriorityUserPractice.HAZARD_ALERT))

        policy.confirmSafePracticePlace()

        assertTrue(policy.snapshot().completedPractices.isEmpty())
        assertTrue(policy.snapshot().educationReviewed)
        assertTrue(policy.snapshot().practiceNecessityReviewed)
        assertTrue(policy.snapshot().educationAccepted)
        assertTrue(policy.snapshot().appUsageReviewed)
        assertTrue(policy.snapshot().appUsageAccepted)
        PriorityUserPractice.entries.forEach { complete(policy, it) }
        assertTrue(policy.snapshot().nativeEducationComplete)
        assertTrue(PriorityUserOnboardingPolicy(policy.snapshot()).snapshot().nativeEducationComplete)
    }

    @Test
    fun listeningAndAcceptingUsageCannotSkipInteractivePractice() {
        val policy = readyPolicy()
        policy.acceptAppUsageEducation()
        assertFalse(policy.snapshot().appUsageAccepted)
        assertFalse(policy.snapshot().nativeEducationComplete)

        PriorityUserPractice.entries.forEach { complete(policy, it) }
        assertFalse(policy.snapshot().nativeEducationComplete)
        policy.acceptAppUsageEducation()
        assertTrue(policy.snapshot().nativeEducationComplete)
    }

    @Test
    fun legacyFlowWithOldOutputOnlyPracticeDoesNotPassTheWalkDecision() {
        val policy = PriorityUserOnboardingPolicy(PriorityUserOnboardingSnapshot(
            ageBand = PriorityUserAgeBand.ADULT_18_PLUS,
            educationReviewed = true,
            safePracticePlaceConfirmed = true,
            completedPractices = PriorityUserPractice.entries.toSet(),
        ))
        val environment = PriorityUserSupportEnvironment(
            screenReaderActive = false,
            largeTextEnabled = false,
            highContrastEnabled = false,
            offlineKoreanVoiceAvailable = true,
            vibrationAvailable = true,
        )
        val decision = policy.evaluate(environment, walkIsActive = true)

        assertFalse(decision.mayStartWalk)
        assertTrue(decision.requiresSafetyStop)
        assertEquals(PriorityUserBlockReason.REQUIRED_PRACTICE_INCOMPLETE, decision.walkBlockReason)

        policy.confirmSafePracticePlace()
        PriorityUserPractice.entries.forEach { complete(policy, it) }
        assertTrue(policy.evaluate(environment).mayStartWalk)
    }

    private fun readyPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
        reviewSafetyEducation(true)
        reviewPracticeNecessity(true)
        acceptEducationConsent()
        acknowledgeUsageConditions()
        finishAppUsageEducationPlayback(beginAppUsageEducationPlayback()!!, completed = true)
        confirmSafePracticePlace()
    }

    private fun deliver(policy: PriorityUserOnboardingPolicy, token: PriorityUserPracticeAttemptToken) {
        PriorityUserPracticeDeliverySignal.entries.forEach { policy.recordPracticeDelivery(token, it) }
    }

    private fun complete(policy: PriorityUserOnboardingPolicy, practice: PriorityUserPractice) {
        val token = policy.beginPractice(practice)!!
        deliver(policy, token)
        if (practice == PriorityUserPractice.HAZARD_ALERT) policy.confirmHazardResponse(token)
    }
}
