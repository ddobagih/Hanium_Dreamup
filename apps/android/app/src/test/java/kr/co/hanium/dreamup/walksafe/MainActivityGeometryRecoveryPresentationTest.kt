package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test

/** Actual Main/Navigator state. SDK permission/location availability are controlled port facts. */
class MainActivityGeometryRecoveryPresentationTest {
    @Test fun retainedGeometryContinuesToDisplayGuidanceWithoutPretendingTheNewFixMatched() {
        val f = Fixture()
        assertTrue(f.nav.hasCurrentRouteGeometryGrace(f.now))
        assertNull(f.nav.currentAcceptedRouteMatchFor(f.now))
        val display = f.display()
        assertTrue(display.outputRunning)
        assertFalse(display.preparationRunning)
        assertNull(display.unavailableReasonKo)
    }

    @Test fun expiredGeometryAndDestinationReplacementCannotBorrowTheRunningState() {
        val f = Fixture()
        f.now = 11_000L
        assertFalse(f.nav.hasCurrentRouteGeometryGrace(f.now))
        assertFalse(f.display().outputRunning)
        val other = Fixture()
        other.set("pendingUiDestination", DestinationSearchResult("other", "다른 목적지", point(100.0, 100.0), null, null, null, null))
        assertFalse(other.display().outputRunning)
    }

    @Test fun confirmedLocationLossExplainsAutomaticRecoveryWithoutARequiredButton() {
        val f = Fixture()
        f.nav.onUntrustedLocation()
        assertTrue(f.nav.canResumeAfterPositionRecovery())
        assertFalse(f.nav.hasCurrentRouteGeometryGrace(f.now))
        val display = f.display()
        assertFalse(display.outputRunning)
        assertTrue(display.unavailableReasonKo.orEmpty(), display.unavailableReasonKo.orEmpty().contains("자동으로"))
        assertFalse(display.unavailableReasonKo.orEmpty().contains("말씀해"))
    }

    @Test fun permissionLossAndRealPositionPauseRemainVisibleAndCannotShowRunningGuidance() {
        val f = Fixture()
        f.nav.onUntrustedLocation()
        assertTrue(f.display(permission = false).unavailableReasonKo.orEmpty().contains("권한"))
        val paused = Fixture()
        paused.set("positionGuidancePaused", true)
        assertFalse(paused.display().outputRunning)
    }

    private class Fixture {
        val main = MainActivity()
        var now = 1_000L
        val nav = MainActivityControllerTestFixture.field("routeNavigator").get(main) as RouteNavigator
        private val destination = point(0.0, 5.0)
        init {
            nav.setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
                listOf(point(0.0), point(20.0), point(20.0, 8.0), point(0.0, 8.0), point(0.0, 5.0), point(20.0, 5.0)), emptyList()),
                destination = destination, origin = TrustedLocation(0.0, 0.0, 3f, 0L), announceStartSummary = false)
            step(5.0, 0.0, 3f, 1_000L)
            check(nav.currentAcceptedRouteMatchFor(1_000L) != null)
            step(6.0, 2.5, 5f, 2_000L)
            set("isRouteActive", true)
            set("latestTmapOnRoute", false)
            set("currentDestination", destination)
            set("pendingUiDestination", DestinationSearchResult("destination", "목적지", destination, null, null, null, null))
            set("positionGuidancePaused", false)
        }
        private fun step(east: Double, north: Double, accuracy: Float, at: Long) {
            now = at
            val p = point(east, north)
            nav.update(TrustedLocation(p.latitude, p.longitude, accuracy, at), at, false,
                facingObservation = RouteFacingObservation(90.0, 3.0, at),
                filteredPosition = FilteredRoutePosition(p, accuracy.toDouble(), at))
        }
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name).set(main, value)
        fun display(permission: Boolean = true) = main.currentRouteGuidanceDisplayInput(
            runtimeActive = true, collectionAllowsWork = { true }, trustedLocationAvailable = { true },
            locationPermissionReady = { permission }, locationServiceEnabled = { true }, elapsedRealtimeMs = { now })
    }
    companion object {
        private const val METRES_PER_DEGREE = 111_194.92664455874
        private fun point(eastM: Double, northM: Double = 0.0) = RoutePoint(northM / METRES_PER_DEGREE, eastM / METRES_PER_DEGREE)
    }
}
