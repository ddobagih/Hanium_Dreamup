package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy behavior only: this does not exercise Activity buttons, TTS or persistent storage. */
class PriorityUserSafetyEducationReplayTest {
    @Test
    fun initialFailedOrOutOfOrderPlaybackCannotUnlockConsent() {
        val policy = adultPolicy()

        policy.reviewSafetyEducation(voicePlaybackCompleted = false)
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
        policy.acceptEducationConsent()

        assertFalse(policy.snapshot().educationReviewed)
        assertFalse(policy.snapshot().practiceNecessityReviewed)
        assertFalse(policy.snapshot().educationAccepted)
        assertFalse(policy.snapshot().safetyEducationConsentComplete)
    }

    @Test
    fun practiceNecessityFailureKeepsFirstCompletionButDoesNotUnlockConsent() {
        val policy = adultPolicy()
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)

        policy.reviewPracticeNecessity(voicePlaybackCompleted = false)
        policy.cancelPractice()
        policy.cancelAppUsageEducationPlayback()
        policy.acceptEducationConsent()

        assertTrue(policy.snapshot().educationReviewed)
        assertFalse(policy.snapshot().practiceNecessityReviewed)
        assertFalse(policy.snapshot().educationAccepted)
    }

    @Test
    fun incompleteReplaysKeepBothCompletedItemsAndExplicitConsentAvailable() {
        val policy = completedSafetyPlaybacks()
        val completedHistory = policy.snapshot()

        policy.reviewSafetyEducation(voicePlaybackCompleted = false)
        policy.reviewPracticeNecessity(voicePlaybackCompleted = false)
        policy.cancelPractice()
        policy.cancelAppUsageEducationPlayback()

        assertEquals(completedHistory, policy.snapshot())
        assertFalse("Listening or replay cancellation is not agreement", policy.snapshot().educationAccepted)
        policy.acceptEducationConsent()
        assertTrue(policy.snapshot().educationReviewed)
        assertTrue(policy.snapshot().practiceNecessityReviewed)
        assertTrue(policy.snapshot().safetyEducationConsentComplete)
    }

    @Test
    fun successfulReplaysKeepTheOtherItemWithoutCompletingUsageOrPhysicalPractice() {
        val policy = completedSafetyPlaybacks()
        val completedHistory = policy.snapshot()

        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        assertEquals(completedHistory, policy.snapshot())
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)

        assertEquals(completedHistory, policy.snapshot())
        assertFalse(policy.snapshot().educationAccepted)
        assertFalse(policy.snapshot().appUsageReviewed)
        assertFalse(policy.snapshot().nativeEducationComplete)
        assertFalse(policy.snapshot().trainingComplete)
        assertTrue(policy.snapshot().completedPractices.isEmpty())
    }

    private fun adultPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
    }

    private fun completedSafetyPlaybacks() = adultPolicy().apply {
        reviewSafetyEducation(voicePlaybackCompleted = true)
        reviewPracticeNecessity(voicePlaybackCompleted = true)
    }
}
