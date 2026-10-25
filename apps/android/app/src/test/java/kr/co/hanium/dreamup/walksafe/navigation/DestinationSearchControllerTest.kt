package kr.co.hanium.dreamup.walksafe.navigation

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestCoordinator
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real search/location owners, with queued worker/UI and admitted SDK/session observations. */
class DestinationSearchControllerTest {
    @After fun clearCapacityFence() = GatewayCapacityProcessState.resetForTests()

    @Test fun constructionDoesNotReadAuthorityCreateLocationSourceOrStartWork() {
        val f = Fixture()
        assertEquals(0, f.factReads)
        assertEquals(0, f.locationCreations)
        assertTrue(f.events.isEmpty())
        assertTrue(f.workerQueue.isEmpty())
        assertTrue(f.locationCallbacks.isEmpty())
        assertFalse(f.controller.state.inFlight)
    }

    @Test fun oneActualOriginAndOneCappedRequestProduceDetachedCandidatesAndExactVoicePage() {
        val f = Fixture()
        f.controller.requestVoiceResults(f.query)
        assertTrue(f.controller.search(true))
        assertTrue(f.controller.isAcquiring)
        assertEquals(0, f.requests.size)
        f.completeLocation()
        assertEquals(1, f.requests.size)
        assertEquals(Request(f.session, f.query, 10, RoutePoint(35.8714, 128.6014)), f.requests.single())
        assertTrue(f.controller.state.inFlight)
        assertFalse(f.controller.search(true))
        f.response = DestinationSearchResponse("fixture", f.query, listOf(candidate(0)) + (0..12).map(::candidate), moreResultsAvailable = true)
        f.runWorker()
        assertTrue(f.controller.state.results.isEmpty())
        f.runUi()
        val captured = f.controller.state
        assertFalse(captured.inFlight)
        assertEquals((0..9).map { "id-$it" }, captured.results.map { it.id })
        assertEquals(DestinationSearchOrigin(RoutePoint(35.8714, 128.6014), 60f, 10_000L), captured.origin)
        assertSame(captured.voicePage, f.offered.single())
        assertSame(captured.origin, f.offered.single().origin)
        assertNull(captured.pendingVoiceQuery)
        assertFalse(f.controller.canLoadMore())
        f.controller.clearResults()
        assertTrue(f.controller.state.results.isEmpty())
        assertEquals(10, captured.results.size)
    }

    @Test fun repeatedSearchSharesThePendingLocationButOnlyLatestQueryIsSent() {
        val f = Fixture()
        assertTrue(f.controller.search(true))
        f.query = "도서관"
        assertTrue(f.controller.search(true))
        assertEquals(1, f.locationCallbacks.size)
        f.completeLocation()
        assertEquals("도서관", f.requests.single().query)
        assertEquals(1, f.locationCreations)
        f.controller.cancel()
        f.runWorker()
        f.runUi()
        assertEquals(0, f.executions)
        assertTrue(f.controller.state.results.isEmpty())
    }

    @Test fun cancelAndReplacementCannotInstallTheOldQueuedResponseOrClearNewTransport() {
        val f = Fixture()
        f.startLocatedSearch()
        f.runWorker()
        assertEquals(1, f.uiQueue.size)
        val oldId = f.controller.state.generation
        f.controller.cancel()
        f.query = "도서관"
        f.startLocatedSearch()
        val newCall = f.calls.last()
        assertTrue(f.controller.state.generation > oldId)
        f.runUi()
        assertTrue(f.controller.state.inFlight)
        assertTrue(f.requestsCoordinator.hasActiveDestinationSearch())
        assertFalse(newCall.isCancelled())
        assertTrue(f.controller.state.results.isEmpty())
        f.runWorker()
        f.runUi()
        assertFalse(f.controller.state.inFlight)
        assertEquals("도서관", f.controller.state.query)
        assertEquals(1, f.controller.state.results.size)
    }

    @Test fun epochAndExactGatewayGenerationFenceQueuedResponses() {
        listOf<(Fixture) -> Unit>(
            { it.epoch = WalkRuntimeEpoch("replacement-walk", 0L) },
            { it.gatewayGeneration += 1L },
            { it.session = session("other-search-actor") },
        ).forEach { retire ->
            val f = Fixture()
            f.startLocatedSearch()
            f.runWorker()
            retire(f)
            f.runUi()
            assertTrue(f.controller.state.results.isEmpty())
            assertTrue(f.offered.isEmpty())
            assertFalse(f.requestsCoordinator.hasActiveDestinationSearch())
        }
    }

