package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCampusWalkingRouteShapeRegressionTest {
    @Test
    fun publicResponseShapeParsesPolylineStepsAndGuides() {
        // Synthetic geometry preserves the observed public response's 23/3/3
        // structure and 531m/424s summary, not its coordinates or instructions.
        val route = parseWalkingRoute(payload(), request())

        assertEquals(531, route.summary.distanceM)
        assertEquals(424, route.summary.durationS)
        assertEquals(23, route.polyline.size)
        assertEquals(listOf(0, 1, 2), route.steps.map { it.index })
        assertEquals(listOf(8, 9, 8), route.steps.map { it.points.size })
        assertEquals(listOf(0, 1, 2), route.guidePoints.map { it.index })
        assertEquals(listOf(0, 169, 531), route.guidePoints.map { it.distanceFromStartM })
        assertEquals(listOf(531, 362, 0), route.guidePoints.map { it.remainingDistanceM })
        assertEquals(request().origin, route.polyline.first())
        assertEquals(request().destination, route.polyline.last())
        assertEquals("STAIR_AVOID", route.priority)
    }

    @Test
    fun requestEndpointMismatchIsStillRejected() {
        val wrongOrigin = request().copy(origin = point(0).copy(longitude = 128.01))

        assertInvalidResponse("route_endpoint_mismatch") {
            parseWalkingRoute(payload(), wrongOrigin)
        }
    }

    @Test
    fun guideOutsideReturnedGeometryIsStillRejected() {
        val response = payload()
        response.getJSONArray("guide_points").getJSONObject(1)
            .getJSONObject("point").put("longitude", 128.01)

        assertInvalidResponse("route_guide_geometry_invalid") {
            parseWalkingRoute(response, request())
        }
    }

    private fun assertInvalidResponse(reason: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue(error is GatewayResponseProtocolException)
        assertEquals("invalid gateway response: $reason", error?.message)
        assertEquals(
            NavigationBackendErrorKind.INVALID_RESPONSE,
            classifyNavigationBackendFailure(requireNotNull(error)).kind,
        )
    }

    private fun request() = WalkingRouteRequest(origin = point(0), destination = point(22))

    private fun point(index: Int) = RoutePoint(latitude = 36.0 + index * 0.000217, longitude = 128.0)

    private fun pointJson(index: Int): JSONObject = JSONObject()
        .put("latitude", point(index).latitude)
        .put("longitude", point(index).longitude)

    private fun points(first: Int, last: Int): JSONArray = JSONArray().apply {
        for (index in first..last) put(pointJson(index))
    }

    private fun step(index: Int, first: Int, last: Int, distance: Int, duration: Int): JSONObject =
        JSONObject()
            .put("index", index)
            .put("distance_m", distance)
            .put("duration_s", duration)
            .put("points", points(first, last))
            .put("facility_type", JSONObject.NULL)
            .put("turn_type", JSONObject.NULL)

    private fun guide(index: Int, pointIndex: Int, distance: Int, remaining: Int): JSONObject =
        JSONObject()
            .put("index", index)
            .put("point", pointJson(pointIndex))
            .put("instruction", "Synthetic guide fixture")
            .put("distance_from_start_m", distance)
            .put("remaining_distance_m", remaining)
            .put("bearing_deg", 0)
            .put("facility_type", JSONObject.NULL)
            .put("turn_type", JSONObject.NULL)

    private fun payload(): JSONObject = JSONObject()
        .put("schema_version", "walksafe.walking_route.v1")
        .put("provider", "tmap_pedestrian")
        .put("provider_result_code", 0)
        .put("priority", "STAIR_AVOID")
        .put("provider_route_id", JSONObject.NULL)
        .put("summary", JSONObject().put("distance_m", 531).put("duration_s", 424))
        .put("polyline", points(0, 22))
        .put(
            "steps",
            JSONArray()
                .put(step(0, 0, 7, 169, 135))
                .put(step(1, 7, 15, 193, 154))
                .put(step(2, 15, 22, 169, 135)),
        )
        .put(
            "guide_points",
            JSONArray()
                .put(guide(0, 0, 0, 531))
                .put(guide(1, 7, 169, 362))
                .put(guide(2, 22, 531, 0)),
        )
}
