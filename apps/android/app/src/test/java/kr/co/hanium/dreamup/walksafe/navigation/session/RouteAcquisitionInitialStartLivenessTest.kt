package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Acq/Archive/IO owners from the existing fixture; SDK facts stay explicit boundaries. */
class RouteAcquisitionInitialStartLivenessTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun archivePreparedDuringGpsRestrictionPreservesInitialStartAndRecoveryConsumesItOnce() {
        val f = Fixture()
        f.call<Unit>("prepareArchive")
        f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot())
        f.location = null
        f.controller.requestRoute(f.destination, "user_destination", true)
        val pending = requireNotNull(f.controller.explicitStart)
        assertTrue(pending.keepUntilCancelled)
        assertTrue(f.archive.preparationPending)
        assertEquals(0, f.transportCalls)

        f.navigationAllowed = false
        f.call<Unit>("runIo")
        f.call<Unit>("runUi")
        assertFalse(f.archive.preparationPending)
        assertEquals(1, f.preparedCallbacks)
        assertSame("Actual archive onPrepared must not retire a current initial intent", pending, f.controller.explicitStart)
        assertFalse(f.controller.requestPending)
        assertEquals(0, f.transportCalls)

        f.now += 60_000L
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
        f.location = f.fix.copy(elapsedRealtimeMs = f.now)
        f.navigationAllowed = true
        f.controller.continuePendingExplicitRouteStartIfReady()
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertTrue(f.controller.requestPending)
        assertEquals(1, f.transportCalls)
    }

    @Test fun lowConfidenceKeepsTheSameInitialIntentWithoutRequestingUntilActuallyRecovered() {
        val f = Fixture()
        f.preparePending()
        val pending = requireNotNull(f.controller.explicitStart)
        f.navigationAllowed = false
        f.location = f.fix.copy(elapsedRealtimeMs = f.now)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
        f.navigationAllowed = true
        f.positionPaused = true
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
        f.positionPaused = false
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertEquals(1, f.transportCalls)
    }

    @Test fun closedAdmissionDoesNotPreserveAnIntentWhoseActualOwnershipWasRetired() {
        val invalidations = listOf<Pair<String, (Fixture) -> Unit>>(
            "foreground" to { it.foreground = false },
            "epoch" to { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
            "gateway generation" to { it.gatewayGeneration++ },
            "session missing" to { it.session = null },
            "session no longer current" to { it.sessionCurrent = false },
            "session invalidated" to { requireNotNull(it.session).invalidate() },
            "actor" to { it.actorId = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
            "raw actor" to { it.rawActorId = null },
            "storage" to { it.storageBlocked = true },
            "deletion recovery" to { it.deletionRecoveryOnly = true },
            "deletion processing" to { assertTrue(it.deletion.enforceFailClosedAuthority(it.deletionLease, "test-block")) },
            "activity lease" to { assertTrue(it.deletion.detachActivity(it.deletionLease)) },
            "destroyed" to { it.destroyed = true },
            "destination" to { it.run.selectDestination(RoutePoint(38.0, 128.0)) },
            "selected destination" to { it.selectedDestination = null },
            "page" to { it.guidancePage = false },
        )
        invalidations.forEach { (reason, invalidate) ->
            val f = Fixture()
            f.preparePending()
            f.navigationAllowed = false
            invalidate(f)
            f.controller.continuePendingExplicitRouteStartIfReady()
            assertNull(reason, f.controller.explicitStart)
            assertFalse(reason, f.controller.requestPending)
            assertEquals(reason, 0, f.transportCalls)
        }
    }

    @Test fun responseBoundInitialIntentStillRejectsAReplacedRequestGeneration() {
        val f = Fixture()
        f.preparePending()
        set(f.controller, "pendingExplicitRouteStartRequestId", f.controller.requestGeneration + 1)
        f.navigationAllowed = false
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
    }

    @Test fun gatewayPublicationBetweenContextAndSessionReadsCannotKeepTheOldIntent() {
        val f = Fixture()
        f.preparePending()
        f.navigationAllowed = false
        var changed = false
        f.afterGatewayRead = {
            if (!changed) { changed = true; f.gatewayGeneration++ }
        }
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
    }

    @Test fun elapsedClockRegressionDoesNotTurnTheInitialIntentIntoAnUnboundedFutureWait() {
        val f = Fixture()
        f.preparePending()
        val pending = requireNotNull(f.controller.explicitStart)
        f.now = pending.requestedAtElapsedRealtimeMs - 1L
        f.navigationAllowed = false
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
    }

    @Test fun noninitialWaitStillCancelsOnClosedAdmissionAndRetainsItsOriginalTimeout() {
        val closed = Fixture()
        closed.guidancePage = false
        closed.preparePending()
        assertFalse(requireNotNull(closed.controller.explicitStart).keepUntilCancelled)
        closed.navigationAllowed = false
        closed.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(closed.controller.explicitStart)
        assertEquals(0, closed.transportCalls)

        val expired = Fixture()
        expired.guidancePage = false
        expired.preparePending()
        val pending = requireNotNull(expired.controller.explicitStart)
        assertFalse(pending.keepUntilCancelled)
        val timeout = expired.delayed.single()
        assertEquals(15_000L, timeout.second)
        expired.now += timeout.second
        timeout.first.run()
        assertNull(expired.controller.explicitStart)
        expired.location = expired.fix.copy(elapsedRealtimeMs = expired.now)
        expired.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(0, expired.transportCalls)
    }

    private class Fixture {
        private val original = Class.forName(RouteAcquisitionControllerTest::class.java.name + "\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val controller: RouteAcquisitionController = get(original, "controller")
        val archive: RouteArchiveController = get(original, "archive")
        val lifecycle: WalkSessionLifecycle = get(original, "lifecycle")
        val run: NavigationRunState = get(original, "run")
        val deletion: kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine = get(original, "deletion")
        val deletionLease: kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease = get(original, "deletionLease")
        val destination: RoutePoint = get(original, "destination")
        val fix: TrustedLocation = get(original, "fix")
        val delayed: List<Pair<Runnable, Long>> get() = get(original, "delayed")
        var location: TrustedLocation?
            get() = get(original, "location")
            set(value) { set(original, "location", value) }
        var now: Long
            get() = get(original, "now")
            set(value) { set(original, "now", value) }
        val transportCalls: Int get() = get(original, "transportCalls")
        private val originalFacts: RouteAcquisitionController.Facts = get(controller, "facts")
        var navigationAllowed = true
        var foreground = true
        var guidancePage = true
        var positionPaused = false
        var destroyed = false
        var actorId = originalFacts.actorId
        var rawActorId = originalFacts.rawActorId
        var session: GatewayFieldSession? = originalFacts.gateway.session
        var sessionCurrent = true
        var gatewayGeneration = originalFacts.gateway.generation
        var storageBlocked = false
        var deletionRecoveryOnly = false
        var selectedDestination = originalFacts.selectedDestination
        var preparedCallbacks = 0
        var afterGatewayRead: (() -> Unit)? = null

        init {
            set(controller, "facts", object : RouteAcquisitionController.Facts by originalFacts {
                override val navigationAllowed get() = this@Fixture.navigationAllowed
                override val environmentOutputsAllowed get() = this@Fixture.navigationAllowed
                override val foreground get() = this@Fixture.foreground
                override val guidancePage get() = this@Fixture.guidancePage
                override val positionPaused get() = this@Fixture.positionPaused
                override val activityDestroyed get() = this@Fixture.destroyed
                override val actorId get() = this@Fixture.actorId
                override val rawActorId get() = this@Fixture.rawActorId
                override val selectedDestination get() = this@Fixture.selectedDestination
                override val gateway get() = GatewaySessionProcessSnapshot(session, null, gatewayGeneration,
                    storageBlocked, null, deletionRecoveryOnly).also { afterGatewayRead?.invoke() }
                override fun sessionCurrent(session: GatewayFieldSession) = this@Fixture.sessionCurrent &&
                    session === this@Fixture.session && originalFacts.sessionCurrent(session)
            })
            // Same callback as NavigationSessionComposition.archive; real preparation executes it.
            set(archive, "onPrepared", { preparedCallbacks++; controller.continuePendingExplicitRouteStartIfReady() })
        }

        fun preparePending() {
            call<Unit>("prepareArchive")
            location = null
            controller.requestRoute(destination, "user_destination", true)
            assertNotNull(controller.explicitStart)
            assertEquals(0, transportCalls)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> call(name: String): T = original.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(original) as T
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner) as T
        private fun set(owner: Any, name: String, value: Any?) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
    }
}
