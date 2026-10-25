package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.distanceToPolylineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-52 (originals/09.txt:133): one local conversion convention (radius, wrapped longitude) shared
 * by the estimator, the session ENU frame, the route corridor matcher and the navigator projection,
 * with shared round-trip and boundary tests. The frame is a short-distance frame; poles are excluded.
 */
class LocalGeodesySharedContractTest {
    @Test
    fun roundTripIsExactForShortPedestrianDistances() {
        for (latitude in listOf(-60.0, 0.0, 37.5, 85.0)) {
            val frame = LocalGeodesy.Frame(latitude, 127.0)
            for ((east, north) in listOf(0.0 to 0.0, 10.0 to -3.0, -2_500.0 to 4_000.0)) {
                val coordinate = frame.toCoordinate(east, north)
                val back = frame.toLocal(coordinate.latitude, coordinate.longitude)
                assertEquals(east, back.eastM, 1e-6)
                assertEquals(north, back.northM, 1e-6)
            }
        }
        assertEquals(LocalGeodesy.EARTH_RADIUS_M * Math.PI / 180.0, LocalGeodesy.METERS_PER_DEGREE, 0.0)
    }

    @Test
    fun antimeridianIsAShortStepAndCoordinatesStayInRange() {
        val frame = LocalGeodesy.Frame(37.5, 179.99995)
        val crossed = frame.toLocal(37.5, -179.99995)
        val expectedEastM = 0.0001 * LocalGeodesy.metersPerLongitudeDegree(37.5)
        assertEquals(expectedEastM, crossed.eastM, 1e-6)
        val back = frame.toCoordinate(crossed.eastM, crossed.northM)
        assertEquals(-179.99995, back.longitude, 1e-9)
        assertEquals(180.0, LocalGeodesy.wrapLongitudeDegrees(540.0), 0.0)
        assertEquals(-179.0, LocalGeodesy.wrapLongitudeDegrees(181.0), 1e-12)
        assertEquals(179.0, LocalGeodesy.wrapLongitudeDegrees(-181.0), 1e-12)
        assertEquals(12.25, LocalGeodesy.wrapLongitudeDegrees(12.25), 0.0)

        // Route projections (navigator and corridor matcher) use the same wrap across the antimeridian.
        val acrossRoute = listOf(RoutePoint(37.5, 179.9999), RoutePoint(37.5, -179.9999))
        val onRoute = TrustedLocation(37.5, 180.0, 3f, 1_000L)
        assertTrue(distanceToPolylineMeters(onRoute, acrossRoute) < 0.01)
        val matched = RouteCorridorMatcher().match("r", acrossRoute, FilteredRoutePosition(RoutePoint(37.5, 180.0), 3.0, 1_000L))
        assertTrue(requireNotNull(matched.crossTrackDistanceM) < 0.01)
        assertTrue(requireNotNull(matched.geometricProgressM) in 8.0..9.0)
    }

    @Test
    fun polesAreOutsideTheLocalFrame() {
        assertThrows(IllegalArgumentException::class.java) { LocalGeodesy.Frame(90.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LocalGeodesy.Frame(-90.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LocalGeodesy.Frame(Double.NaN, 0.0) }
    }

    @Test
    fun estimatorAndSessionFrameAgreeAndRouteProjectionsAgreeWithEachOther() {
        val origin = PositionCoordinate(37.5, 127.0)
        val frame = LocalGeodesy.Frame(origin.latitude, origin.longitude)
        val tenNorth = frame.toCoordinate(0.0, 10.0)
        val twentyEast = frame.toCoordinate(20.0, 0.0)

        // Session ENU (PositioningCoordinator) uses the same frame.
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(GnssPositionObservation(origin.latitude, origin.longitude, 1.5, 1_000L))
        val second = requireNotNull(
            coordinator.observeGnss(GnssPositionObservation(twentyEast.latitude, twentyEast.longitude, 1.5, 2_000L)).raw?.localEnu,
        )
        assertEquals(20.0, second.eastM, 1e-9)
        assertEquals(0.0, second.northM, 1e-9)

        // The estimator's first estimate round-trips through the same frame to the fix itself.
        val estimate = requireNotNull(
            PedestrianPositionEstimator().observeGnss(GnssPositionObservation(tenNorth.latitude, tenNorth.longitude, 1.5, 1_000L)).estimate,
        )
        assertEquals(tenNorth.latitude, estimate.latitude, 1e-12)
        assertEquals(tenNorth.longitude, estimate.longitude, 1e-12)

        // Route matcher and navigator projections use the same physical metre convention as ENU.
        val route = listOf(RoutePoint(origin.latitude, origin.longitude), RoutePoint(twentyEast.latitude, twentyEast.longitude + 0.0002))
        val match = RouteCorridorMatcher().match(
            "r", route, FilteredRoutePosition(RoutePoint(tenNorth.latitude, tenNorth.longitude), 3.0, 1_000L),
        )
        val navigatorDistance = distanceToPolylineMeters(TrustedLocation(tenNorth.latitude, tenNorth.longitude, 3f, 1_000L), route)
        assertEquals(requireNotNull(match.crossTrackDistanceM), navigatorDistance, 1e-9)
        assertEquals(10.0, navigatorDistance, 1e-6)
    }

    @Test
    fun distantQueryCannotTurnAShortAntimeridianSegmentIntoAnOppositeHemisphereRoute() {
        for (route in listOf(
            listOf(RoutePoint(37.5, 179.99995), RoutePoint(37.5, -179.99995)),
            listOf(RoutePoint(37.5, -179.99995), RoutePoint(37.5, 179.99995)),
        )) {
            val query = RoutePoint(37.5, 0.0)
            val match = RouteCorridorMatcher().match("r", route, FilteredRoutePosition(query, 3.0, 1_000L))
            val navigatorDistance = distanceToPolylineMeters(TrustedLocation(query.latitude, query.longitude, 3f, 1_000L), route)

            // This is a projection-contract boundary, not a claim of global ENU distance accuracy.
            assertTrue(navigatorDistance > 10_000_000.0)
            assertEquals(requireNotNull(match.crossTrackDistanceM), navigatorDistance, 1e-6)
        }
    }

    @Test
    fun projectionOffsetsKeepTheirMetreMeaningAtDomesticAndAntimeridianLongitudes() {
        for (longitude in listOf(127.0, 179.99995, -179.99995)) {
            val frame = LocalGeodesy.Frame(37.5, longitude)
            val start = frame.toCoordinate(-20.0, 0.0)
            val end = frame.toCoordinate(20.0, 0.0)
            val route = listOf(RoutePoint(start.latitude, start.longitude), RoutePoint(end.latitude, end.longitude))
            for (north in listOf(-10.0, 0.0, 10.0)) {
                val coordinate = frame.toCoordinate(0.0, north)
                val point = RoutePoint(coordinate.latitude, coordinate.longitude)
                val match = RouteCorridorMatcher().match("r", route, FilteredRoutePosition(point, 3.0, 1_000L))
                val navigatorDistance = distanceToPolylineMeters(
                    TrustedLocation(point.latitude, point.longitude, 3f, 1_000L), route,
                )
                assertEquals(kotlin.math.abs(north), requireNotNull(match.crossTrackDistanceM), 1e-6)
                assertEquals(kotlin.math.abs(north), navigatorDistance, 1e-6)
                assertEquals(requireNotNull(match.crossTrackDistanceM), navigatorDistance, 1e-6)
            }
        }
    }
}
