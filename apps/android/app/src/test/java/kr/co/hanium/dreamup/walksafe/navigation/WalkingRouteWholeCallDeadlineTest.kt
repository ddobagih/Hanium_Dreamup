package kr.co.hanium.dreamup.walksafe.navigation

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.NetworkDeadlineExceededException
import kr.co.hanium.dreamup.walksafe.network.awaitPeerDisconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingRouteWholeCallDeadlineTest {
    @Test(timeout = 5_000)
    fun cancelledPublicSearchCallRejectsAnUncooperativeLateTransportResult() {
        lateinit var call: CancellableNetworkCall<DestinationSearchResponse>
        val transport = object : WalkingRouteTransport {
            override fun post(url: String, body: String, session: GatewayFieldSession): String = error("unused")
            override fun get(url: String, session: GatewayFieldSession): String = error("unused")
            override fun getCall(url: String, session: GatewayFieldSession): CancellableNetworkCall<String> = CancellableNetworkCall(
                executeBlock = {
                    call.cancel()
                    """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"서울역","results":[]}"""
                },
            )
        }
        call = BackendWalkingRouteClient(transport).searchDestinationsCall(
            session("http://127.0.0.1:1"), "서울역", 5, origin = null,
        )
        assertThrows(CancellationException::class.java) { call.execute() }
    }

    @Test(timeout = 5_000)
    fun cancelledPublicRouteCallRejectsAnUncooperativeLateTransportResult() {
        lateinit var call: CancellableNetworkCall<WalkingRoute>
        val transport = object : WalkingRouteTransport {
            override fun post(url: String, body: String, session: GatewayFieldSession): String = error("unused")
            override fun get(url: String, session: GatewayFieldSession): String = error("unused")
            override fun postCall(
                url: String,
                body: String,
                session: GatewayFieldSession,
            ): CancellableNetworkCall<String> = CancellableNetworkCall(
                executeBlock = {
                    call.cancel()
                    ROUTE_RESPONSE
                },
            )
        }
        call = BackendWalkingRouteClient(transport).fetchRouteCall(
            session("http://127.0.0.1:1"), ROUTE_REQUEST,
        )
        assertThrows(CancellationException::class.java) { call.execute() }
    }

    @Test(timeout = 20_000)
    fun slowDripSearchCannotExtendTheWholeCallPastTheOuterDeadline() {
        val started = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val stopDrip = AtomicBoolean(false)
        val disconnected = AtomicBoolean(false)
        LocalHttpTestServer { _, socket ->
            try {
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    flush()
                }
                started.countDown()
                while (!stopDrip.get()) {
                    socket.getOutputStream().apply {
                        write("1\r\n \r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    Thread.sleep(200)
                }
                disconnected.set(socket.awaitPeerDisconnect(3_000))
            } catch (_: IOException) {
                disconnected.set(true)
            } finally {
                peerClosed.countDown()
            }
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val call = BackendWalkingRouteClient().searchDestinationsCall(
                session(server.baseUrl), "서울역", 5, origin = null,
            )
            try {
                val future = executor.submit<DestinationSearchResponse> { call.execute() }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val error = assertThrows(ExecutionException::class.java) {
                    future.get(14, TimeUnit.SECONDS)
                }.cause
                assertTrue(error is NetworkDeadlineExceededException)
                assertEquals(10_000L, (error as NetworkDeadlineExceededException).deadlineMs)
            } finally {
                stopDrip.set(true)
                call.cancel()
                executor.shutdownNow()
            }
            assertTrue(peerClosed.await(4, TimeUnit.SECONDS))
            assertTrue("the expired call must close its socket", disconnected.get())
        }
    }

    @Test(timeout = 20_000)
    fun blockedRouteBodyWriteCannotOutliveTheOuterDeadline() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val peer = Thread {
                runCatching {
                    server.accept().use {
                        accepted.countDown()
                        release.await(18, TimeUnit.SECONDS)
                    }
                }
            }.apply { isDaemon = true; start() }
            val executor = Executors.newSingleThreadExecutor()
            val baseUrl = "http://127.0.0.1:${server.localPort}"
            val call = HttpUrlConnectionWalkingRouteTransport().postCall(
                "$baseUrl/api/navigation/walking",
                "x".repeat(32 * 1024 * 1024),
                session(baseUrl),
            )
            try {
                val future = executor.submit<String> { call.execute() }
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                val error = assertThrows(ExecutionException::class.java) {
                    future.get(14, TimeUnit.SECONDS)
                }.cause
                assertTrue(error is NetworkDeadlineExceededException)
                assertEquals(10_000L, (error as NetworkDeadlineExceededException).deadlineMs)
            } finally {
                call.cancel()
                release.countDown()
                executor.shutdownNow()
                peer.join(1_000)
            }
        }
    }

    @Test(timeout = 8_000)
    fun explicitCancelStillReturnsCancellationWithoutAStaleSearchResult() {
        val responseStarted = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                flush()
            }
            responseStarted.countDown()
            socket.awaitPeerDisconnect(4_000)
            peerClosed.countDown()
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val call = BackendWalkingRouteClient().searchDestinationsCall(
                session(server.baseUrl), "서울역", 5, origin = null,
            )
            try {
                val future = executor.submit<DestinationSearchResponse> { call.execute() }
                assertTrue(responseStarted.await(2, TimeUnit.SECONDS))
                call.cancel()
                val error = assertThrows(ExecutionException::class.java) {
                    future.get(3, TimeUnit.SECONDS)
                }.cause
                assertTrue(error is CancellationException)
                assertTrue(peerClosed.await(3, TimeUnit.SECONDS))
            } finally {
                call.cancel()
                executor.shutdownNow()
            }
        }
    }

    private fun session(baseUrl: String): GatewayFieldSession = GatewayFieldSession.verified(
        gatewayBaseUrl = baseUrl,
        actorId = "deadline-tester",
        cookiePair = "walksafe_field_session=v2.test.session.cookie",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

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
              "guide_points":[
                {
                  "bearing_deg":0.0,
                  "distance_from_start_m":120,
                  "facility_type":15,
                  "index":0,
                  "instruction":"목적지 도착",
                  "point":{"latitude":37.001,"longitude":127.0,"name":null},
                  "point_type":"E",
                  "remaining_distance_m":0,
                  "turn_type":201
                }
              ],
              "provider_result_code":0,
              "provider_result_message":"OK"
            }
        """.trimIndent()
    }
}
