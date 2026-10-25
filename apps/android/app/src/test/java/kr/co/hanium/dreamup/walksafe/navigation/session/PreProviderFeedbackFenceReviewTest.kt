package kr.co.hanium.dreamup.walksafe.navigation.session

import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Additional boundary controls; the original frozen nine methods/fixture are unchanged.
 * Status feedback is explanatory, not navigation admission. Permission loss must not retry;
 * this suite does not demand that an otherwise-current explanatory status require GPS permission.
 * Actual Acq plus maintained fixture; Main output/permission/OS/HTTP/TTS are not executed.
 */
class PreProviderFeedbackFenceReviewTest {
    @After fun clearCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun eachCurrentTerminalGivesOneCauseSpecificExplanationAndDoesNotPretendToRetry() {
        for (mode in listOf("pending_healthy", "pending_gps", "exception_gps", "reject_gps")) {
            val f = F(); f.configure(mode); f.start(explicitStart = !mode.startsWith("pending")); f.worker(); f.ui()
            val spoken = f.explanations.single()
            assertTrue(spoken.contains("다시"))
            assertTrue(when {
                mode.startsWith("pending") -> spoken.contains("안내 서버의 연결을 확인하지 못했습니다")
                mode.startsWith("exception") -> spoken.contains("요청 시간이 초과")
                else -> spoken.contains("경로 요청을 시작하지 못했습니다")
            })
            assertEquals(listOf("길 안내 요청 확인 필요: $spoken"), f.status)
            val terminalMetadata = read<List<String>>(f.raw, "statuses").last()
            if (mode.startsWith("pending")) assertEquals("navigation=route_failed gateway=pending", terminalMetadata)
            else assertTrue(terminalMetadata.startsWith("navigation=route_failed "))
            assertFalse(terminalMetadata.contains("controlled"))
            assertNull(f.c.explicitStart); assertFalse(f.c.requestPending); assertFalse(f.run.active)
            assertEquals(0, f.executed); assertEquals(0, f.guard.failureCount())
            f.recover(); f.ui()
            assertEquals(1, f.created); assertEquals(1, f.explanations.size)
            println("$mode: oneExplicitTerminal=true automaticRetry=0 providerCount=0")
        }
    }
    @Test fun lateForegroundLossSuppressesQueuedFailureExplanation() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        put(f.raw, "foreground", false); f.ui(); f.assertNoExplanationOrRetry("foreground")
    }
    @Test fun lateActivityDestroyedSuppressesQueuedFailureExplanation() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        val facts: RouteAcquisitionController.Facts = read(f.c, "facts")
        put(f.c, "facts", object : RouteAcquisitionController.Facts by facts { override val activityDestroyed = true })
        f.ui(); f.assertNoExplanationOrRetry("destroyed")
    }
    @Test fun lateLocationPermissionLossCannotAutoretryOrInstallFromTheTerminal() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        put(f.raw, "permissionReady", false); f.ui(); f.recover()
        assertNull(f.c.explicitStart); assertFalse(f.run.navigator.hasRoute())
        assertEquals(1, f.created); assertEquals(0, f.executed); assertEquals(0, f.guard.failureCount())
        println("permissionRevoked: automaticRetry=0 providerCount=0 informationalStatus=${f.explanations.size}")
    }
    @Test fun lateSessionReplacementSuppressesQueuedFailureExplanation() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        put(f.raw, "sessionOverridden", true); put(f.raw, "currentSession", null)
        f.ui(); f.assertNoExplanationOrRetry("session_removed")
    }
    @Test fun lateEndedWalkEpochSuppressesQueuedFailureExplanation() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        read<WalkSessionLifecycle>(f.raw, "lifecycle").handle(WalkSessionEvent.EndRequested)
        f.ui(); f.assertNoExplanationOrRetry("epoch_ended")
    }
    @Test fun lateExplicitCancelSuppressesQueuedFailureExplanation() {
        val f = F(); f.configure("pending_gps"); f.start(); f.worker()
        f.c.cancelActiveRouteRequest(false); f.ui(); f.assertNoExplanationOrRetry("cancel")
    }
    @Test fun pendingDuringReplacementDoesNotClearInstalledRouteOrEmitInitialStartAdvice() = assertInstalledRouteUnchanged("pending_gps")
    @Test fun revalidationExceptionDuringReplacementDoesNotClearInstalledRouteOrEmitInitialStartAdvice() = assertInstalledRouteUnchanged("exception_gps")
    @Test fun executorRejectionDuringReplacementDoesNotClearInstalledRouteOrEmitInitialStartAdvice() = assertInstalledRouteUnchanged("reject_gps")

    private fun assertInstalledRouteUnchanged(mode: String) {
        val f = F(); f.start(); f.worker(); f.ui(); call<Unit>(f.raw, "runIo"); f.ui()
        assertTrue(f.run.navigator.hasRoute()); assertTrue(f.run.active)
        val route: WalkingRoute = read(f.run.navigator, "route")
        val routeId = f.run.navigator.currentRouteId()
        val destination: Any? = read(f.run, "destination")
        f.status.clear(); f.explanations.clear(); f.configure(mode)
        f.start(); f.worker(); f.ui()
        assertTrue(f.run.active); assertSame(route, read(f.run.navigator, "route"))
        assertEquals(routeId, f.run.navigator.currentRouteId()); assertSame(destination, read(f.run, "destination"))
        assertEquals(1, f.executed); assertEquals(2, f.created); assertEquals(0, f.guard.failureCount())
        assertTrue(f.explanations.isEmpty()); assertTrue(f.status.isEmpty())
        println("$mode: installedRouteIdentityUnchanged=true newProviderExecuted=0 newInitialAdvice=0")
    }

    private class F {
        val original = Class.forName(PreProviderRevalidationReviewTest::class.java.name + "\$F")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val raw: Any = read(original, "raw")
        val c: RouteAcquisitionController = read(original, "c")
        val run: NavigationRunState = read(original, "run")
        val guard: ConsecutiveTmapFailureGuard = read(original, "guard")
        val status: MutableList<String> = read(original, "userStatus")
        val explanations: MutableList<String> = read(raw, "spokenStatus")
        val created get() = read<Int>(raw, "transportCalls")
        val executed get() = read<Int>(raw, "executedCalls")
        fun configure(mode: String) {
            if (mode.startsWith("reject")) {
                put(c, "worker", Executor { call<Unit>(original, "restrictGps"); throw RejectedExecutionException("controlled worker rejection") })
            } else {
                put(original, "revalidate", {
                    if (mode.endsWith("gps")) call<Unit>(original, "restrictGps")
                    if (mode.startsWith("exception")) throw SocketTimeoutException("controlled revalidation timeout")
                    GatewaySessionRevalidation(GatewaySessionRevalidationStatus.PENDING, "gateway_status_unreachable")
                } as () -> GatewaySessionRevalidation)
            }
        }
        fun start(explicitStart: Boolean = true) {
            if (explicitStart) call<Unit>(original, "start")
            else c.requestRoute(read(raw, "destination"), "user_destination", false)
        }
        fun worker() = call<Unit>(original, "worker")
        fun ui() = call<Unit>(original, "ui")
        fun recover() = call<Unit>(original, "recoverWithFreshObservation")
        fun assertNoExplanationOrRetry(label: String) {
            assertTrue("$label must suppress explanatory status", explanations.isEmpty()); assertTrue(status.isEmpty())
            assertTrue(read<List<String>>(raw, "spoken").isEmpty())
            assertFalse("$label must not publish stale failure metadata", read<List<String>>(raw, "statuses").any { it.startsWith("navigation=route_failed") })
            recover(); assertEquals(1, created); assertEquals(0, executed); assertNull(c.explicitStart)
            assertEquals(0, guard.failureCount())
            println("$label: noOldExplanation=true automaticRetry=0 providerCount=0")
        }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> read(o: Any, n: String): T = o.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(o) as T
        private fun put(o: Any, n: String, value: Any?) { o.javaClass.getDeclaredField(n).apply { isAccessible = true }.set(o, value) }
        @Suppress("UNCHECKED_CAST") private fun <T> call(o: Any, n: String): T = o.javaClass.getDeclaredMethod(n).apply { isAccessible = true }.invoke(o) as T
    }
}
