package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks complement native output/owner behavior tests; no Activity is launched here. */
class MainActivityRestoredLoginSpeechStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/onboarding/FirstRunOnboardingController.kt").readText()
    private fun code(source: String) = ReportStaticSourceInspector.maskNonCode(source)

    @Test fun onlyVerifiedStoredLoginCallbackSuppliesTheRestoredAnnouncementPurpose() {
        val gateway = MainActivityRefactorSource.composition(source, "gatewaySessionController")
        val restored = code(ReportStaticSourceInspector.functionBlock(gateway, "override fun firstRunChanged"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(restored,
            "firstRunOnboardingController.onFirstRunOnboardingStateChanged(", "message,",
            "announcementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN"))
        assertEquals(1, Regex("announcementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN")
            .findAll(code(source)).count())
    }

    @Test fun restoredNoticeUsesAnExplicitCurrentHomeGuardAtAdmissionAndNativeStart() {
        val composition = MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
        val effect = code(ReportStaticSourceInspector.functionBlock(composition, "override fun speakInteraction"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(effect,
            "val restoredLoginCurrent = {", "nativeUiPage == NativeUiPage.HOME &&",
            "nativeHomeFeatureContextAvailable() && !isScreenReaderActive()",
            "val restoredLogin = purpose == FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN",
            "val restoredLoginAllowed = restoredLogin && restoredLoginCurrent()",
            "if (restoredLogin)", "if (restoredLoginAllowed)",
            "speechOutputController.speakCommandResponse(message, statusExplanation = true,",
            "additionalCurrent = restoredLoginCurrent)", "else", "this@MainActivity.speakInteraction(message)"))
        val restoredBranch = ReportStaticSourceInspector.blockAfter(effect, "if (restoredLogin)")
        assertTrue(restoredBranch.contains("if (restoredLoginAllowed)"))
        assertFalse(restoredBranch.contains("this@MainActivity.speakInteraction(message)"))
        val admission = code(MainActivityRefactorSource.member(source, "authorizeNativeFeedbackSpeech"))
        assertTrue(admission.contains("if (!navigationSpeechCapabilityAllowed()) return false"))
        assertTrue(admission.contains("if (externalFallbackSpeechOutputActive(nowElapsedRealtimeMs, screenReaderActive)) return false"))
        assertFalse(effect.contains("startupCapabilityDecision ="))
        assertFalse(effect.contains("offlineKoreanTextToSpeechCapabilityOverride ="))
    }

    @Test fun transitionToEducationDoesNotInheritARestoredLoginAnnouncementPurpose() {
        val changed = code(MainActivityRefactorSource.member(owner, "onFirstRunOnboardingStateChanged"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(changed,
            "if (!effects.screenReaderActive())",
            "val restoredDeviceCheckNeedsEducation = restoredDeviceCheckAdvanced && !snapshot.isComplete",
            "val effectiveAnnouncement = if (restoredDeviceCheckNeedsEducation)",
            "effects.speakInteraction(", "effectiveAnnouncement,",
            "if (restoredDeviceCheckNeedsEducation) AnnouncementPurpose.STATE_CHANGE else announcementPurpose"))
    }
    @Test fun cachedEducationCompletionForwardsTheOriginOnlyThroughTheSuccessfulCommit() {
        val changed = code(MainActivityRefactorSource.member(owner, "onFirstRunOnboardingStateChanged"))
        assertTrue(changed.contains("completeFirstRunFp004TrainingIfReady(firstRunActorId, announcementPurpose)"))
        val completed = code(MainActivityRefactorSource.member(owner, "completeFirstRunFp004TrainingIfReady"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(completed,
            "announcementPurpose: AnnouncementPurpose = AnnouncementPurpose.STATE_CHANGE",
            "profiles.persistNativeCompletedOnboarding(completed.current)",
            "gateway.publishCompletedSnapshot(actorId, snapshot = completed.current)",
            "if (snapshot !== before && snapshot != completed.current) return false",
            "snapshot = completed.current", "onFirstRunOnboardingStateChanged(",
            "announcementPurpose = announcementPurpose"))
    }

}
