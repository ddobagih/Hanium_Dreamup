package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendWalkingRouteClientTest {
    @Test
    fun postsToBackendWalkingProxyWithoutTmapKey() {
        var postedUrl: String? = null
        var postedBody: String? = null
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String): String {
                    postedUrl = url
                    postedBody = body
                    return responseJson
                }

                override fun get(url: String): String {
                    error("GET should not be called by fetchRoute")
                }
            },
        )

        val route = client.fetchRoute(
            baseUrl = "https://backend.example",
            request = WalkingRouteRequest(
                origin = RoutePoint(37.0, 127.0),
                destination = RoutePoint(37.1, 127.1, "목적지"),
            ),
        )

        assertEquals("https://backend.example/navigation/walking", postedUrl)
        assertEquals(true, postedBody!!.contains("\"priority\":\"STAIR_AVOID\""))
        assertEquals("STAIR_AVOID", route.priority)
        assertEquals(2, route.polyline.size)
        val guide = route.guidePoints.single()
        assertEquals("우회전", guide.instruction)
        assertEquals(92.5f, guide.bearingDeg!!)
        assertEquals("turn_right", guide.turnType)
        assertEquals("guide", guide.pointType)
        assertEquals("crosswalk", guide.facilityType)
    }

    private val responseJson = """
        {
          "schema_version": "walksafe.walking_route.v1",
          "provider": "tmap_pedestrian",
          "priority": "STAIR_AVOID",
          "summary": {"distance_m": 120, "duration_s": 90},
          "polyline": [
            {"latitude": 37.0, "longitude": 127.0},
            {"latitude": 37.1, "longitude": 127.1}
          ],
          "steps": [],
          "guide_points": [
            {
              "index": 0,
              "point": {"latitude": 37.05, "longitude": 127.05},
              "instruction": "우회전",
              "distance_from_start_m": 60,
              "remaining_distance_m": 60,
              "bearing_deg": 92.5,
              "turn_type": "turn_right",
              "point_type": "guide",
              "facility_type": "crosswalk"
            }
          ],
          "provider_result_code": 0,
          "provider_result_message": "OK"
        }
    """.trimIndent()
}
