package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 07.txt #4: a guide's declared-vs-projected distance error is bounded by guide/rounding noise,
 * not by 10% of the whole route, and the distance used for turn timing has its own tighter bound.
 */
class GuideDistanceToleranceRegressionTest {
    private val request = WalkingRouteRequest(RoutePoint(37.0, 127.0), RoutePoint(37.018, 127.0))

    @Test
    fun longRouteNoLongerAcceptsAGuideErrorProportionalToTheWholeRoute() {
        // 2 km straight route; the guide lies at ~1000 m of shape progress.
        listOf(false, true).forEach { normalized ->
            val rejected = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(longRoute(declaredFromStartM = 1045, normalized = normalized), request)
            }
            assertTrue(rejected.message, rejected.message.orEmpty().endsWith("route_guide_order_invalid"))
            assertEquals(
                1025,
                parseWalkingRoute(longRoute(declaredFromStartM = 1025, normalized = normalized), request)
                    .guidePoints.single().distanceFromStartM,
            )
        }
    }

    @Test
    fun turnTimingUsesTheStoredShapeWhenTheDeclaredDistanceExceedsTheTurnBound() {
        val north = 1.0 / 111_320.0
        val route = WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
            guidePoints = listOf(
                // The guide point is 100 m along the shape, but declares 125 m (inside the 30 m
                // response bound, outside the 10 m turn-distance bound).
                WalkingRouteGuidePoint(0, RoutePoint(37.0 + 100 * north, 127.0), "좌회전", 125, 75, turnType = 12),
            ),
        )
        val facingNorth = RouteFacingObservation(0.0, 5.0, 1_000L)
        val navigator = RouteNavigator().apply { setRoute(route) }
        val update = navigator.update(
            TrustedLocation(37.0 + 50 * north, 127.0, 3f, 1_000L), 1_000L, false, null, facingNorth,
        )
        val instruction = requireNotNull(update.instruction)
        assertTrue(instruction, instruction.contains("다음 안내 지점까지 경로를 따라 약 50미터"))

        // A declared distance inside the turn bound is kept as the provider rounding value.
        val close = route.copy(guidePoints = listOf(route.guidePoints.single().copy(distanceFromStartM = 106, remainingDistanceM = 94)))
        val closeUpdate = RouteNavigator().apply { setRoute(close) }
            .update(TrustedLocation(37.0 + 50 * north, 127.0, 3f, 1_000L), 1_000L, false, null, facingNorth)
        assertTrue(closeUpdate.instruction, requireNotNull(closeUpdate.instruction).contains("다음 안내 지점까지 경로를 따라 약 56미터"))
    }

    private fun longRoute(declaredFromStartM: Int, normalized: Boolean): JSONObject = JSONObject().apply {
        put("schema_version", "walksafe.walking_route.v1")
        put("provider", "tmap_pedestrian")
        put("priority", "STAIR_AVOID")
        put("summary", JSONObject().put("distance_m", 2000).put("duration_s", 1500))
        val polyline = JSONArray().put(point(37.0)).put(point(37.018))
        put("polyline", polyline)
        put("steps", if (normalized) {
            JSONArray().put(JSONObject().put("index", 0).put("distance_m", 2000).put("duration_s", 1500).put("points", polyline))
        } else JSONArray())
        if (normalized) put("distance_source", WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED.wireValue)
        put("guide_points", JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put("point", point(37.009))
                .put("instruction", "우회전")
                .put("distance_from_start_m", declaredFromStartM)
                .put("remaining_distance_m", 2000 - declaredFromStartM)
                .put("turn_type", 13),
        ))
        put("provider_result_code", 0)
        put("provider_result_message", "OK")
    }

    private fun point(latitude: Double) = JSONObject().put("latitude", latitude).put("longitude", 127.0)
}
