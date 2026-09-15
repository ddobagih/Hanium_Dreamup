package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class MainActivityRouteStartStateTest {
    @Test fun firstRequestAndGpsInTheSameCallbackDoNotReportAnInvalidSavedRoute() {
        val activity = activity()
        set(activity, "isRouteActive", true)
        set(activity, "latestNavigationState", "navigation=route_requesting")
        pending(activity).set(true)
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        assertFalse(navigator.hasRoute())

        deliverLocation(activity)

        assertEquals("navigation=route_requesting", field(activity, "latestNavigationState"))
        assertNull(field(activity, "directionGuidancePauseReason"))
        assertTrue(pending(activity).get())
        assertFalse(navigator.hasRoute())
    }

    @Test fun hazardOnlyLocationDoesNotReplaceTheUiWithRouteMissing() {
        val activity = activity()
        set(activity, "latestNavigationState", "navigation=destination_none")
        deliverLocation(activity)
        assertEquals("navigation=destination_none", field(activity, "latestNavigationState"))
        assertNull(field(activity, "directionGuidancePauseReason"))
    }

    @Test fun rerouteLoadingDoesNotAdvanceOrClearThePreviousRoute() {
        val activity = activity()
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        navigator.setRoute(route())
        val routeId = navigator.currentRouteId()
        set(activity, "isRouteActive", true)
        pending(activity).set(true)

        deliverLocation(activity)

        assertEquals(routeId, navigator.currentRouteId())
        assertNull(navigator.currentRouteMatch())
        assertNull(navigator.pendingUserDecision())
    }

    @Test fun homeDistinguishesSelectedLoadingInstalledAndActuallyMissingRoute() {
        val activity = activity()
        set(activity, "currentDestination", RoutePoint(37.001, 127.0, "목적지"))
        assertTrue(message(activity).contains("목적지가 선택"))
        set(activity, "isRouteActive", true)
        pending(activity).set(true)
        assertTrue(message(activity).contains("경로를 찾고"))
        assertFalse(message(activity).contains("설정되었습니다"))
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        navigator.setRoute(route())
        assertTrue(message(activity).contains("경로를 찾고"))
        pending(activity).set(false)
        assertTrue(message(activity).contains("경로가 설정되었습니다"))
        navigator.clear()
        assertTrue(message(activity).contains("경로 확인이 필요"))
        assertFalse(message(activity).contains("설정되었습니다"))
    }

    @Test fun readyRouteUsesItsRequestOriginAndRetainsRealInvalidDataDiagnostics() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val setRoute = source.substringAfter("routeNavigator.setRoute(").substringBefore("routeStartStepCount =")
        assertTrue(setRoute.contains("origin = origin"))
        val update = source.substringAfter("private fun updateRouteGuidance(")
            .substringBefore("private fun recordRouteAlignmentDiagnostic(")
        assertTrue(update.indexOf("if (!isRouteActive || routeRequestInFlight.get()) return") <
            update.indexOf("routeNavigator.update("))
        assertTrue(update.contains("setOf(\"route_missing\", \"polyline_missing\")"))
        assertTrue(update.contains("reason = \"route_invalid\""))
        assertFalse(update.contains("저장된 TMAP"))
        assertTrue(update.contains("facingObservation = facing"))
        assertTrue(update.contains("recordRouteAlignmentDiagnostic(update, facing"))
        val routeView = source.substringAfter("nativeHomeRouteStatusText = TextView(this).apply")
            .substringBefore("overlay.addView(nativeHomeRouteStatusText")
        assertTrue(routeView.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
    }

    private fun activity() = MainActivity().also { activity ->
        val lifecycle = WalkSessionLifecycle()
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(epoch,
            WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach {
            collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
        }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(100)))
        lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        set(activity, "walkSessionLifecycle", lifecycle)
    }

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 80),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.001, 127.0)), emptyList())

    private fun deliverLocation(activity: MainActivity) {
        val fix = TrustedLocation(37.0, 127.0, 8f, 0L)
        privateMethod("updateRouteGuidance", Void.TYPE, TrustedLocation::class.java,
            TrustedLocation::class.java).invokeWithArguments(activity, fix, fix)
    }

    private fun message(activity: MainActivity) =
        privateMethod("homeRouteStatusMessage", String::class.java).invokeWithArguments(activity) as String

    private fun privateMethod(name: String, result: Class<*>, vararg arguments: Class<*>) =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
            MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))

    private fun pending(activity: MainActivity) = field(activity, "routeRequestInFlight") as AtomicBoolean
    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: MainActivity, name: String, value: Any) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
}
