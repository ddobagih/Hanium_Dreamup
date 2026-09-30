package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02-11 (originals/02.txt:105-109): "I reviewed the content" is separate from "the app voice works".
 * With TalkBack on (FP-010-DETAIL-03 interim) explanatory steps complete by explicit confirmation;
 * practice still needs the app's own speech (FP-004-DETAIL-01, prohibited_behaviors[1]).
 */
class PriorityUserScreenReaderEducationTest {
    private val environment = PriorityUserSupportEnvironment(
        screenReaderActive = true,
        largeTextEnabled = true,
        highContrastEnabled = false,
        offlineKoreanVoiceAvailable = true,
        vibrationAvailable = true,
    )

    private fun adultPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
    }

    private fun PriorityUserOnboardingPolicy.confirm(step: PriorityUserEducationPlayback, screenReader: Boolean = true) =
        confirmEducationReviewedWithScreenReader(step, screenReaderActive = screenReader)

    @Test
    fun withTalkBackTheExplanatoryStepsCompleteInOrderByConfirmation() {
        val policy = adultPolicy()
        policy.confirm(PriorityUserEducationPlayback.PRACTICE_NECESSITY)
        assertFalse("order is kept: safety limits first", policy.snapshot().practiceNecessityReviewed)
        policy.confirm(PriorityUserEducationPlayback.APP_USAGE)
        assertFalse("usage education needs the safety consent first", policy.snapshot().appUsageReviewed)
        policy.confirm(PriorityUserEducationPlayback.SAFETY_LIMITS)
        policy.confirm(PriorityUserEducationPlayback.PRACTICE_NECESSITY)
        assertTrue(policy.snapshot().educationReviewed)
        assertTrue(policy.snapshot().practiceNecessityReviewed)
        policy.acceptEducationConsent()
        assertTrue(policy.snapshot().safetyEducationConsentComplete)
        policy.confirm(PriorityUserEducationPlayback.APP_USAGE)
        assertTrue(policy.snapshot().appUsageReviewed)
    }

    @Test
    fun withoutTalkBackAConfirmationRecordsNothing() {
        val policy = adultPolicy()
        PriorityUserEducationPlayback.entries.forEach { policy.confirm(it, screenReader = false) }
        val snapshot = policy.snapshot()
        assertFalse(snapshot.educationReviewed)
        assertFalse(snapshot.practiceNecessityReviewed)
        assertFalse(snapshot.appUsageReviewed)
    }

    @Test
    fun aBlockedAccountCannotRecordAScreenReview() {
        val policy = PriorityUserOnboardingPolicy() // age not selected
        policy.confirm(PriorityUserEducationPlayback.SAFETY_LIMITS)
        assertFalse(policy.snapshot().educationReviewed)
    }

    @Test
    fun aScreenReviewNeverCompletesPracticeOrOpensTheWalk() {
        val policy = adultPolicy()
        policy.confirm(PriorityUserEducationPlayback.SAFETY_LIMITS)
        policy.confirm(PriorityUserEducationPlayback.PRACTICE_NECESSITY)
        policy.acceptEducationConsent()
        policy.acknowledgeUsageConditions()
        policy.confirm(PriorityUserEducationPlayback.APP_USAGE)
        policy.acceptAppUsageEducation()
        val snapshot = policy.snapshot()
        assertTrue(snapshot.completedPractices.isEmpty())
        assertFalse(snapshot.interactivePracticeComplete)
        assertFalse(snapshot.appUsageAccepted)
        assertFalse(snapshot.nativeEducationComplete)
        assertFalse(policy.evaluate(environment).mayStartWalk)
    }

    @Test
    fun anAppSpeechPlaybackInProgressIsNotShortCutByAScreenReview() {
        val policy = adultPolicy()
        policy.confirm(PriorityUserEducationPlayback.SAFETY_LIMITS)
        policy.confirm(PriorityUserEducationPlayback.PRACTICE_NECESSITY)
        policy.acceptEducationConsent()
        val token = policy.beginAppUsageEducationPlayback()!!
        policy.confirm(PriorityUserEducationPlayback.APP_USAGE)
        assertFalse(policy.snapshot().appUsageReviewed)
        policy.finishAppUsageEducationPlayback(token, completed = false)
        assertFalse(policy.snapshot().appUsageReviewed)
    }

    @Test
    fun theProgressNoticeAsksTalkBackUsersToConfirmNotToPressListen() {
        val initial = adultPolicy().snapshot()
        val talkBack = PriorityUserEducationPresentation.progressNotice(initial, screenReaderReview = true)
        assertFalse(talkBack.contains("듣기를 눌러"))
        assertTrue(talkBack.contains("확인 버튼"))
        assertEquals(
            "1. 안전 제한 안내 듣기를 눌러 안내를 끝까지 들어 주세요.",
            PriorityUserEducationPresentation.progressNotice(initial),
        )
    }
}