    @Test fun locationFailureOrRevokedAdmissionNeverInventsAnOriginAndExplicitRetryWorks() {
        listOf<DestinationSearchLocationFix?>(null, fix().copy(mock = true), fix().copy(elapsedRealtimeMs = -1L)).forEach { invalid ->
            val f = Fixture()
            f.controller.requestVoiceResults(f.query)
            f.controller.search(true)
            f.completeLocation(invalid)
            assertTrue(f.requests.isEmpty())
            assertNull(f.controller.state.pendingVoiceQuery)
            assertEquals(1, f.spoken.size)
            assertTrue(f.controller.search(true))
            f.completeLocation(index = 1)
            assertEquals(1, f.requests.size)
        }
        val f = Fixture()
        f.controller.search(true)
        f.locationAllowed = false
        f.completeLocation()
        assertTrue(f.requests.isEmpty())
        assertFalse(f.controller.isAcquiring)
        assertEquals(1, f.locationCancellations)
    }

    @Test fun sessionRevalidationStopsBeforeTransportExecution() {
        val f = Fixture()
        f.revalidation = GatewaySessionRevalidation(GatewaySessionRevalidationStatus.NOT_READY, "fixture")
        f.startLocatedSearch()
        f.runWorker()
        f.runUi()
        assertEquals(0, f.executions)
        assertTrue(f.calls.single().isCancelled())
        assertEquals(listOf(f.session), f.notReadySessions)
        assertTrue(f.controller.state.results.isEmpty())
    }

    @Test fun errorAdmissionIsCapturedBeforeCleanupAndCheckedAfterIt() {
        listOf(true, false).forEach { failureCurrent ->
            val f = Fixture()
            f.failureCurrent = failureCurrent
            f.error = IOException("fixture")
            f.controller.requestVoiceResults(f.query)
            f.startLocatedSearch()
            f.runWorker()
            f.runUi()
            assertFalse(f.controller.state.inFlight)
            assertNull(f.controller.state.pendingVoiceQuery)
            assertEquals(listOf("failure-capture:true", "failure-check:false"), f.events.filter { it.startsWith("failure-") })
            assertEquals(if (failureCurrent) 1 else 0, f.spoken.size)
            if (failureCurrent) assertEquals(f.controller.state.locationMessage, f.spoken.single())
        }
    }

    @Test fun rejectedExecutorRetiresOnlyTheCurrentRequestAndAllowsRetry() {
        val f = Fixture()
        f.rejectWorker = true
        f.controller.requestVoiceResults(f.query)
        f.startLocatedSearch()
        assertFalse(f.controller.state.inFlight)
        assertFalse(f.requestsCoordinator.hasActiveDestinationSearch())
        assertTrue(f.calls.single().isCancelled())
        assertEquals(1, f.spoken.size)
        assertNull(f.controller.state.pendingVoiceQuery)
        f.rejectWorker = false
        assertTrue(f.controller.search(true))
        assertEquals(2, f.requests.size)
        assertEquals(1, f.locationCallbacks.size)
    }

    @Test fun semanticVoiceAndRetirementCommandsKeepOneCandidateOwnerWithoutEffects() {
        val f = Fixture()
        val page = DestinationSearchVoiceState("약국", listOf(candidate(1)))
        f.controller.showVoicePage(page)
        assertSame(page, f.controller.state.voicePage)
        f.controller.requestVoiceResults("병원")
        assertNull(f.controller.state.voicePage)
        assertEquals("병원", f.controller.state.pendingVoiceQuery)
        val oldGeneration = f.controller.state.generation
        f.controller.retirePending()
        assertEquals(oldGeneration + 1, f.controller.state.generation)
        assertNull(f.controller.state.pendingVoiceQuery)
        assertNull(f.controller.state.pendingVoicePage)
        assertTrue(f.events.isEmpty())
    }

    private data class Request(val session: GatewayFieldSession, val query: String, val limit: Int, val origin: RoutePoint?)

