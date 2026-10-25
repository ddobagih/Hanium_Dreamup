package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkSessionTransitionControllerBehaviorTest {
    @Test fun noMachineOrLeaseReadsDuringConstruction() {
        val f = TransitionFixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.machineReads)
    }
    @Test fun publicationFenceObservesOldSnapshotBeforePublishedStateOrDownstreamEffects() {
        val f = TransitionFixture(); val old = f.gateway.lifecycle.snapshot()
        val t = f.controller.transitionWalkSession(WalkSessionEvent.EnteredForeground)
        assertTrue(t.changed); assertEquals(old, f.atFence)
        assertEquals("beforePublication", f.events.first())
        assertTrue(f.events.indexOf("updateQueueDrain") < f.events.indexOf("handleRawCollectionTransition"))
        assertEquals(t.current, f.gateway.lifecycle.snapshot())
    }
    @Test fun failedFenceCannotPublishNewSnapshotOrRunDependentEffects() {
        val f = TransitionFixture(); val old = f.gateway.lifecycle.snapshot(); f.failFence = true
        try { f.controller.transitionWalkSession(WalkSessionEvent.EnteredForeground); fail("expected fence failure") } catch (_: IllegalStateException) { }
        assertEquals(old, f.gateway.lifecycle.snapshot()); assertEquals(listOf("beforePublication"), f.events)
    }
    @Test fun pausedTransitionRebindsSameLeaseToNewEpochAndSchedulesRenewal() {
        val f = TransitionFixture(); val token = f.gateway.active(); val lease = f.gateway.authority.activeLeaseOrNull(token.epoch)
        val t = f.controller.transitionWalkSession(WalkSessionEvent.RecheckRequested)
        assertEquals(WalkSessionState.PAUSED, t.current.state)
        assertEquals(lease, f.gateway.authority.activeLeaseOrNull(t.current.epoch))
        assertNull(f.gateway.authority.activeLeaseOrNull(token.epoch))
        assertTrue(f.gateway.scheduled.isNotEmpty())
        assertTrue(f.events.indexOf("clearResumeActivation") < f.events.indexOf("updateQueueDrain"))
    }
    @Test fun endRetiresAuthorityBeforePublicationAndSendsExactlyItsHeldLeaseAfterRawTransition() {
        val f = TransitionFixture(); val token = f.gateway.active()
        f.controller.transitionWalkSession(WalkSessionEvent.EndRequested)
        assertNull(f.gateway.authority.activeLeaseOrNull(token.epoch))
        assertFalse(f.leaseHeldAtFence)
        assertEquals("maybeStartPostWalkQueueDrain", f.events.last())
        f.gateway.runBackground(); assertEquals(1, f.gateway.ends.size)
        assertEquals(token.epoch.walkSessionId, f.gateway.ends.single().second.walkId)
    }
    @Test fun safetyStopRecordsActualTransitionBeforeCleanupEffectsAndStopsServerOnce() {
        val f = TransitionFixture(); f.gateway.active()
        f.controller.transitionWalkSession(WalkSessionEvent.SafetyStopRequested)
        assertTrue(f.events.indexOf("recordSafetyStopTransition") < f.events.indexOf("hidePermissionDenial"))
        assertEquals(1, f.gateway.background.size)
        f.gateway.runBackground(); assertEquals(1, f.gateway.ends.size)
    }
}

internal class TransitionFixture {
    val gateway = GatewayFixture(); val events = mutableListOf<String>(); var machineReads = 0
    var atFence: WalkSessionSnapshot? = null; var leaseHeldAtFence = false; var failFence = false
    val controller = WalkSessionTransitionController(
        lifecycle = { machineReads++; gateway.lifecycle }, authority = gateway.authority,
        gateway = gateway.controller, gatewaySession = { gateway.account },
        effects = cleanupPort<WalkTransitionEffects> { name, _ ->
            events += name
            if (name == "beforePublication") {
                atFence = gateway.lifecycle.snapshot()
                leaseHeldAtFence = gateway.authority.activeLeaseOrNull(atFence!!.epoch) != null
                if (failFence) throw IllegalStateException("fence")
            }; Unit
        },
        reports = cleanupPort<WalkTransitionReports> { name, _ -> events += name; Unit },
        diagnostics = cleanupPort<WalkTransitionDiagnostics> { name, _ -> if (name == "available") false else Unit },
    )
}
