package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S02-11: with TalkBack on, education buttons record a screen review instead of starting app speech. */
class PriorityUserScreenReaderEducationStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )

    private fun block(signature: String) = ReportStaticSourceInspector.functionBlock(activity, signature)

    @Test
    fun talkBackUsersConfirmBeforeAnyAppSpeechOrPlaybackTokenStarts() {
        val play = block("private fun playNativeSafetyEducation")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                play,
                "(practiceNecessity && !state.educationReviewed)) return",
                "if (isScreenReaderActive()) {",
                "confirmNativeEducationWithScreenReader(policy, usageEducation, practiceNecessity)",
                "return",
                "policy.beginAppUsageEducationPlayback()",
                "actuator.speakPriorityUserTraining(",
            ),
        )
    }

    @Test
    fun theScreenReviewIsNeverRecordedAsAVoicePlaybackSuccess() {
        val confirm = block("private fun confirmNativeEducationWithScreenReader")
        assertTrue(confirm.contains("policy.confirmEducationReviewedWithScreenReader("))
        assertTrue(confirm.contains("screenReaderActive = isScreenReaderActive()"))
        assertTrue(confirm.contains("persistPriorityUserOnboardingOrFailClosed()"))
        assertFalse(confirm.contains("voicePlaybackCompleted"))
        assertFalse(confirm.contains("finishAppUsageEducationPlayback"))
        assertFalse(confirm.contains("recordPracticeDelivery"))
    }

    @Test
    fun buttonsAndProgressTellTalkBackUsersToConfirm() {
        val ui = block("private fun updateNativeSafetyEducationUi")
        assertTrue(ui.contains("val screenReaderReview = isScreenReaderActive()"))
        assertTrue(ui.contains("state, playback, priorityUserPracticeInFlight, hazardResponseReady, screenReaderReview,"))
        assertTrue(ui.contains("\"1. 안전 제한 안내를 화면에서 모두 확인했습니다\""))
        assertTrue(ui.contains("\"2. 연습 필요성을 화면에서 모두 확인했습니다\""))
        assertTrue(ui.contains("\"사전 연습 설명을 화면에서 모두 확인했습니다\""))
    }
}
