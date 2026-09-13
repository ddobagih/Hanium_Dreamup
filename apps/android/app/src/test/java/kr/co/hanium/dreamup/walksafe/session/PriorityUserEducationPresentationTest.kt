package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserEducationPresentationTest {
    @Test
    fun screenIncludesTheFullSpokenContentForEachEducationStage() {
        val policy = adultPolicy()
        val safetyText = PriorityUserEducationPresentation.screenText(policy.snapshot())
        assertTrue(safetyText.contains(PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.SAFETY_LIMITS)))
        assertTrue(safetyText.contains(PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.PRACTICE_NECESSITY)))

        completeSafetyConsent(policy)

        assertEquals(
            PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE),
            PriorityUserEducationPresentation.screenText(policy.snapshot()),
        )
        assertFalse(policy.snapshot().appUsageReviewed)
        assertFalse(policy.snapshot().trainingComplete)
    }

    @Test
    fun safetyNextActionFollowsCompletedPlaybackAndSeparateConsent() {
        val policy = adultPolicy()
        assertTrue(progress(policy).contains("1. 안전 제한 안내 듣기"))

        policy.reviewSafetyEducation(voicePlaybackCompleted = false)
        assertTrue(progress(policy).contains("1. 안전 제한 안내 듣기"))
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        assertTrue(progress(policy).contains("2. 연습 필요성 듣기"))
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
        assertTrue(progress(policy).contains("안전 교육 동의하고 사전 연습으로"))
        assertFalse(policy.snapshot().educationAccepted)
    }

    @Test
    fun usagePlaybackFirstStillPointsToTheMissingOperatingScopeAcknowledgment() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        val token = policy.beginAppUsageEducationPlayback()!!
        policy.finishAppUsageEducationPlayback(token, completed = true)

        assertTrue(progress(policy).contains("이해했습니다 버튼"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))

        policy.acknowledgeUsageConditions()
        assertTrue(progress(policy).contains("안전한 연습 장소 확인"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
        assertFalse(policy.snapshot().appUsageAccepted)
    }

    @Test
    fun practiceProgressWaitsForTheHazardResponseAndCountsOnlyCompletedActions() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        policy.acknowledgeUsageConditions()
        policy.finishAppUsageEducationPlayback(policy.beginAppUsageEducationPlayback()!!, completed = true)
        policy.confirmSafePracticePlace()
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        PriorityUserPracticeDeliverySignal.entries.forEach { policy.recordPracticeDelivery(token, it) }

        val awaitingResponse = PriorityUserEducationPresentation.progressNotice(
            policy.snapshot(),
            practice = PriorityUserPractice.HAZARD_ALERT,
            hazardResponseReady = policy.isHazardResponseReady(token),
        )
        assertTrue(awaitingResponse.contains(PriorityUserEducationPresentation.hazardResponseActionKo))
        assertFalse(awaitingResponse.contains("연습을 완료"))
        assertTrue(policy.snapshot().completedPractices.isEmpty())

        policy.confirmHazardResponse(token)
        assertTrue(progress(policy).contains("1/4 완료"))
        assertTrue(progress(policy).contains("일시정지 연습 버튼"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
    }

    @Test
    fun failedOrCancelledUsagePlaybackKeepsTheListenActionAndCannotClaimCompletion() {
        val policy = adultPolicy()
        completeSafetyConsent(policy)
        policy.acknowledgeUsageConditions()
        val failed = policy.beginAppUsageEducationPlayback()!!
        policy.finishAppUsageEducationPlayback(failed, completed = false)
        val cancelled = policy.beginAppUsageEducationPlayback()!!
        policy.cancelAppUsageEducationPlayback()
        policy.finishAppUsageEducationPlayback(cancelled, completed = true)

        assertTrue(progress(policy).contains("사전 연습 듣기"))
        assertFalse(progress(policy).contains("완료하고 홈으로"))
        assertFalse(policy.snapshot().nativeEducationComplete)
    }

    @Test
    fun playbackStatusDistinguishesFirstAttemptFromReplayWithoutChangingEvidence() {
        val policy = adultPolicy()
        val initial = policy.snapshot()
        val firstPlayback = PriorityUserEducationPresentation.progressNotice(initial, PriorityUserEducationPlayback.SAFETY_LIMITS)
        assertTrue(firstPlayback.contains("끝까지 재생되어야"))
        assertEquals(initial, policy.snapshot())

        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        val reviewed = policy.snapshot()
        val replay = PriorityUserEducationPresentation.progressNotice(reviewed, PriorityUserEducationPlayback.SAFETY_LIMITS)
        assertTrue(replay.contains("이전 청취 완료 기록은 유지"))
        assertTrue(replay.contains("2. 연습 필요성 듣기"))
        assertEquals(reviewed, policy.snapshot())
    }

    private fun progress(policy: PriorityUserOnboardingPolicy): String =
        PriorityUserEducationPresentation.progressNotice(policy.snapshot())

    private fun adultPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
    }

    private fun completeSafetyConsent(policy: PriorityUserOnboardingPolicy) {
        policy.reviewSafetyEducation(voicePlaybackCompleted = true)
        policy.reviewPracticeNecessity(voicePlaybackCompleted = true)
        policy.acceptEducationConsent()
    }
}
