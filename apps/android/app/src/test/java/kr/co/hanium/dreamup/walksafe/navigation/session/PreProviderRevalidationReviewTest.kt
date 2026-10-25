package kr.co.hanium.dreamup.walksafe.navigation.session

import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual current RouteAcquisition worker/terminal; reuses the maintained fixture's actual
 * lifecycle, selected destination, session, request coordinator and archive. Only backend
 * revalidation/worker rejection and confirmed GPS admission are controlled leaves.
 * No Main publication, OS permission/GPS, external HTTP, or acoustic success is claimed.
 */
class PreProviderRevalidationReviewTest {
    @After fun clearCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun temporaryPendingWithGpsRefusalMustRetainIntentOrExplainTermination() {
        val f = F(); f.revalidate = { f.restrictGps(); pending() }
        f.start(); f.worker(); f.ui(); f.assertNoSilentTermination("pending_gps")
    }
    @Test fun temporaryPendingWithHealthyGpsMustRetainIntentOrExplainTermination() {
        val f = F(); f.revalidate = { pending() }
        f.start(); f.worker(); f.ui(); f.assertNoSilentTermination("pending_healthy")
    }
    @Test fun revalidationExceptionWithGpsRefusalMustRetainIntentOrExplainTermination() {
        val f = F(); f.revalidate = { f.restrictGps(); throw SocketTimeoutException("controlled revalidation timeout") }
        f.start(); f.worker(); f.ui(); f.assertNoSilentTermination("revalidation_exception_gps")
    }
    @Test fun rejectedWorkerWithGpsRefusalMustRetainIntentOrExplainTermination() {
        val f = F(); f.rejectWorker { f.restrictGps() }
        f.start(); f.ui(); f.assertNoSilentTermination("executor_rejected_gps")
    }
    @Test fun revalidationExceptionWithoutGpsRefusalGivesTerminalSpeechNotTmapFailure() {
        val f = F(); f.revalidate = { throw SocketTimeoutException("controlled revalidation timeout") }
        f.start(); f.worker(); f.ui()
        assertTrue(f.spoken.isNotEmpty()); f.assertRetiredWithoutRetry("revalidation_exception_healthy")
    }
    @Test fun rejectedWorkerWithoutGpsRefusalGivesTerminalSpeechNotTmapFailure() {
        val f = F(); f.rejectWorker {}
        f.start(); f.ui()
        assertTrue(f.spoken.isNotEmpty()); f.assertRetiredWithoutRetry("executor_rejected_healthy")
    }
    @Test fun authenticationNotReadyWithGpsRefusalInvokesSessionRetirementAndCannotRetry() {
        val f = F(); f.revalidate = { f.restrictGps(); GatewaySessionRevalidation(GatewaySessionRevalidationStatus.NOT_READY, "controlled auth invalid") }
        f.start(); f.worker(); f.ui()
        assertEquals(1, f.sessionRetirements); f.assertRetiredWithoutRetry("auth_not_ready_gps")
    }
    @Test fun explicitCancellationDuringRevalidationCannotBecomeAnEnvironmentRetry() {
        val f = F(); f.revalidate = { f.c.cancelActiveRouteRequest(false); f.restrictGps(); throw SocketTimeoutException("cancelled revalidation") }
        f.start(); f.worker(); f.ui()
        assertTrue(f.spoken.isEmpty()); f.assertRetiredWithoutRetry("human_cancel_during_revalidation")
    }
    @Test fun obsoleteSessionGenerationDuringRevalidationCannotRetryOrPublishOldFailure() {
        val f = F(); f.revalidate = { put(f.raw, "generation", read<Long>(f.raw, "generation") + 1L); f.restrictGps(); throw SocketTimeoutException("obsolete revalidation") }
        f.start(); f.worker(); f.ui()
        assertTrue(f.spoken.isEmpty()); assertTrue(f.userStatus.isEmpty())
        f.assertRetiredWithoutRetry("old_generation_during_revalidation")
    }

