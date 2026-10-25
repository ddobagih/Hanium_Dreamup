package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteCorridorMatcherGeometryCacheTest {
    @Test
    fun nearbySearchKeepsOriginalSegmentTieOrderAtAnEarlierVertex() {
        val route = List(1_001) { point(it.toDouble()) }
        val matcher = matcher()
        matcher.match("long", route, position(450.0, time = 1_000L))
        val fix = position(440.0, time = 2_000L)
        val result = matcher.match("long", route, fix)

        assertEquals(matcher().match("long", route, fix).segmentIndex, result.segmentIndex)
        assertEquals(440.0, requireNotNull(result.geometricProgressM), 0.001)
    }

    @Test
    fun corridorExitThenDistantReentryUsesGlobalSearch() {
        val route = List(1_001) { point(it.toDouble()) }
        val matcher = matcher()
        matcher.match("long", route, position(10.0, time = 1_000L))
        val outside = matcher.match("long", route, position(20.0, 100.0, 2_000L))
        val reentry = matcher.match("long", route, position(800.0, time = 3_000L))

        assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, outside.reason)
        assertEquals(RouteMatchQuality.HIGH, reentry.quality)
        assertEquals(800.0, requireNotNull(reentry.geometricProgressM), 0.001)
        assertEquals(matcher().match("long", route, position(800.0, time = 3_000L)).segmentIndex, reentry.segmentIndex)
    }

    @Test
    fun localPreferenceStillFindsAnEqualParallelReturnBranch() {
        val route = List(101) { point(it.toDouble()) } +
            listOf(point(100.0, 50.0), point(0.0, 50.0), point(0.0, 5.0)) +
            (1..100).map { point(it.toDouble(), 5.0) }
        val matcher = matcher()
        matcher.match("parallel", route, position(20.0, -3.0, 1_000L))
        val result = matcher.match("parallel", route, position(30.0, 2.5, 2_000L))

        assertEquals(RouteMatchQuality.LOW, result.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
    }

    @Test
    fun sameIdLengthAndCoordinatesReplacementInvalidatesCacheAndTimestampWatermark() {
        val route = mutableListOf(point(0.0), point(100.0))
        val matcher = matcher()
        matcher.match("same", route, position(20.0, time = 5_000L))
        route.clear()
        route.addAll(listOf(point(0.0, 500.0), point(100.0, 500.0), point(200.0, 500.0)))
        val fix = position(150.0, 500.0, 1_000L)

        assertEquals(matcher().match("same", route, fix), matcher.match("same", route, fix))
    }

    @Test
    fun equalCoordinateCopyPreservesTimestampWatermark() {
        val route = listOf(point(0.0), point(100.0))
        val matcher = matcher()
        matcher.match("same", route, position(20.0, time = 5_000L))

        assertEquals(RouteMatchReason.STALE_SAMPLE,
            matcher.match("same", route.map { it.copy(name = "new name") }, position(30.0, time = 4_000L)).reason)
    }

    private fun matcher() = RouteCorridorMatcher(RouteCorridorMatcherConfig(
        progressWeight = 0.0, allowReverseTravel = true, maximumTravelSpeedMps = 0.0,
    ))

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(
        north / 111_194.92664455873, 127.0 + east / 111_194.92664455873,
    )

    private fun position(east: Double, north: Double = 0.0, time: Long) =
        FilteredRoutePosition(point(east, north), 3.0, time)
}
