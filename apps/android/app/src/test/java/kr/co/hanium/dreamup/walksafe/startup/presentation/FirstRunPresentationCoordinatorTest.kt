package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.*
import org.junit.Assert.*
import org.junit.Test

class FirstRunPresentationCoordinatorTest {
    @Test fun constructorAndUnpublishedEntryHaveNoLiveOrSdkQueries() {
        val f = Fixture(); assertTrue(f.events.isEmpty())
        f.initialized = false; f.controller.updateFirstRunOnboardingUi()
        assertEquals(listOf("initialized"), f.events)
        f.events.clear(); f.initialized = true; f.publication.clear(); f.controller.updateFirstRunOnboardingUi()
        assertEquals(listOf("initialized", "published:FIRST_RUN_ONBOARDING_STATUS_TEXT"), f.events)
        assertTrue(f.renders.isEmpty())
    }

    @Test fun unpublishedWaitingAndConsentTargetsDoNotReadSdkOrDraft() {
        val f = Fixture(); f.publication -= NativeUiPublication.Target.FIRST_RUN_WAITING_CARD
        f.publication -= NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON
        f.controller.updateFirstRunOnboardingUi()
        assertFalse(f.events.contains("animations")); assertFalse(f.events.contains("all-consent"))
        assertFalse(f.renders.contains("waiting")); assertFalse(requireNotNull(f.consent).allGranted)
        assertEquals(listOf("stage", "account", "consent-stage", "consent-update", "bind", "preparation", "readiness", "runtime", "features", "home", "notice", "progress", "privacy", "main"), f.renders)
    }

    @Test fun waitingReceiverPrecedesSdkAndLiveForegroundObservations() {
        val f = Fixture(); f.accountInFlight = true; f.foreground = false; f.animations = false
        f.controller.updateFirstRunOnboardingUi()
        assertEquals(listOf("render:waiting", "account-request", "foreground", "animations"),
            f.events.dropWhile { it != "render:waiting" }.take(4))
        val waiting = requireNotNull(f.waiting)
        assertTrue(requireNotNull(waiting.text).contains("로그인을 처리"))
        assertFalse(waiting.activityForeground); assertFalse(waiting.animationsEnabled)
        f.events.clear(); f.failWaitingReceiver = true
        try { f.controller.updateFirstRunOnboardingUi(); fail("missing original presenter must throw first") }
        catch (_: MissingPresenter) { }
        assertFalse(f.events.contains("animations")); assertFalse(f.events.contains("foreground"))
    }

    @Test fun capturedStageAndLiveCompletionRetainOriginalOrdering() {
        val f = Fixture(); f.snapshot = completed(); f.walkState = WalkSessionState.ACTIVE
        val captured = f.snapshot
        f.onAccount = { f.snapshot = FirstRunOnboardingPolicy.initialEmailAccount(2) }
        f.controller.updateFirstRunOnboardingUi()
        assertSame(captured, f.accountSnapshot)
        assertTrue(requireNotNull(f.stage).firstRunComplete)
        assertEquals(7, requireNotNull(f.stage).stageNumber)
        assertTrue(requireNotNull(f.preparation).showWalkPreparation) // captured stage, as before
        assertFalse(requireNotNull(f.preparation).mayUseWalk) // live account completion after update
        assertFalse(requireNotNull(f.runtime).runtimeVisible)
        assertFalse(f.events.contains("permission-block")) // short-circuited by mayUseWalk
    }

    @Test fun activeRuntimeUsesCurrentPermissionBoundaryAndQuickStartRequestsShortCircuit() {
        val f = Fixture(); f.snapshot = completed(); f.walkState = WalkSessionState.ACTIVE
        f.permissionBlocks = true; f.quickStart = true; f.quickRequested = true
        f.controller.updateFirstRunOnboardingUi()
        assertTrue(requireNotNull(f.preparation).walkScreenVisible)
        assertFalse(requireNotNull(f.preparation).mayTrain)
        assertFalse(requireNotNull(f.runtime).runtimeVisible)
        assertFalse(requireNotNull(f.development).enabled)
        val tail = f.events.dropWhile { it != "render:development" }
        assertEquals("quick-requested", tail[1])
        assertFalse(tail.contains("account-request")); assertFalse(tail.contains("permission-request"))
        f.permissionBlocks = false; f.quickRequested = false; f.controller.updateFirstRunOnboardingUi()
        assertTrue(requireNotNull(f.runtime).runtimeVisible); assertTrue(requireNotNull(f.development).enabled)
    }

    @Test fun stageAndWaitingHelpersKeepActualEmailAndLegacyFlowDistinct() {
        val f = Fixture(); val email = FirstRunOnboardingPolicy.initialEmailAccount()
        val legacy = FirstRunOnboardingPolicy.initial()
        assertEquals(1, f.controller.firstRunStageNumber(email)); assertEquals(7, f.controller.firstRunStageCount(email))
        assertEquals(12, f.controller.firstRunStageCount(legacy)); assertNull(f.controller.firstRunWaitingTextOrNull(legacy))
        assertFalse(f.events.contains("account-request"))
        f.accountInFlight = true
        assertNotNull(f.controller.firstRunWaitingTextOrNull(email))
        assertEquals(7, f.controller.firstRunStageNumber(completed()))
    }

