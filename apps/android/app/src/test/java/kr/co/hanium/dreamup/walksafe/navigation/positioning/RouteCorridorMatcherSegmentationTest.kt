package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.*
import org.junit.Test

class RouteCorridorMatcherSegmentationTest {
    @Test fun straightDepartureIsIndependentOfTwoOrOneHundredOneCoordinates() {
        listOf(3.0, 20.0, 40.0).forEach { accuracy ->
            listOf(false, true).forEach { reverse ->
                val sparse = matchOnce(straight(), position(0.0, accuracy = accuracy), reverse)
                val dense = matchOnce(straight(dense = true), position(0.0, accuracy = accuracy), reverse)
                assertEquivalent(sparse, dense)
                assertEquals(RouteMatchQuality.HIGH, dense.quality)
                assertEquals(RouteMatchReason.MATCHED, dense.reason)
            }
        }
    }

    @Test fun forwardAndRetreatSamplesStayOnTheSameCorridorWithoutIndexBasedBranchSwitches() {
        listOf(3.0, 20.0, 40.0).forEach { accuracy ->
            val sparse = RouteCorridorMatcher(RouteCorridorMatcherConfig(allowReverseTravel = true))
            val dense = RouteCorridorMatcher(RouteCorridorMatcherConfig(allowReverseTravel = true))
            listOf(0.0, 20.0, 30.0, 20.0, 0.0).forEachIndexed { index, east ->
                val fix = position(east, accuracy = accuracy, time = (index + 1) * 1_000L)
                val expected = sparse.match("line", straight(), fix)
                val actual = dense.match("line", straight(dense = true), fix)
                assertEquivalent(expected, actual)
                assertEquals(RouteMatchReason.MATCHED, actual.reason)
                assertEquals(east, requireNotNull(actual.geometricProgressM), 0.001)
            }
        }
    }

    @Test fun duplicateCoordinatesDoNotBreakTheCorridorAndOriginalIndexesRemainAvailable() {
        val duplicate = listOf(point(0.0), point(0.0), point(20.0), point(20.0), point(20.0),
            point(50.0), point(50.0), point(100.0))
        val matcher = RouteCorridorMatcher()
        matcher.match("duplicates", duplicate, position(0.0))
        val result = matcher.match("duplicates", duplicate, position(30.0, time = 2_000))
        assertEquals(RouteMatchReason.MATCHED, result.reason)
        assertEquals(4, result.segmentIndex)
        assertEquals(1.0 / 3.0, requireNotNull(result.segmentFraction), 0.000001)
        assertEquals(30.0, requireNotNull(result.geometricProgressM), 0.001)
    }

    @Test fun densifyingAnAdjacentCornerDoesNotInventANonAdjacentBranchChange() {
        val sparseRoute = listOf(point(0.0), point(100.0), point(100.0, 100.0))
        val denseRoute = straight(dense = true) + (1..100).map { point(100.0, it.toDouble()) }
        val sparse = RouteCorridorMatcher()
        val dense = RouteCorridorMatcher()
        listOf(position(0.0), position(20.0, time = 2_000), position(30.0, time = 3_000),
            position(100.0, 10.0, time = 4_000), position(100.0, 30.0, time = 5_000)).forEach { fix ->
            assertEquivalent(sparse.match("corner", sparseRoute, fix), dense.match("corner", denseRoute, fix))
        }
    }

    @Test fun sameDirectionParallelLegsSeparatedByTurnsRemainCompetingBranches() {
        val route = listOf(point(0.0), point(100.0), point(100.0, 50.0), point(0.0, 50.0), point(0.0, 5.0), point(100.0, 5.0))
        listOf(route, subdivide(route)).forEach { line ->
            val result = matchOnce(line, position(50.0, 2.5, accuracy = 5.0), reverse = true)
            assertEquals(RouteMatchQuality.LOW, result.quality)
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
        }
    }

    @Test fun anOverlappingOutAndBackDoesNotBecomeOneStraightCorridor() {
        val route = listOf(point(0.0), point(100.0), point(0.0))
        listOf(route, subdivide(route)).forEach { line ->
            val result = matchOnce(line, position(50.0, accuracy = 5.0), reverse = true)
            assertEquals(RouteMatchQuality.LOW, result.quality)
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
        }
    }

