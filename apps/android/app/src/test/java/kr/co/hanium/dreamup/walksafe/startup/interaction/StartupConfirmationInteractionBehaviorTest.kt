package kr.co.hanium.dreamup.walksafe.startup.interaction

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class StartupConfirmationInteractionBehaviorTest {
    @Test fun constructorDoesNotQueryOwnersAndDispatchAcceptanceDoesNotConfirmNotice() {
        val f=StartupConfirmationFixture(); assertTrue(f.trace.isEmpty())
        f.controller.confirmStartupCapabilityDecision()
        assertTrue(f.controller.confirmationPending()); assertNull(f.controller.confirmedDecision())
        assertTrue(f.current!!()); f.done!!()
        assertNull(f.controller.confirmedDecision()); f.drainUi()
        assertSame(f.decision,f.controller.confirmedDecision()); assertFalse(f.controller.confirmationPending())
        assertFalse(f.controller.retryRequired())
    }
    @Test fun duplicatePendingConfirmationDoesNotDispatchAgain() {
        val f=StartupConfirmationFixture(); f.controller.confirmStartupCapabilityDecision(); f.trace.clear()
        f.controller.confirmStartupCapabilityDecision()
        assertEquals(listOf("getDecision"),f.trace)
    }
    @Test fun currentnessIsRecheckedOnUiDeliveryAfterActorOrGenerationChanges() {
        for(change in listOf<(StartupConfirmationFixture)->Unit>({it.actor="other"},{it.generation++},{it.foreground=false})) {
            val f=StartupConfirmationFixture(); f.controller.confirmStartupCapabilityDecision(); f.done!!(); change(f); f.drainUi()
            assertNull(f.controller.confirmedDecision()); assertTrue(f.controller.confirmationPending()); assertFalse(f.current!!())
        }
    }
    @Test fun replacedWalkEpochCannotAcceptOldCompletionOrFailure() {
        val f=StartupConfirmationFixture(); f.controller.confirmStartupCapabilityDecision()
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        f.done!!(); f.failed!!(); f.drainUi()
        assertNull(f.controller.confirmedDecision()); assertTrue(f.controller.confirmationPending()); assertFalse(f.controller.retryRequired())
    }
    @Test fun ownedCancellationAndRetirementRequireExplicitRetryAndNeverConfirm() {
        for(retirement in listOf(false,true)) {
            val f=StartupConfirmationFixture(); f.controller.confirmStartupCapabilityDecision()
            if(retirement)f.retired!!() else f.cancelled!!(); f.drainUi()
            assertNull(f.controller.confirmedDecision()); assertFalse(f.controller.confirmationPending()); assertTrue(f.controller.retryRequired())
            assertTrue(f.statuses.single().second.contains("전달되지 않았습니다"))
        }
    }
    @Test fun rejectedDispatchUsesFailurePathImmediately() {
        val f=StartupConfirmationFixture(); f.accepted=false; f.controller.confirmStartupCapabilityDecision()
        assertTrue(f.controller.retryRequired()); assertFalse(f.controller.confirmationPending()); assertNull(f.controller.confirmedDecision())
    }
    @Test fun readinessBlockRequestsPermissionsThenExplainsWithoutPublishingConfirmation() {
        val f=StartupConfirmationFixture(); f.block="permission pending"; f.controller.confirmStartupCapabilityDecision()
        assertEquals(listOf("getDecision","readinessBlock","requestMissingPermissions","status"),f.trace)
        assertEquals("permission pending",f.statuses.single().second); assertNull(f.done)
    }
    @Test fun missingReadinessTokenCannotSpeakOrSetPending() {
        val f=StartupConfirmationFixture(); f.lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        f.controller.confirmStartupCapabilityDecision()
        assertNull(f.done); assertFalse(f.controller.confirmationPending()); assertTrue(f.statuses.single().second.contains("같은 보행 점검 세대"))
    }
    @Test fun explicitActionClearsRetryBeforePermissionRecoveryAndStopsLaterQueries() {
        val f=StartupConfirmationFixture(); f.controller.writeRetryRequired(true); f.recoverPermission=true
        f.controller.handleStartupCapabilityConfirmAction()
        assertFalse(f.controller.retryRequired()); assertNull(f.done)
        assertEquals(listOf("lifecycle","getResourceProbeInitialized","resumePermissionRecovery"),f.trace)
    }
    @Test fun endedWalkActionStartsFreshBeforeAnyResourceOrNoticeQueries() {
        val f=StartupConfirmationFixture(); f.lifecycle.handle(WalkSessionEvent.EndRequested)
        f.controller.writeRetryRequired(true); f.controller.handleStartupCapabilityConfirmAction()
        assertEquals(listOf("lifecycle","startFreshWalk","refresh"),f.trace)
        assertFalse(f.controller.retryRequired()); assertNull(f.done)
    }
    @Test fun pausedRecheckActionPreparesResumeBeforeResourcesAndRefreshesWithoutNotice() {
        val f=StartupConfirmationFixture(); f.lifecycle.handle(WalkSessionEvent.StartRequested(f.lifecycle.snapshot().confirmationToken!!))
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground); f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        assertEquals(WalkSessionState.PAUSED,f.lifecycle.snapshot().state)
        f.controller.handleStartupCapabilityConfirmAction()
        assertEquals(listOf("lifecycle","prepareResumeRetry","getResourceProbeInitialized","resumePermissionRecovery","refresh"),f.trace)
        assertNull(f.done)
    }
    @Test fun persistenceDeduplicatesAfterMarkingEvenWhenNativeWriteFails() {
        val f=StartupConfirmationFixture(); val decision=f.decision!!; f.throwOnWrite=true
        assertThrows(IllegalStateException::class.java) { f.controller.persistStartupCapabilityDecision(decision) }
        f.controller.persistStartupCapabilityDecision(decision)
        assertEquals(listOf(decision),f.persisted); assertEquals(1,f.trace.count { it=="profileMatch" })
    }
    @Test fun quickStartBypassShortCircuitsPersistenceBeforeSecondFlagOrProfileQuery() {
        val f=StartupConfirmationFixture(); f.quick=true; f.controller.persistStartupCapabilityDecision(f.decision!!)
        assertEquals(listOf("getQuickStart"),f.trace); assertTrue(f.persisted.isEmpty())
    }
    @Test fun externalLegacyStateDelegatesPointAtTheSameConfirmationOwner() {
        val f=StartupConfirmationFixture(); f.controller.writeConfirmedDecision(f.decision); f.controller.writeConfirmationPending(true)
        assertTrue(f.controller.isStartupCapabilityConfirmed()); assertTrue(f.controller.confirmationPending())
        f.controller.writeConfirmedDecision(null); assertFalse(f.controller.isStartupCapabilityConfirmed())
    }
    @Test fun explicitTouchKeepsPermissionAndNoticeGatesButSelectsButtonDespiteAvailableVoice() {
        for (preferTouch in listOf(false, true)) {
            val f = StartupConfirmationFixture(); f.voiceAvailable = true
            f.lifecycle.handle(WalkSessionEvent.StartRequested(f.lifecycle.snapshot().confirmationToken!!))
            f.lifecycle.handle(WalkSessionEvent.RecheckRequested)
            val epoch = f.lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY)) }
            f.lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(collector.build(1_000L)))
            f.controller.handleStartupCapabilityConfirmAction(preferTouchResume = preferTouch)
            assertNotNull("An explicit touch cannot skip the required notice", f.done)
            assertFalse(f.trace.contains("confirmResumeButton"))
            assertFalse(f.controller.isStartupCapabilityConfirmed())
            f.done!!(); f.drainUi(); assertTrue(f.controller.isStartupCapabilityConfirmed())
            f.trace.clear(); f.recoverPermission = true
            f.controller.handleStartupCapabilityConfirmAction(preferTouchResume = preferTouch)
            assertFalse(f.trace.contains("confirmResumeButton"))
            f.recoverPermission = false; f.trace.clear()
            f.controller.handleStartupCapabilityConfirmAction(preferTouchResume = preferTouch)
            assertEquals(preferTouch, f.trace.contains("confirmResumeButton"))
            assertEquals(!preferTouch, f.trace.contains("voiceResumeAvailable"))
            assertEquals(!preferTouch, f.trace.contains("refresh"))
        }
    }

}
