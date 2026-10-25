package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Actual geometry and Navigator reference cues; does not establish an SDK fix or a walkable connector. */
class RouteDepartureNearestPointTest {
    @Test fun lateralDepartureTargetsThePerpendicularNearestPointWithoutBacktracking() {
        val route = route()
        val here = point(5.0, 30.0)
        val origin = fix(here)
        val position = FilteredRoutePosition(here, 10.0, 1_000L)
        val match = RouteCorridorMatcher().match("nearest", route.polyline, position)
        assertEquals(RouteMatchQuality.LOW, match.quality)
        val selected = RouteAlignmentSelector().apply { reset(origin) }
            .select(route, position, match, 1_000L, 35.0)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, selected.diagnostic.reason)
        val target = requireNotNull(selected.target)
        assertEquals(5.0, target.longitude * METERS_PER_DEGREE, 0.001)
        assertEquals(0.0, target.latitude * METERS_PER_DEGREE, 0.001)
        assertEquals(30.0, distance(here, target), 0.001)
        assertEquals(180.0, requireNotNull(selected.bearingDegrees), 0.001)
        assertNotEquals(route.polyline.first(), target)
        assertTrue(distance(here, target) < distance(here, route.polyline.first()))
    }

    @Test fun nearestPointSpeechRemainsAReferenceWithNoAcceptedProgressOrWalkabilityClaim() {
        for (angularError in listOf(0.1f, 12f)) {
            val here = point(5.0, 30.0)
            val nav = RouteNavigator().apply { setRoute(route(), origin = fix(here)) }
            val firstFacing = rv(1_000L, angularError)
            val first = nav.update(fix(here).copy(accuracyM = 15f), 1_000L, false,
                filteredPosition = FilteredRoutePosition(here, 10.0, 1_000L), facingObservation = firstFacing)
            val nominal = RouteFacingGuidance.evaluate(180.0, firstFacing, 1_000L).clockReferenceKind == RouteClockReferenceKind.NOMINAL_TREND_CLOCK
            val at = if (nominal) 1_001L else 1_000L
            val facing = if (nominal) rv(at, angularError) else firstFacing
            val cue = if (nominal) requireNotNull(nav.currentGuidance(at, facing)) else first
            assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, cue.routeAlignmentDiagnostic?.reason)
            assertEquals("route_guidance", cue.reason)
            assertEquals(RouteMatchQuality.LOW, nav.currentRouteMatch()?.quality)
            assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
            assertTrue(cue.directionOnly)
            val text = requireNotNull(cue.instruction)
            assertFalse(text, text.contains("경로 시작점"))
            assertTrue(text, text.contains("가장 가까운 경로 지점"))
            assertTrue(text, text.contains("걸어갈 수 있는 통로는 확인되지 않았습니다."))
            assertTrue(text, text.contains("약 3시 방향"))
            assertFalse(text, text.contains("시에서") || text.contains("사이 방향"))
            for (command in listOf("이동하세요", "직진하세요", "돌아가세요", "꺾으세요", "맞추세요", "걸으세요")) {
                assertFalse(text, text.contains(command))
            }
            assertFalse(cue.arrived)
            assertFalse(cue.arrivalCandidate)
            assertFalse(cue.shouldReroute)
            assertNull(cue.pendingUserDecision)
            assertTrue(nav.reserveInstruction(cue))
            assertEquals(at, cue.speechCueToken?.facingObservedAtElapsedRealtimeMs)
            assertEquals(at + 500L, cue.speechCueToken?.facingValidUntilElapsedRealtimeMs)
            assertTrue(nav.isInstructionCurrent(cue, at, facing))
            assertFalse(nav.isInstructionCurrent(cue, at + 501L, facing))
            nav.setRoute(route(), origin = fix(here, 1_600L))
            assertFalse(nav.isInstructionCurrent(cue, 1_600L, rv(1_600L, angularError)))
            assertFalse(nav.reserveInstruction(cue))
        }
    }

    @Test fun aPointBeforeTheFirstVertexClampsToThatVertexBecauseItReallyIsNearest() {
        val route = route()
        val here = point(-5.0, 30.0)
        val position = FilteredRoutePosition(here, 10.0, 1_000L)
        val match = RouteCorridorMatcher().match("before-start", route.polyline, position)
        val selected = RouteAlignmentSelector().apply { reset(fix(here)) }
            .select(route, position, match, 1_000L, 35.0)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, selected.diagnostic.reason)
        assertEquals(route.polyline.first(), selected.target)
        assertEquals(hypot(5.0, 30.0), distance(here, requireNotNull(selected.target)), 0.001)
        assertEquals(170.5377, requireNotNull(selected.bearingDegrees), 0.001)
    }

    @Test fun anOpposingNearbyBranchStillPreventsChoosingAConnectorDirection() {
        val route = WalkingRoute("OPPOSING", WalkingRouteSummary(210, 210),
            listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 10.0), point(0.0, 10.0)), emptyList())
        val here = point(5.0, 5.0)
        val position = FilteredRoutePosition(here, 10.0, 1_000L)
        val match = RouteCorridorMatcher().match("opposing", route.polyline, position)
        val selected = RouteAlignmentSelector().apply { reset(fix(here)) }
            .select(route, position, match, 1_000L, 35.0)
        assertEquals(RouteAlignmentReason.BRANCH_AMBIGUOUS, selected.diagnostic.reason)
        assertNull(selected.target)
        assertNull(selected.bearingDegrees)
    }

    @Test fun anUnacceptedFirstLegAnchorCannotOverrideACloserLaterLegOrTheEndpointGuard() {
        // Exercise the actual Matcher's low-confidence result as a Selector input. Navigator's
        // first-update movement-heading replacement is a separate boundary, not claimed here.
        for (lastEast in listOf(0.0, -100.0)) {
            val route = WalkingRoute("LATER", WalkingRouteSummary(320, 320),
                listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 20.0), point(lastEast, 20.0)), emptyList())
            val here = point(5.0, 30.0)
            val position = FilteredRoutePosition(here, 10.0, 1_000L,
                heading = kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate(90.0, 8.0))
            val match = RouteCorridorMatcher().match("later", route.polyline, position)
            assertEquals(RouteMatchQuality.LOW, match.quality)
            assertEquals(0, match.segmentIndex)
            assertEquals(30.0, requireNotNull(match.crossTrackDistanceM), 0.001)
            assertEquals(10.0, distance(here, point(5.0, 20.0)), 0.001)
            val selected = RouteAlignmentSelector().apply { reset(fix(here)) }
                .select(route, position, match, 1_000L, 35.0)
            assertNotEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, selected.diagnostic.reason)
            if (lastEast == 0.0) {
                // The requested rectangle ends only 11.18 m away; retain the existing endpoint guard.
                assertEquals(RouteAlignmentReason.NEAR_ROUTE_END, selected.diagnostic.reason)
                assertNull(selected.target)
                assertNull(selected.bearingDegrees)
            } else {
                assertEquals(RouteAlignmentReason.AGREED_CANDIDATES, selected.diagnostic.reason)
                assertEquals(2, selected.diagnostic.segmentIndex)
                assertEquals(270.0, requireNotNull(selected.bearingDegrees), 0.001)
                val target = requireNotNull(selected.target)
                assertEquals(-10.0, target.longitude * METERS_PER_DEGREE, 0.001)
                assertEquals(20.0, target.latitude * METERS_PER_DEGREE, 0.001)
                assertNotEquals(route.polyline.first(), target)
            }
        }
    }

    private fun route() = WalkingRoute("NEAREST", WalkingRouteSummary(100, 100),
        listOf(point(0.0, 0.0), point(100.0, 0.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0, 0.0), null, 100, 100, turnType = 13)))
    private fun point(east: Double, north: Double) = RoutePoint(north / METERS_PER_DEGREE, east / METERS_PER_DEGREE)
    private fun fix(point: RoutePoint, at: Long = 1_000L) = TrustedLocation(point.latitude, point.longitude, 10f, at)
    private fun distance(a: RoutePoint, b: RoutePoint) =
        hypot((a.longitude - b.longitude) * METERS_PER_DEGREE, (a.latitude - b.latitude) * METERS_PER_DEGREE)
    private fun rv(at: Long, error: Float): RouteFacingObservation {
        val angle = Math.toRadians(90.0)
        val matrix = RotationMatrix3(cos(angle).toFloat(), 0f, (-sin(angle)).toFloat(),
            (-sin(angle)).toFloat(), 0f, (-cos(angle)).toFloat(), 0f, 1f, 0f)
        val measured = RouteCompassHeading.evaluate(DeviceEarthOrientation(matrix, at, error,
            EarthOrientationAccuracy.HIGH), 0.0, at)
        assertEquals(RouteCompassHeadingReason.VALID, measured.reason)
        return requireNotNull(measured.observation)
    }
    companion object { private const val METERS_PER_DEGREE = 111_194.92664455874 }
}
