package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real request/storage owners; controlled HTTP failures, raw GPS time and callback scheduler. */
class RouteAcquisitionAutomaticRecoveryTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun removedTimerCannotPollOrReplaceTheNewHumanStartEpisode() {
        val f = F(); f.failProvider(); f.startAndFail()
        val retiredTimer = f.delayed.single().first
        f.controller.cancelActiveRouteRequest(false)
        f.now += 500L; f.fresh()
        f.controller.requestRoute(f.get("destination"), "user_destination", true); f.drainFailure()
        val replacement = requireNotNull(f.controller.explicitStart)
        val replacementTimer = f.delayed.single()
        val firstFailure = f.recoveryLong("firstFailureAtMs")
        val retryAt = f.recoveryLong("retryAtMs")
        retiredTimer.run()
        assertSame(replacement, f.controller.explicitStart)
        assertEquals(replacementTimer, f.delayed.single())
        assertEquals(firstFailure, f.recoveryLong("firstFailureAtMs"))
        assertEquals(retryAt, f.recoveryLong("retryAtMs"))
        assertEquals(2, f.created)
    }

    @Test fun providerUnavailableKeepsHumanIntentAndAutomaticallyInstallsAfterBoundedRetry() {
        val f = F(); f.failProvider(); f.startAndFail()
        assertNotNull("A transient provider failure must retain the same user's start", f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertEquals(1, f.created); assertFalse(f.run.navigator.hasRoute())
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(1, f.created)
        f.put("responseTransform", { route: WalkingRoute -> route })
        f.fire(2_000L)
        assertEquals(2, f.created); assertTrue(f.controller.requestPending)
        f.drainResponse()
        assertTrue(f.run.navigator.hasRoute()); assertNull(f.controller.explicitStart)
        assertNotNull(f.get<InstalledRouteStartSpeechCoordinator>("startSpeech").pending)
    }

    @Test fun missingFreshGpsKeepsFirstFailureAndRetryDeadlineUntilARealNewFix() {
        val f = F(); f.failProvider(); f.startAndFail()
        assertNotNull(f.controller.explicitStart)
        val firstFailure = f.recoveryLong("firstFailureAtMs")
        val firstDeadline = f.recoveryLong("retryAtMs")
        f.put("location", null); f.fire(2_000L)
        assertEquals(1, f.created)
        f.fire(1_000L); f.fire(1_000L)
        assertEquals(firstFailure, f.recoveryLong("firstFailureAtMs"))
        assertEquals(firstDeadline, f.recoveryLong("retryAtMs"))
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        f.put("responseTransform", { route: WalkingRoute -> route }); f.fresh()
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.created)
        f.drainResponse(); assertTrue(f.run.navigator.hasRoute())
    }

    @Test fun repeatedFailuresBackOffToTenSecondsAndAnnounceTheEpisodeOnce() {
        val f = F(); f.failProvider(); f.startAndFail()
        val firstFailure = f.recoveryLong("firstFailureAtMs")
        listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L).forEach { delay ->
            assertEquals(delay, f.delayed.single().second)
            f.freshAt(f.now + delay); f.fire(delay); f.drainFailure()
            assertNotNull(f.controller.explicitStart)
            assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
            assertEquals(firstFailure, f.recoveryLong("firstFailureAtMs"))
        }
        assertEquals(1, f.get<List<String>>("spokenStatus").size)
        assertTrue(f.get<List<String>>("spoken").isEmpty())
    }

    @Test fun cancelOwnerPermissionDestinationAndEpochInvalidateDelayedAndLateRetries() {
        listOf<(F) -> Unit>(
            { it.controller.cancelActiveRouteRequest(false) },
            { it.controller.cancelNavigationRequestsForPause() },
            { it.controller.clearGatewayNavigationState() },
            { it.put("generation", it.get<Long>("generation") + 1L) },
            { it.put("permissionReady", false) },
            { it.replaceFacts { old -> object : RouteAcquisitionController.Facts by old {
                override val actorId = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            } } },
            { it.run.selectDestination(RoutePoint(38.0, 128.0)) },
            { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
            { it.put("foreground", false) },
        ).forEach { invalidate ->
            val f = F(); f.failProvider(); f.startAndFail()
            val old = f.delayed.single().first
            invalidate(f); f.now += 2_000L; f.fresh(); old.run()
            f.controller.continuePendingExplicitRouteStartIfReady()
            assertNull(f.controller.explicitStart); assertEquals(1, f.created)
            assertFalse(f.run.navigator.hasRoute())
        }
    }

    @Test fun explicitCancellationStillCannotCreateAutomaticRetry() {
        val f = F(); f.prepare(); f.controller.requestRoute(f.get("destination"), "user_destination", true)
        f.controller.cancelActiveRouteRequest(false)
        f.call<Unit>("runWorker"); f.call<Unit>("runUi")
        assertNull(f.controller.explicitStart); assertEquals(1, f.created)
        assertTrue(f.delayed.isEmpty()); assertFalse(f.run.navigator.hasRoute())
    }

    @Test fun networkFailureWithConfirmedGpsRestrictionKeepsTheOriginalObservationFence() {
        val f = F(); f.failProvider()
        f.put("beforeResponse", { f.put("outputsAllowed", false) })
        f.startAndFail(); f.fire(2_000L)
        assertNotNull(f.controller.explicitStart); assertEquals(1, f.created)
        f.put("outputsAllowed", true)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(1, f.created)
        f.put("beforeResponse", {}); f.put("responseTransform", { route: WalkingRoute -> route })
        f.fresh(); f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.created); f.drainResponse(); assertTrue(f.run.navigator.hasRoute())
    }

    @Test fun malformedAuthenticationAndUnavailableDestinationAreNotTransientRetries() {
        listOf(
            GatewayResponseProtocolException("route_endpoint_mismatch"),
            GatewayProxyHttpException(401, "walking_route", "user_session_invalid"),
            GatewayProxyHttpException(422, "walking_route", "route_unavailable"),
        ).forEach { error ->
            val f = F(); f.put("responseTransform", { _: WalkingRoute -> throw error }); f.startAndFail()
            assertNull(f.controller.explicitStart); assertTrue(f.delayed.isEmpty())
            assertEquals(1, f.created); assertFalse(f.run.navigator.hasRoute())
        }
    }

    @Test fun failedStorageReadbackRetainsIntentThenOnlySuccessfulAckInstalls() {
        val f = F(); f.prepare(); var writesFail = true
        f.replaceStorage { original -> object : RouteSnapshotStorage by original {
            override fun write(envelope: String): Boolean = !writesFail && original.write(envelope)
        } }
        f.controller.requestRoute(f.get("destination"), "user_destination", true)
        f.drainResponse()
        assertNotNull(f.controller.explicitStart); assertFalse(f.run.navigator.hasRoute())
        writesFail = false; f.fire(2_000L); f.drainResponse()
        assertTrue(f.run.navigator.hasRoute()); assertNull(f.controller.explicitStart)
    }

    @Test fun pendingSessionCheckDuringAutomaticRetryKeepsTheEpisodeAndEventuallyInstalls() {
        val f = F(); f.failProvider(); f.startAndFail()
        val firstFailure = f.recoveryLong("firstFailureAtMs")
        var status = GatewaySessionRevalidationStatus.PENDING
        f.revalidation { status }
        f.fire(2_000L); f.drainFailure()
        assertNotNull(f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertEquals(firstFailure, f.recoveryLong("firstFailureAtMs"))
        assertEquals(2, f.created); assertEquals(1, f.get<Int>("executedCalls"))
        repeat(3) { f.controller.continuePendingExplicitRouteStartIfReady() }
        assertEquals(2, f.created)
        assertEquals(4_000L, f.delayed.single().second)
        status = GatewaySessionRevalidationStatus.READY
        f.put("responseTransform", { route: WalkingRoute -> route })
        f.fire(4_000L); f.drainResponse()
        assertEquals(3, f.created); assertTrue(f.run.navigator.hasRoute())
        assertEquals(1, f.get<List<String>>("spokenStatus").size)
    }

    @Test fun notReadyAfterAnAutomaticRetryDoesNotKeepARecoveryTimer() {
        val f = F(); f.failProvider(); f.startAndFail()
        var status = GatewaySessionRevalidationStatus.NOT_READY
        f.revalidation { status }; f.fire(2_000L); f.drainFailure()
        assertNull(f.controller.explicitStart); assertTrue(f.delayed.isEmpty())
        status = GatewaySessionRevalidationStatus.READY; f.now = 8_000L; f.fresh()
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.created); assertFalse(f.run.navigator.hasRoute())
    }

    @Test fun cancellationWhileAPendingTerminalIsQueuedCannotReviveTheRecovery() {
        val f = F(); f.failProvider(); f.startAndFail()
        f.revalidation { GatewaySessionRevalidationStatus.PENDING }
        f.fire(2_000L); f.call<Unit>("runWorker")
        f.controller.cancelActiveRouteRequest(false); f.call<Unit>("runUi")
        assertNull(f.controller.explicitStart); assertTrue(f.delayed.isEmpty())
        assertEquals(2, f.created); assertFalse(f.run.navigator.hasRoute())
    }


    @Test fun initialExplicitPendingSessionCheckKeepsHumanStartUntilReadyAndAck() {
        val f = F(); f.prepare(); var status = GatewaySessionRevalidationStatus.PENDING
        f.revalidation { status }
        f.controller.requestRoute(f.get("destination"), "user_destination", true); f.drainFailure()
        assertNotNull("The same verified owner's first human start must survive identified session PENDING", f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertEquals(0, f.get<Int>("executedCalls")); assertEquals(1, f.created)
        assertEquals(2_000L, f.delayed.single().second)
        repeat(3) { f.controller.continuePendingExplicitRouteStartIfReady() }
        assertEquals(1, f.created)
        status = GatewaySessionRevalidationStatus.READY
        f.fire(2_000L); f.drainResponse()
        assertEquals(2, f.created); assertEquals(1, f.get<Int>("executedCalls"))
        assertTrue(f.run.navigator.hasRoute()); assertNull(f.controller.explicitStart)
    }

    @Test fun nonexplicitNotReadyCancelledAndInvalidOwnerInitialPendingNeverCreateAnIntent() {
        listOf<Pair<String, (F) -> Unit>>(
            "no explicit start" to {},
            "not ready" to {},
            "cancel before terminal" to { it.controller.cancelActiveRouteRequest(false) },
            "transport cancelled before pending" to {},
            "permission" to { it.put("permissionReady", false) },
            "actor" to { it.replaceFacts { old -> object : RouteAcquisitionController.Facts by old {
                override val actorId = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            } } },
            "owner generation" to { it.put("generation", it.get<Long>("generation") + 1L) },
            "selected destination" to { it.put("selectionOverridden", true); it.put("selectedCurrent", null) },
            "end" to { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
        ).forEach { (reason, invalidate) ->
            val f = F(); f.prepare()
            f.revalidation {
                if (reason == "transport cancelled before pending")
                    f.get<List<CancellableNetworkCall<WalkingRoute>>>("calls").last().cancel()
                if (reason == "not ready") GatewaySessionRevalidationStatus.NOT_READY else GatewaySessionRevalidationStatus.PENDING
            }
            f.controller.requestRoute(f.get("destination"), "user_destination", reason != "no explicit start")
            f.call<Unit>("runWorker"); invalidate(f); f.call<Unit>("runUi")
            assertNull(reason, f.controller.explicitStart); assertTrue(reason, f.delayed.isEmpty())
            assertEquals(reason, 0, f.get<Int>("executedCalls")); assertFalse(reason, f.run.navigator.hasRoute())
        }
    }

    private class F {
        private val original = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.RouteInitialConnectionRecoveryTest\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val controller: RouteAcquisitionController = get("controller")
        val archive: RouteArchiveController = get("archive")
        val run: NavigationRunState = get("run")
        val lifecycle: WalkSessionLifecycle = get("lifecycle")
        val fix: TrustedLocation = get("fix")
        val created: Int get() = get("transportCalls")
        val delayed: MutableList<Pair<Runnable, Long>> get() = get("delayed")
        var now: Long get() = get("now"); set(value) { put("now", value) }
        fun fresh() = freshAt(now)
        fun freshAt(time: Long) { put("location", fix.copy(elapsedRealtimeMs = time)) }
        fun prepare() { call<Unit>("prepareArchive") }
        fun failProvider() { put("responseTransform", { _: WalkingRoute -> throw GatewayProxyHttpException(503, "walking_route", "tmap_provider_error") }) }
        fun startAndFail() { prepare(); controller.requestRoute(get("destination"), "user_destination", true); drainFailure() }
        fun drainFailure() { call<Unit>("runWorker"); call<Unit>("runUi") }
        fun drainResponse() { drainFailure(); call<Unit>("runIo"); call<Unit>("runUi") }
        fun fire(delay: Long) {
            val pending = delayed.single { it.second == delay }; delayed.remove(pending)
            now += delay; pending.first.run()
        }
        fun recoveryLong(name: String): Long {
            val owner = controller.javaClass.getDeclaredField("initialRouteRetry").apply { isAccessible = true }
                .get(controller) as InitialRouteRetryEpisode
            val recovery = requireNotNull(owner.current)
            return when (name) {
                "firstFailureAtMs" -> recovery.firstFailureAtMs
                "retryAtMs" -> recovery.retryAtMs
                else -> error("Unknown recovery value: $name")
            }
        }
        fun replaceStorage(transform: (RouteSnapshotStorage) -> RouteSnapshotStorage) {
            val store = archive.javaClass.getDeclaredField("routeSnapshotStore").apply { isAccessible = true }.get(archive)
            val field = store.javaClass.getDeclaredField("storage").apply { isAccessible = true }
            field.set(store, transform(field.get(store) as RouteSnapshotStorage))
        }
        fun replaceFacts(transform: (RouteAcquisitionController.Facts) -> RouteAcquisitionController.Facts) {
            val field = controller.javaClass.getDeclaredField("facts").apply { isAccessible = true }
            field.set(controller, transform(field.get(controller) as RouteAcquisitionController.Facts))
        }
        fun revalidation(status: () -> GatewaySessionRevalidationStatus) {
            val field = controller.javaClass.getDeclaredField("backend").apply { isAccessible = true }
            val original = field.get(controller) as RouteAcquisitionController.Backend
            field.set(controller, object : RouteAcquisitionController.Backend by original {
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long) =
                    GatewaySessionRevalidation(status(), "controlled_revalidation")
            })
        }
        @Suppress("UNCHECKED_CAST") fun <T> get(name: String): T = original.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(original) as T
        fun put(name: String, value: Any?) { original.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(original, value) }
        @Suppress("UNCHECKED_CAST") fun <T> call(name: String): T = original.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(original) as T
    }
}
