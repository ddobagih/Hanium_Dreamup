package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test

/** Real Main/Navigator presentation; only SDK admission/location observations and clock are supplied. */
class MainActivityClosestApproachPresentationBehaviorTest {
    @Test
    fun freshClosestInteriorTargetIsRunningGuidanceRatherThanPreparation() {
        val f = Fixture()
        val target = requireNotNull(f.navigator.currentRouteApproachTarget(f.now))
        assertEquals(50.0, target.point.longitude * METRES_PER_DEGREE, 0.01)
        assertEquals(0.0, target.point.latitude, 1e-9)
        assertEquals(22.0, target.distanceM, 0.01)
        assertEquals(1_000L, target.observedAtElapsedRealtimeMs)
        assertTrue(f.navigator.isWaitingForInitialRouteJoin())
        assertNull("An approach reference is not accepted route progress", f.navigator.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(f.instruction, f.instruction.contains("약 3시 방향"))
        assertTrue(f.instruction, f.instruction.contains("직선거리 약 22미터"))
        assertFalse(f.instruction, f.instruction.contains("이동하세요"))

        val actual = f.display()
        assertTrue(actual.outputRunning)
        assertFalse(actual.preparationRunning)
        assertNull(actual.unavailableReasonKo)
        assertEquals("가장 가까운 경로 지점의 방향과 거리를 안내하고 있습니다.", actual.availableMessageKo)
        val presented = DestinationGuidancePresentationPolicy.present(actual,
            GuidanceFeatureDisplayInput(true, false, null), retryAvailable = false)
        assertEquals(DestinationGuidanceDisplayStage.ACTIVE, presented.stage)
        assertEquals("위치·경로: 가장 가까운 경로 지점의 방향과 거리를 안내하고 있습니다.", presented.routeMessageKo)
    }

    @Test
    fun staleOrFutureApproachContextCannotBeDisplayedAsRunningGuidance() {
        val f = Fixture()
        f.now = 11_000L
        assertNotNull(f.navigator.currentRouteApproachTarget(f.now))
        assertTrue(f.display().outputRunning)
        for (now in listOf(11_001L, 999L)) {
            f.now = now
            assertNull(f.navigator.currentRouteApproachTarget(f.now))
            val actual = f.display()
            assertFalse(actual.outputRunning)
            assertTrue(actual.preparationRunning)
            assertNull(actual.availableMessageKo)
        }
        f.now = 1_000L
        assertNotNull("UI reads must not erase a still-current target", f.navigator.currentRouteApproachTarget(f.now))
    }

    @Test
    fun aDifferentCurrentDestinationCannotBorrowTheInstalledApproachTarget() {
        val f = Fixture()
        val target = requireNotNull(f.navigator.currentRouteApproachTarget(f.now))
        f.set("currentDestination", point(200.0, -100.0))
        val actual = f.display()
        assertFalse(actual.outputRunning)
        assertTrue(actual.preparationRunning)
        assertNull(actual.availableMessageKo)
        assertEquals(target, f.navigator.currentRouteApproachTarget(f.now))
    }

    @Test
    fun anActualPendingRouteRequestKeepsApproachOutputBlocked() {
        val f = Fixture()
        val pending = f.field("routeRequestInFlight") as AtomicBoolean
        assertFalse(pending.get())
        pending.set(true)
        val actual = f.display()
        assertFalse(actual.outputRunning)
        assertTrue(actual.preparationRunning)
        assertNull(actual.availableMessageKo)
        assertNotNull(f.navigator.currentRouteApproachTarget(f.now))
        pending.set(false)
        assertTrue(f.display().outputRunning)
    }

    @Test
    fun actualPositionPauseBlocksTheApproachAndKeepsItsActionableReason() {
        val f = Fixture()
        f.set("positionGuidancePaused", true)
        val actual = f.display()
        assertFalse(actual.outputRunning)
        assertTrue(actual.preparationRunning)
        assertEquals("현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰 있습니다.", actual.unavailableReasonKo)
        assertNull(actual.availableMessageKo)
        assertNotNull(f.navigator.currentRouteApproachTarget(f.now))
        f.set("positionGuidancePaused", false)
        assertTrue(f.display().outputRunning)
    }

    @Test
    fun deniedCollectionOrMissingTrustedLocationCannotEnableApproachOutput() {
        val f = Fixture()
        for ((collection, trusted) in listOf(false to true, true to false)) {
            val actual = f.display(collection = collection, trusted = trusted)
            assertFalse(actual.outputRunning)
            assertTrue(actual.preparationRunning)
            assertNull(actual.availableMessageKo)
            assertNotNull(f.navigator.currentRouteApproachTarget(f.now))
        }
        assertTrue(f.display().outputRunning)
    }

    private class Fixture {
        val main = MainActivity()
        var now = 1_000L
        val navigator = field("routeNavigator") as RouteNavigator
        private val destination = point(100.0, -100.0)
        private val origin = point(50.0, 22.0).let { TrustedLocation(it.latitude, it.longitude, 1f, 1_000L) }
        val instruction: String
        init {
            val route = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
                listOf(point(0.0), point(100.0), destination),
                listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
            navigator.setRoute(route, destination = destination, origin = origin, waitForInitialRouteJoin = true,
                announceStartSummary = false)
            val update = navigator.update(origin, now, false,
                facingObservation = RouteFacingObservation(90.0, 1.0, 1_000L),
                filteredPosition = FilteredRoutePosition(RoutePoint(origin.latitude, origin.longitude), 1.0, 1_000L))
            instruction = requireNotNull(update.instruction)
            set("isRouteActive", true)
            set("latestTmapOnRoute", false)
            set("currentDestination", destination)
            set("pendingUiDestination", DestinationSearchResult("destination", "목적지", destination, null, null, null, null))
            set("positionGuidancePaused", false)
        }
        fun display(collection: Boolean = true, trusted: Boolean = true) = main.currentRouteGuidanceDisplayInput(
            runtimeActive = true,
            collectionAllowsWork = { collection },
            trustedLocationAvailable = { trusted },
            locationPermissionReady = { true },
            locationServiceEnabled = { true },
            elapsedRealtimeMs = { now },
        )
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name).get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name).set(main, value)
    }

    companion object {
        private const val METRES_PER_DEGREE = 111_194.92664455874
        private fun point(eastM: Double, northM: Double = 0.0) =
            RoutePoint(northM / METRES_PER_DEGREE, eastM / METRES_PER_DEGREE)
    }
}