    private class F {
        val raw = Class.forName(RouteInitialConnectionRecoveryTest::class.java.name + "\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val c: RouteAcquisitionController = read(raw, "controller")
        val run: NavigationRunState = read(raw, "run")
        val guard: ConsecutiveTmapFailureGuard = read(c, "tmapFailureGuard")
        val spoken: List<String> get() = read(raw, "spoken")
        val userStatus = mutableListOf<String>()
        var sessionRetirements = 0
        var revalidations = 0
        var revalidate: () -> GatewaySessionRevalidation = { GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY, "controlled ready") }
        val created: Int get() = read(raw, "transportCalls")
        val executed: Int get() = read(raw, "executedCalls")
        init {
            call<Unit>(raw, "prepareArchive")
            val backend: RouteAcquisitionController.Backend = read(c, "backend")
            put(c, "backend", object : RouteAcquisitionController.Backend by backend {
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation {
                    revalidations++; return this@F.revalidate()
                }
            })
            val p: RoutePresentation = read(c, "presentation")
            put(c, "presentation", object : RoutePresentation by p {
                override fun status(title: String, detail: String) { userStatus += "$title: $detail"; p.status(title, detail) }
            })
            val e: RouteAcquisitionController.Effects = read(c, "effects")
            put(c, "effects", object : RouteAcquisitionController.Effects by e {
                override fun sessionNotReady(session: GatewayFieldSession) { sessionRetirements++; e.sessionNotReady(session) }
            })
        }
        fun start() {
            val facts: RouteAcquisitionController.Facts = read(c, "facts")
            assertTrue(facts.runtimeActive); assertTrue(facts.guidancePage)
            assertTrue(facts.locationPermissionReady); assertTrue(facts.environmentOutputsAllowed)
            assertTrue(facts.sessionCurrent(requireNotNull(facts.gateway.session)))
            assertSame(read<Any>(raw, "selected"), facts.selectedDestination)
            c.requestRoute(read(raw, "destination"), "user_destination", true)
        }
        fun restrictGps() { put(raw, "now", 5000L); put(raw, "outputsAllowed", false) }
        fun rejectWorker(beforeReject: () -> Unit) { put(c, "worker", Executor { beforeReject(); throw RejectedExecutionException("controlled worker rejection") }) }
        fun worker() = call<Unit>(raw, "runWorker")
        fun ui() = call<Unit>(raw, "runUi")
        fun recoverWithFreshObservation() {
            put(raw, "outputsAllowed", true); put(raw, "now", 6000L)
            // A distinct controlled observation; the old 1900ms object is not rewritten.
            put(raw, "location", read<TrustedLocation>(raw, "fix").copy(elapsedRealtimeMs = 5900L))
            repeat(3) { c.continuePendingExplicitRouteStartIfReady() }
        }
        fun assertNoSilentTermination(label: String) {
            val retained = c.explicitStart != null
            val feedback = spoken.isNotEmpty() || read<List<String>>(raw, "spokenStatus").isNotEmpty() || userStatus.isNotEmpty()
            val inFlight = c.requestPending
            val active = run.active
            assertEquals(0, executed); assertEquals(0, guard.failureCount())
            recoverWithFreshObservation()
            println("$label: retained=$retained userFeedback=$feedback inFlight=$inFlight active=$active retryRequests=$created providerExecuted=$executed tmapFailures=${guard.failureCount()} diagnostics=${read<List<String>>(raw, "statuses")}")
            assertTrue("$label dropped current initial intent without terminal user feedback; fresh GPS created=$created", retained || feedback)
        }
        fun assertRetiredWithoutRetry(label: String) {
            assertNull(c.explicitStart); assertEquals(0, executed); assertEquals(0, guard.failureCount())
            recoverWithFreshObservation()
            assertEquals(1, created); assertNull(c.explicitStart); assertEquals(0, executed)
            println("$label: noAutoRetry=true userSpeech=${spoken.size} sessionRetirements=$sessionRetirements tmapFailures=${guard.failureCount()}")
        }
    }
    companion object {
        private fun pending() = GatewaySessionRevalidation(GatewaySessionRevalidationStatus.PENDING, "gateway_status_unreachable")
        @Suppress("UNCHECKED_CAST") private fun <T> read(o: Any, n: String): T = o.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(o) as T
        private fun put(o: Any, n: String, value: Any?) { o.javaClass.getDeclaredField(n).apply { isAccessible = true }.set(o, value) }
        @Suppress("UNCHECKED_CAST") private fun <T> call(o: Any, n: String): T = o.javaClass.getDeclaredMethod(n).apply { isAccessible = true }.invoke(o) as T
    }
}
