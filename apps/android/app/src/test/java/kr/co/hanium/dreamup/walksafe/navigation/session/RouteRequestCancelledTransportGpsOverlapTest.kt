package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A transport already cancelled at the typed network boundary is not a GPS interruption. */
class RouteRequestCancelledTransportGpsOverlapTest {
    @After fun clearCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun previouslyCancelledCallAndGpsRestrictionCannotIssueAnAutomaticRestartTicket() {
        val fixture = Class.forName(RouteRequestPreProviderGpsInterruptionProbeTest::class.java.name + "\$Fixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val original: Any = get(fixture, "original")
        val controller: RouteAcquisitionController = get(fixture, "controller")
        call(fixture, "start")
        val calls: List<CancellableNetworkCall<WalkingRoute>> = get(original, "calls")
        val firstCall = calls.single()
        val worker: MutableList<Runnable> = get(original, "worker")
        // Cancellation precedes the environment check. The terminal must not classify this
        // captured transport as cancelled by GPS merely because GPS is now also restricted.
        firstCall.cancel()
        put(original, "outputsAllowed", false)
        worker.removeAt(0).run()
        call(fixture, "ui")
        assertTrue(firstCall.isCancelled())
        assertFalse(controller.requestPending)
        assertNull(controller.explicitStart)
        assertEquals(0, get<Int>(original, "executedCalls"))
        put(original, "outputsAllowed", true)
        call(fixture, "fresh")
        controller.continuePendingExplicitRouteStartIfReady()
        assertNull(controller.explicitStart)
        assertFalse(controller.requestPending)
        assertEquals(1, get<Int>(original, "transportCalls"))
        assertEquals(0, get<Int>(original, "executedCalls"))
    }

    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
        private fun call(owner: Any, name: String) { owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner) }
    }
}
