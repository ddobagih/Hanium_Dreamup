package kr.co.hanium.dreamup.walksafe.navigation

import java.net.URI
import java.net.URLDecoder
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationSearchLocationControllerTest {
    private val lease = DestinationSearchLocationLease("search-test-actor", 1L)
    private val publicDaeguFixture = DestinationSearchLocationFix(35.8714, 128.6014, 60f, 10_000L)

    @Test
    fun acquiredCurrentFixBecomesActualSearchOriginWithoutStartingAWalk() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        var requestUrl: String? = null
        val client = BackendWalkingRouteClient(object : WalkingRouteTransport {
            override fun post(url: String, body: String, session: GatewayFieldSession): String =
                error("Destination centering must not request a walking route")
            override fun get(url: String, session: GatewayFieldSession): String {
                requestUrl = url
                return """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi","query":"약국","results":[]}"""
            }
        })
        val session = GatewayFieldSession.verified(
            gatewayBaseUrl = "https://example.test", actorId = lease.actorId,
            cookiePair = "walksafe_field_session=v2.test.cookie", expiresAtEpochMs = Long.MAX_VALUE,
        )
        assertNull(controller.originOrNull(lease))
        assertTrue(controller.acquire(lease, { true }) { origin ->
            if (origin != null) client.searchDestinations(session, "약국", 5, origin)
        })
        assertNull(requestUrl)
        source.complete(0, publicDaeguFixture)
        val parameters = URI(requireNotNull(requestUrl)).rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("35.8714", parameters["origin_lat"])
        assertEquals("128.6014", parameters["origin_lng"])
        assertFalse(controller.isAcquiring)
    }

    @Test
    fun rejectsMissingStaleFutureMockOrInaccurateFixWithoutFallbackCoordinates() {
        val invalid = listOf(
            null, publicDaeguFixture.copy(mock = true), publicDaeguFixture.copy(accuracyM = null),
            publicDaeguFixture.copy(accuracyM = 100.1f), publicDaeguFixture.copy(accuracyM = Float.NaN),
            publicDaeguFixture.copy(latitude = Double.NaN), publicDaeguFixture.copy(longitude = 181.0),
            publicDaeguFixture.copy(elapsedRealtimeMs = 50_001L),
            publicDaeguFixture.copy(elapsedRealtimeMs = -1L),
        )
        invalid.forEach { assertNull(DestinationSearchLocationPolicy.originOrNull(it, 50_000L)) }
        assertNotNull(DestinationSearchLocationPolicy.originOrNull(publicDaeguFixture.copy(accuracyM = 100f), 40_000L))
        assertNull(DestinationSearchLocationPolicy.originOrNull(publicDaeguFixture, 40_001L))
    }

    @Test
    fun unavailableFixAllowsExplicitRetryAndExpiredCacheStartsANewRequest() {
        val source = FakeSource()
        var now = 10_000L
        val controller = DestinationSearchLocationController(source) { now }
        val results = mutableListOf<RoutePoint?>()
        controller.acquire(lease, { true }, results::add)
        source.complete(0, null)
        assertFalse(controller.isAcquiring)
        assertEquals(listOf<RoutePoint?>(null), results)
        controller.acquire(lease, { true }, results::add)
        source.complete(1, publicDaeguFixture)
        assertNotNull(controller.originOrNull(lease))
        now = 40_001L
        assertNull(controller.originOrNull(lease))
        controller.acquire(lease, { true }, results::add)
        assertEquals(3, source.callbacks.size)
    }

    @Test
    fun repeatedSearchReusesPendingFixAndOnlyLatestSearchContinues() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        var oldSearchCalls = 0
        var latestSearchCalls = 0
        controller.acquire(lease, { true }) { oldSearchCalls += 1 }
        controller.acquire(lease, { true }) { latestSearchCalls += 1 }
        assertEquals(1, source.callbacks.size)
        source.complete(0, publicDaeguFixture)
        assertEquals(0, oldSearchCalls)
        assertEquals(1, latestSearchCalls)
    }

    @Test
    fun screenExitCancelsAcquisitionAndIgnoresLateCallbacks() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        var callbackCount = 0
        controller.acquire(lease, { true }) { callbackCount += 1 }
        controller.cancel()
        source.complete(0, publicDaeguFixture)
        assertEquals(1, source.cancelled)
        assertEquals(0, callbackCount)
        assertNull(controller.originOrNull(lease))
    }

    @Test
    fun backgroundPermissionRevocationOrLogoutRejectsCompletionAndCache() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        var foregroundAndAuthorized = true
        var callbackCount = 0
        controller.acquire(lease, { foregroundAndAuthorized }) { callbackCount += 1 }
        foregroundAndAuthorized = false
        source.complete(0, publicDaeguFixture)
        assertEquals(0, callbackCount)
        assertNull(controller.originOrNull(lease))
        assertFalse(controller.acquire(lease, { false }) { callbackCount += 1 })
        assertEquals(1, source.callbacks.size)
    }

    @Test
    fun accountOrSessionReplacementCannotReuseThePreviousFixOrCallback() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        var oldCalls = 0
        var newCalls = 0
        controller.acquire(lease, { true }) { oldCalls += 1 }
        val newLease = DestinationSearchLocationLease("another-test-actor", 2L)
        controller.acquire(newLease, { true }) { newCalls += 1 }
        source.complete(0, publicDaeguFixture)
        assertEquals(0, oldCalls)
        assertNull(controller.originOrNull(newLease))
        source.complete(1, publicDaeguFixture)
        assertEquals(1, newCalls)
        assertNull(controller.originOrNull(lease))
    }

    @Test
    fun returningAfterCancellationStartsFreshAcquisitionAndOldCallbackCannotReplaceIt() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        val oldResults = mutableListOf<RoutePoint?>()
        val currentResults = mutableListOf<RoutePoint?>()
        controller.acquire(lease, { true }, oldResults::add)
        controller.cancel()
        assertTrue(controller.acquire(lease, { true }, currentResults::add))
        source.complete(0, publicDaeguFixture.copy(latitude = 37.5663, longitude = 126.9779))
        assertTrue(controller.isAcquiring)
        assertTrue(oldResults.isEmpty())
        assertTrue(currentResults.isEmpty())
        source.complete(1, publicDaeguFixture)
        assertEquals(listOf(RoutePoint(35.8714, 128.6014)), currentResults)
        assertEquals(RoutePoint(35.8714, 128.6014), controller.originOrNull(lease))
    }

    @Test
    fun timedOutRequestCannotCompleteTheRetryOrReplaceItsSuccessfulFix() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        val results = mutableListOf<RoutePoint?>()
        controller.acquire(lease, { true }, results::add)
        source.complete(0, null)
        controller.acquire(lease, { true }, results::add)
        val oldFix = publicDaeguFixture.copy(latitude = 37.5663, longitude = 126.9779)
        source.complete(0, oldFix)
        assertTrue(controller.isAcquiring)
        assertEquals(listOf<RoutePoint?>(null), results)
        source.complete(1, publicDaeguFixture)
        source.complete(0, oldFix)
        assertEquals(listOf(null, RoutePoint(35.8714, 128.6014)), results)
        assertEquals(RoutePoint(35.8714, 128.6014), controller.originOrNull(lease))
    }

    @Test
    fun renewedSessionForSameActorCannotReuseCachedCoordinates() {
        val source = FakeSource()
        val controller = DestinationSearchLocationController(source) { 10_000L }
        controller.acquire(lease, { true }) {}
        source.complete(0, publicDaeguFixture)
        assertNotNull(controller.originOrNull(lease))
        val renewedLease = lease.copy(sessionGeneration = lease.sessionGeneration + 1L)
        assertNull(controller.originOrNull(renewedLease))
        val results = mutableListOf<RoutePoint?>()
        assertTrue(controller.acquire(renewedLease, { true }, results::add))
        assertEquals(2, source.callbacks.size)
        source.complete(0, publicDaeguFixture)
        assertTrue(results.isEmpty())
        source.complete(1, publicDaeguFixture.copy(latitude = 35.872, longitude = 128.602))
        assertEquals(listOf(RoutePoint(35.872, 128.602)), results)
    }

    private class FakeSource : DestinationSearchLocationSource {
        val callbacks = mutableListOf<(DestinationSearchLocationFix?) -> Unit>()
        var cancelled = 0
        override fun request(onResult: (DestinationSearchLocationFix?) -> Unit): () -> Unit {
            callbacks += onResult
            return { cancelled += 1 }
        }
        fun complete(index: Int, fix: DestinationSearchLocationFix?) = callbacks[index](fix)
    }
}
