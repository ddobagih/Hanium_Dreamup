package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S07RouteResponseInstallationTest {
    private val route = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(111, 90),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001)),
        guidePoints = emptyList(),
    )

    @Test fun movedAlongTheReturnedRouteAndProviderStartOffsetRemainConnected() {
        val atStart = TrustedLocation(0.0, 0.0, 3f, 1_000)
        val movedAlongRoute = TrustedLocation(0.0, 0.0008, 3f, 1_000)
        val providerStartOffset = TrustedLocation(0.000135, 0.0, 3f, 1_000)

        listOf(atStart, movedAlongRoute, providerStartOffset).forEach { fix ->
            val requestOrigin = if (fix == providerStartOffset) providerStartOffset else atStart
            assertEquals(
                RouteResponseInstallLocationStatus.CONNECTED,
                routeResponseInstallLocationStatus(route, requestOrigin, fix, 1_500),
            )
        }
    }

    @Test fun missingStaleAndOffRouteFixesCannotInstallTheDelayedResponse() {
        val origin = TrustedLocation(0.0, 0.0, 3f, 1_000)
        assertEquals(
            RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            routeResponseInstallLocationStatus(route, origin, null, 1_500),
        )
        assertEquals(
            RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            routeResponseInstallLocationStatus(route, origin, origin, 11_001),
        )
        assertEquals(
            RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            routeResponseInstallLocationStatus(
                route, origin, TrustedLocation(0.0008, 0.0005, 3f, 1_500), 2_000,
            ),
        )
        // 33 m is inside the old 35 m corridor bound but outside the actual 8 m guidance search.
        assertEquals(
            RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            routeResponseInstallLocationStatus(
                route, origin, TrustedLocation(0.000297, 0.0005, 3f, 1_500), 2_000,
            ),
        )
    }

    @Test fun lateAResponseCannotPassTheCurrentOwnerGateOrReachInstallationBeforeLocationCheck() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val selection = source.substringAfter("private fun onDestinationSelected(")
            .substringBefore("\n    private fun ")
        val request = source.substringAfter("private fun requestRoute(")
            .substringBefore("\n    private fun isRouteRequestLeaseCurrent(")
        assertTrue(selection.indexOf("cancelNavigationRequestsForDestinationSelection()") <
            selection.indexOf("requestRoute("))
        assertTrue(selection.contains("clearActiveRouteRequestState("))
        val cancellation = source.substringAfter("private fun clearActiveRouteRequestState(")
            .substringBefore("private fun suspendNavigationForRecovery(")
        assertTrue(cancellation.contains("invalidateActiveRouteRequestLease()"))
        assertTrue(request.contains("val requestId = ++routeRequestGeneration"))
        assertTrue(request.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))

        val response = request.substringAfter("val route = routeCall.execute()")
        val save = response.indexOf("routeSnapshotStore.saveFirstRoute(")
        val install = response.indexOf("routeNavigator.setRoute(")
        val currentOwnerCheck = response.indexOf(
            "routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)",
        )
        val currentLocationCheck = response.indexOf("routeResponseInstallLocationStatus(")
        val rejection = response.substringAfter("if (installLocationStatus != RouteResponseInstallLocationStatus.CONNECTED)")
            .substringBefore("val connectedInstallLocation")
        assertTrue(currentOwnerCheck in 0 until currentLocationCheck)
        assertTrue(currentLocationCheck in 0 until save)
        assertTrue(save in 0 until install)
        assertTrue(rejection.contains("routeRequestInFlight.set(false)"))
        assertTrue(rejection.contains("retainExistingRouteAfterFailure(detail)"))
        assertTrue(rejection.contains("return@runOnUiThread"))
    }

    @Test fun completedAResponseWaitingOnUiCannotOverwriteSelectedAndInstalledB() {
        val activity = MainActivity()
        val navigator = getField(activity, "routeNavigator") as RouteNavigator
        val destinationA = RoutePoint(0.0, 0.001, "A")
        val destinationB = RoutePoint(0.001, 0.0, "B")
        val routeA = route.copy(providerRouteId = "A")
        val routeB = route.copy(
            polyline = listOf(RoutePoint(0.0, 0.0), destinationB),
            providerRouteId = "B",
        )
        val inFlight = getField(activity, "routeRequestInFlight") as AtomicBoolean
        setField(activity, "routeRequestGeneration", 1)
        setField(activity, "isRouteActive", true)
        setField(activity, "currentDestination", destinationA)
        inFlight.set(true)
        val completedA = activity.trackRouteRequest(CancellableNetworkCall(
            executeBlock = { routeA }, cancelBlock = {},
        ))
        activity.completeRouteRequest(completedA)

        var savedSnapshot: WalkingRoute? = null
        val pendingUi = ArrayDeque<Runnable>()
        fun postInstall(id: Int, response: WalkingRoute, destination: RoutePoint) {
            pendingUi.addLast(Runnable {
                if (!activity.routeResponseInstallLeaseCurrent(id, { true })) return@Runnable
                savedSnapshot = response
                navigator.setRoute(response, destination)
                setField(activity, "currentDestination", destination)
                inFlight.set(false)
            })
        }
        postInstall(1, routeA, destinationA) // HTTP A completed, but its UI install is still queued.

        activity.cancelNavigationRequestsForDestinationSelection()
        activity.invalidateActiveRouteRequestLease() // Same owner transition used by clearActiveRouteRequestState.
        assertEquals(2, getField(activity, "routeRequestGeneration"))
        setField(activity, "routeRequestGeneration", 3) // B's request claims the next generation.
        setField(activity, "isRouteActive", true)
        setField(activity, "currentDestination", destinationB)
        inFlight.set(true)
        postInstall(3, routeB, destinationB)

        pendingUi.removeFirst().run() // A arrives while B's request is still in flight.
        assertNull(savedSnapshot)
        assertNull(navigator.currentRouteId())
        assertEquals(destinationB, getField(activity, "currentDestination"))
        assertTrue(inFlight.get())

        pendingUi.removeFirst().run() // Only B may install.
        assertEquals("B", savedSnapshot?.providerRouteId)
        assertEquals("B", navigator.currentRouteId())
        assertEquals(destinationB, getField(activity, "currentDestination"))
        assertEquals(3, getField(activity, "routeRequestGeneration"))
    }

    private fun getField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity)
        }

    private fun setField(activity: MainActivity, name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }
}
