package kr.co.hanium.dreamup.walksafe.navigation

import java.io.IOException
import java.net.Socket
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseProtocolException
import kr.co.hanium.dreamup.walksafe.network.writeChunkedResponse
import kr.co.hanium.dreamup.walksafe.network.writeFixedResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WalkingRouteResponseIntegrityTest {
    @Test
    fun rejects8193PolylineCoordinatesBeforeMappingThem() {
        val payload = routePayload()
        payload.put("polyline", straightPoints(8_193))
        assertEquals(
            "route_polyline_invalid",
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, ROUTE_REQUEST)
            }.reason,
        )
    }

    @Test
    fun rejects1025StepsBeforeMappingThem() {
        val payload = routePayload()
        val steps = JSONArray()
        steps.put(step(0, JSONArray().put(ORIGIN).put(DESTINATION), distanceM = 120))
        repeat(1_024) { index ->
            steps.put(step(index + 1, JSONArray().put(DESTINATION), distanceM = 0))
        }
        payload.put("steps", steps)
        assertEquals(
            "route_steps_invalid",
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, ROUTE_REQUEST)
            }.reason,
        )
    }

    @Test
    fun rejects1025GuidesBeforeMappingThem() {
        val payload = routePayload()
        payload.put("guide_points", JSONArray().apply {
            repeat(1_025) { index -> put(destinationGuide(index)) }
        })
        assertEquals(
            "route_guide_points_invalid",
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, ROUTE_REQUEST)
            }.reason,
        )
    }

    @Test
    fun rejects8193AggregateStepCoordinatesBeforeMappingThem() {
        val points = JSONArray().put(ORIGIN).put(DESTINATION)
        repeat(8_191) { points.put(DESTINATION) }
        val payload = routePayload().put("steps", JSONArray().put(step(0, points, distanceM = 120)))
        assertEquals(
            "route_steps_invalid",
            assertThrows(GatewayResponseProtocolException::class.java) {
                parseWalkingRoute(payload, ROUTE_REQUEST)
            }.reason,
        )
    }

    @Test
    fun aNormalLegacyRouteWithEmptyStepsAndOptionalFieldsAbsentStillParses() {
        val route = parseWalkingRoute(routePayload(), ROUTE_REQUEST)
        assertEquals(2, route.polyline.size)
        assertEquals(0, route.steps.size)
        assertEquals(1, route.guidePoints.size)
    }

    @Test
    fun searchRejectsValidJsonWhenFixedLengthClaimsMoreUtf8BytesThanArrived() {
        val body = SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(200, body, declaredLength = body.size.toLong() + 3L)
        }.use { server ->
            assertThrows(IOException::class.java) {
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute()
            }
        }
    }

    @Test
    fun searchRejectsMalformedOrDuplicateContentLengthHeaders() {
        val body = SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(200, body, headers = mapOf("Content-Length" to body.size.toString()))
        }.use { server ->
            assertThrows(IOException::class.java) {
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute()
            }
        }
        LocalHttpTestServer { _, socket ->
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: invalid\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                write(body)
                flush()
            }
        }.use { server ->
            assertThrows(IOException::class.java) {
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute()
            }
        }
    }

    @Test
    fun searchAcceptsAValidCloseDelimitedBodyWithoutLengthHeaders() {
        val body = SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket ->
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                write(body)
                flush()
            }
        }.use { server ->
            assertEquals(
                "서울역",
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute().query,
            )
        }
    }

    @Test
    fun searchRejectsNonIdentityEncodedSuccessBodies() {
        val body = SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(200, body, headers = mapOf("Content-Encoding" to "gzip"))
        }.use { server ->
            assertThrows(NetworkResponseProtocolException::class.java) {
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute()
            }
        }
    }

    @Test
    fun routeRejectsMalformedUtf8EvenWhenJsonAndCoordinatesWouldOtherwiseBeValid() {
        val prefix = routePayload().toString().dropLast(1) + ",\"ignored\":\""
        val body = prefix.toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte()) +
            "\"}".toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket -> socket.writeFixedResponse(200, body) }.use { server ->
            val error = assertThrows(NetworkResponseProtocolException::class.java) {
                BackendWalkingRouteClient().fetchRouteCall(session(server.baseUrl), ROUTE_REQUEST).execute()
            }
            assertEquals("response_utf8_invalid", error.reason)
            assertEquals(NavigationBackendErrorKind.INVALID_RESPONSE, classifyNavigationBackendFailure(error).kind)
        }
    }

    @Test
    fun searchAcceptsACompleteChunkedBodyWithoutContentLength() {
        LocalHttpTestServer { _, socket ->
            socket.writeChunkedResponse(200, SEARCH_RESPONSE.toByteArray(Charsets.UTF_8))
        }.use { server ->
            assertEquals(
                "서울역",
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute().query,
            )
        }
    }

    @Test
    fun searchAcceptsExactFixedLengthMeasuredInUtf8Bytes() {
        val body = SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)
        LocalHttpTestServer { _, socket -> socket.writeFixedResponse(200, body) }.use { server ->
            assertEquals(
                "서울역",
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute().query,
            )
        }
    }

    @Test
    fun searchRejectsHttp206EvenIfItsBodyIsValidJson() {
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(206, SEARCH_RESPONSE.toByteArray(Charsets.UTF_8))
        }.use { server ->
            assertThrows(GatewayResponseProtocolException::class.java) {
                BackendWalkingRouteClient().searchDestinationsCall(
                    session(server.baseUrl), "서울역", 5, origin = null,
                ).execute()
            }
        }
    }

    @Test
    fun searchRejectsAChunkedBodyWithoutItsTerminalChunk() {
        LocalHttpTestServer { _, socket -> socket.writeUnterminatedChunk(SEARCH_RESPONSE.toByteArray(Charsets.UTF_8)) }
            .use { server ->
                assertThrows(Exception::class.java) {
                    BackendWalkingRouteClient().searchDestinationsCall(
                        session(server.baseUrl), "서울역", 5, origin = null,
                    ).execute()
                }
            }
    }

    private fun Socket.writeUnterminatedChunk(body: ByteArray) {
        getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                        "${body.size.toString(16)}\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
            write(body)
            write("\r\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    private fun routePayload(): JSONObject = JSONObject()
        .put("schema_version", "walksafe.walking_route.v1")
        .put("provider", "tmap_pedestrian")
        .put("provider_result_code", 0)
        .put("priority", "STAIR_AVOID")
        .put("summary", JSONObject().put("distance_m", 120).put("duration_s", 90))
        .put("polyline", JSONArray().put(ORIGIN).put(DESTINATION))
        .put("steps", JSONArray())
        .put("guide_points", JSONArray().put(destinationGuide(0)))

    private fun straightPoints(count: Int): JSONArray = JSONArray().apply {
        repeat(count) { index ->
            put(point(37.0 + 0.001 * index / (count - 1)))
        }
    }

    private fun step(index: Int, points: JSONArray, distanceM: Int): JSONObject = JSONObject()
        .put("index", index)
        .put("distance_m", distanceM)
        .put("duration_s", if (index == 0) 90 else 0)
        .put("points", points)

    private fun destinationGuide(index: Int): JSONObject = JSONObject()
        .put("index", index)
        .put("point", DESTINATION)
        .put("distance_from_start_m", 120)
        .put("remaining_distance_m", 0)
        .put("turn_type", 201)
        .put("point_type", "E")

    private fun point(latitude: Double): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", 127.0)

    private fun session(baseUrl: String): GatewayFieldSession = GatewayFieldSession.verified(
        gatewayBaseUrl = baseUrl,
        actorId = "response-integrity-tester",
        cookiePair = "walksafe_field_session=v2.test.session.cookie",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    private companion object {
        val ORIGIN = JSONObject().put("latitude", 37.0).put("longitude", 127.0)
        val DESTINATION = JSONObject().put("latitude", 37.001).put("longitude", 127.0)
        val ROUTE_REQUEST = WalkingRouteRequest(RoutePoint(37.0, 127.0), RoutePoint(37.001, 127.0))
        const val SEARCH_RESPONSE =
            """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"서울역","results":[]}"""
    }
}
