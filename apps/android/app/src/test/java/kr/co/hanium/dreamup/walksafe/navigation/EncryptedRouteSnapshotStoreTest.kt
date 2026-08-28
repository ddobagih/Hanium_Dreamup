package kr.co.hanium.dreamup.walksafe.navigation

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EncryptedRouteSnapshotStoreTest {
    @Test
    fun storesTheFirstCompleteRouteAsCiphertextAndRestoresItWithin24Hours() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        val route = route(providerRouteId = "first-route")

        assertTrue(store.saveFirstRoute("walk-1", route, destination, nowEpochMs = 1_000L))

        val rawEnvelope = requireNotNull(storage.envelope)
        assertFalse(rawEnvelope.contains("walk-1"))
        assertFalse(rawEnvelope.contains("first-route"))
        assertFalse(rawEnvelope.contains("37.001"))
        val ciphertextBytes = Base64.getDecoder().decode(
            JSONObject(rawEnvelope).getString("ciphertext"),
        )
        val ciphertextAsText = String(ciphertextBytes, Charsets.UTF_8)
        assertFalse(ciphertextAsText.contains("walk-1"))
        assertFalse(ciphertextAsText.contains("first-route"))
        assertFalse(ciphertextAsText.contains("walk_session_id"))
        val restored = store.load(nowEpochMs = 2_000L)
        assertEquals("walk-1", restored?.walkSessionId)
        assertEquals(route, restored?.route)
        assertEquals(destination, restored?.destination)
    }

    @Test
    fun keepsTheFirstRouteForTheSameWalkAndDeletesItAt24Hours() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute("walk-1", route("first"), destination, nowEpochMs = 1_000L))
        assertTrue(store.saveFirstRoute("walk-1", route("replacement"), destination, nowEpochMs = 2_000L))

        assertEquals("first", store.load(nowEpochMs = 3_000L)?.route?.providerRouteId)
        assertNull(store.load(nowEpochMs = 1_000L + ROUTE_SNAPSHOT_TTL_MS))
        assertNull(storage.envelope)
    }

    @Test
    fun tamperedCiphertextAndSessionEndBothFailClosedToNoRoute() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute("walk-1", route("first"), destination, nowEpochMs = 1_000L))
        val envelope = requireNotNull(storage.envelope)
        val offset = envelope.indexOf("\"ciphertext\":\"") + "\"ciphertext\":\"".length
        storage.envelope = envelope.replaceRange(
            offset,
            offset + 1,
            if (envelope[offset] == 'A') "B" else "A",
        )

        assertNull(store.load(nowEpochMs = 2_000L))
        assertNull(storage.envelope)

        assertTrue(store.saveFirstRoute("walk-2", route("second"), destination, nowEpochMs = 3_000L))
        assertTrue(store.clear())
        assertNull(store.load(nowEpochMs = 4_000L))
    }

    @Test
    fun reportsFailedTtlAndSessionEndPurges() {
        val storage = FakeRouteSnapshotStorage()
        val store = newStore(storage)
        assertTrue(store.saveFirstRoute("walk-1", route("first"), destination, nowEpochMs = 1_000L))
        storage.clearSucceeds = false

        assertFalse(store.purgeExpired(nowEpochMs = 1_000L + ROUTE_SNAPSHOT_TTL_MS))
        assertNotNull(storage.envelope)
        assertFalse(store.clear())
        assertNotNull(storage.envelope)
    }

    private fun newStore(storage: FakeRouteSnapshotStorage): EncryptedRouteSnapshotStore {
        val policy = AeadKeyPolicy(
            aliasPrefix = "walksafe.test.route_snapshot.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        return EncryptedRouteSnapshotStore(
            storage = storage,
            aead = VersionedLocalAead(policy, FakeKeyProvider()),
            testOnly = Unit,
        )
    }

    private fun route(providerRouteId: String): WalkingRoute = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 111, durationS = 90),
        polyline = listOf(RoutePoint(37.0, 127.0), destination),
        guidePoints = listOf(
            WalkingRouteGuidePoint(
                index = 0,
                point = RoutePoint(37.0005, 127.0),
                instruction = "직진",
                distanceFromStartM = 56,
                remainingDistanceM = 55,
                bearingDeg = 0f,
                turnType = 11,
                pointType = "guide",
                facilityType = 15,
            ),
        ),
        steps = listOf(
            WalkingRouteStep(
                index = 0,
                distanceM = 111,
                durationS = 90,
                points = listOf(RoutePoint(37.0, 127.0), destination),
                instruction = "직진",
                roadName = "테스트길",
                turnType = 11,
                facilityType = 15,
            ),
        ),
        providerRouteId = providerRouteId,
    )

    private val destination = RoutePoint(37.001, 127.0, "목적지")
}

private class FakeRouteSnapshotStorage : RouteSnapshotStorage {
    var envelope: String? = null
    var clearSucceeds = true

    override fun read(): String? = envelope

    override fun write(envelope: String): Boolean {
        this.envelope = envelope
        return true
    }

    override fun clear(): Boolean {
        if (clearSucceeds) envelope = null
        return clearSucceeds
    }
}

private class FakeKeyProvider : LocalAeadKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias, ::newKey)

    override fun getExisting(alias: String): SecretKey? = keys[alias]

    override fun delete(alias: String): Boolean {
        keys.remove(alias)
        return true
    }

    override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
        return newKey().also { keys[alias] = it }
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
