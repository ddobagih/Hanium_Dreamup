package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import java.net.URLDecoder
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
    fun preservesEachExplicitNormalizedDistanceSource() {
        WalkingRouteDistanceSource.entries.forEach { source ->
            val route = parseWalkingRoute(normalizedDistanceResponse(source), routeRequest)

            assertEquals(source, route.distanceSource)
            assertEquals(120L, route.steps.sumOf { it.distanceM.toLong() })
            assertEquals(60, route.guidePoints.single().distanceFromStartM)
            assertEquals(60, route.guidePoints.single().remainingDistanceM)
        }
    }

    @Test
    fun rejectsUnknownAndMalformedDistanceSources() {
        listOf("geometry_guess", "", true, 1, JSONObject(), JSONArray()).forEach { source ->
            val payload = normalizedDistanceResponse().put("distance_source", source)

            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, routeRequest)
            }
        }
    }

    @Test
    fun explicitDistanceSourceRequiresExactStepSumWithoutIntOverflow() {
        val payload = normalizedDistanceResponse()
        payload.getJSONArray("steps").getJSONObject(0).put("distance_m", 119)
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, routeRequest)
        }

        // 2 * Int.MAX_VALUE + 122 wraps to 120 in Int arithmetic.
        val line = payload.getJSONArray("polyline")
        val end = line.getJSONObject(line.length() - 1)
        payload.put("steps", JSONArray().apply {
            listOf(Int.MAX_VALUE, Int.MAX_VALUE, 122).forEachIndexed { index, distance ->
                put(JSONObject().put("index", index).put("distance_m", distance).put("duration_s", 1)
                    .put("points", if (index == 0) line else JSONArray().put(end)))
            }
        })
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, routeRequest)
        }
    }

    @Test
    fun explicitDistanceSourceRequiresBothGuideDistancesAndExactSum() {
        listOf("distance_from_start_m", "remaining_distance_m").forEach { field ->
            val payload = normalizedDistanceResponse()
            payload.getJSONArray("guide_points").getJSONObject(0).remove(field)
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, routeRequest)
            }
        }
        val payload = normalizedDistanceResponse()
        payload.getJSONArray("guide_points").getJSONObject(0).put("remaining_distance_m", 59)
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, routeRequest)
        }
    }

    @Test
    fun missingOrNullDistanceSourceKeepsLegacyToleranceAndMissingDistances() {
        listOf(false, true).forEach { explicitNull ->
            val payload = normalizedDistanceResponse()
            if (explicitNull) payload.put("distance_source", JSONObject.NULL) else payload.remove("distance_source")
            payload.getJSONArray("steps").getJSONObject(0).put("distance_m", 119)
            payload.getJSONArray("guide_points").getJSONObject(0).put("remaining_distance_m", 59)
            assertNull(parseWalkingRoute(payload, routeRequest).distanceSource)

            payload.getJSONArray("guide_points").getJSONObject(0).remove("remaining_distance_m")
            assertNull(parseWalkingRoute(payload, routeRequest).guidePoints.single().remainingDistanceM)
        }
    }

    @Test
    fun legacyDistanceSourceStillRejectsAnOutOfOrderStepWhenStepsArePresent() {
        val payload = normalizedDistanceResponse().apply {
            remove("distance_source")
            getJSONArray("steps").getJSONObject(0).put("index", 1)
        }
        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, routeRequest)
        }
        assertEquals("invalid gateway response: route_step_order_invalid", error.message)
        // A legacy route with no steps remains displayable, as before.
        assertEquals(0, parseWalkingRoute(JSONObject(responseJson), routeRequest).steps.size)
    }

    @Test
    fun normalizedSourceStillChecksGeometryAndRevisitedGuideOrder() {
        val payload = revisitedRouteResponse(secondVisitLongitude = 127.0)
        payload.put("distance_source", WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED.wireValue)
        payload.put("steps", JSONArray().put(JSONObject()
            .put("index", 0).put("distance_m", 400).put("duration_s", 300)
            .put("points", payload.getJSONArray("polyline"))))
        assertEquals(listOf(100, 200, 300), parseWalkingRoute(payload, revisitedRouteRequest).guidePoints.map { it.distanceFromStartM })

        val guides = payload.getJSONArray("guide_points")
        guides.getJSONObject(0).put("distance_from_start_m", 300).put("remaining_distance_m", 100)
        guides.getJSONObject(2).put("distance_from_start_m", 100).put("remaining_distance_m", 300)
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, revisitedRouteRequest)
        }
        val offRoute = normalizedDistanceResponse()
        offRoute.getJSONArray("guide_points").getJSONObject(0)
            .put("point", routePointJson(37.0005, 127.01))
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(offRoute, routeRequest)
        }
    }

    private fun normalizedDistanceResponse(
        source: WalkingRouteDistanceSource = WalkingRouteDistanceSource.PROVIDER_TOTAL_GEOMETRY_SCALED,
    ): JSONObject = JSONObject(responseJson).apply {
        put("distance_source", source.wireValue)
        put("steps", JSONArray().put(
            JSONObject().put("index", 0).put("distance_m", 120).put("duration_s", 90)
                .put("points", getJSONArray("polyline")),
        ))
    }

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
    fun destinationSearchSendsBackendCanonicalQueryAndRejectsUnrelatedResponse() {
        var requestedUrl: String? = null
        var responseQuery = "서울역"
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String =
                    error("POST should not be called by destination search")

                override fun get(url: String, session: GatewayFieldSession): String {
                    requestedUrl = url
                    return validDestinationPayload(query = responseQuery).toString()
                }
            },
        )
        val session = session("search-tester")

        listOf(
            "서울역 안내해줘",
            "목적지를 서울역까지 안내해줘",
            "서울역으로 안내해줘",
            "서울역 목적지 설정해",
        ).forEach { spokenQuery ->
            val response = client.searchDestinations(session, spokenQuery, limit = 5, origin = null)
            val sentQuery = requireNotNull(requestedUrl).substringAfter("?query=").substringBefore('&')
            assertEquals("서울역", URLDecoder.decode(sentQuery, Charsets.UTF_8.name()))
            assertEquals("서울역", response.query)
            assertEquals("서울역", response.results.single().name)
        }

        responseQuery = "강남역"
        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            client.searchDestinations(session, "서울역 안내해줘", limit = 5, origin = null)
        }
        assertEquals("invalid gateway response: destination_query_mismatch", error.message)
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
    fun walkingRouteRejectsMultiTurnRouteWithoutGuidePoints() {
        val missing = multiTurnRouteResponse().apply { remove("guide_points") }
        val nullGuides = multiTurnRouteResponse().apply { put("guide_points", JSONObject.NULL) }
        val emptyGuides = multiTurnRouteResponse().apply { put("guide_points", JSONArray()) }

        listOf(missing, nullGuides, emptyGuides).forEach { response ->
            val error = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(response, multiTurnRouteRequest)
            }
            assertEquals("invalid gateway response: route_guide_points_invalid", error.message)
            assertEquals(
                NavigationBackendErrorKind.GUIDANCE_INCOMPLETE,
                classifyNavigationBackendFailure(error).kind,
            )
        }
    }

    @Test
    fun walkingRouteAcceptsMultiTurnRouteWithValidGuidePoints() {
        val route = parseWalkingRoute(multiTurnRouteResponse(), multiTurnRouteRequest)

        assertEquals(4, route.polyline.size)
        assertEquals(listOf("우회전", "좌회전"), route.guidePoints.map { it.instruction })
    }

    @Test
    fun clearStepTurnNeedsGuideAtTheTurnNotOnlyAtTheStart() {
        val payload = twoStepGuideResponse(37.0002, 127.0003)
        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", error.message)

        payload.getJSONArray("guide_points").put(
            JSONObject()
                .put("index", 1)
                .put("point", routePointJson(37.0002, 127.0))
                .put("instruction", "우회전")
                .put("turn_type", 13)
                .put("distance_from_start_m", 22)
                .put("remaining_distance_m", 27),
        )
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
    }

    @Test
    fun straightAndSmallStepTurnsDoNotRequireExtraGuide() {
        listOf(
            twoStepGuideResponse(37.0004, 127.0),
            twoStepGuideResponse(37.0004, 127.000025),
        ).forEach { payload ->
            assertEquals(1, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
        }
    }

    @Test
    fun zeroLengthEdgeNextToGuidedTurnDoesNotHideIt() {
        val payload = twoStepGuideResponse(37.0002, 127.0003)
        val firstStepPoints = payload.getJSONArray("steps").getJSONObject(0).getJSONArray("points")
        firstStepPoints.put(routePointJson(37.0002, 127.0))
        payload.getJSONArray("guide_points").put(
            JSONObject()
                .put("index", 1)
                .put("point", routePointJson(37.0002, 127.0))
                .put("instruction", "우회전")
                .put("turn_type", 13)
                .put("distance_from_start_m", 22)
                .put("remaining_distance_m", 27),
        )
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
    }

    @Test
    fun subMeterSeamEdgeDoesNotHideAnOtherwiseClearTurn() {
        val payload = singleStepGuideResponse(37.0002, 127.0003)
        val seam = routePointJson(37.0002004, 127.0000005)
        val line = JSONArray()
            .put(routePointJson(37.0, 127.0))
            .put(routePointJson(37.0002, 127.0))
            .put(seam)
            .put(routePointJson(37.0002, 127.0003))
        payload.put("polyline", line)
        payload.getJSONArray("steps").getJSONObject(0).put("points", line)

        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", error.message)
    }

    @Test
    fun singleStepInternalTurnNeedsAnActualCornerInstruction() {
        val payload = singleStepGuideResponse(37.0002, 127.0003)
        val startOnly = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", startOnly.message)

        val cornerGuide = JSONObject()
            .put("index", 1)
            .put("point", routePointJson(37.0002, 127.0))
            .put("distance_from_start_m", 22)
            .put("remaining_distance_m", 27)
        payload.getJSONArray("guide_points").put(cornerGuide)
        val noManeuver = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", noManeuver.message)

        cornerGuide.put("instruction", "Guide")
        val genericGuide = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", genericGuide.message)
        cornerGuide.remove("instruction")

        cornerGuide.put("turn_type", 11)
        val straightAtCorner = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", straightAtCorner.message)

        cornerGuide.put("turn_type", 13)
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
        cornerGuide.remove("turn_type")
        cornerGuide.put("instruction", "우회전하세요.")
        val uncodedGuide = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", uncodedGuide.message)
        listOf("좌 회전하세요.", "유 턴하세요.", "2 시 방향으로 이동하세요.", "turn right").forEach { explicitDirection ->
            cornerGuide.put("instruction", explicitDirection)
            val rawOnly = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, requestForRoute(payload))
            }
            assertEquals("invalid gateway response: route_guidance_incomplete", rawOnly.message)
        }
        cornerGuide.put("instruction", "우회전하세요.").put("turn_type", 13)

        val line = payload.getJSONArray("polyline")
        val withZeroEdge = JSONArray().put(line.getJSONObject(0)).put(line.getJSONObject(1))
            .put(line.getJSONObject(1)).put(line.getJSONObject(2))
        payload.put("polyline", withZeroEdge)
        payload.getJSONArray("steps").getJSONObject(0).put("points", withZeroEdge)
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
    }

    @Test
    fun uncodedOrUnknownTextOnlyGuideCannotCertifyAnUnspokenCornerTurn() {
        val payload = singleStepGuideResponse(37.0002, 127.0003)
        val cornerGuide = JSONObject()
            .put("index", 1)
            .put("point", routePointJson(37.0002, 127.0))
            .put("instruction", "지금 차도로 오른쪽으로 꺾으세요.")
            .put("distance_from_start_m", 22)
            .put("remaining_distance_m", 27)
        payload.getJSONArray("guide_points").put(cornerGuide)

        listOf(null, 999).forEach { turnType ->
            if (turnType == null) cornerGuide.remove("turn_type") else cornerGuide.put("turn_type", turnType)
            val error = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, requestForRoute(payload))
            }
            assertEquals("invalid gateway response: route_guidance_incomplete", error.message)
        }
        cornerGuide.put("turn_type", 13)
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
    }

    @Test
    fun singleStepStraightAndMinorBendKeepTheirStartGuide() {
        listOf(
            singleStepGuideResponse(37.0004, 127.0),
            singleStepGuideResponse(37.0004, 127.000025),
        ).forEach { payload ->
            assertEquals(1, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
        }
    }

    @Test
    fun crosswalkTextCannotStandInForAnUnspokenTurn() {
        val payload = singleStepGuideResponse(37.0002, 127.0003)
        val crosswalk = JSONObject().put("index", 1)
            .put("point", routePointJson(37.0002, 127.0))
            .put("distance_from_start_m", 22).put("remaining_distance_m", 27)
            .put("instruction", "횡단보도에서 우회전하세요.")
            .put("turn_type", 211)
        payload.getJSONArray("guide_points").put(crosswalk)
        fun assertIncomplete() {
            val error = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, requestForRoute(payload))
            }
            assertEquals("invalid gateway response: route_guidance_incomplete", error.message)
        }

        assertIncomplete()
        crosswalk.remove("turn_type")
        assertIncomplete()
        crosswalk.put("point_type", "crosswalk").put("instruction", "오른쪽으로 꺾으세요.")
        assertIncomplete()

        crosswalk.put("turn_type", 13)
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
        crosswalk.remove("turn_type")
        payload.getJSONArray("guide_points").put(JSONObject()
            .put("index", 2).put("point", routePointJson(37.0002, 127.0))
            .put("instruction", "우회전하세요.")
            .put("turn_type", 13)
            .put("distance_from_start_m", 22).put("remaining_distance_m", 27))
        assertEquals(3, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
    }

    @Test
    fun fragmentedLongLegsStillNeedACornerGuideButGradualCurveDoesNot() {
        val payload = singleStepGuideResponse(37.000108, 127.000135)
        val line = JSONArray()
            .put(routePointJson(37.0, 127.0))
            .put(routePointJson(37.000054, 127.0))
            .put(routePointJson(37.000108, 127.0))
            .put(routePointJson(37.000108, 127.0000675))
            .put(routePointJson(37.000108, 127.000135))
        payload.put("polyline", line)
        payload.getJSONArray("steps").getJSONObject(0).put("points", line).put("distance_m", 24)
        payload.getJSONObject("summary").put("distance_m", 24)
        payload.getJSONArray("guide_points").getJSONObject(0).put("remaining_distance_m", 24)
        val missingTurn = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, requestForRoute(payload))
        }
        assertEquals("invalid gateway response: route_guidance_incomplete", missingTurn.message)

        payload.getJSONArray("guide_points").put(
            JSONObject().put("index", 1).put("point", routePointJson(37.000108, 127.0))
                .put("instruction", "우회전하세요.")
                .put("turn_type", 13)
                .put("distance_from_start_m", 12).put("remaining_distance_m", 12),
        )
        assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)

        val gentle = singleStepGuideResponse(37.0001844, 127.00023)
        val curvedLine = JSONArray()
            .put(routePointJson(37.0, 127.0))
            .put(routePointJson(37.000108, 127.0))
            .put(routePointJson(37.0001844, 127.000095))
            .put(routePointJson(37.0001844, 127.00023))
        gentle.put("polyline", curvedLine)
        gentle.getJSONArray("steps").getJSONObject(0).put("points", curvedLine).put("distance_m", 36)
        gentle.getJSONObject("summary").put("distance_m", 36)
        gentle.getJSONArray("guide_points").getJSONObject(0).put("remaining_distance_m", 36)
        assertEquals(1, parseWalkingRoute(gentle, requestForRoute(gentle)).guidePoints.size)
    }

    @Test
    fun manySubMeterEdgesCannotHideATurnOnEitherSideOfTheCorner() {
        listOf(19, 20).forEach { northEdges ->
            val summaryM = if (northEdges == 19) 23 else 24
            val cornerLat = 37.0 + northEdges * 0.00000539
            val line = JSONArray()
            for (index in 0..northEdges) line.put(routePointJson(37.0 + index * 0.00000539, 127.0))
            for (index in 1..20) line.put(routePointJson(cornerLat, 127.0 + index * 0.00000675))
            val payload = singleStepGuideResponse(cornerLat, 127.000135)
            payload.put("polyline", line)
            payload.getJSONArray("steps").getJSONObject(0).put("points", line).put("distance_m", summaryM)
            payload.getJSONObject("summary").put("distance_m", summaryM)
            payload.getJSONArray("guide_points").getJSONObject(0).put("remaining_distance_m", summaryM)

            val missingTurn = assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, requestForRoute(payload))
            }
            assertEquals("invalid gateway response: route_guidance_incomplete", missingTurn.message)

            payload.getJSONArray("guide_points").put(
                JSONObject().put("index", 1).put("point", routePointJson(cornerLat, 127.0))
                    .put("instruction", "우회전하세요.")
                    .put("turn_type", 13)
                    .put("distance_from_start_m", summaryM - 12).put("remaining_distance_m", 12),
            )
            assertEquals(2, parseWalkingRoute(payload, requestForRoute(payload)).guidePoints.size)
        }
    }

    @Test
    fun walkingRouteAcceptsDeclaredSecondVisitTwoMetersFromFirst() {
        val route = parseWalkingRoute(revisitedRouteResponse(127.000025), revisitedRouteRequest)

        assertEquals(listOf(100, 200, 300), route.guidePoints.map { it.distanceFromStartM })
    }

    @Test
    fun walkingRouteAcceptsDeclaredSecondVisitAtIdenticalCoordinate() {
        val route = parseWalkingRoute(revisitedRouteResponse(127.0), revisitedRouteRequest)

        assertEquals(listOf(100, 200, 300), route.guidePoints.map { it.distanceFromStartM })
    }

    @Test
    fun walkingRouteRejectsUndeclaredGuideOnOverlappingOutAndBackSegments() {
        val payload = revisitedRouteResponse(127.0)
        val guides = payload.getJSONArray("guide_points")
        payload.put("guide_points", JSONArray()
            .put(guides.getJSONObject(0))
            .put(JSONObject().put("index", 1).put("point", routePointJson(37.00135, 127.0)))
            .put(guides.getJSONObject(1).put("index", 2))
            .put(guides.getJSONObject(2).put("index", 3)))

        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            parseWalkingRoute(payload, revisitedRouteRequest)
        }
        assertEquals("invalid gateway response: route_guide_geometry_invalid", error.message)
    }

    @Test
    fun walkingRouteStillRejectsOffRouteOrMismatchedSecondGuide() {
        val offRoute = revisitedRouteResponse(127.000025).apply {
            getJSONArray("guide_points").getJSONObject(2).getJSONObject("point").put("longitude", 127.01)
        }
        val mismatchedProgress = revisitedRouteResponse(127.000025).apply {
            getJSONArray("guide_points").getJSONObject(2)
                .put("distance_from_start_m", 200)
                .put("remaining_distance_m", 200)
        }

        listOf(offRoute, mismatchedProgress).forEach { response ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(response, revisitedRouteRequest)
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
    fun malformedWalkingRouteJsonIsClassifiedAsInvalidResponse() {
        listOf("", "{").forEach { response ->
            val parseError = assertThrows(org.json.JSONException::class.java) {
                JSONObject(response)
            }
            assertEquals(
                NavigationBackendErrorKind.INVALID_RESPONSE,
                classifyNavigationBackendFailure(parseError).kind,
            )
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
                NavigationBackendErrorKind.BACKEND_UNAVAILABLE,
            GatewayProxyHttpException(502, "walking_route_failed", "route_guidance_incomplete") to
                NavigationBackendErrorKind.GUIDANCE_INCOMPLETE,
            GatewayProxyHttpException(502, "walking_route_failed", "route_guide_points_invalid") to
                NavigationBackendErrorKind.GUIDANCE_INCOMPLETE,
        )

        cases.forEach { (error, expected) ->
            assertEquals(expected, classifyNavigationBackendFailure(error).kind)
        }
        assertEquals(
            NavigationBackendErrorKind.GUIDANCE_INCOMPLETE,
            classifyNavigationBackendFailure(GatewayResponseProtocolException("route_guidance_incomplete")).kind,
        )
        assertEquals(
            "회전 안내가 불완전해 길안내를 시작할 수 없습니다. 경로를 다시 요청해 주세요.",
            NavigationBackendErrorKind.GUIDANCE_INCOMPLETE.userMessage,
        )
    }

    @Test
    fun separatesFailureLayerReasonAndRetryPolicyBeforeShowingRouteError() {
        val userSession = classifyNavigationBackendFailure(
            GatewayProxyHttpException(401, "walking_route_failed", null),
        )
        assertEquals(NavigationFailureLayer.USER_SESSION, userSession.layer)
        assertEquals(NavigationFailureReason.AUTHENTICATION, userSession.reason)
        assertEquals(NavigationFailureRetryability.AFTER_LOGIN, userSession.retryability)

        val gatewayUpstreamAuth = classifyNavigationBackendFailure(
            GatewayProxyHttpException(502, "walking_route_failed", "gateway_upstream_auth_failed"),
        )
        assertEquals(NavigationBackendErrorKind.BACKEND_UNAVAILABLE, gatewayUpstreamAuth.kind)
        assertEquals(NavigationFailureLayer.GATEWAY, gatewayUpstreamAuth.layer)
        assertEquals(NavigationFailureReason.UPSTREAM_AUTHENTICATION, gatewayUpstreamAuth.reason)
        assertEquals(NavigationFailureRetryability.SERVICE_RECOVERY, gatewayUpstreamAuth.retryability)
        assertFalse(gatewayUpstreamAuth.routeUserMessage.contains("로그인"))
        assertFalse(gatewayUpstreamAuth.routeUserMessage.contains("TMAP"))

        val gatewayRateLimit = classifyNavigationBackendFailure(
            GatewayProxyHttpException(429, "walking_route_failed", null),
        )
        assertEquals(NavigationBackendErrorKind.RATE_LIMITED, gatewayRateLimit.kind)
        assertEquals(NavigationFailureLayer.GATEWAY, gatewayRateLimit.layer)
        assertEquals(NavigationFailureReason.RATE_LIMIT, gatewayRateLimit.reason)
        assertEquals(NavigationFailureRetryability.RETRY_LATER, gatewayRateLimit.retryability)
        assertFalse(gatewayRateLimit.routeUserMessage.contains("TMAP"))

        val providerRateLimit = classifyNavigationBackendFailure(
            GatewayProxyHttpException(429, "walking_route_failed", "tmap_rate_limited"),
        )
        assertEquals(NavigationFailureLayer.PROVIDER, providerRateLimit.layer)
        assertEquals(NavigationFailureReason.RATE_LIMIT, providerRateLimit.reason)
        assertEquals(NavigationFailureRetryability.RETRY_LATER, providerRateLimit.retryability)
        assertEquals(true, providerRateLimit.routeUserMessage.contains("TMAP"))

        val gatewayTimeout = classifyNavigationBackendFailure(
            GatewayProxyHttpException(504, "walking_route_failed", null),
        )
        assertEquals(NavigationBackendErrorKind.TIMEOUT, gatewayTimeout.kind)
        assertEquals(NavigationFailureLayer.GATEWAY, gatewayTimeout.layer)
        assertEquals(NavigationFailureReason.TIMEOUT, gatewayTimeout.reason)
        assertFalse(gatewayTimeout.routeUserMessage.contains("TMAP"))

        val clientTimeout = classifyNavigationBackendFailure(java.net.SocketTimeoutException("read"))
        assertEquals(NavigationFailureLayer.NETWORK, clientTimeout.layer)
        assertEquals(NavigationFailureReason.TIMEOUT, clientTimeout.reason)
        assertFalse(clientTimeout.routeUserMessage.contains("TMAP"))

        val malformedJson = classifyNavigationBackendFailure(org.json.JSONException("invalid JSON"))
        assertEquals(NavigationBackendErrorKind.INVALID_RESPONSE, malformedJson.kind)
        assertEquals(NavigationFailureLayer.RESPONSE_PROTOCOL, malformedJson.layer)
        assertEquals(NavigationFailureReason.MALFORMED_JSON, malformedJson.reason)
        assertEquals(NavigationFailureRetryability.SERVICE_RECOVERY, malformedJson.retryability)
    }

    @Test
    fun destinationSearchFailuresUseActionableSearchMessagesWithoutChangingRouteMessages() {
        val errors = listOf(
            GatewayProxyHttpException(401, "destination_search_failed", "gateway_upstream_auth_failed"),
            GatewayProxyHttpException(503, "destination_search_failed", "tmap_app_key_missing"),
            GatewayProxyHttpException(429, "destination_search_failed", "tmap_rate_limited"),
            GatewayProxyHttpException(504, "destination_search_failed", "tmap_timeout"),
            GatewayProxyHttpException(502, "destination_search_failed", "tmap_provider_error"),
            GatewayProxyHttpException(502, "destination_search_failed", "invalid_tmap_response"),
            java.io.IOException("provider detail must not be shown"),
            GatewayProxyHttpException(503, "destination_search_failed", null),
            IllegalStateException("provider detail must not be shown"),
        )

        errors.forEach { error ->
            val kind = classifyNavigationBackendFailure(error).kind
            val message = kind.destinationSearchUserMessage
            assertEquals(true, message.contains("검색"))
            assertEquals(false, message.contains("경로"))
            assertEquals(false, message.contains("provider detail"))
            assertEquals(false, message.contains("tmap_"))
        }
        assertEquals(
            "경로 응답을 안전하게 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            NavigationBackendErrorKind.INVALID_RESPONSE.userMessage,
        )
        assertEquals(
            "검색 결과를 안전하게 확인할 수 없습니다. 다시 검색해 주세요.",
            NavigationBackendErrorKind.INVALID_RESPONSE.destinationSearchUserMessage,
        )
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

    @Test
    fun destinationSearchRejectsInvalidQueriesAndPreservesNormalizedRequestIdentity() {
        val requestedUrls = mutableListOf<String>()
        var responseQuery = "서울역"
        val client = BackendWalkingRouteClient(object : WalkingRouteTransport {
            override fun post(url: String, body: String, session: GatewayFieldSession): String =
                error("unexpected route request")

            override fun get(url: String, session: GatewayFieldSession): String {
                requestedUrls += url
                return JSONObject()
                    .put("schema_version", "walksafe.destination_search.v1")
                    .put("provider", "tmap_poi")
                    .put("query", responseQuery)
                    .put("results", JSONArray())
                    .toString()
            }
        })
        val gatewaySession = session("destination-query-test")

        listOf("", " \t\n", "안내해줘", "목적지 안내해줘", "목적지로 안내해줘", "서울\uD800역", "서울\uDC00역").forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                client.searchDestinations(gatewaySession, query, 5, origin = null)
            }
        }
        assertEquals(emptyList<String>(), requestedUrls)

        listOf(
            "서울역" to "서울역",
            "서울역 안내해줘" to "서울역",
            "서울역으로 안내해줘" to "서울역",
            "서울\u0085역" to "서울 역",
        ).forEach { (query, expectedQuery) ->
            responseQuery = expectedQuery
            val result = client.searchDestinations(gatewaySession, query, 5, origin = null)
            assertEquals(expectedQuery, result.query)
            assertEquals(
                expectedQuery,
                java.net.URLDecoder.decode(
                    requestedUrls.last().substringAfter("?query=").substringBefore('&'),
                    "UTF-8",
                ),
            )
        }
        assertEquals(4, requestedUrls.size)
        assertThrows(GatewayResponseProtocolException::class.java) {
            parseDestinationSearchResponse(validDestinationPayload("다른역"), query = "서울역")
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

    private fun revisitedRouteResponse(secondVisitLongitude: Double): JSONObject = JSONObject(responseJson).apply {
        getJSONObject("summary").put("distance_m", 400).put("duration_s", 300)
        put(
            "polyline",
            JSONArray()
                .put(routePointJson(37.0, 127.0))
                .put(routePointJson(37.0009, 127.0))
                .put(routePointJson(37.0018, 127.0))
                .put(routePointJson(37.0009, secondVisitLongitude))
                .put(routePointJson(37.0009, 127.00115)),
        )
        put(
            "guide_points",
            JSONArray()
                .put(
                    JSONObject()
                        .put("index", 0)
                        .put("point", routePointJson(37.0009, 127.0))
                        .put("distance_from_start_m", 100)
                        .put("remaining_distance_m", 300),
                )
                .put(
                    JSONObject()
                        .put("index", 1)
                        .put("point", routePointJson(37.0018, 127.0))
                        .put("instruction", "뒤로 돌아가세요.")
                        .put("turn_type", 14)
                        .put("distance_from_start_m", 200)
                        .put("remaining_distance_m", 200),
                )
                .put(
                    JSONObject()
                        .put("index", 2)
                        .put("point", routePointJson(37.0009, 127.0))
                        .put("instruction", "우회전하세요.")
                        .put("turn_type", 13)
                        .put("distance_from_start_m", 300)
                        .put("remaining_distance_m", 100),
                ),
        )
    }

    private fun routePointJson(latitude: Double, longitude: Double): JSONObject =
        JSONObject().put("latitude", latitude).put("longitude", longitude)

    private fun twoStepGuideResponse(lastLatitude: Double, lastLongitude: Double): JSONObject =
        JSONObject(responseJson).apply {
            val start = routePointJson(37.0, 127.0)
            val middle = routePointJson(37.0002, 127.0)
            val end = routePointJson(lastLatitude, lastLongitude)
            val firstLength = 22
            val totalLength = if (lastLongitude == 127.0) 44 else if (lastLatitude == 37.0002) 49 else 45
            getJSONObject("summary").put("distance_m", totalLength)
            put("polyline", JSONArray().put(start).put(middle).put(end))
            put("steps", JSONArray()
                .put(JSONObject().put("index", 0).put("distance_m", firstLength)
                    .put("duration_s", 45).put("points", JSONArray().put(start).put(middle)))
                .put(JSONObject().put("index", 1).put("distance_m", totalLength - firstLength)
                    .put("duration_s", 45).put("points", JSONArray().put(middle).put(end))))
            put("guide_points", JSONArray().put(
                JSONObject().put("index", 0).put("point", start).put("instruction", "출발")
                    .put("distance_from_start_m", 0).put("remaining_distance_m", totalLength),
            ))
        }

    private fun singleStepGuideResponse(lastLatitude: Double, lastLongitude: Double): JSONObject =
        twoStepGuideResponse(lastLatitude, lastLongitude).apply {
            put("steps", JSONArray().put(
                JSONObject().put("index", 0).put("distance_m", getJSONObject("summary").getInt("distance_m"))
                    .put("duration_s", 90).put("points", getJSONArray("polyline")),
            ))
        }

    private fun requestForRoute(payload: JSONObject): WalkingRouteRequest {
        val points = payload.getJSONArray("polyline")
        val start = points.getJSONObject(0)
        val end = points.getJSONObject(points.length() - 1)
        return WalkingRouteRequest(
            origin = RoutePoint(start.getDouble("latitude"), start.getDouble("longitude")),
            destination = RoutePoint(end.getDouble("latitude"), end.getDouble("longitude")),
        )
    }

    private fun multiTurnRouteResponse(): JSONObject = JSONObject(responseJson).apply {
        getJSONObject("summary").put("distance_m", 156).put("duration_s", 120)
        put(
            "polyline",
            JSONArray()
                .put(routePointJson(37.0, 127.0))
                .put(routePointJson(37.0005, 127.0))
                .put(routePointJson(37.0005, 127.0005))
                .put(routePointJson(37.001, 127.0005)),
        )
        put(
            "guide_points",
            JSONArray()
                .put(
                    JSONObject()
                        .put("index", 0)
                        .put("point", routePointJson(37.0005, 127.0))
                        .put("instruction", "우회전")
                        .put("turn_type", 13)
                        .put("distance_from_start_m", 56)
                        .put("remaining_distance_m", 100),
                )
                .put(
                    JSONObject()
                        .put("index", 1)
                        .put("point", routePointJson(37.0005, 127.0005))
                        .put("instruction", "좌회전")
                        .put("turn_type", 12)
                        .put("distance_from_start_m", 100)
                        .put("remaining_distance_m", 56),
                ),
        )
    }

    private val multiTurnRouteRequest = WalkingRouteRequest(
        origin = RoutePoint(37.0, 127.0),
        destination = RoutePoint(37.001, 127.0005),
    )

    private val revisitedRouteRequest = WalkingRouteRequest(
        origin = RoutePoint(37.0, 127.0),
        destination = RoutePoint(37.0009, 127.00115),
    )

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