    private class MissingPresenter : RuntimeException()
    private class Fixture : FirstRunPresentationFacts, FirstRunPresentationEffects {
        val events = mutableListOf<String>(); val renders = mutableListOf<String>()
        val publication = NativeUiPublication.Target.entries.toMutableSet()
        private var initializedValue = true
        override var initialized: Boolean get() { events += "initialized"; return initializedValue }
            set(v) { initializedValue = v }
        private var foregroundValue = true
        override var foreground: Boolean get() { events += "foreground"; return foregroundValue }
            set(v) { foregroundValue = v }
        var quickStart = false; override val quickStartEnabled get() = quickStart
        override val guidanceBypassEnabled = false
        var snapshot = FirstRunOnboardingPolicy.initialEmailAccount()
        var walkState = WalkSessionState.READY; var accountInFlight = false; var animations = true
        var quickRequested = false; var permissionBlocks = false; var failWaitingReceiver = false
        var onAccount: (() -> Unit)? = null
        var stage: FirstRunOnboardingPresenter.StageFacts? = null
        var consent: FirstRunOnboardingPresenter.ConsentStageFacts? = null
        var waiting: FirstRunOnboardingPresenter.WaitingFacts? = null
        var preparation: FirstRunOnboardingPresenter.PreparationFacts? = null
        var runtime: FirstRunOnboardingPresenter.RuntimeFacts? = null
        var development: FirstRunOnboardingPresenter.DevelopmentFacts? = null
        var accountSnapshot: FirstRunOnboardingSnapshot? = null
        val controller = FirstRunPresentationCoordinator(this, this, "모두 동의", 7, 12)
        override fun published(target: NativeUiPublication.Target) = (target in publication).also { events += "published:$target" }
        override fun snapshot() = snapshot.also { events += "snapshot" }
        override fun complete() = snapshot.isComplete.also { events += "complete" }
        override fun showPhonePosture() = true.also { events += "posture" }
        override fun allConsentGranted() = true.also { events += "all-consent" }
        override fun animationsEnabled() = animations.also { events += "animations" }
        override fun cameraAnalysisEnabled() = true.also { events += "camera" }
        override fun hasCurrentDeviceBinding() = true.also { events += "binding" }
        override fun walkStateOrReady() = walkState.also { events += "walk" }
        override fun permissionRecoveryBlocksStart() = permissionBlocks.also { events += "permission-block" }
        override fun developmentButtonPresent() = true
        override fun quickStartRequested() = quickRequested.also { events += "quick-requested" }
        override fun accountRequestInFlight() = accountInFlight.also { events += "account-request" }
        override fun permissionRequestInFlight() = false.also { events += "permission-request" }
        override fun isAccountEntryScreen() = true.also { events += "account-screen" }
        private fun render(name: String) { events += "render:$name"; renders += name }
        override fun renderStage(facts: () -> FirstRunOnboardingPresenter.StageFacts) { render("stage"); stage = facts() }
        override fun updateAccount(snapshot: FirstRunOnboardingSnapshot) { render("account"); accountSnapshot = snapshot; onAccount?.invoke() }
        override fun renderConsentStage(facts: () -> FirstRunOnboardingPresenter.ConsentStageFacts) { render("consent-stage"); consent = facts() }
        override fun updateConsent() { render("consent-update") }
        override fun renderWaiting(facts: () -> FirstRunOnboardingPresenter.WaitingFacts) { render("waiting"); if (failWaitingReceiver) throw MissingPresenter(); waiting = facts() }
        override fun bindRuntimeViews() { render("bind") }
        override fun renderPreparation(facts: () -> FirstRunOnboardingPresenter.PreparationFacts) { render("preparation"); preparation = facts() }
        override fun updateReadiness() { render("readiness") }
        override fun renderRuntime(facts: () -> FirstRunOnboardingPresenter.RuntimeFacts) { render("runtime"); runtime = facts() }
        override fun updateFeatureAvailability() { render("features") }
        override fun renderDevelopmentControl(facts: () -> FirstRunOnboardingPresenter.DevelopmentFacts) { render("development"); development = facts() }
        override fun refreshHome() { render("home") }
        override fun refreshNotice() { render("notice") }
        override fun renderProgress(facts: () -> FirstRunOnboardingPresenter.ProgressFacts) { render("progress"); facts() }
        override fun updatePrivacyVisibility() { render("privacy") }
        override fun renderMain() { render("main") }
    }
    companion object {
        private fun completed() = FirstRunOnboardingPolicy.initialEmailAccount().copy(
            stage = FirstRunOnboardingStage.COMPLETE, ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
            completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
            verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider("actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
    }
}
