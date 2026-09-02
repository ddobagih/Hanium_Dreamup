package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class BackendWalkingRouteClientTest {
    @Test
    fun postsToBackendWalkingProxyWithoutTmapKey() {
        var postedUrl: String? = null
        var postedBody: String? = null
        var postedSession: GatewayFieldSession? = null
        val session = session("route-tester")
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String {
                    postedUrl = url
                    postedBody = body
                    postedSession = session
                    return responseJson
                }

                override fun get(url: String, session: GatewayFieldSession): String {
                    error("GET should not be called by fetchRoute")
                }
            },
        )

        val route = client.fetchRoute(
            session = session,
            request = routeRequest,
        )

        assertEquals("https://field.example/api/navigation/walking", postedUrl)
        assertEquals(true, postedBody!!.contains("\"priority\":\"STAIR_AVOID\""))
        assertEquals(session, postedSession)
        assertEquals("STAIR_AVOID", route.priority)
        assertEquals(2, route.polyline.size)
        val guide = route.guidePoints.single()
        assertEquals("우회전", guide.instruction)
        assertEquals(92.5f, guide.bearingDeg!!)
        assertEquals(11, guide.turnType)
        assertEquals("guide", guide.pointType)
        assertEquals(15, guide.facilityType)
    }

    @Test
    fun destinationSearchForwardsTheSameNamedActorAuth() {
        var requestedUrl: String? = null
        var requestedSession: GatewayFieldSession? = null
        val session = session("search-tester")
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String {
                    error("POST should not be called by destination search")
                }

                override fun get(url: String, session: GatewayFieldSession): String {
                    requestedUrl = url
                    requestedSession = session
                    return """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"서울역","results":[]}"""
                }
            },
        )

        client.searchDestinations(
            session = session,
            query = "서울역",
            limit = 5,
            origin = RoutePoint(37.0, 127.0),
        )

        assertEquals(session, requestedSession)
        assertEquals(true, requestedUrl!!.startsWith("https://field.example/api/navigation/destinations/search?"))
        assertEquals(true, requestedUrl!!.contains("origin_lat=37.0"))
    }

    @Test
    fun destinationSearchUsesTheBackendUnicodeCodePointLimit() {
        val acceptedQuery = "😀".repeat(80)
        var getCalled = false
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String =
                    error("POST should not be called by destination search")

                override fun get(url: String, session: GatewayFieldSession): String {
                    getCalled = true
                    return JSONObject()
                        .put("schema_version", "walksafe.destination_search.v1")
                        .put("provider", "tmap_poi")
                        .put("query", acceptedQuery)
                        .put("results", JSONArray())
                        .toString()
                }
            },
        )

        assertEquals(
            acceptedQuery,
            client.searchDestinations(
                session = session("search-tester"),
                query = acceptedQuery,
                limit = 5,
                origin = null,
            ).query,
        )
        assertEquals(acceptedQuery, canonicalDestinationSearchQueryOrNull(acceptedQuery))
        assertNull(canonicalDestinationSearchQueryOrNull("😀".repeat(81)))
        getCalled = false
        assertThrows(IllegalArgumentException::class.java) {
            client.searchDestinations(
                session = session("search-tester"),
                query = "😀".repeat(81),
                limit = 5,
                origin = null,
            )
        }
        assertFalse(getCalled)
    }

    @Test
    fun walkingRouteRejectsInvalidOrUnusableGeometry() {
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(
                JSONObject(responseJson.replace("\"latitude\": 37.001", "\"latitude\": 91.0")),
                routeRequest,
            )
        }
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(
                JSONObject(responseJson.replace("\"latitude\": 37.0005", "\"latitude\": -91.0")),
                routeRequest,
            )
        }
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(
                JSONObject("""{"priority":"STAIR_AVOID","summary":{"distance_m":1,"duration_s":1},"polyline":[]}"""),
                routeRequest,
            )
        }
    }

    @Test
    fun walkingRouteBindsGeometryAndGuidesToTheRequest() {
        val wrongDestination = JSONObject(responseJson).apply {
            getJSONArray("polyline").getJSONObject(1).put("latitude", 37.01)
        }
        val zeroSummary = JSONObject(responseJson).apply {
            getJSONObject("summary").put("distance_m", 0)
        }
        val geometrySummaryMismatch = JSONObject(responseJson).apply {
            getJSONObject("summary").put("distance_m", 5_000)
        }
        val wrongGuideIndex = JSONObject(responseJson).apply {
            getJSONArray("guide_points").getJSONObject(0).put("index", 1)
        }
        val reversedGuides = JSONObject(responseJson).apply {
            put(
                "guide_points",
                JSONArray(
                    """
                    [
                      {
                        "index":0,
                        "point":{"latitude":37.0008,"longitude":127.0},
                        "distance_from_start_m":96,
                        "remaining_distance_m":24
                      },
                      {
                        "index":1,
                        "point":{"latitude":37.0002,"longitude":127.0},
                        "distance_from_start_m":24,
                        "remaining_distance_m":96
                      }
                    ]
                    """.trimIndent(),
                ),
            )
        }

        listOf(wrongDestination, zeroSummary, geometrySummaryMismatch, wrongGuideIndex, reversedGuides).forEach { payload ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, routeRequest)
            }
        }
    }

    @Test
    fun destinationSearchRejectsOneMalformedEntryInsteadOfPublishingPartialResults() {
        val payload = validDestinationPayload().apply {
            getJSONArray("results").put(
                JSONObject()
                    .put("id", "missing-point")
                    .put("name", "좌표없음")
                    .put("result_type", "poi"),
            )
        }

        assertThrows(GatewayResponseProtocolException::class.java) {
            parseDestinationSearchResponse(payload, query = "서울역", limit = 5)
        }
    }

    @Test
    fun destinationSearchBindsNormalizedQueryLimitIdsAndResultTypes() {
        val parsed = parseDestinationSearchResponse(
            validDestinationPayload(query = "서울 역"),
            query = "  서울   역 ",
            limit = 1,
        )
        assertEquals(listOf("poi-1"), parsed.results.map { it.id })

        val wrongQuery = validDestinationPayload(query = "서울역")
        val duplicateIds = validDestinationPayload(query = "서울 역").apply {
            getJSONArray("results").put(JSONObject(getJSONArray("results").getJSONObject(0).toString()))
        }
        val unsupportedType = validDestinationPayload(query = "서울 역").apply {
            getJSONArray("results").getJSONObject(0).put("result_type", "road")
        }
        val overLimit = validDestinationPayload(query = "서울 역").apply {
            getJSONArray("results").put(
                JSONObject(getJSONArray("results").getJSONObject(0).toString())
                    .put("id", "poi-2"),
            )
        }

        listOf(
            wrongQuery to 5,
            duplicateIds to 5,
            unsupportedType to 5,
            overLimit to 1,
        ).forEach { (payload, limit) ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseDestinationSearchResponse(payload, query = "서울 역", limit = limit)
            }
        }
    }

    @Test
    fun walkingRouteRejectsAnythingOutsideTheTmapSuccessContract() {
        listOf(
            responseJson.replace("walksafe.walking_route.v1", "walksafe.walking_route.v2"),
            responseJson.replace("tmap_pedestrian", "kakao_walking"),
            responseJson.replace("\"provider_result_code\": 0", "\"provider_result_code\": 1"),
            responseJson.replace("\"provider_result_code\": 0,", ""),
            responseJson.replace("\"priority\": \"STAIR_AVOID\"", "\"priority\": \"RECOMMEND\""),
        ).forEach { payload ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(JSONObject(payload), routeRequest)
            }
        }
    }

    @Test
    fun classifiesBackendFailuresWithoutExposingProviderMessages() {
        val cases = mapOf(
            GatewayProxyHttpException(503, "walking_route_failed", "tmap_app_key_missing") to
                NavigationBackendErrorKind.PROVIDER_CONFIGURATION,
            GatewayProxyHttpException(429, "walking_route_failed", "tmap_provider_error") to
                NavigationBackendErrorKind.RATE_LIMITED,
            GatewayProxyHttpException(504, "walking_route_failed", "tmap_timeout") to
                NavigationBackendErrorKind.TIMEOUT,
            GatewayProxyHttpException(502, "walking_route_failed", "invalid_tmap_response") to
                NavigationBackendErrorKind.INVALID_RESPONSE,
            GatewayProxyHttpException(502, "walking_route_failed", "gateway_upstream_auth_failed") to
                NavigationBackendErrorKind.AUTHENTICATION,
        )

        cases.forEach { (error, expected) ->
            assertEquals(expected, classifyNavigationBackendFailure(error).kind)
        }
    }

    @Test
    fun twoConsecutiveTmapFailuresRequireSafetyStopAndSuccessResetsTheCount() {
        val guard = ConsecutiveTmapFailureGuard(safetyStopThreshold = 2)

        assertEquals(false, guard.recordFailure())
        assertEquals(true, guard.recordFailure())
        guard.recordSuccess()
        assertEquals(0, guard.failureCount())
        assertEquals(false, guard.recordFailure())
    }

    @Test
    fun destinationSearchRejectsNonTmapOrUnversionedPayloads() {
        listOf(
            """{"provider":"tmap_poi","results":[]}""",
            """{"schema_version":"walksafe.destination_search.v1","provider":"kakao_poi","query":"서울역","results":[]}""",
            """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"서울역"}""",
        ).forEach { payload ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseDestinationSearchResponse(JSONObject(payload), query = "서울역", limit = 5)
            }
        }
    }

    @Test
    fun consumesBackendGeneratedWalkingRouteFixtureNumericCodes() {
        val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { directory -> File(directory, "contracts/fixtures/walking-route-v1.json") }
            .first { it.isFile }
        val response = JSONObject(fixture.readText()).getJSONObject("response")

        val route = parseWalkingRoute(response, routeRequest)

        assertEquals(11, route.steps.single().turnType)
        assertEquals(15, route.steps.single().facilityType)
        assertEquals(201, route.guidePoints.single().turnType)
        assertEquals(15, route.guidePoints.single().facilityType)
        assertEquals("contract-route-1", route.providerRouteId)
    }

    @Test
    fun rejectsNegativeOrNonIntegerRouteCodes() {
        listOf(-1, "11", 1.5).forEach { invalidValue ->
            val response = JSONObject(responseJson).apply {
                getJSONArray("guide_points").getJSONObject(0).put("turn_type", invalidValue)
            }
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(response, routeRequest)
            }
        }
    }

    private fun validDestinationPayload(query: String = "서울역"): JSONObject {
        return JSONObject(
            """
            {
              "schema_version":"walksafe.destination_search.v1",
              "provider":"tmap_poi",
              "query":"$query",
              "results":[
                {
                  "id":"poi-1",
                  "name":"서울역",
                  "point":{"latitude":37.5547,"longitude":126.9706,"name":"서울역"},
                  "address":null,
                  "road_address":"서울 중구 한강대로 405",
                  "category":"교통",
                  "result_type":"poi",
                  "distance_m":120
                }
              ]
            }
            """.trimIndent(),
        )
    }

    private fun session(actorId: String): GatewayFieldSession {
        return GatewayFieldSession.verified(
            gatewayBaseUrl = "https://field.example",
            actorId = actorId,
            cookiePair = "walksafe_field_session=v2.test.session.cookie",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
    }

    private val routeRequest = WalkingRouteRequest(
        origin = RoutePoint(37.0, 127.0),
        destination = RoutePoint(37.001, 127.0, "목적지"),
    )

    private val responseJson = """
        {
          "schema_version": "walksafe.walking_route.v1",
          "provider": "tmap_pedestrian",
          "priority": "STAIR_AVOID",
          "summary": {"distance_m": 120, "duration_s": 90},
          "polyline": [
            {"latitude": 37.0, "longitude": 127.0},
            {"latitude": 37.001, "longitude": 127.0}
          ],
          "steps": [],
          "guide_points": [
            {
              "index": 0,
              "point": {"latitude": 37.0005, "longitude": 127.0},
              "instruction": "우회전",
              "distance_from_start_m": 60,
              "remaining_distance_m": 60,
              "bearing_deg": 92.5,
              "turn_type": 11,
              "point_type": "guide",
              "facility_type": 15
            }
          ],
          "provider_result_code": 0,
          "provider_result_message": "OK"
        }
    """.trimIndent()
}
