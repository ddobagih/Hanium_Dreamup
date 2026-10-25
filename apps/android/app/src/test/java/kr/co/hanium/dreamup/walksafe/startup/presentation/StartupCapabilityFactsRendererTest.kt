package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.DeviceCheckPresenter
import org.junit.Assert.*
import org.junit.Test

class StartupCapabilityFactsRendererTest {
    @Test fun constructionDoesNotReadOwnersAndUnpassedDeviceSkipsSdkSettings() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); f.render()
        val facts = requireNotNull(f.output)
        assertFalse(f.events.contains("restrictions")); assertFalse(f.events.contains("permissions"))
        assertFalse(f.events.contains("service")); assertFalse(f.events.contains("speech-settings"))
        assertTrue(facts.settings.locationServiceEnabled)
        assertEquals(listOf("device", "items", "binding", "deferred", "walk"), f.events.take(5))
        assertEquals("render", f.events.last())
    }
    @Test fun currentRestrictionAndPermissionChecksKeepShortCircuitAndRenderOrder() {
        val f = Fixture(); f.snapshot = f.snapshot.copy(state = PostLoginDeviceCheckState.FULL)
        f.restricted = true; f.permissionsMissing = true; f.explicitResume = true
        f.render()
        val facts = requireNotNull(f.output)
        assertEquals(listOf("device", "restrictions", "disabled", "items", "binding", "deferred", "permissions", "walk"), f.events.take(8))
        assertTrue(facts.settings.requiredPermissionsMissing); assertTrue(facts.settings.locationServiceEnabled)
        assertFalse(f.events.contains("service")); assertEquals(listOf("render", "explicit-resume"), f.events.takeLast(2))
        assertEquals("카메라", facts.check.disabledFeaturesText)
        f.events.clear(); f.permissionsMissing = false; f.service = false; f.render()
        assertFalse(requireNotNull(f.output).settings.locationServiceEnabled)
        assertEquals(1, f.events.count { it == "service" })
    }
    @Test fun failureAndKoreanSpeechDetailAreReadOnlyForTheCurrentFailure() {
        val f = Fixture(); f.snapshot = f.snapshot.copy(state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE)
        f.render()
        val facts = requireNotNull(f.output)
        assertEquals("실패:KOREAN_TTS_UNAVAILABLE", facts.check.failureMessage)
        assertEquals("음성 설정", facts.settings.koreanSpeechSettingsButtonText)
        assertTrue(f.events.indexOf("failure") < f.events.indexOf("items"))
        assertFalse(f.events.contains("service"))
        f.events.clear(); f.snapshot = f.snapshot.copy(failure = PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE)
        f.render(); assertNull(requireNotNull(f.output).settings.koreanSpeechSettingsButtonText)
        assertFalse(f.events.contains("speech-settings"))
    }
    @Test fun newWalkAndConfirmationFactsAreReadAtEachRenderWithoutCachedAuthority() {
        val f = Fixture(); f.snapshot = f.snapshot.copy(state = PostLoginDeviceCheckState.LIMITED)
        f.awaitingResume = true; f.complete = false; f.retry = true; f.render()
        val first = requireNotNull(f.output)
        assertTrue(first.confirmation.awaitingExplicitResume); assertFalse(first.confirmation.onboardingComplete)
        assertTrue(first.confirmation.retryRequiresUserAction); assertEquals(WalkSessionState.READY, first.confirmation.walkState)
        f.lifecycle.handle(WalkSessionEvent.EndRequested); f.awaitingResume = false; f.complete = true; f.retry = false
        f.render(); val second = requireNotNull(f.output)
        assertEquals(WalkSessionState.ENDED, second.confirmation.walkState)
        assertFalse(second.confirmation.awaitingExplicitResume); assertTrue(second.confirmation.onboardingComplete)
        assertFalse(second.confirmation.confirmationTokenPresent); assertFalse(second.confirmation.retryRequiresUserAction)
        assertFalse(f.events.contains("restrictions")) // LIMITED branch reads disabled text directly
    }
    private class Fixture : StartupCapabilityPresentationFacts, StartupCapabilityPresentationEffects {
        val events = mutableListOf<String>()
        var snapshot = PostLoginDeviceCheckPolicy.initial()
        val lifecycle = WalkSessionLifecycle({ "presentation" }, { 1_000L })
        var restricted = false; var permissionsMissing = false; var service = true; var explicitResume = false
        var awaitingResume = false; var complete = true; var retry = false
        var output: DeviceCheckPresenter.StartupFacts? = null
        val renderer = StartupCapabilityFactsRenderer(this, this)
        fun render() = renderer.renderStartupCapabilityFacts(
            WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "준비됨"),
            true, PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION, null, null, emptyList(), false), true, true)
        override fun deviceSnapshot() = snapshot.also { events += "device" }
        override fun hasCurrentRestrictions() = restricted.also { events += "restrictions" }
        override fun disabledFeatureText() = "카메라".also { events += "disabled" }
        override fun failureMessage(failure: PostLoginDeviceCheckFailure?) = "실패:$failure".also { events += "failure" }
        override fun itemsMessage() = "항목".also { events += "items" }
        override fun hasCurrentDeviceBinding() = true.also { events += "binding" }
        override fun deferredChecksRetriable() = false.also { events += "deferred" }
        override fun requiredPermissionsMissing() = permissionsMissing.also { events += "permissions" }
        override fun locationServiceEnabled() = service.also { events += "service" }
        override fun walkSnapshot() = lifecycle.snapshot().also { events += "walk" }
        override fun onboardingStage() = FirstRunOnboardingStage.COMPLETE
        override fun storedBindingValidationPending() = false
        override fun locationRequestFailed() = false
        override fun locationFixReason(): DeviceCheckLocationFixReason? = null
        override fun koreanSpeechSettingsButtonText() = "음성 설정".also { events += "speech-settings" }
        override fun wakeSignal() = PostLoginDeviceCheckSignal.PENDING
        override fun voiceModelReady() = false
        override fun wakeProbeActive() = false
        override fun wakeStartScheduled() = false
        override fun awaitingExplicitResume() = awaitingResume
        override fun onboardingComplete() = complete
        override fun capabilityConfirmationPending() = false
        override fun resumePromptPending() = false
        override fun quickStartEnabled() = false
        override fun retryRequiresUserAction() = retry
        override fun render(facts: DeviceCheckPresenter.StartupFacts): Boolean { events += "render"; output = facts; return explicitResume }
        override fun renderExplicitResume() { events += "explicit-resume" }
    }
}
