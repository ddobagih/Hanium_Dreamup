package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorBearingCandidatesTest {
    @Test fun longStraightRouteSupportsFrontAndBehindAtTwentyThirtyAndFortyMeterAccuracy() {
        listOf(false, true).forEach { degraded ->
            listOf(20f, 30f, 40f).forEach { accuracy ->
                listOf(90.0 to "앞쪽이 경로", 270.0 to "경로 진행 방향이 뒤쪽").forEach { (heading, expected) ->
                    val navigator = navigator(straight(), degraded)
                    val result = navigator.update(fix(500.0, accuracy = accuracy), 1_000, false,
                        facingObservation = facing(heading))
                    assertTrue("degraded=$degraded accuracy=$accuracy heading=$heading: ${result.instruction}",
                        result.instruction?.contains(expected) == true)
                }
            }
        }
    }

    @Test fun adjacentCornerUsesTheCurrentMatchedLegInsteadOfDiscardingItForTheFutureLeg() {
        val route = route(listOf(point(0.0), point(500.0), point(500.0, -500.0)))
        val navigator = navigator(route)
        val result = navigator.update(fix(450.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(result.instruction?.contains("약 12시 방향") == true)
        assertEquals(0, result.routeAlignmentDiagnostic?.segmentIndex)
        assertEquals(RouteMatchReason.MATCHED, navigator.currentRouteMatch()?.reason)
    }

    @Test fun reverseParallelSegmentInTheUncertaintyAreaCannotBeIgnoredBecauseMatcherChoseOneBranch() {
        val route = route(listOf(point(0.0), point(1_000.0), point(1_000.0, 100.0), point(0.0, 100.0)))
        val navigator = navigator(route)
        val result = navigator.update(fix(500.0, 30.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertRouteDirectionUncertain(result)
        assertEquals(RouteMatchReason.MATCHED, navigator.currentRouteMatch()?.reason)
    }

    @Test fun routeOutsideTheLocalCorridorIsNotMadeReliableByAWideUncertaintyCircle() {
        val result = navigator(straight()).update(fix(500.0, 60.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertRouteDirectionUncertain(result)
    }

    @Test fun extremelyLargePositionErrorNeverProducesARelativeDirectionEvenOnALongStraightRoute() {
        listOf(101f, 1_000f, Float.MAX_VALUE).forEach { accuracy ->
            val result = navigator(route(listOf(point(0.0), point(10_000.0)))).update(
                fix(5_000.0, accuracy = accuracy), 1_000, false, facingObservation = facing(90.0))
            assertRouteDirectionUncertain(result)
        }
    }

    @Test fun stationaryManualAndRetryReevaluateTheStoredCandidatesWithFreshFacing() {
        val navigator = navigator(straight())
        val first = navigator.update(fix(500.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(first.instruction?.contains("앞쪽이 경로") == true)
        val match = navigator.currentRouteMatch()
        val turned = requireNotNull(navigator.retryGuidance(1_100, facing(270.0, 1_100)))
        assertTrue(turned.instruction?.contains("경로 진행 방향이 뒤쪽") == true)
        assertSame(match, navigator.currentRouteMatch())
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.reserveInstruction(turned))
        navigator.acknowledgeInstruction(turned, 1_200)
        val manual = requireNotNull(navigator.currentGuidance(1_300, facing(90.0, 1_300)))
        assertTrue(manual.instruction?.contains("앞쪽이 경로") == true)
        assertSame(match, navigator.currentRouteMatch())
    }

    @Test fun turningDuringTheSameGuideSentenceDoesNotCancelItAndTheNextManualRequestUsesTheTurn() {
        val navigator = navigator(straight())
        val first = navigator.update(fix(500.0, accuracy = 30f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(navigator.reserveInstruction(first))
        val duringSpeech = navigator.update(fix(500.0, time = 2_000, accuracy = 30f), 2_000, false,
            facingObservation = facing(270.0, 2_000))
        assertEquals("guidance_in_flight", duringSpeech.reason)
        assertFalse(duringSpeech.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 2_100)
        assertTrue(navigator.currentGuidance(2_200, facing(270.0, 2_200))?.instruction?.contains("경로 진행 방향이 뒤쪽") == true)
    }

    @Test fun candidatesUseUnsnappedFilteredPointAndUncertaintyWithoutARawAccuracyCutoff() {
        val filteredPrecise = navigator(straight()).update(fix(500.0, accuracy = 40f), 1_000, false,
            FilteredRoutePosition(point(500.0), 3.0, 1_000), facingObservation = facing(90.0))
        assertTrue(filteredPrecise.instruction?.contains("앞쪽이 경로") == true)

        val corner = route(listOf(point(0.0), point(500.0), point(500.0, -500.0)))
        val filteredUncertain = navigator(corner).update(fix(450.0, accuracy = 3f), 1_000, false,
            FilteredRoutePosition(point(500.0, -30.0), 40.0, 1_000), facingObservation = facing(90.0))
        assertTrue(filteredUncertain.instruction?.contains("약 3시 방향") == true)
        assertEquals(1, filteredUncertain.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun zeroLengthRoutePointsAreSkippedInsteadOfCreatingANorthBearing() {
        val repeatedPoint = route(listOf(point(0.0), point(500.0), point(500.0), point(1_000.0)))
        val result = navigator(repeatedPoint).update(fix(500.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(result.instruction?.contains("앞쪽이 경로") == true)
    }

    @Test fun uncertaintyThatCoversTheWholeShortRouteDoesNotProveWhichSideOfTheDestinationWeAreOn() {
        val short = route(listOf(point(0.0), point(20.0)))
        assertRouteDirectionUncertain(navigator(short).update(fix(10.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0)))
    }

    @Test fun uncertaintyAroundTheEndOfALongRouteAlsoSuppressesForwardAlignment() {
        assertRouteDirectionUncertain(navigator(straight()).update(fix(980.0, accuracy = 20f), 1_000, false,
            facingObservation = facing(90.0)))
        val beforeEndArea = navigator(straight()).update(fix(900.0, accuracy = 20f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(beforeEndArea.instruction?.contains("앞쪽이 경로") == true)
    }

    @Test fun denseCollinearSegmentsCanAgreeOnFacingWithoutUpgradingAmbiguousPositionEvidence() {
        val dense = route(listOf(point(0.0), point(480.0), point(500.0), point(500.0), point(520.0), point(1_000.0)))
        listOf(90.0 to "앞쪽이 경로", 270.0 to "경로 진행 방향이 뒤쪽").forEach { (heading, expected) ->
            val navigator = navigator(dense)
            val result = navigator.update(fix(500.0, accuracy = 40f), 1_000, false,
                facingObservation = facing(heading))
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, navigator.currentRouteMatch()?.reason)
            assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
            assertNull(navigator.currentBearingDeg())
            assertTrue(result.instruction?.contains("추정") == true)
            assertTrue(result.instruction?.contains(expected) == true)
        }
        val strict = navigator(dense, degraded = false).update(fix(500.0, accuracy = 40f), 1_000, false,
            facingObservation = facing(90.0))
        assertEquals("route_match_untrusted", strict.reason)
        assertNull(strict.instruction)
    }

    @Test fun retriesReevaluateCurrentLegFacingWithoutSwitchingToTheNearbyOutgoingSegment() {
        val bend = route(listOf(point(0.0), point(500.0), point(1_000.0, -500.0)))
        val navigator = navigator(bend)
        val first = navigator.update(fix(490.0, accuracy = 20f), 1_000, false,
            facingObservation = facing(90.0))
        assertTrue(first.instruction?.contains("약 12시 방향") == true)
        val match = navigator.currentRouteMatch()
        val turned = requireNotNull(navigator.retryGuidance(1_100, facing(0.0, 1_100)))
        assertTrue(turned.instruction?.contains("오른쪽 경로 방향으로 몸을 돌리세요") == true)
        assertSame(match, navigator.currentRouteMatch())
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.currentGuidance(1_200, facing(270.0, 1_200))?.instruction?.contains("약 6시 방향") == true)
    }

    @Test fun validRouteCandidatesDoNotReplaceMissingOrStaleCompassWithMovementCourse() {
        listOf<RouteFacingObservation?>(null, facing(90.0, 499)).forEach { observation ->
            val result = navigator(straight()).update(fix(500.0, accuracy = 40f), 1_000, false,
                facingObservation = observation)
            assertTrue(result.instruction?.contains("현재 바라보는 방향을 확인할 수 없습니다") == true)
            assertFalse(result.instruction?.contains("앞쪽이 경로") == true)
        }
    }

    private fun assertRouteDirectionUncertain(result: RouteNavigatorUpdate) {
        assertTrue(result.instruction.toString(), result.instruction?.contains("현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다") == true)
        assertFalse(result.instruction?.contains("앞쪽이 경로") == true)
        assertFalse(result.instruction?.contains("몸을 돌리세요") == true)
        assertFalse(result.instruction?.contains("나침반 방향은 측정되지만") == true)
    }

    private fun navigator(route: WalkingRoute, degraded: Boolean = true) =
        RouteNavigator(allowDegradedRouteGuidance = degraded).apply { setRoute(route) }
    private fun straight() = route(listOf(point(0.0), point(500.0), point(1_000.0)))
    private fun route(polyline: List<RoutePoint>): WalkingRoute {
        val distanceM = polyline.zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }.roundToInt()
        return WalkingRoute("TEST", WalkingRouteSummary(distanceM, distanceM), polyline, emptyList())
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun fix(east: Double, north: Double = 0.0, time: Long = 1_000, accuracy: Float = 3f): TrustedLocation =
        point(east, north).let { TrustedLocation(it.latitude, it.longitude, accuracy, time) }
    private fun facing(heading: Double, time: Long = 1_000) = RouteFacingObservation(heading, 5.0, time)
}
