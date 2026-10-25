package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** S09-52: the public parser must use the short east/west path across the antimeridian. */
class BackendWalkingRouteCoordinateContractRegressionTest {
    @Test
    fun acceptsEastboundAntimeridianRouteWithPositive180MidpointGuide() {
        assertAcceptedMidpointGuide(179.999, -179.999, 180.0)
    }

    @Test
    fun acceptsEastboundAntimeridianRouteWithNegative180MidpointGuide() {
        assertAcceptedMidpointGuide(179.999, -179.999, -180.0)
    }

    @Test
    fun acceptsWestboundAntimeridianRouteWithPositive180MidpointGuide() {
        assertAcceptedMidpointGuide(-179.999, 179.999, 180.0)
    }

    @Test
    fun acceptsWestboundAntimeridianRouteWithNegative180MidpointGuide() {
        assertAcceptedMidpointGuide(-179.999, 179.999, -180.0)
    }

    @Test
    fun acceptsSameEastboundMidpointContractAtDomesticLongitude() {
        assertAcceptedMidpointGuide(127.0, 127.002, 127.001)
    }

    @Test
    fun acceptsSameWestboundMidpointContractAtDomesticLongitude() {
        assertAcceptedMidpointGuide(127.002, 127.0, 127.001)
    }

    private fun assertAcceptedMidpointGuide(
        originLongitude: Double,
        destinationLongitude: Double,
        guideLongitude: Double,
    ) {
        val request = WalkingRouteRequest(
            origin = RoutePoint(LATITUDE, originLongitude),
            destination = RoutePoint(LATITUDE, destinationLongitude),
        )
        val route = parseWalkingRoute(
            response(originLongitude, destinationLongitude, guideLongitude),
            request,
        )

        assertEquals(listOf(request.origin, request.destination), route.polyline)
        assertEquals(DISTANCE_M, route.summary.distanceM)
        assertEquals(DISTANCE_M, route.steps.single().distanceM)
        assertEquals(WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED, route.distanceSource)
        val guide = route.guidePoints.single()
        assertEquals(RoutePoint(LATITUDE, guideLongitude), guide.point)
        assertEquals(HALF_DISTANCE_M, guide.distanceFromStartM)
        assertEquals(HALF_DISTANCE_M, guide.remainingDistanceM)
    }

    private fun response(
        originLongitude: Double,
        destinationLongitude: Double,
        guideLongitude: Double,
    ): JSONObject {
        val line = JSONArray()
            .put(point(originLongitude))
            .put(point(destinationLongitude))
        return JSONObject()
            .put("schema_version", "walksafe.walking_route.v1")
            .put("provider", "tmap_pedestrian")
            .put("provider_result_code", 0)
            .put("priority", "STAIR_AVOID")
            .put("distance_source", WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED.wireValue)
            .put("summary", JSONObject().put("distance_m", DISTANCE_M).put("duration_s", 150))
            .put("polyline", line)
            .put("steps", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("distance_m", DISTANCE_M)
                .put("duration_s", 150)
                .put("points", line)))
            .put("guide_points", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("point", point(guideLongitude))
                .put("instruction", "중간 안내 지점")
                .put("distance_from_start_m", HALF_DISTANCE_M)
                .put("remaining_distance_m", HALF_DISTANCE_M)
                .put("turn_type", 11)
                .put("point_type", "guide")))
    }

    private fun point(longitude: Double): JSONObject = JSONObject()
        .put("latitude", LATITUDE)
        .put("longitude", longitude)

    private companion object {
        const val LATITUDE = 37.0
        // A 0.002-degree east/west segment at 37 degrees is about 177.6 m.
        // Integer distances remain identical for the domestic and antimeridian fixtures.
        const val DISTANCE_M = 178
        const val HALF_DISTANCE_M = 89
    }
}
