package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S07-20: "adjacent" means connected along the route, not a corridor index difference of one.
 * S07-21: on an overlapping out-and-back, visit order separates legs only when the other leg is
 * physically unreachable since the last accepted match; otherwise the ambiguity stays LOW.
 */
class RouteCorridorMatcherConnectedBranchTest {
    @Test
    fun quarterCircleVertexDensityDoesNotChangeMatchQualityOrProgress() {
        val radius = 60.0
        fun arc(stepDeg: Double) = generateSequence(0.0) { it + stepDeg }.takeWhile { it <= 90.0 + 1e-9 }
            .map { degrees -> arcPoint(radius, degrees) }.toList()
        val samples = listOf(10.0, 25.0, 40.0, 55.0, 70.0)
        val reference = walk(arc(15.0), samples.map { arcPoint(radius, it) })

        listOf(10.0, 5.0, 3.0, 2.0, 1.0, 0.5).forEach { step ->
            val results = walk(arc(step), samples.map { arcPoint(radius, it) })
            results.forEachIndexed { index, result ->
                assertTrue(
                    "step=$step sample=$index ${result.quality}/${result.reason}",
                    result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM,
                )
                assertEquals(
                    "step=$step sample=$index progress",
                    requireNotNull(reference[index].geometricProgressM),
                    requireNotNull(result.geometricProgressM),
                    1.5,
                )
            }
        }
    }

    @Test
    fun aJumpBeyondWalkingReachStillNeedsBranchConfirmationOnSparseAndDenseCurves() {
        val radius = 60.0
        fun arc(stepDeg: Double) = generateSequence(0.0) { it + stepDeg }.takeWhile { it <= 90.0 + 1e-9 }
            .map { degrees -> arcPoint(radius, degrees) }.toList()
        listOf(15.0, 0.5).forEach { step ->
            val matcher = navigatorMatcher()
            val route = arc(step)
            val first = matcher.match("arc", route, FilteredRoutePosition(arcPoint(radius, 5.0), 3.0, 1_000L))
            // 50 degrees of arc (about 52 m) in one second is beyond the pedestrian bound.
            val jumped = matcher.match(
                "arc", route, FilteredRoutePosition(arcPoint(radius, 55.0), 3.0, 2_000L), first.geometricProgressM,
            )

            assertEquals("step=$step", RouteMatchReason.BRANCH_SWITCH_PENDING, jumped.reason)
            assertEquals("step=$step", RouteMatchQuality.LOW, jumped.quality)
        }
    }

    @Test
    fun farApartPiecesOfADenseLoopStillCompete() {
        val loop = (0..720).map { step ->
            val angle = Math.toRadians(step * 0.5)
            point(50.0 * kotlin.math.sin(angle), 50.0 * (1.0 - kotlin.math.cos(angle)))
        }
        val result = RouteCorridorMatcher(RouteCorridorMatcherConfig(allowReverseTravel = true))
            .match("loop", loop, position(0.0))

        assertEquals(RouteMatchQuality.LOW, result.quality)
    }

    @Test
    fun outAndBackLegIsResolvedWhenTheReturnLegIsUnreachable() {
        listOf(outAndBack(), subdivide(outAndBack())).forEach { route ->
            val matcher = navigatorMatcher()
            var progress: Double? = null
            var time = 1_000L
            var east = -20.0
            // Walk 1.4 m/s from the unique approach onto the overlapped section.
            var result = matcher.match("oab", route, position(east, time = time), progress)
            repeat(12) {
                time += 2_000L
                east += 2.8
                if (result.quality != RouteMatchQuality.LOW) progress = result.geometricProgressM
                result = matcher.match("oab", route, position(east, time = time), progress)
            }

            assertTrue(east > 10.0)
            assertNotEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
            assertTrue(result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM)
            assertEquals(east + 30.0, requireNotNull(result.geometricProgressM), 1.0)
        }
    }

