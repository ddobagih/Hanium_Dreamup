package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S02-11: with TalkBack on, education buttons record a screen review instead of starting app speech. */
class PriorityUserScreenReaderEducationStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )
    private val panel = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt",
    )
    private val presenter = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    )

    private val education = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/workflow/EducationInteractionController.kt",
    )
    private fun block(signature: String): String {
        val name = signature.substringAfter("fun ").substringBefore("(")
        return MainActivityRefactorSource.member(if (name in setOf("playNativeSafetyEducation",
            "confirmNativeEducationWithScreenReader")) education else activity, name)
    }

    @Test
    fun talkBackUsersConfirmBeforeAnyAppSpeechOrPlaybackTokenStarts() {
        val play = block("private fun playNativeSafetyEducation")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                play,
                "(practiceNecessity && !state.educationReviewed)) return",
                "if (admission.screenReaderActive()) {",
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
        assertTrue(confirm.contains("screenReaderActive = admission.screenReaderActive()"))
        assertTrue(confirm.contains("profiles.persistOrFailClosed()"))
        assertFalse(confirm.contains("voicePlaybackCompleted"))
        assertFalse(confirm.contains("finishAppUsageEducationPlayback"))
        assertFalse(confirm.contains("recordPracticeDelivery"))
        val ports = MainActivityRefactorSource.composition(activity, "educationInteractionController")
        assertTrue(ports.contains("override fun screenReaderActive(): Boolean = this@MainActivity.isScreenReaderActive()"))
        assertTrue(ports.contains("override fun persistOrFailClosed(): Boolean = this@MainActivity.persistPriorityUserOnboardingOrFailClosed()"))
    }

    @Test
    fun buttonsAndProgressTellTalkBackUsersToConfirm() {
        val ui = MainActivityRefactorSource.member(presenter, "updateNativeSafetyEducationUi")
        val facts = block("private fun updateNativeSafetyEducationUi")
        val content = block("private fun buildContentView")
        assertTrue(panel.contains("onClick = actions.education.onSafetyEducation"))
        assertTrue(panel.contains("onClick = actions.education.onPracticeNecessity"))
        assertTrue(panel.contains("onClick = actions.education.onEducationAgree"))
        assertTrue(content.contains("onSafetyEducation = ::reviewPriorityUserSafetyEducation"))
        assertTrue(content.contains("playNativeSafetyEducation(practiceNecessity = true)"))
        assertTrue(content.contains("onEducationAgree = ::acceptNativeSafetyEducation"))
        assertTrue(content.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        val registration = ReportStaticSourceInspector.read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt")
        val bind = MainActivityRefactorSource.member(registration, "bindRegistration")
        assertTrue(bind.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bind.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(presenter.contains("fun bindOnboardingPanel(panel: OnboardingReadinessPanel) { onboarding = panel }"))
        assertTrue(ui.contains("= with(onboarding)"))
        for (field in listOf("priorityUserEducationButton", "priorityUserPracticeNecessityButton", "priorityUserEducationAgreeButton")) {
            assertTrue(field, panel.contains("val $field"))
            assertTrue(field, ui.contains("$field.text ="))
        }
        assertTrue(MainActivityRefactorSource.member(activity, "reviewPriorityUserSafetyEducation")
            .contains("educationInteractionController.reviewPriorityUserSafetyEducation()"))
        assertTrue(MainActivityRefactorSource.member(education, "reviewPriorityUserSafetyEducation")
            .contains("playNativeSafetyEducation(practiceNecessity = false)"))
        assertTrue(facts.contains("val screenReaderReview = isScreenReaderActive()"))
        assertTrue(facts.contains("state, playback, educationInteractionController.presentationState().practiceInFlight, hazardResponseReady, screenReaderReview,"))
        assertTrue(facts.contains("screenReaderReview = screenReaderReview"))
        assertTrue(facts.contains("progress = progress"))
        assertTrue(ui.contains("val screenReaderReview = facts.screenReaderReview"))
        assertTrue(ui.contains("val progress = facts.progress"))
        assertTrue(ui.contains("\"1. 안전 제한 안내를 화면에서 모두 확인했습니다\""))
        assertTrue(ui.contains("\"2. 연습 필요성을 화면에서 모두 확인했습니다\""))
        assertTrue(ui.contains("\"사전 연습 설명을 화면에서 모두 확인했습니다\""))
    }
}
