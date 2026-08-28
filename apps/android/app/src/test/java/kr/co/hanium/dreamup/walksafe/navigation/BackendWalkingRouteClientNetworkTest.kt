package kr.co.hanium.dreamup.walksafe.navigation

import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseTooLargeException
import kr.co.hanium.dreamup.walksafe.network.awaitPeerDisconnect
import kr.co.hanium.dreamup.walksafe.network.newNavigationRequestExecutor
import kr.co.hanium.dreamup.walksafe.network.writeChunkedResponse
import kr.co.hanium.dreamup.walksafe.network.writeFixedResponse
import kr.co.hanium.dreamup.walksafe.network.writeStalledChunkedHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendWalkingRouteClientNetworkTest {
    @Test
    fun mainActivityPauseCancelsStalledRouteAndReplacementStartsImmediately() {
        val stalledResponseStarted = CountDownLatch(1)
        LocalHttpTestServer { requestIndex, socket ->
            if (requestIndex == 0) {
                socket.writeStalledChunkedHeaders()
                stalledResponseStarted.countDown()
                socket.awaitPeerDisconnect(3_000)
            } else {
                socket.writeFixedResponse(200, ROUTE_RESPONSE.toByteArray())
            }
        }.use { server ->
            val executor = newNavigationRequestExecutor()
            val activity = MainActivity()
            try {
                val client = BackendWalkingRouteClient()
                val session = session(server.baseUrl)
                val stalledCall = activity.trackRouteRequest(client.fetchRouteCall(session, ROUTE_REQUEST))
                val stalledFuture = executor.submit<WalkingRoute> { stalledCall.execute() }
                assertTrue(stalledResponseStarted.await(2, TimeUnit.SECONDS))

                activity.cancelNavigationRequestsForPause()
                val replacementCall = activity.trackRouteRequest(client.fetchRouteCall(session, ROUTE_REQUEST))
                val replacement = executor.submit<WalkingRoute> {
                    replacementCall.execute()
                }.get(2, TimeUnit.SECONDS)
                activity.completeRouteRequest(replacementCall)

                assertEquals(120, replacement.summary.distanceM)
                val failure = assertThrows(ExecutionException::class.java) {
                    stalledFuture.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
            } finally {
                activity.cancelNavigationRequestsForDestroy()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun destinationSelectionCancelsStalledSearchAndReplacementStartsImmediately() {
        val stalledResponseStarted = CountDownLatch(1)
        LocalHttpTestServer { requestIndex, socket ->
            if (requestIndex == 0) {
                socket.writeStalledChunkedHeaders()
                stalledResponseStarted.countDown()
                socket.awaitPeerDisconnect(3_000)
            } else {
                socket.writeFixedResponse(200, SEARCH_RESPONSE.toByteArray())
            }
        }.use { server ->
            val executor = newNavigationRequestExecutor()
            val activity = MainActivity()
            try {
                val client = BackendWalkingRouteClient()
                val session = session(server.baseUrl)
                val stalledCall = activity.trackDestinationSearchRequest(
                    client.searchDestinationsCall(session, "서울역", 5, origin = null),
                )
                val stalledFuture = executor.submit<DestinationSearchResponse> { stalledCall.execute() }
                assertTrue(stalledResponseStarted.await(2, TimeUnit.SECONDS))

                activity.cancelNavigationRequestsForDestinationSelection()
                val replacementCall = activity.trackDestinationSearchRequest(
                    client.searchDestinationsCall(session, "서울역", 5, origin = null),
                )
                val replacement = executor.submit<DestinationSearchResponse> {
                    replacementCall.execute()
                }.get(2, TimeUnit.SECONDS)
                activity.completeDestinationSearchRequest(replacementCall)

                assertEquals("서울역", replacement.query)
                val failure = assertThrows(ExecutionException::class.java) {
                    stalledFuture.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
            } finally {
                activity.cancelNavigationRequestsForDestroy()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsOversizedSuccessFromContentLengthBeforeBodyOrJsonParsing() {
        val releaseServer = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.writeHeadersWithLength(200, WALKING_ROUTE_MAX_RESPONSE_BYTES.toLong() + 1L)
            releaseServer.await(3, TimeUnit.SECONDS)
        }.use { server ->
            try {
                assertThrows(NetworkResponseTooLargeException::class.java) {
                    BackendWalkingRouteClient().fetchRouteCall(session(server.baseUrl), ROUTE_REQUEST).execute()
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun rejectsOversizedChunkedErrorBeforeGatewayErrorParsing() {
        LocalHttpTestServer { _, socket ->
            socket.writeChunkedResponse(502, ByteArray(WALKING_ROUTE_MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() })
        }.use { server ->
            assertThrows(NetworkResponseTooLargeException::class.java) {
                BackendWalkingRouteClient()
                    .searchDestinationsCall(session(server.baseUrl), "서울역", 5, origin = null)
                    .execute()
            }
        }
    }

    @Test
    fun preservesOnlyTheBoundedBackendErrorCodeForTaxonomy() {
        val payload =
            """{"detail":{"code":"tmap_timeout","message":"provider detail must not be retained"}}"""
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(504, payload.toByteArray())
        }.use { server ->
            val error = assertThrows(GatewayProxyHttpException::class.java) {
                BackendWalkingRouteClient()
                    .fetchRouteCall(session(server.baseUrl), ROUTE_REQUEST)
                    .execute()
            }

            assertEquals("tmap_timeout", error.backendCode)
            assertEquals(
                NavigationBackendErrorKind.TIMEOUT,
                classifyNavigationBackendFailure(error).kind,
            )
            assertTrue(!error.message.orEmpty().contains("provider detail"))
        }
    }

    private fun session(baseUrl: String): GatewayFieldSession {
        return GatewayFieldSession.verified(
            gatewayBaseUrl = baseUrl,
            actorId = "network-tester",
            cookiePair = "walksafe_field_session=v2.test.session.cookie",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
    }

    private fun Socket.writeHeadersWithLength(statusCode: Int, contentLength: Long) {
        getOutputStream().apply {
            write(
                "HTTP/1.1 $statusCode Test\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            flush()
        }
    }

    private companion object {
        val ROUTE_REQUEST = WalkingRouteRequest(
            origin = RoutePoint(37.0, 127.0),
            destination = RoutePoint(37.001, 127.0),
        )

        val ROUTE_RESPONSE = """
            {
              "schema_version":"walksafe.walking_route.v1",
              "provider":"tmap_pedestrian",
              "priority":"STAIR_AVOID",
              "summary":{"distance_m":120,"duration_s":90},
              "polyline":[
                {"latitude":37.0,"longitude":127.0},
                {"latitude":37.001,"longitude":127.0}
              ],
              "steps":[],
              "guide_points":[],
              "provider_result_code":0,
              "provider_result_message":"OK"
            }
        """.trimIndent()

        const val SEARCH_RESPONSE =
            """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"서울역","results":[]}"""
    }
}
