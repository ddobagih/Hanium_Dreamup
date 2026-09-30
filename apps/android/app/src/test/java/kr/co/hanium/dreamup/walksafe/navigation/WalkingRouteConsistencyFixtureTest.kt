package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Shared normalized-response cases also exercised by the backend contract tests. */
class WalkingRouteConsistencyFixtureTest {
    @Test
    fun androidAcceptsAndRejectsTheSameNormalizedRouteCasesAsTheBackend() {
        val fixture = sequenceOf(
            File("../../../tests/fixtures/walking_route_consistency.json"),
            File("tests/fixtures/walking_route_consistency.json"),
        ).first { it.isFile }
        val cases = JSONObject(fixture.readText()).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val route = case.getJSONObject("route")
            val points = route.getJSONArray("polyline")
            val origin = points.getJSONObject(0)
            val destination = points.getJSONObject(points.length() - 1)
            val request = WalkingRouteRequest(
                origin = RoutePoint(origin.getDouble("latitude"), origin.getDouble("longitude")),
                destination = RoutePoint(destination.getDouble("latitude"), destination.getDouble("longitude")),
            )
            if (case.getBoolean("valid")) {
                assertEquals(name, "STAIR_AVOID", parseWalkingRoute(route, request).priority)
            } else {
                val error = assertThrows(name, GatewayResponseProtocolException::class.java) {
                    parseWalkingRoute(route, request)
                }
                val expectedStepReason = mapOf(
                    "step_index_gap" to "route_step_order_invalid",
                    "step_disconnected" to "route_step_disconnected",
                    "step_shape_mismatch" to "route_step_geometry_mismatch",
                    "step_total_mismatch" to "route_step_distance_total_invalid",
                    "legacy_loop_ambiguous_progress" to "route_guide_geometry_invalid",
                    "legacy_short_loop_ambiguous_progress" to "route_guide_geometry_invalid",
                    "legacy_backtrack_midpoint_ambiguous_progress" to "route_guide_geometry_invalid",
                    "normalized_u_turn_wrong_visit" to "route_guide_order_invalid",
                )[name]
                if (expectedStepReason != null) {
                    assertEquals(name, "invalid gateway response: $expectedStepReason", error.message)
                }
            }
        }
    }
}
