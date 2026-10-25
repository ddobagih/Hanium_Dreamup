package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DirectionalCrosswalkRouteContractTest {
    @Test
    fun eachDirectionalCodeDecodesAndReachesActualGuidanceWithoutCrossingPermission() {
        directions.forEach { (code, direction) ->
            val route = decode(payload(code))
            assertEquals(code, route.guidePoints[1].turnType)
            val navigator = RouteNavigator().apply { setRoute(route) }
            val update = navigator.update(fix(20.0, 1_000), 1_000, false,
                facingObservation = RouteFacingObservation(0.0, 2.0, 1_000))

            assertEquals(1, update.guideIndex)
            assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty()
                .contains("다음 횡단보도는 ${direction} 방향입니다."))
            assertReferenceOnly(update.instruction.orEmpty())
            assertTrue(navigator.reserveInstruction(update))
            assertTrue(navigator.isInstructionCurrent(update, 1_100,
                RouteFacingObservation(0.0, 2.0, 1_100)))
        }
    }

    @Test
    fun unknownFacingWithholdsLiveGuidanceWhileTheOverviewRemainsRouteRelative() {
        directions.forEach { (code, direction) ->
            val route = decode(payload(code))
            val navigator = RouteNavigator().apply { setRoute(route) }
            val update = navigator.update(fix(20.0, 1_000), 1_000, false, facingObservation = null)
            assertNull(update.instruction)
            assertNull(update.speechCueToken)
            assertFalse(navigator.reserveInstruction(update))
            val text = requireNotNull(route.guidePoints[1].maneuverOverview(40))
            assertTrue(text, text.contains("경로 기준으로 다음 횡단보도는 ${direction} 방향입니다."))
            assertReferenceOnly(text)
        }
    }

    @Test
    fun codeOnlyDiagonalCrosswalksNeedNewEvidenceAfterThePrecedingTurn() {
        for (code in 214..217) {
            fun north(meters: Double) = RoutePoint(meters / 111_194.92664455874, 0.0)
            val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
                listOf(north(0.0), north(200.0)), listOf(
                    WalkingRouteGuidePoint(0, north(20.0), null, 20, 180, turnType = 13),
                    WalkingRouteGuidePoint(1, north(30.0), null, 30, 170, turnType = code),
                ))
            val navigator = RouteNavigator().apply { setRoute(route) }
            assertEquals(0, navigator.update(fix(0.0, 1_000), 1_000, false,
                facingObservation = RouteFacingObservation(0.0, 2.0, 1_000)).guideIndex)
            val jump = navigator.update(fix(40.0, 11_000), 11_000, false,
                facingObservation = RouteFacingObservation(0.0, 2.0, 11_000))
            assertEquals(1, jump.guideIndex)
            assertFalse(jump.arrivalCandidate)
        }
    }

    @Test
    fun normalizedCapturedProviderFixtureDecodesAndAnnouncesTheRightCrosswalk() {
        val source = requireNotNull(javaClass.getResourceAsStream("/navigation/directional-crosswalk-213.json"))
            .bufferedReader().use { it.readText() }
        val route = decode(JSONObject(source))
        assertEquals(213, route.guidePoints[1].turnType)
        assertEquals(15, route.guidePoints[1].facilityType)
        assertEquals(53, route.guidePoints[1].distanceFromStartM)
        assertEquals(7, route.polyline.size)
        for (heading in listOf(null, RouteFacingObservation(0.0, 2.0, 1_000))) {
            val navigator = RouteNavigator().apply { setRoute(route) }
            val point = route.polyline[1]
            val initial = navigator.update(TrustedLocation(point.latitude, point.longitude, 2f, 1_000),
                1_000, false, facingObservation = heading)
            val update = if (heading == null) {
                assertNull(initial.instruction)
                assertNull(initial.speechCueToken)
                assertFalse(navigator.reserveInstruction(initial))
                requireNotNull(navigator.currentGuidance(1_100, RouteFacingObservation(0.0, 2.0, 1_100)))
            } else initial
            assertEquals(1, update.guideIndex)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000))
            assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty()
                .contains("다음 횡단보도는 3시 방향입니다."))
            assertReferenceOnly(update.instruction.orEmpty())
            assertTrue(navigator.reserveInstruction(update))
        }
    }

    @Test
    fun plainCrosswalkMissingCodeAndUnknownCodeCannotCertifyTheCorner() {
        listOf(null, 11, 211, 218, 999).forEach { code ->
            val error = assertThrows(GatewayResponseProtocolException::class.java) { decode(payload(code)) }
            assertEquals("invalid gateway response: route_guidance_incomplete", error.message)
        }
    }

    @Test
    fun aDirectionalCrosswalkAtAnotherPointCannotHideAMissingNinetyDegreeCorner() {
        val json = payload(213)
        val unrelated = point(0.0, 35.0)
        json.getJSONArray("guide_points").getJSONObject(1)
            .put("point", unrelated).put("distance_from_start_m", 35).put("remaining_distance_m", 105)
        val error = assertThrows(GatewayResponseProtocolException::class.java) { decode(json) }
        assertEquals("invalid gateway response: route_guidance_incomplete", error.message)
    }

    private fun assertReferenceOnly(text: String) {
        assertTrue(text, text.contains(CROSSWALK_REFERENCE_NOTICE_KO))
        listOf("건너세요", "꺾으세요", "이동하세요", "직진하세요", "신호가", "초록", "원문").forEach {
            assertFalse(text, text.contains(it))
        }
    }

    private fun decode(json: JSONObject): WalkingRoute {
        val points = json.getJSONArray("polyline")
        fun routePoint(index: Int) = points.getJSONObject(index).let {
            RoutePoint(it.getDouble("latitude"), it.getDouble("longitude"))
        }
        return parseWalkingRoute(json, WalkingRouteRequest(routePoint(0), routePoint(points.length() - 1)))
    }

    private fun payload(code: Int?): JSONObject {
        val bearing = when (code) {
            212 -> -90.0
            214 -> -120.0
            215 -> -65.0
            216 -> 65.0
            217 -> 120.0
            else -> 90.0
        }
        val angle = Math.toRadians(bearing)
        val start = point(0.0, 0.0)
        val corner = point(0.0, 60.0)
        val end = point(80.0 * sin(angle), 60.0 + 80.0 * cos(angle))
        val crossing = JSONObject().put("index", 1).put("point", corner)
            .put("distance_from_start_m", 60).put("remaining_distance_m", 80)
            .put("instruction", "원문 신호가 초록이면 건너세요")
        if (code != null) crossing.put("turn_type", code)
        return JSONObject().put("schema_version", "walksafe.walking_route.v1")
            .put("provider", "tmap_pedestrian").put("priority", "STAIR_AVOID")
            .put("provider_result_code", 0)
            .put("summary", JSONObject().put("distance_m", 140).put("duration_s", 120))
            .put("polyline", JSONArray().put(start).put(corner).put(end))
            .put("steps", JSONArray()
                .put(JSONObject().put("index", 0).put("distance_m", 60).put("duration_s", 50)
                    .put("points", JSONArray().put(start).put(corner)))
                .put(JSONObject().put("index", 1).put("distance_m", 80).put("duration_s", 70)
                    .put("points", JSONArray().put(corner).put(end))))
            .put("guide_points", JSONArray()
                .put(JSONObject().put("index", 0).put("point", start).put("turn_type", 200)
                    .put("point_type", "SP").put("distance_from_start_m", 0).put("remaining_distance_m", 140))
                .put(crossing))
    }

    private fun point(east: Double, north: Double) = JSONObject()
        .put("latitude", north / 111_194.92664455874).put("longitude", east / 111_194.92664455874)

    private fun fix(north: Double, at: Long) = TrustedLocation(north / 111_194.92664455874, 0.0, 2f, at)

    private val directions = mapOf(212 to "9시", 213 to "3시", 214 to "8시", 215 to "10시", 216 to "2시", 217 to "4시")
}