    @Test
    fun outAndBackStaysLowWhenBothLegsAreReachable() {
        listOf(outAndBack(), subdivide(outAndBack())).forEach { route ->
            val matcher = navigatorMatcher()
            val approach = matcher.match("oab", route, position(-20.0, time = 1_000L))
            assertEquals(RouteMatchQuality.HIGH, approach.quality)

            // Eighty seconds later both legs at east=10 (progress 40 and 220) are within walking
            // reach of the last accepted progress 10, so position cannot tell them apart.
            val later = matcher.match("oab", route, position(10.0, time = 81_000L), approach.geometricProgressM)

            assertEquals(RouteMatchQuality.LOW, later.quality)
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, later.reason)
        }
    }

    @Test
    fun outAndBackWithoutHistoryStaysLow() {
        listOf(outAndBack(), subdivide(outAndBack())).forEach { route ->
            val result = navigatorMatcher().match("oab", route, position(40.0))

            assertEquals(RouteMatchQuality.LOW, result.quality)
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
        }
    }

    @Test
    fun uTurnAcrossAShortCrossingNeedsBranchConfirmationForTheReturnLeg() {
        // S07-20 review: approach east 0..100 m, a 20 m crossing north, return west. The return leg
        // is two corridors away and reversed, so one displaced fix must not jump onto it.
        listOf(13.0, 14.0, 15.0).forEach { errorNorth ->
            val matcher = navigatorMatcher()
            var accepted: Double? = null
            var time = 1_000L
            var east = 60.0
            repeat(22) {
                val result = matcher.match("u", uTurnAcrossCrossing(), position(east, accuracy = 5.0, time = time), accepted)
                if (result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM) {
                    accepted = result.geometricProgressM
                }
                time += 1_000L
                east += 1.4
            }
            assertEquals(90.8, requireNotNull(accepted), 3.0)

            // The user is still on the approach sidewalk; one fix is displaced toward the return side.
            val jumped = matcher.match(
                "u", uTurnAcrossCrossing(), position(east, errorNorth, accuracy = 10.0, time = time), accepted,
            )

            val onReturnLeg = jumped.segmentIndex == 2
            assertFalse(
                "errorNorth=$errorNorth ${jumped.quality}/${jumped.reason} seg=${jumped.segmentIndex}",
                onReturnLeg && (jumped.quality == RouteMatchQuality.HIGH || jumped.quality == RouteMatchQuality.MEDIUM),
            )
            assertNotEquals("errorNorth=$errorNorth", RouteMatchQuality.HIGH, jumped.quality)
        }
    }

    @Test
    fun initialParallelReturnLegAtTwoSigmaRemainsAmbiguous() {
        val result = navigatorMatcher().match(
            "u", uTurnAcrossCrossing(), position(60.0, accuracy = 10.0, time = 1_000L),
        )

        assertEquals(RouteMatchQuality.LOW, result.quality)
    }

    @Test
    fun walkingTheCrossingAndTheReturnLegIsStillMatchedOnTheReturnLeg() {
        val matcher = navigatorMatcher()
        var accepted: Double? = null
        var time = 1_000L
        fun step(east: Double, north: Double): RouteCorridorMatchResult {
            val result = matcher.match("u", uTurnAcrossCrossing(), position(east, north, accuracy = 5.0, time = time), accepted)
            if (result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM) {
                accepted = result.geometricProgressM
            }
            time += 1_000L
            return result
        }
        var east = 60.0
        while (east < 100.0) { step(east, 0.0); east += 1.4 }
        var north = 0.0
        while (north < 20.0) { step(100.0, north); north += 1.4 }
        east = 100.0
        var last = step(east, 20.0)
        while (east > 40.0) { east -= 1.4; last = step(east, 20.0) }

        assertEquals(2, last.segmentIndex)
        assertTrue("${last.quality}/${last.reason}", last.quality == RouteMatchQuality.HIGH || last.quality == RouteMatchQuality.MEDIUM)
        assertEquals(100.0 + 20.0 + (100.0 - east), requireNotNull(last.geometricProgressM), 3.0)
    }

    private fun walk(route: List<RoutePoint>, points: List<RoutePoint>): List<RouteCorridorMatchResult> {
        val matcher = navigatorMatcher()
        var progress: Double? = null
        return points.mapIndexed { index, point ->
            val result = matcher.match("arc", route, FilteredRoutePosition(point, 3.0, 1_000L + index * 2_000L), progress)
            if (result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM) {
                progress = result.geometricProgressM
            }
            result
        }
    }

    // Same matcher configuration RouteNavigator uses (reverse travel allowed, 80 m progress sigma).
    private fun navigatorMatcher() =
        RouteCorridorMatcher(RouteCorridorMatcherConfig(progressSigmaM = 80.0, allowReverseTravel = true))

    // A unique 30 m approach, then 100 m east and the same 100 m back west.
    private fun outAndBack() = listOf(point(-30.0), point(100.0), point(0.0))

    // Approach east 0..100 m, a 20 m crossing north, then the return leg west (corridor diff 2).
    private fun uTurnAcrossCrossing() = listOf(point(0.0), point(100.0), point(100.0, 20.0), point(0.0, 20.0))

    private fun subdivide(route: List<RoutePoint>) = route.zipWithNext().flatMap { (a, b) ->
        (0 until 10).map { i ->
            RoutePoint(a.latitude + (b.latitude - a.latitude) * i / 10.0, a.longitude + (b.longitude - a.longitude) * i / 10.0)
        }
    } + route.last()

    private fun arcPoint(radius: Double, degrees: Double) = point(
        radius * kotlin.math.sin(Math.toRadians(degrees)),
        radius * (1 - kotlin.math.cos(Math.toRadians(degrees))),
    )

    private fun point(east: Double, north: Double = 0.0) =
        RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)

    private fun position(east: Double, north: Double = 0.0, accuracy: Double = 3.0, time: Long = 1_000L) =
        FilteredRoutePosition(point(east, north), accuracy, time)
}
