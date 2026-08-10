package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationCompositionTest {
    @Test
    fun destinationSearchPreservesRequestsUntilActualCandidateSelectionCancelsBoth() {
        val activity = MainActivity()
        val routeCancels = AtomicInteger()
        val searchCancels = AtomicInteger()
        val route = activity.trackRouteRequest(recordingCall(routeCancels))
        val search = activity.trackDestinationSearchRequest(recordingCall(searchCancels))

        assertFalse(route.isCancelled())
        assertFalse(search.isCancelled())

        setReporterUserId(activity)
        val entryFailure = invokeEntry(
            activity = activity,
            methodName = "onDestinationSelected",
            parameterTypes = arrayOf(DestinationSearchResult::class.java),
            arguments = arrayOf(
                DestinationSearchResult(
                    id = "destination-1",
                    name = "서울역",
                    point = RoutePoint(37.5547, 126.9707, "서울역"),
                    address = null,
                    roadAddress = null,
                    category = null,
                    distanceM = null,
                ),
            ),
        )

        assertTrue("selection entry failed before cancellation: $entryFailure", route.isCancelled())
        assertTrue("selection entry failed before cancellation: $entryFailure", search.isCancelled())
        assertEquals(1, routeCancels.get())
        assertEquals(1, searchCancels.get())
    }

    @Test
    fun actualPauseAndDestroyEntriesEachCancelTheMainActivityActiveRouteAndSearchCalls() {
        listOf("onPause", "onDestroy").forEach { lifecycleMethod ->
            val activity = MainActivity()
            setBooleanField(activity, "privacyStartupInspectionComplete", true)
            val route = activity.trackRouteRequest(recordingCall(AtomicInteger()))
            val search = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

            val entryFailure = invokeEntry(activity, lifecycleMethod)

            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", route.isCancelled())
            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", search.isCancelled())
        }
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementRouteCall() {
        val activity = MainActivity()
        val oldRoute = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackRouteRequest(recordingCall(AtomicInteger()))

        activity.completeRouteRequest(oldRoute)
        val paused = activity.cancelNavigationRequestsForPause()

        assertTrue(paused.routeCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementDestinationSearchCall() {
        val activity = MainActivity()
        val oldSearch = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

        activity.completeDestinationSearchRequest(oldSearch)
        val destroyed = activity.cancelNavigationRequestsForDestroy()

        assertTrue(destroyed.destinationSearchCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun pauseCancelsRerouteAndRequiresFreshRouteWhileRetainingDestinationProposal() {
        val activity = MainActivity()
        val destination = RoutePoint(37.001, 127.0, "목적지")
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
                polyline = listOf(RoutePoint(37.0, 127.0), destination),
                guidePoints = emptyList(),
            ),
            destination = destination,
        )
        setBooleanField(activity, "isRouteActive", true)
        MainActivity::class.java.getDeclaredField("currentDestination").apply {
            isAccessible = true
            set(activity, destination)
        }
        (field(activity, "routeRequestInFlight") as AtomicBoolean).set(true)
        val rerouteCall = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        setBooleanField(activity, "privacyStartupInspectionComplete", true)

        invokeEntry(activity, "onPause")

        assertTrue(rerouteCall.isCancelled())
        assertFalse(MainActivity::class.java.getDeclaredField("isRouteActive").run {
            isAccessible = true
            getBoolean(activity)
        })
        assertFalse(navigator.hasRoute())
        assertEquals(destination, field(activity, "currentDestination"))
        assertFalse((field(activity, "routeRequestInFlight") as AtomicBoolean).get())
    }

    private fun recordingCall(cancelCount: AtomicInteger): CancellableNetworkCall<Unit> {
        return CancellableNetworkCall(
            executeBlock = {},
            cancelBlock = { cancelCount.incrementAndGet() },
        )
    }

    private fun setReporterUserId(activity: MainActivity) {
        MainActivity::class.java.getDeclaredField("reporterUserId").apply {
            isAccessible = true
            set(activity, "test-user")
        }
    }

    private fun field(activity: MainActivity, name: String): Any? {
        return MainActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity)
        }
    }

    private fun setBooleanField(activity: MainActivity, name: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun invokeEntry(
        activity: MainActivity,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any> = emptyArray(),
    ): Throwable? {
        return runCatching {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, methodName, MethodType.methodType(Void.TYPE, parameterTypes.toList()))
                .bindTo(activity)
                .invokeWithArguments(arguments.toList())
        }.exceptionOrNull()
    }
}