    private class Fixture {
        var now = 10_000L
        var query = "약국"
        var epoch = WalkRuntimeEpoch("search-walk", 0L)
        var session = session("search-test-actor")
        var gatewayGeneration = 1L
        var locationAllowed = true
        var factReads = 0
        var locationCreations = 0
        var locationCancellations = 0
        var executions = 0
        var rejectWorker = false
        var failureCurrent = true
        var error: Exception? = null
        var response = DestinationSearchResponse("fixture", query, listOf(candidate(1)))
        var revalidation = GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY, "fixture")
        val requestsCoordinator = AndroidNavigationRequestCoordinator()
        val requests = mutableListOf<Request>()
        val calls = mutableListOf<CancellableNetworkCall<DestinationSearchResponse>>()
        val locationCallbacks = mutableListOf<(DestinationSearchLocationFix?) -> Unit>()
        val workerQueue = mutableListOf<Runnable>()
        val uiQueue = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val offered = mutableListOf<DestinationSearchVoiceState>()
        val notReadySessions = mutableListOf<GatewayFieldSession>()
        val controller: DestinationSearchController = DestinationSearchController(
            facts = object : DestinationSearchController.Facts {
                override fun searchAllowed(): Boolean { factReads += 1; return true }
                override fun rawQuery(): String { factReads += 1; return query }
                override fun epoch(): WalkRuntimeEpoch { factReads += 1; return epoch }
                override fun actorId(): String? { factReads += 1; return session.actorId }
                override fun gatewaySnapshot(): GatewaySessionProcessSnapshot {
                    factReads += 1
                    return GatewaySessionProcessSnapshot(session, null, gatewayGeneration, false, null, false)
                }
                override fun isSessionCurrent(session: GatewayFieldSession): Boolean = session === this@Fixture.session
                override fun isEpochAllowed(epoch: WalkRuntimeEpoch): Boolean = epoch == this@Fixture.epoch
                override fun locationAccessAllowed(): Boolean { factReads += 1; return locationAllowed }
                override fun locationSourceReady(): Boolean { factReads += 1; return true }
            },
            actions = object : DestinationSearchController.Actions {
                override fun explainUnavailable() { events += "unavailable" }
                override fun blockRouteMutation() = false
                override fun requireActor() = true
                override fun networkAllowed() = true
                override fun sessionOrNull() = session
                override fun ensureLocationPermission() = true
                override fun render() { events += "render" }
                override fun controls(controls: DestinationSearchController.Controls) { events += "controls:$controls" }
                override fun navigationStatus(text: String) { events += text }
                override fun status(title: String, detail: String) { events += "status:$title:$detail" }
                override fun speak(message: String) { spoken += message }
                override fun offerCandidates(state: DestinationSearchVoiceState, message: String) { offered += state }
                override fun sessionNotReady(session: GatewayFieldSession) { notReadySessions += session }
                override fun failureAdmission(session: GatewayFieldSession, error: Exception): (() -> Boolean) {
                    events += "failure-capture:${controller.state.inFlight}"
                    return { events += "failure-check:${controller.state.inFlight}"; failureCurrent }
                }
            },
            backend = object : DestinationSearchController.Backend {
                override fun searchCall(session: GatewayFieldSession, query: String, limit: Int, origin: RoutePoint?): CancellableNetworkCall<DestinationSearchResponse> {
                    requests += Request(session, query, limit, origin)
                    return CancellableNetworkCall(executeBlock = {
                        executions += 1
                        error?.let { throw it }
                        response
                    }).also(calls::add)
                }
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long) = revalidation
            },
            navigationRequests = requestsCoordinator,
            worker = Executor { if (rejectWorker) throw RejectedExecutionException("fixture") else workerQueue += it },
            postToUi = { uiQueue += it },
            locationFactory = {
                locationCreations += 1
                DestinationSearchLocationController(DestinationSearchLocationSource { callback ->
                    locationCallbacks += callback
                    ({ locationCancellations += 1 })
                }) { now }
            },
        )

        fun completeLocation(value: DestinationSearchLocationFix? = fix(), index: Int = 0) = locationCallbacks[index](value)
        fun startLocatedSearch() {
            val before = locationCallbacks.size
            controller.search(true)
            if (locationCallbacks.size > before) completeLocation(index = locationCallbacks.lastIndex)
        }
        fun runWorker() { while (workerQueue.isNotEmpty()) workerQueue.removeAt(0).run() }
        fun runUi() { while (uiQueue.isNotEmpty()) uiQueue.removeAt(0).invoke() }
    }

    companion object {
        private fun fix() = DestinationSearchLocationFix(35.8714, 128.6014, 60f, 10_000L)
        private fun session(actor: String) = GatewayFieldSession.verified("https://example.test", actor, "walksafe_field_session=v2.test.cookie", Long.MAX_VALUE)
        private fun candidate(index: Int) = DestinationSearchResult("id-$index", "약국 $index", RoutePoint(35.8714, 128.6014 + index * 0.0001), "주소 $index", null, null, index * 10)
    }
}
