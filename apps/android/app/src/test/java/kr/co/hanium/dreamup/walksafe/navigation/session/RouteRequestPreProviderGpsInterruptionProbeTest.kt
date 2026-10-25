package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real request worker/terminal; confirmed GPS restriction is an explicit Facts observation. */
class RouteRequestPreProviderGpsInterruptionProbeTest {
    @After fun clearCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun gpsRestrictionBeforeFirstWorkerCheckKeepsTheInitialHumanIntent() {
        val f = Fixture()
        f.start()
        f.allowed = false
        f.worker.removeAt(0).run()
        f.ui()
        f.assertTerminalAndRecovery("before_worker", expectedRevalidations = 0)
    }

    @Test fun gpsRestrictionAfterReadyRevalidationKeepsTheInitialHumanIntent() {
        val f = Fixture()
        f.start()
        f.afterRevalidation = { f.allowed = false }
        f.worker.removeAt(0).run()
        f.ui()
        f.assertTerminalAndRecovery("after_revalidation", expectedRevalidations = 1)
    }

    @Test fun userCancellationDoesNotBecomeAnAutomaticRestartWhenGpsRecovers() {
        val f = Fixture()
        f.start()
        f.controller.cancelActiveRouteRequest(false)
        f.worker.removeAt(0).run()
        f.ui()
        assertTrue(f.firstCall.isCancelled())
        assertFalse(f.controller.requestPending)
        assertNull(f.controller.explicitStart)
        f.allowed = true
        f.fresh()
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(1, f.createdRequests)
        assertEquals(0, f.executedRequests)
    }

    @Test fun retiredActorGatewayGenerationAndEpochCannotInventAnInitialResume() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
            { it.putOriginal("generation", it.generation + 1L) },
            { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
        )) {
            val f = Fixture()
            f.start()
            change(f)
            f.allowed = false
            f.worker.removeAt(0).run()
            f.ui()
            assertTrue(f.firstCall.isCancelled())
            assertNull(f.controller.explicitStart)
            f.allowed = true
            f.fresh()
            f.controller.continuePendingExplicitRouteStartIfReady()
            assertEquals(1, f.createdRequests)
            assertEquals(0, f.executedRequests)
        }
    }

    @Test fun cancelledOldWorkerCannotFinishTheReplacementRequestOrRetainItsOldIntent() {
        val f = Fixture()
        f.start()
        val oldGeneration = f.controller.requestGeneration
        f.controller.cancelActiveRouteRequest(false)
        f.controller.requestRoute(f.destination, "user_destination", true)
        val replacementGeneration = f.controller.requestGeneration
        assertTrue(replacementGeneration > oldGeneration)
        f.allowed = false
        f.worker.removeAt(0).run()
        f.ui()
        assertTrue(f.controller.requestPending)
        assertEquals(replacementGeneration, f.controller.requestGeneration)
        assertNull(f.controller.explicitStart)
        assertEquals(0, f.executedRequests)
        assertFalse(f.calls.last().isCancelled())
    }

    private class Fixture {
        private val original = Class.forName(RouteAcquisitionControllerTest::class.java.name + "\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val controller: RouteAcquisitionController = get(original, "controller")
        val lifecycle: WalkSessionLifecycle = get(original, "lifecycle")
        val destination: RoutePoint = get(original, "destination")
        private val facts: RouteAcquisitionController.Facts = get(controller, "facts")
        private val backend: RouteAcquisitionController.Backend = get(controller, "backend")
        val worker: MutableList<Runnable> = get(original, "worker")
        val calls: List<kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall<kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute>> get() = get(original, "calls")
        val firstCall get() = calls.first()
        val generation: Long get() = get(original, "generation")
        val createdRequests: Int get() = get(original, "transportCalls")
        val executedRequests: Int get() = get(original, "executedCalls")
        var revalidations = 0
        var actor = facts.actorId
        var afterRevalidation: () -> Unit = {}
        var allowed: Boolean
            get() = get(original, "outputsAllowed")
            set(value) { put(original, "outputsAllowed", value) }
        var now: Long
            get() = get(original, "now")
            set(value) { put(original, "now", value) }

        init {
            put(controller, "facts", object : RouteAcquisitionController.Facts by facts {
                override val actorId get() = actor
                override val rawActorId get() = actor
            })
            put(controller, "backend", object : RouteAcquisitionController.Backend by backend {
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long) =
                    backend.revalidate(session, actorId, capacitySessionGeneration).also {
                        revalidations++; afterRevalidation()
                    }
            })
            call<Unit>("prepareArchive")
        }

        fun start() {
            assertTrue(facts.guidancePage)
            assertEquals(destination, facts.selectedDestination?.point)
            assertTrue(facts.runtimeActive)
            assertTrue(facts.sessionCurrent(requireNotNull(facts.gateway.session)))
            controller.requestRoute(destination, "user_destination", true)
            assertTrue(controller.requestPending)
            assertNull(controller.explicitStart)
            assertNotNull(get<Any?>(requireNotNull(get<Any?>(controller, "activeRouteRequestOwner")), "initialExplicitStart"))
            assertEquals(1, createdRequests)
            assertEquals(0, executedRequests)
        }

        fun assertTerminalAndRecovery(stage: String, expectedRevalidations: Int) {
            val pending = controller.explicitStart
            val pendingAfterTerminal = pending != null
            assertTrue(firstCall.isCancelled())
            assertFalse(controller.requestPending)
            assertEquals(expectedRevalidations, revalidations)
            assertEquals(0, executedRequests)
            assertTrue(facts.sessionCurrent(requireNotNull(facts.gateway.session)))
            assertTrue(facts.runtimeActive)
            allowed = true
            fresh()
            controller.continuePendingExplicitRouteStartIfReady()
            println("stage=$stage cancelled=${firstCall.isCancelled()} initialPendingAfterTerminal=$pendingAfterTerminal createdAfterRecovery=$createdRequests httpExecuted=$executedRequests")
            assertNotNull("Current initial human intent must survive $stage GPS restriction", pending)
            assertTrue(requireNotNull(pending).keepUntilCancelled)
            assertEquals(2_000L, pending.requestedAtElapsedRealtimeMs)
            assertEquals(2, createdRequests)
        }

        fun fresh() {
            now += 1_000L
            val fix: TrustedLocation = get(original, "fix")
            put(original, "location", fix.copy(elapsedRealtimeMs = now))
        }
        fun ui() = call<Unit>("runUi")
        fun putOriginal(name: String, value: Any?) = put(original, name, value)
        @Suppress("UNCHECKED_CAST") private fun <T> call(name: String): T = original.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(original) as T
    }

    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