    @Test fun returningToTheSameDirectionAfterALoopStillNeedsBranchConfirmation() {
        val route = listOf(point(0.0), point(100.0), point(100.0, 50.0), point(0.0, 50.0), point(0.0, 5.0), point(100.0, 5.0))
        listOf(route, subdivide(route)).forEach { line ->
            val matcher = RouteCorridorMatcher()
            val first = matcher.match("loop", line, position(20.0))
            assertEquals(RouteMatchReason.MATCHED, first.reason)
            val pending = matcher.match("loop", line, position(30.0, 5.0, time = 2_000), previousProgressM = 325.0)
            val confirmed = matcher.match("loop", line, position(40.0, 5.0, time = 3_000), previousProgressM = 335.0)
            assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, pending.reason)
            assertEquals(RouteMatchQuality.LOW, pending.quality)
            assertEquals(RouteMatchReason.MATCHED, confirmed.reason)
            assertEquals(335.0, requireNotNull(confirmed.geometricProgressM), 0.01)
        }
    }

    @Test fun graduallyTurningClosedLoopDoesNotMergeItsStartAndReturnDirection() {
        val loop = (0..720).map { step ->
            val angle = Math.toRadians(step * 0.5)
            point(50.0 * kotlin.math.sin(angle), 50.0 * (1.0 - kotlin.math.cos(angle)))
        }
        val result = matchOnce(loop, position(0.0), reverse = true)
        assertEquals(RouteMatchQuality.LOW, result.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
    }

    @Test fun weakPositionFitAndUncertainProgressAreNotPromotedByDensification() {
        val sparse = RouteCorridorMatcher()
        val dense = RouteCorridorMatcher()
        val poorFix = position(50.0, 12.0, accuracy = 3.0)
        val sparsePoor = sparse.match("poor", straight(), poorFix)
        val densePoor = dense.match("poor", straight(dense = true), poorFix)
        assertEquivalent(sparsePoor, densePoor)
        assertEquals(RouteMatchQuality.LOW, densePoor.quality)
        assertEquals(RouteMatchReason.LOW_CONFIDENCE, densePoor.reason)
        sparse.reset()
        dense.reset()
        val initial = position(0.0)
        sparse.match("jump", straight(), initial)
        dense.match("jump", straight(dense = true), initial)
        val jump = position(100.0, time = 2_000)
        val sparseJump = sparse.match("jump", straight(), jump)
        val denseJump = dense.match("jump", straight(dense = true), jump)
        assertEquivalent(sparseJump, denseJump)
        assertEquals(RouteMatchQuality.LOW, denseJump.quality)
        assertEquals(jump.point, denseJump.filteredPoint)
    }

    private fun assertEquivalent(expected: RouteCorridorMatchResult, actual: RouteCorridorMatchResult) {
        assertEquals("expected=$expected actual=$actual", expected.quality, actual.quality)
        assertEquals(expected.reason, actual.reason)
        assertEquals(expected.confidence, actual.confidence, 0.000001)
        assertEquals(requireNotNull(expected.geometricProgressM), requireNotNull(actual.geometricProgressM), 0.001)
        assertEquals(requireNotNull(expected.crossTrackDistanceM), requireNotNull(actual.crossTrackDistanceM), 0.001)
        assertEquals(requireNotNull(expected.matchedPoint).latitude, requireNotNull(actual.matchedPoint).latitude, 0.00000001)
        assertEquals(requireNotNull(expected.matchedPoint).longitude, requireNotNull(actual.matchedPoint).longitude, 0.00000001)
    }
    private fun matchOnce(route: List<RoutePoint>, fix: FilteredRoutePosition, reverse: Boolean = false) =
        RouteCorridorMatcher(RouteCorridorMatcherConfig(allowReverseTravel = reverse)).match("route", route, fix)
    private fun straight(dense: Boolean = false) = if (dense) (0..100).map { point(it.toDouble()) } else listOf(point(0.0), point(100.0))
    private fun subdivide(route: List<RoutePoint>) = route.zipWithNext().flatMap { (a, b) ->
        (0 until 100).map { step -> RoutePoint(a.latitude + (b.latitude - a.latitude) * step / 100.0,
            a.longitude + (b.longitude - a.longitude) * step / 100.0) }
    } + route.last()
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun position(east: Double, north: Double = 0.0, accuracy: Double = 3.0, time: Long = 1_000) =
        FilteredRoutePosition(point(east, north), accuracy, time)
}
