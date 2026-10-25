package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual app classes and compiled fixture; only callback timing and admitted Facts are controlled. */
class RouteResponseArchivePreparationRecoveryTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun validApproachWithFreshCurrentLocationInstallsAfterArchiveReadbackWithoutRace() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", { f.put("now", 3_000L); f.put("location", f.fix.copy(elapsedRealtimeMs = 2_900L)) })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertFalse(f.run.navigator.hasRoute())
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        assertTrue(f.run.navigator.hasRoute()); assertNull(f.controller.explicitStart)
        assertTrue(f.stages.contains("ROUTE_INSTALLED"))
    }

    @Test fun sameOwnerArchivePreparationDuringValidProviderResponseMustRetainTheOriginalInitialIntent() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", {
            f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot())
            assertTrue(f.archive.preparationPending)
        })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertFalse(f.run.navigator.hasRoute()); assertFalse(f.controller.requestPending)
        println("ARCHIVE_RACE pending=${f.controller.explicitStart != null} active=${f.run.active} statuses=${f.statuses} stages=${f.stages}")
        assertNotNull("A valid initial human start under the same owner must wait for archive preparation rather than disappear", f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        repeat(3) { f.controller.continuePendingExplicitRouteStartIfReady() }
        assertEquals(1, f.get<Int>("transportCalls"))
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertTrue(f.controller.requestPending)
        assertEquals(2, f.get<Int>("transportCalls"))
        assertEquals(1_900L, f.get<TrustedLocation>("location").elapsedRealtimeMs)
    }

    @Test fun confirmedOutputRestrictionThenNewGoodGpsAutomaticallyContinuesTheOriginalIntent() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", { f.put("outputsAllowed", false) })
        f.call<Unit>("runWorker")
        f.put("outputsAllowed", true); f.put("now", 3_500L)
        f.put("location", f.fix.copy(elapsedRealtimeMs = 3_400L))
        f.call<Unit>("runUi")
        assertTrue(f.controller.requestPending)
        assertEquals(2, f.get<Int>("transportCalls"))
        f.put("beforeResponse", {})
        f.call<Unit>("runWorker"); f.call<Unit>("runUi"); f.call<Unit>("runIo"); f.call<Unit>("runUi")
        assertTrue(f.run.navigator.hasRoute())
    }


    @Test fun archiveAndOutputRestrictionPreserveOriginalFixFenceUntilANewGoodObservation() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", {
            f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot())
            f.put("outputsAllowed", false)
        })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertEquals(2_000L, requireNotNull(f.controller.explicitStart).requestedAtElapsedRealtimeMs)
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.put("outputsAllowed", true)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(1, f.get<Int>("transportCalls"))
        assertNotNull(f.controller.explicitStart)
        f.put("now", 3_500L); f.put("location", f.fix.copy(elapsedRealtimeMs = 3_400L))
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.get<Int>("transportCalls")); assertTrue(f.controller.requestPending)
    }

    @Test fun archiveRebindingWhileAStorageAckIsQueuedKeepsOnlyTheHumanIntent() {
        val f = F(); f.prepare(); f.start()
        f.call<Unit>("runWorker"); f.call<Unit>("runUi"); f.call<Unit>("runIo")
        assertFalse(f.run.navigator.hasRoute())
        f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot())
        f.call<Unit>("runUi")
        assertFalse(f.run.navigator.hasRoute())
        assertEquals(2_000L, requireNotNull(f.controller.explicitStart).requestedAtElapsedRealtimeMs)
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.controller.continuePendingExplicitRouteStartIfReady()
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertFalse(f.run.navigator.hasRoute())
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        assertTrue(f.run.navigator.hasRoute())
    }

    @Test fun existingConnectionRecoveryKeepsItsFirstIntentAndNoticeAcrossArchiveOnlyWait() {
        val f = F(); f.prepare(); f.put("routeOffsetM", 90.0); f.start()
        f.put("beforeResponse", {
            f.put("now", 3_000L)
            f.put("location", f.fix.copy(longitude = 127.0 - metersToLongitude(20.0), elapsedRealtimeMs = 2_900L))
        })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        val notice = f.get<List<Pair<Runnable, Long>>>("delayed").single { it.second == 3_000L }.first
        f.put("now", 3_500L); f.put("location", f.fix.copy(elapsedRealtimeMs = 3_400L))
        f.put("beforeResponse", { f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot()) })
        f.controller.continuePendingExplicitRouteStartIfReady()
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertEquals(2_000L, requireNotNull(f.controller.explicitStart).requestedAtElapsedRealtimeMs)
        assertSame(notice, f.get<List<Pair<Runnable, Long>>>("delayed").single { it.second == 3_000L }.first)
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(3, f.get<Int>("transportCalls"))
    }

    @Test fun cancelActorPermissionDestinationEpochStorageAndClosedLeaseCannotAcquireAnArchiveRetry() {
        val invalidations = listOf<Pair<String, (F) -> Unit>>(
            "cancel" to { it.controller.cancelActiveRouteRequest(false) },
            "actor" to { it.replaceFacts { old -> object : RouteAcquisitionController.Facts by old {
                override val actorId = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            } } },
            "permission" to { it.put("permissionReady", false) },
            "destination" to { it.run.selectDestination(RoutePoint(38.0, 128.0)) },
            "selection" to { it.put("selectionOverridden", true); it.put("selectedCurrent", null) },
            "epoch" to { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            "storage" to { it.replaceFacts { old -> object : RouteAcquisitionController.Facts by old {
                override val gateway get() = old.gateway.let { GatewaySessionProcessSnapshot(it.session, it.restoredFirstRunSnapshot, it.generation, true, it.inFlightOperationId, it.deletionRecoveryOnly, it.renewalRetryAtElapsedMs) }
            } } },
            "closed archive lease" to { it.archive.detach() },
        )
        invalidations.forEach { (reason, invalidate) ->
            val f = F(); f.prepare(); f.start()
            f.put("beforeResponse", { f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot()) })
            f.call<Unit>("runWorker"); invalidate(f); f.call<Unit>("runUi")
            assertNull(reason, f.controller.explicitStart)
            assertFalse(reason, f.run.navigator.hasRoute())
            assertEquals(reason, 1, f.get<Int>("transportCalls"))
        }
    }

    @Test fun failureWhileArchivePreparationIsPendingCannotMasqueradeAsAValidatedProviderReply() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", { f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot()) })
        f.put("responseTransform", { _: WalkingRoute -> throw java.io.IOException("provider unavailable") })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertNotNull(f.controller.explicitStart); assertFalse(f.run.navigator.hasRoute())
        assertFalse(f.stages.contains("ROUTE_HTTP_COMPLETE"))
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(1, f.get<Int>("transportCalls"))
    }

    @Test fun cancellationAfterArchiveDeferralCannotRetryWhenPreparationCompletes() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", { f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot()) })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertNotNull(f.controller.explicitStart)
        f.controller.cancelActiveRouteRequest(false)
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertNull(f.controller.explicitStart); assertFalse(f.run.navigator.hasRoute())
        assertEquals(1, f.get<Int>("transportCalls"))
    }

    @Test fun failedArchivePreparationGuardCannotRetryOrInstallTheRetainedResponse() {
        val f = F(); f.prepare(); f.start()
        f.put("beforeResponse", { f.archive.submitRouteSnapshotOwnership(f.call("gateway"), f.lifecycle.snapshot()) })
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertNotNull(f.controller.explicitStart)
        f.call<Unit>("runIo"); f.call<Unit>("runUi")
        f.putArchive("routeSnapshotPurgeFailed", true)
        try {
            f.controller.continuePendingExplicitRouteStartIfReady()
            fail("The real archive purge-failed guard must safety-stop before a retry")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("route_snapshot_purge_failed"))
        }
        assertEquals(1, f.get<Int>("transportCalls")); assertFalse(f.run.navigator.hasRoute())
    }

    private fun metersToLongitude(m: Double) = Math.toDegrees(m / (6_371_000.0 * kotlin.math.cos(Math.toRadians(37.0))))

    private class F {
        private val original = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.RouteInitialConnectionRecoveryTest\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val controller: RouteAcquisitionController = get("controller")
        val archive: RouteArchiveController = get("archive")
        val lifecycle: WalkSessionLifecycle = get("lifecycle")
        val run: NavigationRunState = get("run")
        val fix: TrustedLocation = get("fix")
        val stages: List<String> get() = get("stages")
        val statuses: List<String> get() = get("statuses")
        fun replaceFacts(transform: (RouteAcquisitionController.Facts) -> RouteAcquisitionController.Facts) {
            val field = controller.javaClass.getDeclaredField("facts").apply { isAccessible = true }
            field.set(controller, transform(field.get(controller) as RouteAcquisitionController.Facts))
        }
        fun putArchive(name: String, value: Any?) { archive.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(archive, value) }
        fun prepare() { put("routeOffsetM", 50.0); call<Unit>("prepareArchive") }
        fun start() { controller.requestRoute(get("destination"), "user_destination", true) }
        @Suppress("UNCHECKED_CAST") fun <T> get(name: String): T = original.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(original) as T
        fun put(name: String, value: Any?) { original.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(original, value) }
        @Suppress("UNCHECKED_CAST") fun <T> call(name: String): T = original.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(original) as T
    }
}
