package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Executes the real request worker and queued terminal against stable, admitted Facts.
 * Authentication outcomes, confirmed environment restriction and session currentness are
 * controlled boundaries; this does not run Main actor publication, OS permission or HTTP.
 * The original five-case fixture/source is reused without changing its bytes.
 */
class RouteRequestPreProviderRetirementBoundaryTest {
    @After fun clearCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun authenticationNotReadyAndActuallyCancelledPendingDoNotRetainInitialIntentWhenGpsAlsoCloses() {
        for (status in listOf(GatewaySessionRevalidationStatus.NOT_READY,
            GatewaySessionRevalidationStatus.PENDING)) {
            val f = Fixture()
            f.returnRevalidation(status)
            f.afterRevalidation = {
                f.allowed = false
                if (status == GatewaySessionRevalidationStatus.PENDING) f.firstCall.cancel()
            }
            f.start()
            f.runWorker()
            f.ui()
            assertEquals(1, f.revalidations)
            assertFalse(f.controller.requestPending)
            f.assertNoRestart(status.name)
        }
    }

    @Test fun notCurrentSessionBeforeWorkerOrAfterReadyCannotRetainInitialIntent() {
        for (afterReady in listOf(false, true)) {
            val f = Fixture()
            f.controlSessionCurrent()
            f.start()
            if (afterReady) {
                f.afterRevalidation = { f.allowed = false; f.sessionCurrent = false }
            } else {
                f.sessionCurrent = false
            }
            f.runWorker()
            f.ui()
            assertEquals(if (afterReady) 1 else 0, f.revalidations)
            assertFalse(f.controller.requestPending)
            f.assertNoRestart("session_not_current_afterReady=$afterReady")
        }
    }

    @Test fun transportCancellationAloneDoesNotBecomeAnEnvironmentRestart() {
        val f = Fixture()
        f.start()
        f.firstCall.cancel()
        f.runWorker()
        f.ui()
        assertFalse(f.controller.requestPending)
        f.assertNoRestart("transport_cancel_only")
    }

    @Test fun humanCancellationAfterEnvironmentMarkerRetiresTheQueuedInitialResume() {
        val f = Fixture()
        f.start()
        f.allowed = false
        f.runWorker()
        assertTrue(f.uiTasks.isNotEmpty())
        assertTrue(f.controller.requestPending)
        val oldGeneration = f.controller.requestGeneration
        f.controller.cancelActiveRouteRequest(false)
        assertTrue(f.controller.requestGeneration > oldGeneration)
        f.ui()
        assertFalse(f.controller.requestPending)
        f.assertNoRestart("human_cancel_after_marker")
    }

    @Test fun actorChangeAfterEnvironmentMarkerCannotPublishTheOldHumanIntent() {
        val f = Fixture()
        f.start()
        f.allowed = false
        f.runWorker()
        f.actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        f.ui()
        // Full actor retirement is outside this fixture. The old queued terminal cannot
        // publish a pending context or acquire a second request under the replacement actor.
        f.assertNoRestart("actor_changed_after_marker")
    }

    @Test fun sessionCurrentLossAfterEnvironmentMarkerCannotPublishTheOldHumanIntent() {
        val f = Fixture()
        f.controlSessionCurrent()
        f.start()
        f.allowed = false
        f.runWorker()
        f.sessionCurrent = false
        f.ui()
        assertFalse(f.controller.requestPending)
        f.assertNoRestart("session_lost_after_marker")
    }

    @Test fun resumedInitialIntentRequiresANewerFixAndConsumesExactlyOnce() {
        val f = Fixture()
        f.start()
        f.allowed = false
        f.runWorker()
        f.ui()
        val pending = requireNotNull(f.controller.explicitStart)
        assertEquals(2_000L, pending.requestedAtElapsedRealtimeMs)
        assertTrue(pending.keepUntilCancelled)
        assertFalse(f.controller.requestPending)
        f.allowed = true
        // A recovered output gate does not re-label the request-origin fix as a newer fix.
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart)
        assertEquals(1, f.createdRequests)
        f.fresh()
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart)
        assertTrue(f.controller.requestPending)
        assertEquals(2, f.createdRequests)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.createdRequests)
        assertEquals(0, f.executedRequests)
    }

    private class Fixture {
        private val delegate = Class.forName(RouteRequestPreProviderGpsInterruptionProbeTest::class.java.name + "\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        private val original: Any = get(delegate, "original")
        val controller: RouteAcquisitionController = get(delegate, "controller")
        val worker: MutableList<Runnable> = get(original, "worker")
        val uiTasks: List<Runnable> = get(original, "ui")
        val firstCall get() = get<List<kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall<kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute>>>(original, "calls").first()
        val revalidations: Int get() = get(delegate, "revalidations")
        val createdRequests: Int get() = get(original, "transportCalls")
        val executedRequests: Int get() = get(original, "executedCalls")
        var sessionCurrent = true
        var allowed: Boolean
            get() = get(original, "outputsAllowed")
            set(value) { put(original, "outputsAllowed", value) }
        var actor: String?
            get() = get(delegate, "actor")
            set(value) { put(delegate, "actor", value) }
        var afterRevalidation: () -> Unit
            get() = get(delegate, "afterRevalidation")
            set(value) { put(delegate, "afterRevalidation", value) }

        fun returnRevalidation(status: GatewaySessionRevalidationStatus) {
            val backend: RouteAcquisitionController.Backend = get(controller, "backend")
            put(controller, "backend", object : RouteAcquisitionController.Backend by backend {
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation {
                    backend.revalidate(session, actorId, capacitySessionGeneration)
                    return GatewaySessionRevalidation(status, "controlled_auth_boundary")
                }
            })
        }
        fun controlSessionCurrent() {
            val facts: RouteAcquisitionController.Facts = get(controller, "facts")
            put(controller, "facts", object : RouteAcquisitionController.Facts by facts {
                override fun sessionCurrent(session: GatewayFieldSession) =
                    sessionCurrent && facts.sessionCurrent(session)
            })
        }
        fun start() = call("start")
        fun fresh() = call("fresh")
        fun ui() = call("ui")
        fun runWorker() = worker.removeAt(0).run()
        fun assertNoRestart(reason: String) {
            assertTrue(firstCall.isCancelled())
            assertNull("$reason must not retain a human intent", controller.explicitStart)
            allowed = true
            sessionCurrent = true
            fresh()
            controller.continuePendingExplicitRouteStartIfReady()
            assertNull(controller.explicitStart)
            assertEquals(1, createdRequests)
            assertEquals(0, executedRequests)
        }
        private fun call(name: String) { delegate.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(delegate) }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
