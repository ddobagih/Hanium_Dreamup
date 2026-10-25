package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.readiness.StartupCapabilityReadinessEvidence
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import kr.co.hanium.dreamup.walksafe.startup.capability.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

/** Real startup refresh -> prewalk -> admission -> gateway/lifecycle; SDK and server leaves are controlled. */
class StartupPrewalkAdmissionContinuityTest {
    @Test fun expiredReadinessReplacementContinuesWithoutAnExtraSensorOrUiEvent() {
        val f = Fixture()
        val old = f.prepareExpiredStart()
        assertEquals(1, f.posts.size)
        assertNotEquals(old, f.walk.gateway.lifecycle.snapshot().confirmationToken)
        assertNull(f.confirmation.confirmed)
        f.runPosted()
        assertNull("The old callback cannot confirm a replacement token", f.confirmation.confirmed)
        assertEquals("The current token must get its own confirmation", 1, f.posts.size)
        f.runPosted()
        assertSame(f.walk.decisionValue, f.confirmation.confirmed)
        assertEquals(1, f.walk.gateway.background.size)
        f.walk.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.walk.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.walk.gateway.activations)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun currentTokenStartsWithOneConfirmationWithoutReplacement() {
        val f = Fixture()
        f.walk.gateway.prepare()
        f.begin()
        f.startup.refreshStartupCapabilityUi()
        assertEquals(1, f.posts.size)
        f.runPosted()
        assertTrue(f.posts.isEmpty())
        f.walk.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.walk.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.walk.gateway.activations)
    }

    @Test fun canceledOrReplacedOwnerCannotRepostOrStartFromTheOldCallback() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.native.cancel(cancelFeatureEntry = true) },
            { it.actor = "different-actor" },
            { it.gatewayGeneration++ },
            { it.walk.gateway.lifecycle.handle(WalkSessionEvent.EndRequested) },
            { it.data.workflow.pendingUiDestination = null },
            { it.foreground = false },
        )
        for (change in changes) {
            val f = Fixture(); f.prepareExpiredStart(); change(f); f.runPosted()
            assertTrue(f.posts.isEmpty())
            assertNull(f.confirmation.confirmed)
            assertTrue(f.walk.gateway.background.isEmpty())
            assertEquals(0, f.walk.gateway.activations)
        }
    }

    @Test fun replacedTokenCannotBypassCurrentEnvironmentOrCapabilityFailure() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.environmentReady = false },
            { it.walk.decisionValue = it.walk.decisionValue.copy(
                tier = WalkSafeStartupCapabilityTier.BLOCKED,
                unavailableRequirements = listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)) },
            { it.walk.blockedReason = "permission_revoked" },
        )
        for (change in changes) {
            val f = Fixture(); f.prepareExpiredStart(); change(f); f.runPosted()
            assertTrue(f.posts.isEmpty())
            assertNull(f.confirmation.confirmed)
            assertTrue(f.walk.gateway.background.isEmpty())
        }
    }

    @Test fun olderQueuedConfirmationCannotReleaseANewerRequestConfirmation() {
        val f = Fixture()
        f.walk.gateway.prepare(); f.begin()
        val older = f.posts.removeAt(0)
        f.native.cancel(cancelFeatureEntry = false)
        f.begin()
        assertEquals(1, f.posts.size)
        older.invoke()
        f.startup.refreshStartupCapabilityUi()
        assertEquals("The retired request must leave the current confirmation pending", 1, f.posts.size)
        assertNull(f.confirmation.confirmed)
        f.runPosted(); f.walk.gateway.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.walk.gateway.lifecycle.snapshot().state)
        assertEquals(1, f.walk.gateway.activations)
    }

    private class Fixture {
        val walk = AdmissionFixture()
        val data = NativeGuidanceTestFixture()
        val posts = mutableListOf<() -> Unit>()
        var actor: String? = "actor"
        var gatewayGeneration = 3L
        var foreground = true
        var environmentReady = true
        val confirmation = object : StartupCapabilityConfirmation {
            override var confirmed: WalkSafeStartupCapabilityDecision? = null
            override var pending = false
            override var retryRequiresUserAction = false
            override fun isConfirmed() = confirmed == walk.decisionValue
        }
        val evidence = StartupCapabilityReadinessEvidence({ walk.decisionValue }, { "test" }, { walk.gateway.now })
        val admission: WalkSessionAdmissionController = WalkSessionAdmissionController(walk, walk.gateway.authority, walk.gateway.controller,
            walk.resume.controller, object : WalkResumeReadiness by walk {
                override fun isConfirmed() = confirmation.isConfirmed()
            }, walk, object : WalkAdmissionEffects by walk {
                override fun refreshReadinessUi() { startup.refreshStartupCapabilityUi() }
            }, setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), { walk.gateway.now })
        val native: NativePrewalkController = NativePrewalkController(data.workflow,
            fakePort<NativePrewalkController.Scope> { name, _ -> when (name) {
                "homeAvailable" -> true; "foreground" -> foreground; "reporterId" -> actor
                "gatewayGeneration" -> gatewayGeneration; "walk" -> walk.gateway.lifecycle.snapshot()
                "runtimeActive" -> walk.gateway.lifecycle.snapshot().state == WalkSessionState.ACTIVE
                else -> null
            } }, fakePort<NativePrewalkController.Checks> { name, _ -> when (name) {
                "firstRunComplete", "environmentProfilesPresent", "officialProfilePresent", "mountingProfilePresent" -> true
                "environmentReadiness", "mountingReadiness" -> if (environmentReady) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.PENDING
                "startupDecision" -> walk.decisionValue
                "readinessBlockReason" -> walk.blockedReason
                "confirmationPending" -> confirmation.pending
                "startupConfirmed" -> confirmation.isConfirmed()
                else -> null
            } }, fakePort(), fakePort<NativePrewalkController.Actions> { name, args -> when (name) {
                "calibrationReady" -> true
                "postStartup" -> { @Suppress("UNCHECKED_CAST") posts += args[0] as () -> Unit; null }
                "completeStartupConfirmation" -> { confirmation.confirmed = args[0] as WalkSafeStartupCapabilityDecision; null }
                "renderMain" -> { startup.refreshStartupCapabilityUi(); null }
                "cancelPendingFeatureEntry" -> { data.workflow.pendingNativeUiPage = null; null }
                else -> null
            } }, fakePort(), { walk.gateway.now })
        val startup: StartupCapabilityCoordinator = StartupCapabilityCoordinator(
            fakePort<StartupCapabilityEvaluation> { name, _ -> when (name) {
                "probeDecision" -> walk.decisionValue; else -> null
            } }, { evidence }, { walk.gateway.lifecycle }, confirmation,
            fakePort<StartupCapabilityUi> { name, _ -> when (name) {
                "ready" -> true
                "priority" -> PriorityUserOnboardingDecision(PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION, null, null, emptyList(), false)
                "officialReadiness", "mountingReadiness" -> WalkSessionReadinessStatus.READY
                "updatePrewalk" -> { native.continueIfReady(); null }
                "advanceAfterCheck" -> { admission.maybeAdvanceWalkSessionAfterCapabilityCheck(); null }
                else -> null
            } }, fakePort(), "voice unavailable")

        fun begin() {
            data.workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
            data.workflow.pendingUiDestination = data.destination
            native.beginFromHome()
        }

        fun prepareExpiredStart(): WalkSessionConfirmationToken {
            val old = walk.gateway.prepare()
            walk.gateway.now += 30_001L
            begin()
            return old
        }

        fun runPosted() { posts.removeAt(0).invoke() }
    }
}
